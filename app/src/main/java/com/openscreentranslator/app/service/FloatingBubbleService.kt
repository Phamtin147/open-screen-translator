package com.openscreentranslator.app.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.openscreentranslator.app.R
import com.openscreentranslator.app.data.AppPreferences
import com.openscreentranslator.app.ocr.OcrManager
import com.openscreentranslator.app.ocr.ReadingDirection
import com.openscreentranslator.app.overlay.OverlayManager
import com.openscreentranslator.app.translation.LlamaBridge
import com.openscreentranslator.app.translation.TranslationManager
import com.openscreentranslator.app.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer

class FloatingBubbleService : Service() {
    private val TAG = "FloatingBubbleService"
    private val CHANNEL_ID = "OpenScreenTranslatorChannel"
    private val NOTIFICATION_ID = 101

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var prefs: AppPreferences
    private lateinit var overlayManager: OverlayManager
    private lateinit var ocrManager: OcrManager
    private lateinit var translationManager: TranslationManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 420

    @Volatile
    private var isProcessing = false
    private var activeTranslationJob: Job? = null
    private var lastNoticeTimestamp = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        prefs = AppPreferences(this)
        ocrManager = OcrManager()
        translationManager = TranslationManager(applicationContext)
        overlayManager = OverlayManager(this) {
            when {
                overlayManager.isShowingTranslations() -> overlayManager.clearTranslationCards()
                isProcessing -> cancelActiveTranslation()
                else -> captureAndTranslate()
            }
        }

