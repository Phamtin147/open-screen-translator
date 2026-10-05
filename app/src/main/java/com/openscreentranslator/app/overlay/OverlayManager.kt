package com.openscreentranslator.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.openscreentranslator.app.R
import com.openscreentranslator.app.data.AppPreferences
import java.util.concurrent.CopyOnWriteArrayList

class OverlayManager(
    private val context: Context,
    private val onBubbleClick: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = AppPreferences(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val activeTranslationViews = CopyOnWriteArrayList<View>()

    private var autoClearRunnable: Runnable? = null
    private var isShowing = false

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    fun isShowingTranslations(): Boolean = isShowing

    fun showFloatingBubble() {
        if (bubbleView != null) return

        mainHandler.post {
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.layout_floating_bubble, null)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 50
                y = 300
            }

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isClick = false

            view.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isClick = false
                        }
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: Exception) {}
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            onBubbleClick()
                        }
                        true
                    }
                    else -> false
                }
            }

            try {
                windowManager.addView(view, params)
                bubbleView = view
                bubbleParams = params
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setBubbleLoading(isLoading: Boolean) {
        mainHandler.post {
            bubbleView?.let { view ->
                val icon = view.findViewById<ImageView>(R.id.bubble_icon)
                val progress = view.findViewById<ProgressBar>(R.id.bubble_progress)
                if (isLoading) {
                    icon.visibility = View.GONE
                    progress.visibility = View.VISIBLE
                } else {
                    progress.visibility = View.GONE
                    icon.visibility = View.VISIBLE
                    updateBubbleIcon()
                }
            }
        }
    }

    private fun updateBubbleIcon() {
        bubbleView?.let { view ->
            val icon = view.findViewById<ImageView>(R.id.bubble_icon)
            if (isShowing) {
                icon.setImageResource(R.drawable.ic_close)
            } else {
                icon.setImageResource(R.drawable.ic_translate)
            }
        }
    }

    fun drawTranslationCards(translations: List<Pair<String, Rect>>) {
        mainHandler.post {
            // Cancel any previous auto-clear timer
            cancelAutoClearTimer()
            clearTranslationViewsInternal()

            if (translations.isEmpty()) {
                isShowing = false
                updateBubbleIcon()
                return@post
            }

            val alphaVal = (prefs.overlayOpacity * 255 / 100).coerceIn(0, 255)
            val textSizeSp = prefs.textSize.toFloat()

            for ((translatedText, rect) in translations) {
                val textView = TextView(context).apply {
                    text = translatedText
                    setTextColor(Color.WHITE)
                    textSize = textSizeSp
                    gravity = Gravity.CENTER
                    val pad = dpToPx(6)
                    setPadding(pad, pad, pad, pad)
                }

                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(8).toFloat()
                    setColor(Color.argb(alphaVal, 15, 23, 42)) // Slate 900 background
                    setStroke(dpToPx(1), Color.argb(alphaVal, 0, 230, 118)) // Accent green border
                }
                textView.background = bg

                val minWidth = dpToPx(60)
                val width = rect.width().coerceAtLeast(minWidth)
                val params = WindowManager.LayoutParams(
                    width,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = rect.left
                    y = rect.top
                }

                // Long press on a card to dismiss just this card
                textView.setOnLongClickListener {
                    removeViewSafely(textView)
                    true
                }

                try {
                    windowManager.addView(textView, params)
                    activeTranslationViews.add(textView)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            isShowing = true
            updateBubbleIcon()

            // Schedule auto-clear only if user set it > 0 (0 = manual toggle only)
            val delaySeconds = prefs.autoClearSeconds
            if (delaySeconds > 0) {
                autoClearRunnable = Runnable {
                    clearTranslationCards()
                }
                mainHandler.postDelayed(autoClearRunnable!!, delaySeconds * 1000L)
            }
        }
    }

    private fun cancelAutoClearTimer() {
        autoClearRunnable?.let {
            mainHandler.removeCallbacks(it)
            autoClearRunnable = null
        }
    }

    fun clearTranslationCards() {
        mainHandler.post {
            cancelAutoClearTimer()
            clearTranslationViewsInternal()
            isShowing = false
            updateBubbleIcon()
        }
    }

    private fun clearTranslationViewsInternal() {
        for (view in activeTranslationViews) {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {}
        }
        activeTranslationViews.clear()
    }

    private fun removeViewSafely(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {}
        activeTranslationViews.remove(view)
        if (activeTranslationViews.isEmpty()) {
            isShowing = false
            updateBubbleIcon()
        }
    }

    fun removeFloatingBubble() {
        mainHandler.post {
            cancelAutoClearTimer()
            clearTranslationViewsInternal()
            bubbleView?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {}
                bubbleView = null
            }
            isShowing = false
        }
    }
}
