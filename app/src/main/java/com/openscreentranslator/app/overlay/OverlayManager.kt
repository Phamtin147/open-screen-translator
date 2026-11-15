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
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = AppPreferences(context)

    private var bubbleView: View? = null
    private var bubbleButton: ImageButton? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val translationCards = mutableListOf<View>()

    private val handler = Handler(Looper.getMainLooper())
    private val autoClearRunnable = Runnable { clearTranslationCards() }

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

        windowManager.addView(bubbleView, bubbleParams)
    }

    fun removeFloatingBubble() {
        clearTranslationCards()
        bubbleView?.let {
            windowManager.removeView(it)
            bubbleView = null
            bubbleButton = null
        }
    }

    fun setBubbleLoading(loading: Boolean) {
        handler.post {
            bubbleButton?.apply {
                isEnabled = !loading
                setImageResource(
                    if (loading) R.drawable.ic_translate_active else R.drawable.ic_translate
                )
                setBackgroundResource(
                    if (loading) R.drawable.bubble_background_active else R.drawable.bubble_background
                )
                alpha = if (loading) 0.96f else 1.0f
            }
        }
    }

    fun drawTranslationCards(
        results: List<Pair<String, Rect>>,
        showBubbleOrderIndex: Boolean = prefs.comicModeEnabled && results.size > 1
    ) {
        handler.post {
            clearTranslationCardsImmediate()
            if (results.isEmpty()) return@post

            val themedCtx = createThemedOverlayContext()
            val (screenW, screenH) = currentScreenBounds()
            val safeMargin = dpToPx(8)
            val topMargin = dpToPx(24)
            val bottomMargin = dpToPx(48)

            val alphaPercent = prefs.overlayOpacity.coerceIn(65, 100)
            val bgAlpha = (alphaPercent * 255) / 100
            val textSizeSp = prefs.textSize.coerceIn(10, 24).toFloat()

            val baseBgColor = ContextCompat.getColor(themedCtx, R.color.overlay_card_bg)
            val strokeColor = ContextCompat.getColor(themedCtx, R.color.overlay_card_stroke)
            val textColor = ContextCompat.getColor(themedCtx, R.color.overlay_card_text)
            val badgeColor = ContextCompat.getColor(themedCtx, R.color.overlay_badge_bg)
            val bgColorWithAlpha = ColorUtils.setAlphaComponent(baseBgColor, bgAlpha)

            results.forEachIndexed { idx, (translatedText, rect) ->
                if (translatedText.isBlank()) return@forEachIndexed

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

                // Expand narrow vertical CJK speech bubbles horizontally so Vietnamese text reads cleanly
                val maxAllowedWidth = (screenW - safeMargin * 2).coerceAtLeast(dpToPx(120))
                val isVerticalBubble = rect.height() > rect.width() * 1.35f
                val targetWidth = if (isVerticalBubble) {
                    maxOf(rect.width(), dpToPx(188)).coerceAtMost(maxAllowedWidth)
                } else {
                    maxOf(rect.width(), dpToPx(104)).coerceAtMost(maxAllowedWidth)
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
                    windowManager.removeView(tv)
                    translationCards.remove(tv)
                    true
                }

                windowManager.addView(tv, params)
                translationCards.add(tv)
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