        createNotificationChannel()
        overlayManager.showFloatingBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceNotification()

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection?.stop()
            mediaProjection = mpManager.getMediaProjection(resultCode, data)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))

            setupVirtualDisplay()
        } else if (imageReader == null) {
            // MediaProjection grants cannot be reconstructed after an intent-less restart
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundServiceNotification() {
        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = "ACTION_STOP"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_desc))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop_service), stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            screenWidth = metrics.bounds.width().coerceAtLeast(1)
            screenHeight = metrics.bounds.height().coerceAtLeast(1)
            screenDensity = resources.configuration.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels.coerceAtLeast(1)
            screenHeight = metrics.heightPixels.coerceAtLeast(1)
            screenDensity = metrics.densityDpi
        }

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCaptureStream",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            object : VirtualDisplay.Callback() {
                override fun onStopped() {
                    Log.w(TAG, "VirtualDisplay stopped by system")
                }
            },
            Handler(Looper.getMainLooper())
        )
    }

    private fun cancelActiveTranslation() {
        LlamaBridge.cancelIfLoaded()
        activeTranslationJob?.cancel()
        activeTranslationJob = null
        isProcessing = false
        overlayManager.setBubbleLoading(false)
        showNotice("Đã hủy thao tác dịch.", force = true)
    }

    private fun captureAndTranslate() {
        if (isProcessing) return
        val reader = imageReader
        if (reader == null) {
            showNotice("Mất quyền chụp màn hình — hãy Dừng rồi Bật lại trong app.", force = true)
            return
        }

        isProcessing = true
        overlayManager.setBubbleLoading(true)

        val srcLang = prefs.sourceLanguage
        val targetLang = prefs.targetLanguage
        val engineMode = prefs.engineMode
        val comicMode = prefs.comicModeEnabled
        val wholePageContext = comicMode && prefs.wholePageContextEnabled
        val geminiApiKey = prefs.geminiApiKey
        val preferredHymtVariant = prefs.hymtModelVariant
        val readingDir = if (prefs.readingDirection == AppPreferences.DIRECTION_LTR) {
            ReadingDirection.COMIC_LTR
        } else {
            ReadingDirection.MANGA_RTL
        }

        activeTranslationJob = serviceScope.launch {
            val currentJob = coroutineContext[Job]
            var capturedBitmap: Bitmap? = null
            try {
                withTimeout(90_000L) {
                    capturedBitmap = withContext(Dispatchers.IO) {
                        acquireScreenBitmapWithRetry(reader)
                    }

                    val bitmap = capturedBitmap
                    if (bitmap == null) {
                        showNotice("Chưa lấy được khung hình — hãy vuốt nhẹ hoặc chọn Chia sẻ toàn màn hình.", force = true)
                        return@withTimeout
                    }

                    val ocrBlocks = ocrManager.processImage(
                        bitmap = bitmap,
                        languageCode = srcLang,
                        comicModeEnabled = comicMode,
                        readingDirection = readingDir
                    )

                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                    capturedBitmap = null

                    if (ocrBlocks.isEmpty()) {
                        showNotice("Không nhận diện được chữ trên màn hình.", force = true)
                        return@withTimeout
                    }

                    val pageResult = translationManager.translatePageBubbles(
                        blocks = ocrBlocks,
                        sourceLang = srcLang,
                        targetLang = targetLang,
                        engineMode = engineMode,
                        geminiApiKey = geminiApiKey,
                        wholePageContextEnabled = wholePageContext,
                        preferredHymtVariant = preferredHymtVariant
                    )

                    pageResult.fallbackNotice?.let { notice ->
                        showNotice(notice, force = false)
                    }

                    overlayManager.drawTranslationCards(
                        results = pageResult.translatedBlocks,
                        showBubbleOrderIndex = comicMode && pageResult.translatedBlocks.size > 1
                    )
                }
            } catch (_: TimeoutCancellationException) {
                LlamaBridge.cancelIfLoaded()
                Log.e(TAG, "Screen translation pipeline timed out")
                showNotice("Quá thời gian chờ dịch — vui lòng thử lại hoặc đổi bộ dịch.", force = true)
            } catch (_: CancellationException) {
                // Cancelled explicitly by user tap or service destruction
            } catch (t: Throwable) {
                Log.e(TAG, "Translation pipeline failed", t)
                showNotice("Lỗi khi dịch: ${t.message ?: t.javaClass.simpleName}", force = true)
            } finally {
                capturedBitmap?.let { bmp ->
                    if (!bmp.isRecycled) bmp.recycle()
                }
                withContext(NonCancellable + Dispatchers.Main) {
                    if (activeTranslationJob === currentJob) {
                        overlayManager.setBubbleLoading(false)
                        isProcessing = false
                        activeTranslationJob = null
                    }
                }
            }
        }
    }

    private suspend fun acquireScreenBitmapWithRetry(reader: ImageReader): Bitmap? {
        repeat(5) { attempt ->
            val image: Image? = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                Log.e(TAG, "acquireLatestImage error", e)
                null
            }
            if (image != null) {
                return try {
                    withContext(Dispatchers.Default) {
                        imageToBitmap(image)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "imageToBitmap error", t)
                    null
                } finally {
                    try {
                        image.close()
                    } catch (_: Exception) {
                    }
                }
            }
            if (attempt < 4) {
                delay(75L)
            }
        }
        return null
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        buffer.rewind()
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = (rowStride - pixelStride * image.width).coerceAtLeast(0)
        val paddedWidth = image.width + rowPadding / pixelStride.coerceAtLeast(1)

        val paddedBitmap = Bitmap.createBitmap(
            paddedWidth,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        paddedBitmap.copyPixelsFromBuffer(buffer)

        if (paddedWidth == image.width) {
            return paddedBitmap
        }
        val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
        if (cropped !== paddedBitmap) {
            paddedBitmap.recycle()
        }
        return cropped
    }

    private fun showNotice(message: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNoticeTimestamp <= 8000L) return
        lastNoticeTimestamp = now
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this@FloatingBubbleService, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Open Screen Translator Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Running background translation service"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        LlamaBridge.cancelIfLoaded()
        activeTranslationJob?.cancel()
        serviceScope.cancel()
        overlayManager.removeFloatingBubble()
        ocrManager.close()
        translationManager.close()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }

    companion object {
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }
}
