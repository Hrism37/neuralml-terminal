package com.neuralml.terminal.ui

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.neuralml.terminal.core.SimulationEngine
import com.neuralml.terminal.util.HudKit
import java.util.Locale
import java.util.Random

/**
 * Left-lower panel: ML ENGINE STATUS.
 *
 * Model identity + inference mode, three accuracy/confidence gauges, then the
 * step counter, loss and inference latency in a compact three-column footer.
 */
class EngineStatusPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var engine: SimulationEngine? = null
    private val rng = Random()
    private var glitchUntil = 0L
    private var nextGlitchAt = 0L

    init {
        isClickable = false
        isFocusable = false
    }

    fun bind(engine: SimulationEngine) {
        this.engine = engine
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val e = engine ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 80f || h < 80f) return

        HudKit.panel(canvas, 0f, 0f, w, h, HudKit.CYAN)

        val density = resources.displayMetrics.density
        val pad = (9f * density).coerceAtMost(h * 0.035f)
        val titleSize = (h * 0.062f).coerceIn(9.5f * density, 15f * density)
        val titleBaseline = pad + titleSize

        val now = SystemClock.uptimeMillis()
        if (now >= nextGlitchAt) {
            glitchUntil = now + 60L + rng.nextInt(150)
            nextGlitchAt = now + 3200L + rng.nextInt(4200)
        }
        val titleText = "ML ENGINE STATUS"
        if (now < glitchUntil) {
            canvas.drawText(titleText, pad + 1.6f, titleBaseline + 1f, HudKit.textPaint(titleSize, HudKit.alpha(HudKit.RED, 120), bold = true))
            canvas.drawText(titleText, pad - 1.2f, titleBaseline - 0.8f, HudKit.textPaint(titleSize, HudKit.alpha(HudKit.GREEN, 100), bold = true))
        }
        HudKit.title(canvas, titleText, pad, titleBaseline, titleSize, HudKit.CYAN)

        val titleW = HudKit.textPaint(titleSize, HudKit.CYAN, bold = true).measureText(titleText)
        val subtitlePaint = HudKit.textPaint(titleSize * 0.72f, HudKit.GREY)
        val subtitle = "机器学习引擎"
        val subtitleW = subtitlePaint.measureText(subtitle)
        if (pad + titleW + pad + subtitleW < w - pad) {
            canvas.drawText(subtitle, w - pad - subtitleW, titleBaseline, subtitlePaint)
        }

        val contentTop = titleBaseline + titleSize * 0.85f
        val contentBottom = h - pad
        val contentH = contentBottom - contentTop
        if (contentH < 60f) return

        canvas.save()
        canvas.clipRect(0f, contentTop, w, contentBottom)

        val headerH = contentH * 0.27f
        val barsH = contentH * 0.44f
        val bottomH = contentH - headerH - barsH

        // ── header: model + mode ─────────────────────────────────────────────
        val headerLine1 = headerH * 0.36f
        val headerLine2 = headerH * 0.36f
        var modelSize = (headerLine1 * 0.86f).coerceIn(8f * density, 14f * density)
        val modelText = e.state.modelName
        var modelPaint = HudKit.textPaint(modelSize, HudKit.CYAN, bold = true)
        val modelAvail = w - pad * 2f
        if (modelPaint.measureText(modelText) > modelAvail) {
            modelSize = (modelSize * modelAvail / modelPaint.measureText(modelText)).coerceAtLeast(7f * density)
            modelPaint = HudKit.textPaint(modelSize, HudKit.CYAN, bold = true)
        }
        val modelBaseline = contentTop + headerLine1 * 0.80f
        canvas.drawText(modelText, pad, modelBaseline, modelPaint)

        val modeSize = (headerLine2 * 0.72f).coerceIn(7.5f * density, 12f * density)
        val modePaint = HudKit.textPaint(modeSize, HudKit.TEXT)
        val modeBaseline = contentTop + headerLine1 + headerLine2 * 0.74f
        canvas.drawText(e.state.inferenceMode, pad, modeBaseline, modePaint)

        val badgeText = "MODE · ${e.state.decodeModeLabel}"
        val badgePaint = HudKit.textPaint(modeSize, HudKit.GREEN, bold = true)
        val badgeW = badgePaint.measureText(badgeText) + 10f * density
        val badgeH = modeSize * 1.45f
        val badgeRight = w - pad
        val badgeLeft = badgeRight - badgeW
        if (badgeLeft > pad + maxOf(modelPaint.measureText(modelText), modePaint.measureText(e.state.inferenceMode)) + 6f * density) {
            canvas.drawRect(
                badgeLeft, modeBaseline - badgeH * 0.78f, badgeRight, modeBaseline + badgeH * 0.22f,
                HudKit.stroke(HudKit.alpha(HudKit.GREEN, 120), 1f)
            )
            canvas.drawText(badgeText, badgeLeft + 5f * density, modeBaseline - badgeH * 0.10f, badgePaint)
        }

        // ── three gauges ─────────────────────────────────────────────────────
        val barsTop = contentTop + headerH
        val barRowH = barsH / 3f
        drawGaugeRow(canvas, pad, w - pad, barsTop, barRowH, "FEATURE EXTRACTION · 特征提取", e.state.featureRate, HudKit.CYAN, density)
        drawGaugeRow(canvas, pad, w - pad, barsTop + barRowH, barRowH, "EMOTION CONFIDENCE · 情绪置信", e.state.emotionConfidence, HudKit.GREEN, density)
        drawGaugeRow(canvas, pad, w - pad, barsTop + barRowH * 2f, barRowH, "DECODE ACCURACY · 解码准确率", e.state.decodeAccuracy, HudKit.AMBER, density)

        // ── footer: step / loss / latency ────────────────────────────────────
        val footTop = contentTop + headerH + barsH
        val third = (w - pad * 2f) / 3f
        if (third > 95f * density) {
            drawFootStat(canvas, pad, pad + third, footTop, bottomH, 0, "STEP 迭代步数", String.format(Locale.US, "%,d", e.state.iteration), HudKit.CYAN, density)
            drawFootStat(canvas, pad + third, pad + third * 2f, footTop, bottomH, 1, "LOSS", String.format(Locale.US, "%.4f", e.state.loss), HudKit.GREEN, density)
            drawFootStat(canvas, pad + third, pad + third * 2f, footTop, bottomH, 2, "LATENCY", String.format(Locale.US, "%.0f ms", e.state.latencyMs), HudKit.AMBER, density)
        } else {
            val rowH = bottomH / 2f
            drawFootStat(canvas, pad, w - pad, footTop, rowH, 0, "STEP · 迭代步数", String.format(Locale.US, "%,d", e.state.iteration), HudKit.CYAN, density)
            val half = (w - pad * 2f) / 2f
            drawFootStat(canvas, pad, pad + half, footTop + rowH, rowH, 1, "LOSS", String.format(Locale.US, "%.4f", e.state.loss), HudKit.GREEN, density)
            drawFootStat(canvas, pad + half, w - pad, footTop + rowH, rowH, 2, "LATENCY", String.format(Locale.US, "%.0f ms", e.state.latencyMs), HudKit.AMBER, density)
        }
        canvas.restore()
    }

    private fun drawGaugeRow(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        rowH: Float,
        label: String,
        value: Float,
        color: Int,
        density: Float,
    ) {
        val textSize = (rowH * 0.34f).coerceIn(7f * density, 12f * density)
        val labelPaint = HudKit.textPaint(textSize, HudKit.GREY)
        val valuePaint = HudKit.textPaint(textSize, color, bold = true)
        val baseline = top + rowH * 0.44f
        val valueText = String.format(Locale.US, "%.1f%%", value)
        val valueW = valuePaint.measureText(valueText)
        canvas.drawText(valueText, right - valueW, baseline, valuePaint)
        canvas.save()
        canvas.clipRect(left, top, right - valueW - 6f * density, top + rowH)
        canvas.drawText(label, left, baseline, labelPaint)
        canvas.restore()

        val barTop = top + rowH * 0.58f
        val barBottom = barTop + (rowH * 0.20f).coerceIn(2.5f, 5f * density)
        HudKit.bar(canvas, left, barTop, right, barBottom, value / 100f, color)
    }

    private fun drawFootStat(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        height: Float,
        column: Int,
        label: String,
        value: String,
        color: Int,
        density: Float,
    ) {
        if (height < 16f) return
        val sepX = left - 5f * density
        if (column > 0) {
            canvas.drawLine(sepX, top + height * 0.12f, sepX, top + height * 0.88f, HudKit.stroke(HudKit.alpha(HudKit.CYAN, 40), 1f))
        }
        val labelSize = (height * 0.22f).coerceIn(6.5f * density, 10f * density)
        val valueSize = (height * 0.36f).coerceIn(8.5f * density, 13.5f * density)
        val labelPaint = HudKit.textPaint(labelSize, HudKit.GREY)
        val valuePaint = HudKit.textPaint(valueSize, color, bold = true)
        val labelBaseline = top + height * 0.40f
        val valueBaseline = top + height * 0.82f
        canvas.drawText(label, left, labelBaseline, labelPaint)
        canvas.drawText(value, left, valueBaseline, valuePaint)
    }
}
