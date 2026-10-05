package com.openscreentranslator.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import com.openscreentranslator.app.R
import com.openscreentranslator.app.data.AppPreferences
import com.openscreentranslator.app.service.FloatingBubbleService

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences
    private var isServiceRunning = false

    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusDesc: TextView
    private lateinit var btnToggleService: Button
    private lateinit var spinnerSource: Spinner
    private lateinit var spinnerTarget: Spinner
    private lateinit var spinnerMode: Spinner
    private lateinit var sliderOpacity: Slider
    private lateinit var sliderTextSize: Slider
    private lateinit var tvOpacityLabel: TextView
    private lateinit var tvTextSizeLabel: TextView

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startFloatingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Bạn cần đồng ý quyền chụp màn hình để dịch!", Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (checkOverlayPermission()) {
            requestScreenCapture()
        } else {
            Toast.makeText(this, "Vui lòng cấp quyền hiển thị trên ứng dụng khác!", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Continue even if denied, but good practice
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = AppPreferences(this)
        initViews()
        setupListeners()
        requestNotificationPermission()
    }

    private fun initViews() {
        tvStatusTitle = findViewById(R.id.tv_status_title)
        tvStatusDesc = findViewById(R.id.tv_status_desc)
        btnToggleService = findViewById(R.id.btn_toggle_service)
        spinnerSource = findViewById(R.id.spinner_source_lang)
        spinnerTarget = findViewById(R.id.spinner_target_lang)
        spinnerMode = findViewById(R.id.spinner_engine_mode)
        sliderOpacity = findViewById(R.id.slider_opacity)
        sliderTextSize = findViewById(R.id.slider_text_size)
        tvOpacityLabel = findViewById(R.id.tv_opacity_label)
        tvTextSizeLabel = findViewById(R.id.tv_text_size_label)

        // Languages mapping
        val languages = listOf(
            "Tiếng Anh (en)" to "en",
            "Tiếng Nhật (ja)" to "ja",
            "Tiếng Trung (zh)" to "zh",
            "Tiếng Hàn (ko)" to "ko",
            "Tiếng Việt (vi)" to "vi"
        )

        val langDisplayNames = languages.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langDisplayNames)
        spinnerSource.adapter = adapter
        spinnerTarget.adapter = adapter

        // Set default selection
        val srcIndex = languages.indexOfFirst { it.second == prefs.sourceLanguage }.coerceAtLeast(0)
        spinnerSource.setSelection(srcIndex)

        val targetIndex = languages.indexOfFirst { it.second == prefs.targetLanguage }.coerceAtLeast(4) // default vi
        spinnerTarget.setSelection(targetIndex)

        // Engine modes
        val modes = listOf(
            "On-Device (ML Kit - 100% Offline, 0% Data)" to "on_device",
            "Cloud Free (Google Translate Endpoint - Không cần Key)" to "cloud_free"
        )
        val modeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes.map { it.first })
        spinnerMode.adapter = modeAdapter
        val modeIndex = modes.indexOfFirst { it.second == prefs.engineMode }.coerceAtLeast(0)
        spinnerMode.setSelection(modeIndex)

        // Sliders
        sliderOpacity.value = prefs.overlayOpacity.toFloat()
        tvOpacityLabel.text = "Độ trong suốt khung dịch: ${prefs.overlayOpacity}%"

        sliderTextSize.value = prefs.textSize.toFloat()
        tvTextSizeLabel.text = "Cỡ chữ bản dịch: ${prefs.textSize}sp"
    }

    private fun setupListeners() {
        btnToggleService.setOnClickListener {
            if (!isServiceRunning) {
                if (!checkOverlayPermission()) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    overlayPermissionLauncher.launch(intent)
                } else {
                    requestScreenCapture()
                }
            } else {
                stopFloatingService()
            }
        }

        val languages = listOf("en", "ja", "zh", "ko", "vi")
        spinnerSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.sourceLanguage = languages[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.targetLanguage = languages[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val modes = listOf("on_device", "cloud_free")
        spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.engineMode = modes[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        sliderOpacity.addOnChangeListener { _, value, _ ->
            prefs.overlayOpacity = value.toInt()
            tvOpacityLabel.text = "Độ trong suốt khung dịch: ${value.toInt()}%"
        }

        sliderTextSize.addOnChangeListener { _, value, _ ->
            prefs.textSize = value.toInt()
            tvTextSizeLabel.text = "Cỡ chữ bản dịch: ${value.toInt()}sp"
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestScreenCapture() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun startFloatingService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        isServiceRunning = true
        tvStatusTitle.text = "Trạng thái: Đang hoạt động"
        tvStatusDesc.text = "Bong bóng dịch đang nổi trên màn hình. Mở game/app khác và chạm vào để dịch."
        btnToggleService.text = "DỪNG DỊCH NỀN"
        btnToggleService.setBackgroundColor(getColor(android.R.color.holo_red_dark))
    }

    private fun stopFloatingService() {
        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = "ACTION_STOP"
        }
        startService(stopIntent)

        isServiceRunning = false
        tvStatusTitle.text = "Trạng thái: Chưa kích hoạt"
        tvStatusDesc.text = "Nhấn nút bên dưới để khởi động bong bóng dịch chạy nền."
        btnToggleService.text = "BẬT DỊCH NỀN"
        btnToggleService.setBackgroundColor(getColor(R.color.primary))
    }
}
