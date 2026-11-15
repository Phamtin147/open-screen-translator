package com.openscreentranslator.app.translation

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class HymtModelVariant(
    val id: String,
    val displayName: String,
    val badgeLabel: String,
    val quantLabel: String,
    val fileName: String,
    val approxSizeMb: Int,
    val minFreeBytes: Long,
    val downloadUrl: String,
    val summaryVi: String
)

sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data class Downloading(
        val variantId: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long
    ) : ModelDownloadState
    data class Completed(val variantId: String) : ModelDownloadState
    data class Error(val variantId: String, val message: String) : ModelDownloadState
}

object ModelManager {

    val VARIANTS: List<HymtModelVariant> = listOf(
        HymtModelVariant(
            id = "UD_IQ2_M",
            displayName = "Hy-MT2 1.8B · Compact",
            badgeLabel = "NHẸ · RAM ≥ 4GB",
            quantLabel = "UD-IQ2_M",
            fileName = "hy-mt2-1.8b-ud-iq2_m.gguf",
            approxSizeMb = 689,
            minFreeBytes = 900L * 1024L * 1024L,
            downloadUrl = "https://huggingface.co/unsloth/Hy-MT2-1.8B-GGUF/resolve/main/Hy-MT2-1.8B-UD-IQ2_M.gguf",
            summaryVi = "Tải nhanh nhất (~689 MB), hợp máy tầm trung."
        ),
        HymtModelVariant(
            id = "Q3_K_S",
            displayName = "Hy-MT2 1.8B · Balanced",
            badgeLabel = "KHUYÊN DÙNG",
            quantLabel = "Q3_K_S",
            fileName = "hy-mt2-1.8b-q3_k_s.gguf",
            approxSizeMb = 832,
            minFreeBytes = 1050L * 1024L * 1024L,
            downloadUrl = "https://huggingface.co/unsloth/Hy-MT2-1.8B-GGUF/resolve/main/Hy-MT2-1.8B-Q3_K_S.gguf",
            summaryVi = "Cân bằng giữa ngữ nghĩa tự nhiên và tốc độ (~832 MB)."
        ),
        HymtModelVariant(
            id = "Q4_K_M",
            displayName = "Hy-MT2 1.8B · High Quality",
            badgeLabel = "CHẤT LƯỢNG CAO",
            quantLabel = "Q4_K_M",
            fileName = "hy-mt2-1.8b-q4_k_m.gguf",
            approxSizeMb = 1081,
            minFreeBytes = 1300L * 1024L * 1024L,
            downloadUrl = "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/main/Hy-MT2-1.8B-Q4_K_M.gguf",
            summaryVi = "Độ chuẩn cao nhất (~1.08 GB), hợp máy RAM ≥ 8GB."
        )
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .dns(ResilientDns)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    @Volatile
    private var activeDownloadJob: Job? = null

    fun findVariant(variantId: String): HymtModelVariant {
        return VARIANTS.firstOrNull { it.id == variantId } ?: VARIANTS[1]
    }

    fun getModelsDir(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getModelFile(context: Context, variantId: String): File {
        val variant = findVariant(variantId)
        return File(getModelsDir(context), variant.fileName)
    }

    fun getPartFile(context: Context, variantId: String): File {
        val variant = findVariant(variantId)
        return File(getModelsDir(context), "${variant.fileName}.part")
    }

    fun isModelInstalled(context: Context, variantId: String): Boolean {
        val f = getModelFile(context, variantId)
        return f.exists() && f.length() > 100L * 1024L * 1024L && hasGgufMagic(f)
    }

    fun getAnyInstalledVariant(context: Context, preferredVariantId: String): HymtModelVariant? {
        if (isModelInstalled(context, preferredVariantId)) {
            return findVariant(preferredVariantId)
        }
        return VARIANTS.firstOrNull { isModelInstalled(context, it.id) }
    }

    fun getAvailableBytes(context: Context): Long {
        val stat = StatFs(getModelsDir(context).absolutePath)
        return stat.availableBytes
    }

    fun startDownload(context: Context, variantId: String) {
        val appContext = context.applicationContext
        val variant = findVariant(variantId)
        if (activeDownloadJob?.isActive == true) {
            return
        }

        if (isModelInstalled(appContext, variant.id)) {
            _downloadState.value = ModelDownloadState.Completed(variant.id)
            return
        }

        val partFile = getPartFile(appContext, variant.id)
        val existingBytes = if (partFile.exists()) partFile.length() else 0L
        val neededBytes = (variant.minFreeBytes - existingBytes).coerceAtLeast(100L * 1024L * 1024L)
        if (getAvailableBytes(appContext) < neededBytes) {
            _downloadState.value = ModelDownloadState.Error(
                variant.id,
                "Không đủ dung lượng trống (cần ít nhất ~${variant.approxSizeMb} MB)."
            )
            return
        }

        activeDownloadJob = scope.launch {
            val maxAttempts = 3
            var lastError: Exception? = null
            for (attempt in 1..maxAttempts) {
                if (!isActive) return@launch
                try {
                    downloadWithResume(appContext, variant, partFile)
                    return@launch
                } catch (e: CancellationException) {
                    _downloadState.value = ModelDownloadState.Idle
                    return@launch
                } catch (e: Exception) {
                    lastError = e
                    val shouldRetry = attempt < maxAttempts && isTransientNetworkError(e) && isActive
                    if (!shouldRetry) break
                    // Clear stale DoH cache before retry in case Wi-Fi/carrier DNS switched mid-transfer.
                    ResilientDns.clearCache()
                    delay(1200L * attempt)
                }
            }
            if (!isActive) {
                _downloadState.value = ModelDownloadState.Idle
                return@launch
            }
            _downloadState.value = ModelDownloadState.Error(
                variant.id,
                formatDownloadError(lastError)
            )
        }
    }

    fun cancelDownload() {
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        _downloadState.value = ModelDownloadState.Idle
    }

    fun deleteModel(context: Context, variantId: String): Boolean {
        val appContext = context.applicationContext
        if (HymtEngineHolder.loadedVariantId == variantId) {
            HymtEngineHolder.unload()
        }
        val f = getModelFile(appContext, variantId)
        val part = getPartFile(appContext, variantId)
        var removed = false
        if (f.exists()) removed = f.delete() || removed
        if (part.exists()) removed = part.delete() || removed
        _downloadState.value = ModelDownloadState.Idle
        return removed
    }

    fun resetStateIfNotDownloading() {
        if (_downloadState.value !is ModelDownloadState.Downloading) {
            _downloadState.value = ModelDownloadState.Idle
        }
    }

    private suspend fun CoroutineScope.downloadWithResume(
        context: Context,
        variant: HymtModelVariant,
        partFile: File
    ) {
        var resumeFrom = if (partFile.exists()) partFile.length() else 0L
        val reqBuilder = Request.Builder().url(variant.downloadUrl)
        if (resumeFrom > 0L) {
            reqBuilder.header("Range", "bytes=$resumeFrom-")
        }

        val initialTotal = variant.approxSizeMb.toLong() * 1024L * 1024L
        _downloadState.value = ModelDownloadState.Downloading(
            variantId = variant.id,
            downloadedBytes = resumeFrom,
            totalBytes = initialTotal,
            speedBytesPerSec = 0L
        )

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            val append = response.code == 206
            if (!response.isSuccessful && response.code != 416) {
                throw IllegalStateException("HTTP ${response.code}: không thể tải file GGUF.")
            }

            if (response.code == 416) {
                // Local part is already complete or server offset mismatched -> restart cleanly
                partFile.delete()
                resumeFrom = 0L
                val freshResp = httpClient.newCall(Request.Builder().url(variant.downloadUrl).build()).execute()
                freshResp.use { fresh ->
                    if (!fresh.isSuccessful) {
                        throw IllegalStateException("HTTP ${fresh.code}: không thể tải lại GGUF.")
                    }
                    streamBodyToFile(context, variant, fresh, partFile, 0L, false)
                }
                return
            }

            if (!append) {
                resumeFrom = 0L
            }
            streamBodyToFile(context, variant, response, partFile, resumeFrom, append)
        }
    }

    private fun CoroutineScope.streamBodyToFile(
        context: Context,
        variant: HymtModelVariant,
        response: okhttp3.Response,
        partFile: File,
        resumeFrom: Long,
        append: Boolean
    ) {
        val body = response.body ?: throw IllegalStateException("Phản hồi tải model rỗng.")
        val contentLength = body.contentLength()
        val totalBytes = if (contentLength > 0) {
            resumeFrom + contentLength
        } else {
            variant.approxSizeMb.toLong() * 1024L * 1024L
        }

        var downloaded = resumeFrom
        var lastEmitTime = System.currentTimeMillis()
        var bytesSinceEmit = 0L
        val buffer = ByteArray(64 * 1024)

        body.byteStream().use { input ->
            FileOutputStream(partFile, append).use { output ->
                while (isActive) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    bytesSinceEmit += read

                    val now = System.currentTimeMillis()
                    val elapsedMs = now - lastEmitTime
                    if (elapsedMs >= 250L) {
                        val speed = (bytesSinceEmit * 1000L) / elapsedMs.coerceAtLeast(1L)
                        _downloadState.value = ModelDownloadState.Downloading(
                            variantId = variant.id,
                            downloadedBytes = downloaded,
                            totalBytes = totalBytes.coerceAtLeast(downloaded),
                            speedBytesPerSec = speed
                        )
                        lastEmitTime = now
                        bytesSinceEmit = 0L
                    }
                }
                output.flush()
            }
        }

        if (!isActive) return

        if (!hasGgufMagic(partFile)) {
            partFile.delete()
            throw IllegalStateException("File tải về không đúng định dạng GGUF hợp lệ.")
        }

        val finalFile = getModelFile(context, variant.id)
        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            throw IllegalStateException("Không thể lưu file model vào bộ nhớ ứng dụng.")
        }

