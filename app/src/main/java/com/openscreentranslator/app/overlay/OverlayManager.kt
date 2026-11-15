package com.openscreentranslator.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.openscreentranslator.app.R
import com.openscreentranslator.app.data.AppPreferences
import kotlin.math.abs

class OverlayManager(
    private val context: Context,
    private val onBubbleClick: () -> Unit
) {
    private val TAG = "OverlayManager"
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = AppPreferences(context)

    private var bubbleView: View? = null
    private var bubbleButton: ImageButton? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var dismissBackdropView: View? = null
    private var statusPillView: TextView? = null
    private val translationCards = mutableListOf<View>()

    private val handler = Handler(Looper.getMainLooper())
    private val autoClearRunnable = Runnable { clearTranslationCards() }
    private val hideStatusRunnable = Runnable { hideStatusPillImmediate() }

    private val layoutType: Int
        get() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    @SuppressLint("ClickableViewAccessibility")
    fun showFloatingBubble() {
        if (bubbleView != null) return

        bubbleView = LayoutInflater.from(context).inflate(R.layout.layout_floating_bubble, null)
        bubbleButton = bubbleView?.findViewById(R.id.btnFloatingBubble)

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 320
        }

        val clickThreshold = dpToPx(10)
        bubbleButton?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = bubbleParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoved = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (abs(dx) > clickThreshold || abs(dy) > clickThreshold) {
                            isMoved = true
                        }
                        params.x = initialX + dx
                        params.y = initialY + dy
                        bubbleView?.let { windowManager.updateViewLayout(it, params) }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!isMoved) {
                            onBubbleClick()
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(bubbleView, bubbleParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach floating bubble", e)
            bubbleView = null
            bubbleButton = null
        }
    }

    fun removeFloatingBubble() {
        clearTranslationCards()
        hideStatusPillImmediate()
        bubbleView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
            bubbleView = null
            bubbleButton = null
        }
    }

    fun setBubbleLoading(loading: Boolean) {
        handler.post {
            bubbleButton?.apply {
                isEnabled = true
                setImageResource(
                    if (loading) R.drawable.ic_translate_active else R.drawable.ic_translate
                )
                setBackgroundResource(
                    if (loading) R.drawable.bubble_background_active else R.drawable.bubble_background
                )
                alpha = if (loading) 0.88f else 1.0f
            }
        }
    }

    fun showStatusPill(message: String, autoHideMs: Long = 0L) {
        handler.post {
            handler.removeCallbacks(hideStatusRunnable)
            val pill = statusPillView ?: TextView(createThemedOverlayContext()).apply {
                setTextColor(Color.parseColor("#F4F7FB"))
                textSize = 12.5f
                fontFeatureSettings = "tnum"
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(20).toFloat()
                    setColor(Color.parseColor("#EB091326"))
                    setStroke(dpToPx(1), Color.parseColor("#D8C7A8"))
                }
            }.also { created ->
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dpToPx(42)
                }
                try {
                    windowManager.addView(created, params)
                    statusPillView = created
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to show status pill", e)
                    return@post
                }
            }

            pill.text = message
            if (autoHideMs > 0L) {
                handler.postDelayed(hideStatusRunnable, autoHideMs)
            }
        }
    }

    fun hideStatusPill() {
        handler.post { hideStatusPillImmediate() }
    }

    private fun hideStatusPillImmediate() {
        handler.removeCallbacks(hideStatusRunnable)
        statusPillView?.let { pill ->
            try {
                windowManager.removeView(pill)
            } catch (_: Exception) {
            }
            statusPillView = null
        }
    }

    fun drawTranslationCards(
        results: List<Pair<String, Rect>>,
        showBubbleOrderIndex: Boolean = prefs.comicModeEnabled && results.size > 1
    ) {
        handler.post {
            clearTranslationCardsImmediate()
            val nonBlankResults = results.filter { it.first.isNotBlank() }
            if (nonBlankResults.isEmpty()) {
                hideStatusPillImmediate()
                return@post
            }

            hideStatusPillImmediate()
            val themedCtx = createThemedOverlayContext()
            val (screenW, screenH) = currentScreenBounds()
            val safeMargin = dpToPx(8)
            val topMargin = dpToPx(24)
            val bottomMargin = dpToPx(48)

            // Full-screen transparent backdrop so tapping ANYWHERE on screen dismisses translation cards
            // even when cards overlap or hide the floating bubble button.
            val backdrop = View(themedCtx).apply {
                setBackgroundColor(Color.argb(14, 8, 17, 33))
                setOnClickListener { clearTranslationCards() }
            }
            val backdropParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            try {
                windowManager.addView(backdrop, backdropParams)
                dismissBackdropView = backdrop
            } catch (e: Exception) {
                Log.w(TAG, "Failed to attach tap-anywhere dismiss backdrop", e)
                dismissBackdropView = null
            }

            val alphaPercent = prefs.overlayOpacity.coerceIn(65, 100)
            val bgAlpha = (alphaPercent * 255) / 100
            val textSizeSp = prefs.textSize.coerceIn(10, 24).toFloat()

            val baseBgColor = ContextCompat.getColor(themedCtx, R.color.overlay_card_bg)
            val strokeColor = ContextCompat.getColor(themedCtx, R.color.overlay_card_stroke)
            val textColor = ContextCompat.getColor(themedCtx, R.color.overlay_card_text)
            val badgeColor = ContextCompat.getColor(themedCtx, R.color.overlay_badge_bg)
            val bgColorWithAlpha = ColorUtils.setAlphaComponent(baseBgColor, bgAlpha)

            nonBlankResults.forEachIndexed { idx, (translatedText, rect) ->

                val tv = TextView(themedCtx).apply {
                    setTextColor(textColor)
                    this.textSize = textSizeSp
                    fontFeatureSettings = "tnum"
                    setLineSpacing(dpToPx(2).toFloat(), 1.08f)
                    gravity = Gravity.CENTER

                    text = if (showBubbleOrderIndex) {
                        val prefix = "[%02d] ".format(idx + 1)
                        SpannableStringBuilder(prefix).apply {
                            setSpan(ForegroundColorSpan(badgeColor), 0, prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            setSpan(StyleSpan(Typeface.BOLD), 0, prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            setSpan(RelativeSizeSpan(0.84f), 0, prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            append(translatedText)
                        }
                    } else {
                        translatedText
                    }

                    setPadding(dpToPx(9), dpToPx(6), dpToPx(9), dpToPx(6))

                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dpToPx(10).toFloat()
                        setColor(bgColorWithAlpha)
                        setStroke(dpToPx(1), strokeColor)
                    }
                }

                // Expand narrow vertical CJK speech bubbles and allow slight width breathing room for Vietnamese text
                val maxAllowedWidth = (screenW - safeMargin * 2).coerceAtLeast(dpToPx(120))
                val isVerticalBubble = rect.height() > rect.width() * 1.35f
                val targetWidth = if (isVerticalBubble) {
                    maxOf(rect.width(), dpToPx(188)).coerceAtMost(maxAllowedWidth)
                } else {
                    maxOf((rect.width() * 1.18f).toInt(), dpToPx(112)).coerceAtMost(maxAllowedWidth)
                }

                // Estimate height for safe vertical clamping within viewport
                val widthSpec = View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST)
                tv.measure(widthSpec, heightSpec)
                val estHeight = tv.measuredHeight.coerceAtLeast(dpToPx(36))

                val desiredX = rect.centerX() - targetWidth / 2
                val clampedX = desiredX.coerceIn(safeMargin, (screenW - safeMargin - targetWidth).coerceAtLeast(safeMargin))

                val desiredY = if (isVerticalBubble) {
                    (rect.centerY() - estHeight / 2).coerceAtLeast(rect.top)
                } else {
                    rect.top
                }
                val maxY = (screenH - bottomMargin - estHeight).coerceAtLeast(topMargin)
                val clampedY = desiredY.coerceIn(topMargin, maxY)

                val params = WindowManager.LayoutParams(
                    targetWidth,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = clampedX
                    y = clampedY
                }

                tv.setOnClickListener {
                    clearTranslationCards()
                }
                tv.setOnLongClickListener {
                    try {
                        windowManager.removeView(tv)
                    } catch (_: Exception) {
                    }
                    translationCards.remove(tv)
                    if (translationCards.isEmpty()) {
                        clearTranslationCardsImmediate()
                    }
                    true
                }

                try {
                    windowManager.addView(tv, params)
                    translationCards.add(tv)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to attach translation card #$idx", e)
                }
            }

            if (translationCards.isEmpty()) {
                clearTranslationCardsImmediate()
                return@post
            }

            val autoClearSec = prefs.autoClearSeconds
            if (autoClearSec > 0) {
                handler.postDelayed(autoClearRunnable, autoClearSec * 1000L)
            }
        }
    }

    fun clearTranslationCards() {
        handler.post { clearTranslationCardsImmediate() }
    }

    private fun clearTranslationCardsImmediate() {
        handler.removeCallbacks(autoClearRunnable)
        val iterator = translationCards.iterator()
        while (iterator.hasNext()) {
            val view = iterator.next()
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
            iterator.remove()
        }
        dismissBackdropView?.let { backdrop ->
            try {
                windowManager.removeView(backdrop)
            } catch (_: Exception) {
            }
            dismissBackdropView = null
        }
    }

    fun isShowingTranslations(): Boolean = translationCards.isNotEmpty()

    private fun createThemedOverlayContext(): Context {
        val uiNightFlag = when (prefs.themeMode) {
            AppPreferences.THEME_LIGHT -> Configuration.UI_MODE_NIGHT_NO
            AppPreferences.THEME_DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        }
        val config = Configuration(context.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or uiNightFlag
        return context.createConfigurationContext(config)
    }

    private fun currentScreenBounds(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
