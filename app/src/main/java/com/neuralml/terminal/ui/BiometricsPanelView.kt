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
 * Left-upper panel: TARGET BIOMETRICS.
 *
 * Nine stacked rows — target id, four EEG bands (α/β/θ/δ, in Hz), heart rate,
 * galvanic skin response, attention and neural activity. Every numeric row has a
 * horizontal bar that follows the (already smoothed) engine value.
 */
class BiometricsPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var engine: SimulationEngine? = null
    private val rng = Random()
    private var glitchUntil = 0L
    private var nextGlitchAt = 0L

    private val labels = arrayOf(
        "TARGET · 目标编号",
        "α ALPHA · α波",
        "β BETA · β波",
        "θ THETA · θ波",
        "δ DELTA · δ波",
        "HR · 心率",
        "GSR · 皮电",
        "ATTENTION · 注意力",
        "NEURO ACT · 神经活跃",
    )

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

        // Low-frequency glitch on the panel title.
        val now = SystemClock.uptimeMillis()
        if (now >= nextGlitchAt) {
            glitchUntil = now + 60L + rng.nextInt(150)
            nextGlitchAt = now + 2800L + rng.nextInt(4200)
        }
        val titleText = "TARGET BIOMETRICS"
        if (now < glitchUntil) {
            canvas.drawText(titleText, pad + 1.6f, titleBaseline + 1f, HudKit.textPaint(titleSize, HudKit.alpha(HudKit.RED, 120), bold = true))
            canvas.drawText(titleText, pad - 1.2f, titleBaseline - 0.8f, HudKit.textPaint(titleSize, HudKit.alpha(HudKit.GREEN, 100), bold = true))
        }
        HudKit.title(canvas, titleText, pad, titleBaseline, titleSize, HudKit.CYAN)

        val titleW = HudKit.textPaint(titleSize, HudKit.CYAN, bold = true).measureText(titleText)
        val subtitlePaint = HudKit.textPaint((titleSize * 0.72f), HudKit.GREY)
        val subtitle = "目标生物特征 · BIO-LINK 4CH"
        val subtitleW = subtitlePaint.measureText(subtitle)
        if (pad + titleW + pad + subtitleW < w - pad) {
            canvas.drawText(subtitle, w - pad - subtitleW, titleBaseline, subtitlePaint)
        }

        val contentTop = titleBaseline + titleSize * 0.75f
        val contentBottom = h - pad
        val contentH = contentBottom - contentTop
        if (contentH < 40f) return

        val rowH = contentH / labels.size
        val textSize = minOf(rowH * 0.42f, 13.5f * density).coerceAtLeast(4.5f * density)
        val labelPaint = HudKit.textPaint(textSize, HudKit.GREY)
        val valuePaint = HudKit.textPaint(textSize * 1.04f, HudKit.CYAN, bold = true)
        val barH = (rowH * 0.15f).coerceIn(2f, 4.5f * density)
        val dividerPaint = HudKit.stroke(HudKit.alpha(HudKit.CYAN, 26), 1f)

        val s = e.state
        canvas.save()
        canvas.clipRect(0f, contentTop, w, contentBottom)

        for (i in labels.indices) {
            val rowTop = contentTop + rowH * i
            val isIdRow = i == 0
            val textBaseline = if (isIdRow) rowTop + rowH * 0.58f else rowTop + rowH * 0.44f

            var valueText: String
            var fraction: Float
            var barColor = HudKit.CYAN
            when (i) {
                0 -> {
                    valueText = s.targetId
                    fraction = 0f
                }
                1 -> {
                    valueText = String.format(Locale.US, "%.1f Hz", s.alphaHz)
                    fraction = (s.alphaHz - 8f) / 5f
                    barColor = HudKit.CYAN
                }
                2 -> {
                    valueText = String.format(Locale.US, "%.1f Hz", s.betaHz)
                    fraction = (s.betaHz - 14f) / 16f
                    barColor = HudKit.GREEN
                }
                3 -> {
                    valueText = String.format(Locale.US, "%.1f Hz", s.thetaHz)
                    fraction = (s.thetaHz - 4f) / 4f
                    barColor = HudKit.AMBER
                }
                4 -> {
                    valueText = String.format(Locale.US, "%.1f Hz", s.deltaHz)
                    fraction = (s.deltaHz - 0.5f) / 3.5f
                    barColor = HudKit.BLUE
                }
                5 -> {
                    valueText = String.format(Locale.US, "%.0f BPM", s.heartRate)
                    fraction = (s.heartRate - 55f) / 55f
                    barColor = HudKit.RED
                }
                6 -> {
                    valueText = String.format(Locale.US, "%.1f µS", s.gsr)
                    fraction = s.gsr / 12f
                    barColor = HudKit.AMBER
                }
                7 -> {
                    valueText = String.format(Locale.US, "%.0f%%", s.attention)
                    fraction = s.attention / 100f
                    barColor = HudKit.GREEN
                }
                else -> {
                    valueText = String.format(Locale.US, "%.0f%%", s.neuralActivity)
                    fraction = s.neuralActivity / 100f
                    barColor = HudKit.CYAN
                }
            }

            if (i > 0) {
                canvas.drawLine(pad, rowTop, w - pad, rowTop, dividerPaint)
            }

            val label = labels[i]
            val labelMax = w - pad * 2f - valuePaint.measureText(valueText) - pad
            canvas.save()
            canvas.clipRect(pad, rowTop, pad + labelMax.coerceAtLeast(0f), rowTop + rowH)
            canvas.drawText(label, pad, textBaseline, labelPaint)
            canvas.restore()

            canvas.drawText(valueText, w - pad - valuePaint.measureText(valueText), textBaseline, valuePaint)

            if (!isIdRow) {
                val barTop = rowTop + rowH * 0.66f
                val barBottom = barTop + barH
                HudKit.bar(canvas, pad, barTop, w - pad, barBottom, fraction, barColor)
            } else {
                // Identity row gets a full "locked" bar instead of a synthetic value.
                val barTop = rowTop + rowH * 0.66f
                HudKit.bar(canvas, pad, barTop, w - pad, barTop + barH, 1f, HudKit.GREEN)
            }
        }
        canvas.restore()
    }
}