        _downloadState.value = ModelDownloadState.Completed(variant.id)
    }

    private fun hasGgufMagic(file: File): Boolean {
        if (!file.exists() || file.length() < 16L) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(4)
                val read = raf.read(header)
                read == 4 &&
                    header[0] == 'G'.code.toByte() &&
                    header[1] == 'G'.code.toByte() &&
                    header[2] == 'U'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isTransientNetworkError(error: Throwable): Boolean {
        var cur: Throwable? = error
        while (cur != null) {
            if (cur is UnknownHostException ||
                cur is SocketTimeoutException ||
                cur is ConnectException ||
                cur is SocketException ||
                cur is SSLException ||
                cur is EOFException
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private fun formatDownloadError(error: Exception?): String {
        if (error == null) return "Lỗi kết nối khi tải model từ Hugging Face."
        val isDnsFailure = generateSequence<Throwable>(error) { it.cause }
            .any { it is UnknownHostException }
        if (isDnsFailure) {
            return "Không phân giải được tên miền tải model (đã thử DNS máy và 1.1.1.1 / 8.8.8.8). Hãy kiểm tra Wi-Fi/4G hoặc tắt VPN/AdGuard rồi thử lại.\n(${error.message})"
        }
        return error.message ?: "Lỗi kết nối khi tải model từ Hugging Face."
    }
}
