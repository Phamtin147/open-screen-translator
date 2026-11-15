package com.openscreentranslator.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.openscreentranslator.app.R
import com.openscreentranslator.app.data.AppLanguages
import com.openscreentranslator.app.data.AppPreferences
import com.openscreentranslator.app.ocr.ReadingDirection
import com.openscreentranslator.app.service.FloatingBubbleService
import com.openscreentranslator.app.translation.HymtEngineHolder
import com.openscreentranslator.app.translation.HymtEngineState
import com.openscreentranslator.app.translation.ModelDownloadState
import com.openscreentranslator.app.translation.ModelManager
import com.openscreentranslator.app.translation.TranslationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences

    // UI references
    private lateinit var btnThemeMode: MaterialButton
    private lateinit var viewStatusDot: View
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvStatusHeadline: TextView
    private lateinit var tvActiveSummary: TextView
    private lateinit var layoutOverlayPermission: LinearLayout
    private lateinit var btnGrantOverlay: MaterialButton
    private lateinit var btnToggleService: MaterialButton

    private lateinit var btnSourceLanguagePicker: LinearLayout
    private lateinit var tvSourceLangCode: TextView
    private lateinit var tvSourceLangName: TextView
    private lateinit var btnTargetLanguagePicker: LinearLayout
    private lateinit var tvTargetLangCode: TextView
    private lateinit var tvTargetLangName: TextView
    private lateinit var btnSwapLanguages: ImageButton

    private lateinit var rowEngineHymt: LinearLayout
    private lateinit var rowEngineMlKit: LinearLayout
    private lateinit var rowEngineGemini: LinearLayout
    private lateinit var rowEngineCloud: LinearLayout
    private lateinit var tvHymtEngineTag: TextView

    private lateinit var layoutHymtManager: LinearLayout
    private lateinit var toggleHymtVariant: MaterialButtonToggleGroup
    private lateinit var btnVarCompact: MaterialButton
    private lateinit var btnVarBalanced: MaterialButton
    private lateinit var btnVarQuality: MaterialButton
    private lateinit var tvModelVariantDesc: TextView
    private lateinit var layoutDownloadProgress: LinearLayout
    private lateinit var progressModelDownload: LinearProgressIndicator
    private lateinit var tvDownloadProgressStats: TextView
    private lateinit var btnDownloadOrLoadModel: MaterialButton
    private lateinit var btnDeleteModel: MaterialButton

    private lateinit var layoutGeminiConfig: LinearLayout
    private lateinit var etGeminiApiKey: TextInputEditText
    private lateinit var tvGetApiKey: TextView

    private lateinit var layoutMlKitConfig: LinearLayout
    private lateinit var tvMlKitStatus: TextView
    private lateinit var btnDownloadMlKitPacks: MaterialButton
    private var isDownloadingMlKitPacks = false

    private lateinit var layoutComicModeRow: LinearLayout
    private lateinit var switchComicMode: MaterialSwitch
    private lateinit var layoutWholePageRow: LinearLayout
    private lateinit var switchWholePageContext: MaterialSwitch
    private lateinit var toggleReadingDirection: MaterialButtonToggleGroup
    private lateinit var btnDirRtl: MaterialButton
    private lateinit var btnDirLtr: MaterialButton
    private lateinit var tvPreviewSpec: TextView
    private lateinit var comicPreviewView: ComicPreviewView

    private lateinit var tvOpacityValue: TextView
    private lateinit var sliderOpacity: Slider
    private lateinit var tvTextSizeValue: TextView
    private lateinit var sliderTextSize: Slider
    private lateinit var tvAutoClearValue: TextView
    private lateinit var sliderAutoClear: Slider

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startTranslatorService(result.resultCode, result.data!!)
        } else {
            showNotice("Cần cấp quyền chụp màn hình để dịch.")
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionAndServiceUi()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Proceed with MediaProjection regardless; notification improves stability on API 33+
        requestMediaProjection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = AppPreferences(this)
        applySavedNightMode(prefs.themeMode)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        applyWindowInsets()
        setupThemeButton()
        setupLanguageControls()
        setupEngineDeck()
        setupHymtManagerControls()
        setupMlKitControls()
        setupGeminiControls()
        setupComicModeControls()
        setupCalibrationSliders()
        setupServiceButtons()
        observeFlows()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionAndServiceUi()
        refreshHymtStatusUi()
        refreshMlKitStatusUi()
        updateActiveSummaryLine()
    }

    private fun bindViews() {
        btnThemeMode = findViewById(R.id.btnThemeMode)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvStatusHeadline = findViewById(R.id.tvStatusHeadline)
        tvActiveSummary = findViewById(R.id.tvActiveSummary)
        layoutOverlayPermission = findViewById(R.id.layoutOverlayPermission)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        btnToggleService = findViewById(R.id.btnToggleService)

        btnSourceLanguagePicker = findViewById(R.id.btnSourceLanguagePicker)
        tvSourceLangCode = findViewById(R.id.tvSourceLangCode)
        tvSourceLangName = findViewById(R.id.tvSourceLangName)
        btnTargetLanguagePicker = findViewById(R.id.btnTargetLanguagePicker)
        tvTargetLangCode = findViewById(R.id.tvTargetLangCode)
        tvTargetLangName = findViewById(R.id.tvTargetLangName)
        btnSwapLanguages = findViewById(R.id.btnSwapLanguages)

        rowEngineHymt = findViewById(R.id.rowEngineHymt)
        rowEngineMlKit = findViewById(R.id.rowEngineMlKit)
        rowEngineGemini = findViewById(R.id.rowEngineGemini)
        rowEngineCloud = findViewById(R.id.rowEngineCloud)
        tvHymtEngineTag = findViewById(R.id.tvHymtEngineTag)

        layoutHymtManager = findViewById(R.id.layoutHymtManager)
        toggleHymtVariant = findViewById(R.id.toggleHymtVariant)
        btnVarCompact = findViewById(R.id.btnVarCompact)
        btnVarBalanced = findViewById(R.id.btnVarBalanced)
        btnVarQuality = findViewById(R.id.btnVarQuality)
        tvModelVariantDesc = findViewById(R.id.tvModelVariantDesc)
        layoutDownloadProgress = findViewById(R.id.layoutDownloadProgress)
        progressModelDownload = findViewById(R.id.progressModelDownload)
        tvDownloadProgressStats = findViewById(R.id.tvDownloadProgressStats)
        btnDownloadOrLoadModel = findViewById(R.id.btnDownloadOrLoadModel)
        btnDeleteModel = findViewById(R.id.btnDeleteModel)

        layoutGeminiConfig = findViewById(R.id.layoutGeminiConfig)
        etGeminiApiKey = findViewById(R.id.etGeminiApiKey)
        tvGetApiKey = findViewById(R.id.tvGetApiKey)

        layoutMlKitConfig = findViewById(R.id.layoutMlKitConfig)
        tvMlKitStatus = findViewById(R.id.tvMlKitStatus)
        btnDownloadMlKitPacks = findViewById(R.id.btnDownloadMlKitPacks)

        layoutComicModeRow = findViewById(R.id.layoutComicModeRow)
        switchComicMode = findViewById(R.id.switchComicMode)
        layoutWholePageRow = findViewById(R.id.layoutWholePageRow)
        switchWholePageContext = findViewById(R.id.switchWholePageContext)
        toggleReadingDirection = findViewById(R.id.toggleReadingDirection)
        btnDirRtl = findViewById(R.id.btnDirRtl)
        btnDirLtr = findViewById(R.id.btnDirLtr)
        tvPreviewSpec = findViewById(R.id.tvPreviewSpec)
        comicPreviewView = findViewById(R.id.comicPreviewView)

        tvOpacityValue = findViewById(R.id.tvOpacityValue)
        sliderOpacity = findViewById(R.id.sliderOpacity)
        tvTextSizeValue = findViewById(R.id.tvTextSizeValue)
        sliderTextSize = findViewById(R.id.sliderTextSize)
        tvAutoClearValue = findViewById(R.id.tvAutoClearValue)
        sliderAutoClear = findViewById(R.id.sliderAutoClear)
    }

    private fun applyWindowInsets() {
        val scrollView = findViewById<NestedScrollView>(R.id.rootScrollView)
        val container = findViewById<LinearLayout>(R.id.mainContentContainer)
        val baseHorizontal = resources.getDimensionPixelSize(R.dimen.atlas_horizontal_margin)
        val baseTop = dpToPx(12)
        val baseBottom = dpToPx(28)
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            container.updatePadding(
                left = bars.left + baseHorizontal,
                top = bars.top + baseTop,
                right = bars.right + baseHorizontal,
                bottom = bars.bottom + baseBottom
            )
            insets
        }
    }

    private fun showNotice(message: String) {
        Snackbar.make(findViewById(R.id.rootScrollView), message, Snackbar.LENGTH_SHORT).show()
    }

    private fun setupThemeButton() {
        updateThemeButtonLabel()
        btnThemeMode.setOnClickListener {
            val next = when (prefs.themeMode) {
                AppPreferences.THEME_SYSTEM -> AppPreferences.THEME_LIGHT
                AppPreferences.THEME_LIGHT -> AppPreferences.THEME_DARK
                else -> AppPreferences.THEME_SYSTEM
            }
            prefs.themeMode = next
            applySavedNightMode(next)
            updateThemeButtonLabel()
        }
    }

    private fun updateThemeButtonLabel() {
        btnThemeMode.text = when (prefs.themeMode) {
            AppPreferences.THEME_LIGHT -> getString(R.string.theme_light)
            AppPreferences.THEME_DARK -> getString(R.string.theme_dark)
            else -> getString(R.string.theme_system)
        }
    }

    private fun applySavedNightMode(mode: String) {
        val target = when (mode) {
            AppPreferences.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppPreferences.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != target) {
            AppCompatDelegate.setDefaultNightMode(target)
        }
    }

    private fun setupLanguageControls() {
        syncLanguageSelectionUi()

        btnSourceLanguagePicker.setOnClickListener {
            LanguagePickerSheet.show(
                context = this,
                title = "Chọn ngôn ngữ gốc",
                subtitle = "Nên để Tự phát hiện nếu 1 trang truyện có nhiều thứ tiếng cùng lúc.",
                options = AppLanguages.SOURCE_OPTIONS,
                selectedCode = prefs.sourceLanguage
            ) { selected ->
                prefs.sourceLanguage = selected.code
                syncLanguageSelectionUi()
                refreshMlKitStatusUi()
                updateActiveSummaryLine()
            }
        }

        btnTargetLanguagePicker.setOnClickListener {
            LanguagePickerSheet.show(
                context = this,
                title = "Chọn ngôn ngữ đích",
                subtitle = "Ngôn ngữ hiển thị trên thẻ dịch nổi.",
                options = AppLanguages.TARGET_OPTIONS,
                selectedCode = prefs.targetLanguage
            ) { selected ->
                prefs.targetLanguage = selected.code
                syncLanguageSelectionUi()
                refreshMlKitStatusUi()
                updateActiveSummaryLine()
            }
        }

        btnSwapLanguages.setOnClickListener {
            val currentSrc = prefs.sourceLanguage
            val currentTgt = prefs.targetLanguage
            if (currentSrc == AppLanguages.AUTO) {
                if (AppLanguages.isOcrSupported(currentTgt)) {
                    prefs.sourceLanguage = currentTgt
                    prefs.targetLanguage = if (currentTgt == AppLanguages.DEFAULT_TARGET) "en" else AppLanguages.DEFAULT_TARGET
                } else {
                    showNotice("Chế độ Tự phát hiện không thể đặt làm ngôn ngữ đích.")
                    return@setOnClickListener
                }
            } else if (!AppLanguages.isOcrSupported(currentTgt)) {
                showNotice("Chưa hỗ trợ quét OCR chiều ngược cho ${AppLanguages.shortCode(currentTgt)}.")
                return@setOnClickListener
            } else {
                prefs.sourceLanguage = currentTgt
                prefs.targetLanguage = currentSrc
            }
            syncLanguageSelectionUi()
            refreshMlKitStatusUi()
            updateActiveSummaryLine()
        }
    }

    private fun syncLanguageSelectionUi() {
        val sourceOptions = AppLanguages.SOURCE_OPTIONS
        val targetOptions = AppLanguages.TARGET_OPTIONS
        val srcOpt = sourceOptions.firstOrNull { it.code == prefs.sourceLanguage } ?: sourceOptions.first()
        val tgtOpt = targetOptions.firstOrNull { it.code == prefs.targetLanguage } ?: targetOptions.first()
        tvSourceLangCode.text = srcOpt.shortCode
        tvSourceLangName.text = srcOpt.labelVi
        tvTargetLangCode.text = tgtOpt.shortCode
        tvTargetLangName.text = tgtOpt.labelVi
    }

    private fun setupEngineDeck() {
        rowEngineHymt.setOnClickListener { selectEngine(AppPreferences.ENGINE_HY_MT2) }
        rowEngineMlKit.setOnClickListener { selectEngine(AppPreferences.ENGINE_ML_KIT) }
        rowEngineGemini.setOnClickListener { selectEngine(AppPreferences.ENGINE_GEMINI) }
        rowEngineCloud.setOnClickListener { selectEngine(AppPreferences.ENGINE_CLOUD_FREE) }
        updateEngineSelectionUi()
    }

    private fun selectEngine(mode: String) {
        prefs.engineMode = mode
        updateEngineSelectionUi()
        updateActiveSummaryLine()
    }

    private fun updateEngineSelectionUi() {
        val mode = prefs.engineMode
        rowEngineHymt.setBackgroundResource(
            if (mode == AppPreferences.ENGINE_HY_MT2) R.drawable.bg_option_selected else R.drawable.bg_option_unselected
        )
        rowEngineMlKit.setBackgroundResource(
            if (mode == AppPreferences.ENGINE_ML_KIT) R.drawable.bg_option_selected else R.drawable.bg_option_unselected
        )
        rowEngineGemini.setBackgroundResource(
            if (mode == AppPreferences.ENGINE_GEMINI) R.drawable.bg_option_selected else R.drawable.bg_option_unselected
        )
        rowEngineCloud.setBackgroundResource(
            if (mode == AppPreferences.ENGINE_CLOUD_FREE) R.drawable.bg_option_selected else R.drawable.bg_option_unselected
        )

        layoutHymtManager.visibility = if (mode == AppPreferences.ENGINE_HY_MT2) View.VISIBLE else View.GONE
        layoutMlKitConfig.visibility = if (mode == AppPreferences.ENGINE_ML_KIT) View.VISIBLE else View.GONE
        layoutGeminiConfig.visibility = if (mode == AppPreferences.ENGINE_GEMINI) View.VISIBLE else View.GONE
        if (mode == AppPreferences.ENGINE_ML_KIT) {
            refreshMlKitStatusUi()
        }
    }

    private fun setupHymtManagerControls() {
        val checkedId = when (prefs.hymtModelVariant) {
            "UD_IQ2_M" -> R.id.btnVarCompact
            "Q4_K_M" -> R.id.btnVarQuality
            else -> R.id.btnVarBalanced
        }
        toggleHymtVariant.check(checkedId)
        // MaterialButtonToggleGroup overrides child MaterialButtons to single-line + end ellipsis on inflation
        listOf(btnVarCompact, btnVarBalanced, btnVarQuality).forEach { btn ->
            btn.maxLines = 2
            btn.ellipsize = null
            btn.setLineSpacing(0f, 1.08f)
        }

        toggleHymtVariant.addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.hymtModelVariant = when (id) {
                R.id.btnVarCompact -> "UD_IQ2_M"
                R.id.btnVarQuality -> "Q4_K_M"
                else -> "Q3_K_S"
            }
            ModelManager.resetStateIfNotDownloading()
            refreshHymtStatusUi()
            updateActiveSummaryLine()
        }

        btnDownloadOrLoadModel.setOnClickListener {
            val state = ModelManager.downloadState.value
            val variantId = prefs.hymtModelVariant
            if (state is ModelDownloadState.Downloading) {
                ModelManager.cancelDownload()
                return@setOnClickListener
            }

            if (ModelManager.isModelInstalled(this, variantId)) {
                val curEngine = HymtEngineHolder.engineState.value
                if (curEngine is HymtEngineState.Loading) {
                    showNotice("Model đang được nạp vào RAM, vui lòng đợi vài giây...")
                    return@setOnClickListener
                }
                if (curEngine is HymtEngineState.Ready && curEngine.variantId == variantId) {
                    selectEngine(AppPreferences.ENGINE_HY_MT2)
                    refreshHymtStatusUi()
                    showNotice("Model ${ModelManager.findVariant(variantId).quantLabel} đã nạp sẵn và đang được chọn!")
                    return@setOnClickListener
                }

                // Pre-warm GGUF in background thread so first screen tap is instant
                btnDownloadOrLoadModel.isEnabled = false
                btnDownloadOrLoadModel.text = "Đang nạp vào RAM..."
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        HymtEngineHolder.ensureLoaded(this@MainActivity, variantId)
                    }
                    if (ok) {
                        selectEngine(AppPreferences.ENGINE_HY_MT2)
                        showNotice("Đã nạp xong Hy-MT2-1.8B vào RAM và sẵn sàng dịch!")
                    } else {
                        val err = (HymtEngineHolder.engineState.value as? HymtEngineState.Error)?.message
                            ?: "Không thể nạp model GGUF."
                        showNotice(err)
                    }
                    refreshHymtStatusUi()
                }
            } else {
                ModelManager.startDownload(this, variantId)
            }
        }

        btnDeleteModel.setOnClickListener {
            val variant = ModelManager.findVariant(prefs.hymtModelVariant)
            MaterialAlertDialogBuilder(this)
                .setTitle("Xóa file model GGUF?")
                .setMessage("Xóa ${variant.fileName} (~${variant.approxSizeMb} MB) khỏi bộ nhớ thiết bị?")
                .setNegativeButton("Giữ lại", null)
                .setPositiveButton("Xóa file") { _, _ ->
                    ModelManager.deleteModel(this, variant.id)
                    refreshHymtStatusUi()
                    updateActiveSummaryLine()
                    showNotice("Đã xóa file model.")
                }
                .show()
        }
    }

    private fun refreshHymtStatusUi() {
        val selected = ModelManager.findVariant(prefs.hymtModelVariant)
        val installed = ModelManager.isModelInstalled(this, selected.id)
        val anyInstalled = ModelManager.getAnyInstalledVariant(this, selected.id)
        val dlState = ModelManager.downloadState.value
        val engineState = HymtEngineHolder.engineState.value

        val isLoadingSelected = engineState is HymtEngineState.Loading
        val isReadySelected = engineState is HymtEngineState.Ready && engineState.variantId == selected.id

        tvHymtEngineTag.text = when {
            dlState is ModelDownloadState.Downloading -> "ĐANG TẢI"
            isLoadingSelected -> "ĐANG NẠP..."
            isReadySelected -> "ĐÃ NẠP"
            engineState is HymtEngineState.Error && installed -> "LỖI NẠP"
            installed -> "SẴN SÀNG"
            anyInstalled != null -> anyInstalled.quantLabel
            else -> "CHƯA TẢI"
        }

        tvHymtEngineTag.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    isLoadingSelected -> R.color.status_warning
                    engineState is HymtEngineState.Error && installed -> R.color.accent_vermilion_text
                    isReadySelected || installed || anyInstalled != null -> R.color.status_active
                    else -> R.color.ink_muted
                }
            )
        )

        val statusSuffix = when {
            isLoadingSelected -> "Đang nạp vào RAM (vui lòng đợi)..."
            isReadySelected -> "Đã nạp vào RAM · Sẵn sàng dịch"
            engineState is HymtEngineState.Error && installed -> "Lỗi nạp: ${engineState.message}"
            installed -> "Đã lưu trên máy · Bấm nút dưới để nạp vào RAM"
            else -> "Chưa tải · Cần kết nối Wi-Fi để tải 1 lần"
        }
        tvModelVariantDesc.text = "${selected.summaryVi}\nTrạng thái: $statusSuffix"

        when (dlState) {
            is ModelDownloadState.Downloading -> {
                layoutDownloadProgress.visibility = View.VISIBLE
                val pct = if (dlState.totalBytes > 0) {
                    ((dlState.downloadedBytes * 100L) / dlState.totalBytes).toInt().coerceIn(0, 100)
                } else 0
                progressModelDownload.setProgressCompat(pct, true)
                val dlMb = dlState.downloadedBytes / (1024f * 1024f)
                val totMb = dlState.totalBytes / (1024f * 1024f)
                val spdMb = dlState.speedBytesPerSec / (1024f * 1024f)
                tvDownloadProgressStats.text = "%.0f / %.0f MB · %.1f MB/s (%d%%)".format(dlMb, totMb, spdMb, pct)
                btnDownloadOrLoadModel.text = "Hủy tải xuống"
                btnDownloadOrLoadModel.isEnabled = true
                btnDeleteModel.visibility = View.GONE
            }

            is ModelDownloadState.Error -> {
                layoutDownloadProgress.visibility = View.VISIBLE
                progressModelDownload.progress = 0
                tvDownloadProgressStats.text = "Lỗi: ${dlState.message}"
                btnDownloadOrLoadModel.text = "Thử tải lại (${selected.quantLabel})"
                btnDownloadOrLoadModel.isEnabled = true
                btnDeleteModel.visibility = if (installed) View.VISIBLE else View.GONE
            }

            else -> {
                layoutDownloadProgress.visibility = View.GONE
                if (installed) {
                    btnDownloadOrLoadModel.text = when {
                        isLoadingSelected -> "Đang nạp vào RAM..."
                        isReadySelected -> "Đã nạp & Sẵn sàng dùng (${selected.quantLabel})"
                        else -> "Nạp vào RAM để dùng (${selected.quantLabel})"
                    }
                    btnDownloadOrLoadModel.isEnabled = !isLoadingSelected
                    btnDeleteModel.visibility = if (isLoadingSelected) View.GONE else View.VISIBLE
                } else {
                    btnDownloadOrLoadModel.text = "Tải ${selected.quantLabel} (${selected.approxSizeMb} MB)"
                    btnDownloadOrLoadModel.isEnabled = true
                    btnDeleteModel.visibility = View.GONE
                }
            }
        }
    }

    private fun setupMlKitControls() {
        btnDownloadMlKitPacks.setOnClickListener {
            if (isDownloadingMlKitPacks) return@setOnClickListener
            val neededCodes = requiredMlKitCodesForCurrentPrefs()
            isDownloadingMlKitPacks = true
            btnDownloadMlKitPacks.isEnabled = false
            btnDownloadMlKitPacks.text = "Đang tải gói offline..."
            tvMlKitStatus.text = "Đang kết nối Google ML Kit để tải..."

            lifecycleScope.launch {
                val res = TranslationManager.downloadMlKitLanguages(neededCodes) { done, total, code ->
                    runOnUiThread {
                        tvMlKitStatus.text = "Đang tải gói '${code.uppercase()}' ($done/$total)..."
                    }
                }
                isDownloadingMlKitPacks = false
                if (res.isSuccess) {
                    showNotice("Đã tải xong gói ngôn ngữ ML Kit offline!")
                } else {
                    val msg = res.exceptionOrNull()?.message ?: "Không thể tải gói offline"
                    showNotice(msg)
                }
                refreshMlKitStatusUi()
            }
        }
    }

    private fun requiredMlKitCodesForCurrentPrefs(): List<String> {
        val tgt = prefs.targetLanguage
        val src = prefs.sourceLanguage
        return if (src == AppLanguages.AUTO) {
            listOf(tgt, "en", "ja", "ko", "zh").distinct()
        } else {
            listOf(tgt, src).distinct()
        }
    }

    private fun refreshMlKitStatusUi() {
        if (!::layoutMlKitConfig.isInitialized || isDownloadingMlKitPacks) return
        val needed = requiredMlKitCodesForCurrentPrefs()
        lifecycleScope.launch {
            val downloaded = TranslationManager.listDownloadedMlKitCodes()
            val mappedNeeded = needed.mapNotNull { code ->
                TranslationManager.mapToMlKitLanguage(code)?.let { mlCode ->
                    code.uppercase() to (mlCode.lowercase() in downloaded)
                }
            }
            val missing = mappedNeeded.filter { !it.second }.map { it.first }
            val ready = mappedNeeded.filter { it.second }.map { it.first }

            if (missing.isEmpty() && mappedNeeded.isNotEmpty()) {
                tvMlKitStatus.text = "Đã sẵn sàng 100% offline: ${ready.joinToString(", ")}"
                btnDownloadMlKitPacks.text = "Đã đủ gói offline (${ready.joinToString(", ")})"
                btnDownloadMlKitPacks.isEnabled = false
            } else {
                val readyLabel = if (ready.isNotEmpty()) ready.joinToString(", ") else "Chưa có"
                tvMlKitStatus.text = "Đã tải: $readyLabel · Cần tải thêm: ${missing.joinToString(", ")}"
                btnDownloadMlKitPacks.text = "Tải ${missing.size} gói offline (${missing.joinToString(", ")})"
                btnDownloadMlKitPacks.isEnabled = true
            }
        }
    }

    private fun setupGeminiControls() {
        etGeminiApiKey.setText(prefs.geminiApiKey)
        etGeminiApiKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                prefs.geminiApiKey = s?.toString()?.trim().orEmpty()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        tvGetApiKey.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }
    }

    private fun setupComicModeControls() {
        switchComicMode.isChecked = prefs.comicModeEnabled
        switchWholePageContext.isChecked = prefs.wholePageContextEnabled

        toggleReadingDirection.check(
            if (prefs.readingDirection == AppPreferences.DIRECTION_LTR) R.id.btnDirLtr else R.id.btnDirRtl
        )

        layoutComicModeRow.setOnClickListener { switchComicMode.toggle() }
        switchComicMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.comicModeEnabled = isChecked
            syncPreviewPlate()
            updateActiveSummaryLine()
        }

        layoutWholePageRow.setOnClickListener { switchWholePageContext.toggle() }
        switchWholePageContext.setOnCheckedChangeListener { _, isChecked ->
            prefs.wholePageContextEnabled = isChecked
        }

        toggleReadingDirection.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.readingDirection = if (checkedId == R.id.btnDirLtr) {
                AppPreferences.DIRECTION_LTR
            } else {
                AppPreferences.DIRECTION_RTL
            }
            syncPreviewPlate()
            updateActiveSummaryLine()
        }

        syncPreviewPlate()
    }

    private fun setupCalibrationSliders() {
        sliderOpacity.value = prefs.overlayOpacity.coerceIn(65, 100).toFloat()
        tvOpacityValue.text = "${prefs.overlayOpacity}%"
        sliderOpacity.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            prefs.overlayOpacity = v
            tvOpacityValue.text = "$v%"
            syncPreviewPlate()
        }

        sliderTextSize.value = prefs.textSize.coerceIn(10, 24).toFloat()
        tvTextSizeValue.text = "${prefs.textSize} sp"
        sliderTextSize.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            prefs.textSize = v
            tvTextSizeValue.text = "$v sp"
            syncPreviewPlate()
        }

        sliderAutoClear.value = prefs.autoClearSeconds.coerceIn(0, 30).toFloat()
        tvAutoClearValue.text = if (prefs.autoClearSeconds == 0) "Tắt" else "${prefs.autoClearSeconds} s"
        sliderAutoClear.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            prefs.autoClearSeconds = v
            tvAutoClearValue.text = if (v == 0) "Tắt" else "$v s"
        }

        syncPreviewPlate()
    }

    private fun syncPreviewPlate() {
        val dir = if (prefs.readingDirection == AppPreferences.DIRECTION_LTR) {
            ReadingDirection.COMIC_LTR
        } else {
            ReadingDirection.MANGA_RTL
        }
        comicPreviewView.readingDirection = dir
        comicPreviewView.comicModeEnabled = prefs.comicModeEnabled
        comicPreviewView.overlayOpacityPercent = prefs.overlayOpacity
        comicPreviewView.previewTextSizeSp = prefs.textSize

        val dirTag = if (!prefs.comicModeEnabled) {
            "Gộp tắt"
        } else if (dir == ReadingDirection.MANGA_RTL) {
            "R→L"
        } else {
            "L→R"
        }
        tvPreviewSpec.text = "$dirTag · ${prefs.overlayOpacity}% · ${prefs.textSize}sp"
    }

    private fun setupServiceButtons() {
        btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }

        btnToggleService.setOnClickListener {
            if (FloatingBubbleService.isRunning.value) {
                stopService(Intent(this, FloatingBubbleService::class.java))
                updatePermissionAndServiceUi()
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    showNotice("Vui lòng cấp quyền hiển thị nổi trước.")
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    overlayPermissionLauncher.launch(intent)
                    return@setOnClickListener
                }
                if (prefs.engineMode == AppPreferences.ENGINE_GEMINI && prefs.geminiApiKey.isBlank()) {
                    showNotice("Bạn chưa nhập Gemini API Key!")
                    return@setOnClickListener
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    requestMediaProjection()
                }
            }
        }
    }

    private fun requestMediaProjection() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun startTranslatorService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
        }
        startForegroundService(serviceIntent)
        updatePermissionAndServiceUi()
    }

    private fun observeFlows() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    FloatingBubbleService.isRunning.collect {
                        updatePermissionAndServiceUi()
                    }
                }
                launch {
                    ModelManager.downloadState.collect {
                        refreshHymtStatusUi()
                        updateActiveSummaryLine()
                    }
                }
                launch {
                    HymtEngineHolder.engineState.collect {
                        refreshHymtStatusUi()
                        updateActiveSummaryLine()
                    }
                }
            }
        }
    }

    private fun updatePermissionAndServiceUi() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val running = FloatingBubbleService.isRunning.value

        layoutOverlayPermission.visibility = if (hasOverlay) View.GONE else View.VISIBLE

        if (running) {
            viewStatusDot.setBackgroundResource(R.drawable.dot_status_active)
            tvStatusBadge.text = "ĐANG CHẠY"
            tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            tvStatusHeadline.text = "Nút nổi đang hoạt động trên màn hình"
            btnToggleService.text = "Dừng dịch màn hình"
            btnToggleService.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_danger_fill))
        } else {
            viewStatusDot.setBackgroundResource(R.drawable.dot_status_idle)
            tvStatusBadge.text = if (hasOverlay) "TẠM DỪNG" else "CHƯA CẤP QUYỀN"
            tvStatusBadge.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (hasOverlay) R.color.ink_muted else R.color.status_warning
                )
            )
            tvStatusHeadline.text = if (hasOverlay) {
                "Sẵn sàng dịch trên mọi ứng dụng"
            } else {
                "Cần quyền Hiển thị trên ứng dụng khác"
            }
            btnToggleService.text = getString(R.string.start_service)
            btnToggleService.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_vermilion))
        }
    }

    private fun updateActiveSummaryLine() {
        val src = AppLanguages.shortCode(prefs.sourceLanguage)
        val tgt = AppLanguages.shortCode(prefs.targetLanguage)
        val engineLabel = when (prefs.engineMode) {
            AppPreferences.ENGINE_HY_MT2 -> {
                val installed = ModelManager.getAnyInstalledVariant(this, prefs.hymtModelVariant)
                if (installed != null) "Hy-MT2 ${installed.quantLabel}" else "Hy-MT2 (Chưa tải)"
            }
            AppPreferences.ENGINE_GEMINI -> "Gemini AI"
            AppPreferences.ENGINE_CLOUD_FREE -> "Google Translate"
            else -> "Google ML Kit"
        }
        val modeLabel = when {
            !prefs.comicModeEnabled -> "Dịch thường"
            prefs.readingDirection == AppPreferences.DIRECTION_LTR -> "Webtoon L→R"
            else -> "Manga R→L"
        }
        tvActiveSummary.text = "$src → $tgt · $engineLabel · $modeLabel"
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
