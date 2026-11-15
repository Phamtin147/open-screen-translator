package com.openscreentranslator.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.openscreentranslator.app.R
import com.openscreentranslator.app.ocr.ReadingDirection

/**
 * Interactive Celestial Star Atlas schematic demonstrating speech-bubble reading order (RTL vs LTR),
 * multi-line unwrapping, and live overlay opacity + font-size calibration.
 */
class ComicPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var readingDirection: ReadingDirection = ReadingDirection.MANGA_RTL
        set(value) {
            field = value
            invalidate()
        }

    var comicModeEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var overlayOpacityPercent: Int = 92
        set(value) {
            field = value.coerceIn(65, 100)
            invalidate()
        }

    var previewTextSizeSp: Int = 14
        set(value) {
            field = value.coerceIn(10, 24)
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    }

    private val bubbleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val overlayFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val overlayStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
    }

    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            10f,
            resources.displayMetrics
        )
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val metaTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            11f,
            resources.displayMetrics
        )
        typeface = Typeface.DEFAULT_BOLD
    }

    private val overlayTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
    }

    // Preallocated rects to avoid object allocations inside onDraw during slider drags.
    private val leftPanel = RectF()
    private val rightTopPanel = RectF()
    private val rightBotPanel = RectF()
    private val bLeft = RectF()
    private val bRightTop = RectF()
    private val bRightBot = RectF()
    private val liveCard = RectF()
    private val badgePillRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val hairline = ContextCompat.getColor(context, R.color.hairline_strong)
        val surfaceCard = ContextCompat.getColor(context, R.color.surface_card)
        val surfaceRecessed = ContextCompat.getColor(context, R.color.surface_recessed)
        val inkPrimary = ContextCompat.getColor(context, R.color.ink_primary)
        val inkMuted = ContextCompat.getColor(context, R.color.ink_muted)
        val primaryBadgeBg = ContextCompat.getColor(context, R.color.accent_vermilion)
        val badgeOnAccent = ContextCompat.getColor(context, R.color.overlay_badge_text)
        val overlayBg = ContextCompat.getColor(context, R.color.overlay_card_bg)
        val overlayStroke = ContextCompat.getColor(context, R.color.overlay_card_stroke)
        val overlayText = ContextCompat.getColor(context, R.color.overlay_card_text)

        framePaint.color = hairline
        dashedPaint.color = ColorUtils.setAlphaComponent(inkMuted, 130)
        bubbleFillPaint.color = ColorUtils.setAlphaComponent(surfaceCard, 215)
        badgeFillPaint.color = primaryBadgeBg
        badgeTextPaint.color = badgeOnAccent
        metaTextPaint.color = inkMuted

        val pad = 8f * density
        val gutter = 7f * density

        // Left tall comic panel & Right 2 stacked panels (classic layout from unit tests)
        val midX = w * 0.48f
        leftPanel.set(pad, pad, midX - gutter * 0.5f, h - pad)
        rightTopPanel.set(midX + gutter * 0.5f, pad, w - pad, h * 0.5f - gutter * 0.5f)
        rightBotPanel.set(midX + gutter * 0.5f, h * 0.5f + gutter * 0.5f, w - pad, h - pad)

        val panelRadius = 8f * density
        canvas.drawRoundRect(leftPanel, panelRadius, panelRadius, framePaint)
        canvas.drawRoundRect(rightTopPanel, panelRadius, panelRadius, framePaint)
        canvas.drawRoundRect(rightBotPanel, panelRadius, panelRadius, framePaint)

        // Subtle meridian reticle tick in top-left corner of left panel
        val tickLen = 6f * density
        canvas.drawLine(leftPanel.left + 6f * density, leftPanel.top + 10f * density, leftPanel.left + 6f * density + tickLen, leftPanel.top + 10f * density, dashedPaint)

        // Speech bubble slots
        bLeft.set(leftPanel.left + 10f * density, leftPanel.top + 22f * density, leftPanel.right - 10f * density, leftPanel.bottom - 14f * density)
        bRightTop.set(rightTopPanel.left + 8f * density, rightTopPanel.top + 8f * density, rightTopPanel.right - 8f * density, rightTopPanel.bottom - 8f * density)
        bRightBot.set(rightBotPanel.left + 8f * density, rightBotPanel.top + 8f * density, rightBotPanel.right - 8f * density, rightBotPanel.bottom - 8f * density)

        // Draw underlying speech bubble silhouettes
        canvas.drawRoundRect(bLeft, 10f * density, 10f * density, bubbleFillPaint)
        canvas.drawRoundRect(bLeft, 10f * density, 10f * density, dashedPaint)
        canvas.drawRoundRect(bRightTop, 10f * density, 10f * density, bubbleFillPaint)
        canvas.drawRoundRect(bRightTop, 10f * density, 10f * density, dashedPaint)
        canvas.drawRoundRect(bRightBot, 10f * density, 10f * density, bubbleFillPaint)
        canvas.drawRoundRect(bRightBot, 10f * density, 10f * density, dashedPaint)

        val leadRect: RectF
        val secondRect: RectF
        val thirdRect: RectF
        if (readingDirection == ReadingDirection.MANGA_RTL) {
            leadRect = bRightTop
            secondRect = bRightBot
            thirdRect = bLeft
        } else {
            leadRect = bLeft
            secondRect = bRightTop
            thirdRect = bRightBot
        }

        // Draw secondary slots first so expanded live card renders cleanly on top
        drawBubbleHint(canvas, secondRect, if (comicModeEnabled) "Thoại theo ngữ cảnh" else "Dịch tách rời", inkMuted)
        drawBubbleHint(canvas, thirdRect, if (comicModeEnabled) "Giữ đúng đại từ" else "Mất mạch thoại", inkMuted)
        drawOrderBadge(canvas, secondRect, if (comicModeEnabled) "02" else "B", false, primaryBadgeBg, surfaceRecessed, badgeOnAccent, inkPrimary, hairline)
        drawOrderBadge(canvas, thirdRect, if (comicModeEnabled) "03" else "C", false, primaryBadgeBg, surfaceRecessed, badgeOnAccent, inkPrimary, hairline)

        // Draw active sample translated overlay card on slot [01] using exact opacity & sp size
        val alpha255 = (overlayOpacityPercent * 255) / 100
        overlayFillPaint.color = ColorUtils.setAlphaComponent(overlayBg, alpha255)
        overlayStrokePaint.color = overlayStroke

        val sampleTranslation = if (comicModeEnabled) {
            "Đợi đã! Câu này được gộp liền mạch."
        } else {
            "Đợi đã!\nCâu này bị ngắt dòng."
        }

        overlayTextPaint.color = overlayText
        overlayTextPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            previewTextSizeSp.coerceIn(10, 24).toFloat(),
            resources.displayMetrics
        )

        val innerPad = 6f * density
        val availWidth = (leadRect.width() - innerPad * 2).toInt().coerceAtLeast((48 * density).toInt())
        val textLayout = StaticLayout.Builder
            .obtain(sampleTranslation, 0, sampleTranslation.length, overlayTextPaint, availWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(3)
            .build()

        val cardBottom = maxOf(leadRect.bottom, leadRect.top + textLayout.height + innerPad * 2 + 14f * density)
            .coerceAtMost(h - 4f * density)
        liveCard.set(leadRect.left, leadRect.top, leadRect.right, cardBottom)
        canvas.drawRoundRect(liveCard, 8f * density, 8f * density, overlayFillPaint)
        canvas.drawRoundRect(liveCard, 8f * density, 8f * density, overlayStrokePaint)

        canvas.save()
        canvas.clipRect(liveCard.left + innerPad, liveCard.top + innerPad + 8f * density, liveCard.right - innerPad, liveCard.bottom - innerPad)
        canvas.translate(liveCard.left + innerPad, liveCard.top + innerPad + 8f * density)
        textLayout.draw(canvas)
        canvas.restore()

        // Lead order badge [01]
        drawOrderBadge(canvas, liveCard, if (comicModeEnabled) "01" else "A", true, primaryBadgeBg, surfaceRecessed, badgeOnAccent, inkPrimary, hairline)
    }

    private fun drawBubbleHint(canvas: Canvas, rect: RectF, text: String, color: Int) {
        metaTextPaint.color = ColorUtils.setAlphaComponent(color, 210)
        metaTextPaint.textAlign = Paint.Align.CENTER
        val cy = rect.centerY() - (metaTextPaint.descent() + metaTextPaint.ascent()) * 0.5f
        canvas.drawText(text, rect.centerX(), cy, metaTextPaint)
        metaTextPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawOrderBadge(
        canvas: Canvas,
        rect: RectF,
        label: String,
        isPrimary: Boolean,
        primaryBg: Int,
        secondaryBg: Int,
        primaryText: Int,
        secondaryText: Int,
        strokeColor: Int
    ) {
        val bw = 24f * density
        val bh = 16f * density
        val bx = rect.left + 5f * density
        val by = (rect.top - bh * 0.35f).coerceAtLeast(2f * density)

        badgePillRect.set(bx, by, bx + bw, by + bh)
        badgeFillPaint.color = if (isPrimary) primaryBg else secondaryBg
        canvas.drawRoundRect(badgePillRect, 4f * density, 4f * density, badgeFillPaint)
        if (!isPrimary) {
            framePaint.color = strokeColor
            canvas.drawRoundRect(badgePillRect, 4f * density, 4f * density, framePaint)
        }
        badgeTextPaint.color = if (isPrimary) primaryText else secondaryText
        badgeTextPaint.textAlign = Paint.Align.CENTER
        val textY = badgePillRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) * 0.5f
        canvas.drawText(label, badgePillRect.centerX(), textY, badgeTextPaint)
    }
}
