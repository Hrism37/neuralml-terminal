package com.neuralml.terminal.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.neuralml.terminal.core.SimulationEngine
import com.neuralml.terminal.core.WaveData
import com.neuralml.terminal.util.HudKit
import kotlin.math.min

/**
 * Four-channel EEG waveform with scrolling window.
 *
 * Each channel gets its own lane inside the panel; a horizontal centre line marks
 * the zero axis. Samples come from [SimulationEngine.waves] (ring buffer), and
 * ML feature marks are drawn as fading neon dots. Geometry is recomputed in place
 * into reused Path/Paint objects, so per-frame allocation is negligible.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        val CHANNEL_COLORS = intArrayOf(HudKit.CYAN, HudKit.GREEN, HudKit.AMBER, HudKit.BLUE)
        val CHANNEL_LABELS = arrayOf("α", "β", "θ", "δ")
        const val SAMPLE_STEP_PX = 2.2f
    }

    private var engine: SimulationEngine? = null
    private val paths = Array(WaveData.CHANNELS) { Path() }
    private val glowPaints = arrayOfNulls<Paint>(WaveData.CHANNELS)
    private val linePaints = arrayOfNulls<Paint>(WaveData.CHANNELS)
    private val markGlowPaints = arrayOfNulls<Paint>(WaveData.CHANNELS)
    private val markCorePaints = arrayOfNulls<Paint>(WaveData.CHANNELS)
    private var ready = false
    private var gainKey = Int.MIN_VALUE
    private var gainText = ""
    private val hzKeys = IntArray(WaveData.CHANNELS) { Int.MIN_VALUE }
    private val hzTexts = arrayOf("", "", "", "")

    init {
        isClickable = false
        isFocusable = false
    }

    fun bind(engine: SimulationEngine) {
        ensurePaints()
        this.engine = engine
        invalidate()
    }

    private fun ensurePaints() {
        if (ready) return
        val density = resources.displayMetrics.density
        for (ch in 0 until WaveData.CHANNELS) {
            val color = CHANNEL_COLORS[ch]
            glowPaints[ch] = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.color = HudKit.alpha(color, 52)
                strokeWidth = 2.6f * density
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            linePaints[ch] = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.color = HudKit.alpha(color, 215)
                strokeWidth = 1.15f * density
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            markGlowPaints[ch] = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = HudKit.alpha(color, 60)
            }
            markCorePaints[ch] = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = HudKit.alpha(color, 235)
                isAntiAlias = true
            }
        }
        ready = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ensurePaints()
        val e = engine ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 100f || h < 80f) return

        HudKit.panel(canvas, 0f, 0f, w, h, HudKit.CYAN)

        val density = resources.displayMetrics.density
        val pad = 9f * density
        val titleSize = (h * 0.085f).coerceIn(8.5f * density, 13f * density)
        val titleBaseline = pad + titleSize
        HudKit.title(canvas, "NEURAL WAVEFORM · 4CH 四通道脑波", pad, titleBaseline, titleSize, HudKit.CYAN)

        val gainInt = (e.state.waveGain * 10f).toInt()
        if (gainInt != gainKey) {
            gainKey = gainInt
            gainText = "GAIN ${HudKit.oneDecimal(e.state.waveGain)}x"
        }
        val gainPaint = HudKit.textPaint(titleSize * 0.78f, HudKit.GREEN)
        val titleW = HudKit.textPaint(titleSize, HudKit.CYAN, bold = true).measureText("NEURAL WAVEFORM · 4CH 四通道脑波")
        val gainW = gainPaint.measureText(gainText)
        if (pad + titleW + pad + gainW < w - pad) {
            canvas.drawText(gainText, w - pad - gainW, titleBaseline, gainPaint)
        } else {
            canvas.drawText(gainText, w - pad - gainW, titleBaseline, HudKit.textPaint(titleSize * 0.62f, HudKit.GREEN))
        }

        val contentTop = titleBaseline + titleSize * 0.95f
        val contentBottom = h - pad
        val contentH = contentBottom - contentTop
        if (contentH < 40f) return

        // Left gutter for channel labels keeps the signal plot clean.
        val gutterW = (titleSize * 2.5f).coerceAtMost(w * 0.10f)
        val plotLeft = pad + gutterW
        val plotRight = w - pad
        val plotW = plotRight - plotLeft
        if (plotW < 40f) return

        val laneH = contentH / WaveData.CHANNELS
        val amp = laneH * 0.40f

        // Grid: 8 vertical time divisions + lane centre lines.
        val gridPaint = HudKit.stroke(HudKit.alpha(HudKit.CYAN, 30), 1f)
        val centrePaint = HudKit.stroke(HudKit.alpha(HudKit.CYAN, 55), 1f)
        for (i in 0..8) {
            val x = plotLeft + plotW * i / 8f
            canvas.drawLine(x, contentTop, x, contentBottom, gridPaint)
        }
        for (ch in 0 until WaveData.CHANNELS) {
            val cy = contentTop + laneH * (ch + 0.5f)
            canvas.drawLine(plotLeft, cy, plotRight, cy, centrePaint)
        }

        val unitPaint = HudKit.textPaint(titleSize * 0.66f, HudKit.DIM)
        for (ch in 0 until WaveData.CHANNELS) {
            val cy = contentTop + laneH * (ch + 0.5f)
            val color = CHANNEL_COLORS[ch]
            val labelPaintColored = HudKit.textPaint(titleSize * 0.95f, color, bold = true)
            canvas.drawText(CHANNEL_LABELS[ch], pad, cy + titleSize * 0.30f, labelPaintColored)
            val hzValue = when (ch) {
                0 -> e.state.alphaHz
                1 -> e.state.betaHz
                2 -> e.state.thetaHz
                else -> e.state.deltaHz
            }
            val hzInt = (hzValue * 10f).toInt()
            if (hzInt != hzKeys[ch]) {
                hzKeys[ch] = hzInt
                hzTexts[ch] = "${HudKit.oneDecimal(hzValue)}Hz"
            }
            val hzPaint = HudKit.textPaint(titleSize * 0.66f, HudKit.GREY)
            canvas.drawText(hzTexts[ch], pad, cy + titleSize * 1.12f, hzPaint)
        }

        // Waveforms.
        val count = e.waves.count()
        canvas.save()
        canvas.clipRect(plotLeft, contentTop, plotRight, contentBottom)
        if (count < 4) {
            canvas.drawText("ACQUIRING SIGNAL ...", plotLeft + plotW * 0.04f, contentTop + laneH * 0.5f, HudKit.textPaint(titleSize, HudKit.DIM))
        } else {
            val points = min(count, (plotW / SAMPLE_STEP_PX).toInt().coerceAtLeast(8))
            for (ch in 0 until WaveData.CHANNELS) {
                val path = paths[ch]
                path.rewind()
                val cy = contentTop + laneH * (ch + 0.5f)
                for (i in 0 until points) {
                    val sampleIndex = (count - 1) * i / (points - 1)
                    val sample = e.waves.at(ch, sampleIndex)
                    val x = plotLeft + plotW * i / (points - 1f)
                    val y = cy - sample * amp
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                glowPaints[ch]?.let { canvas.drawPath(path, it) }
                linePaints[ch]?.let { canvas.drawPath(path, it) }
            }

            // Feature marks: neon dots that fade out.
            val now = android.os.SystemClock.uptimeMillis()
            val marks = e.waves.marks(now)
            for (mark in marks) {
                val age = ((now - mark.bornMs).toFloat() / WaveData.MARK_LIFETIME_MS).coerceIn(0f, 1f)
                if (age >= 1f) continue
                val ch = mark.channel
                val sampleIndex = (mark.position * (count - 1)).toInt().coerceIn(0, count - 1)
                val sample = e.waves.at(ch, sampleIndex)
                val cy = contentTop + laneH * (ch + 0.5f)
                val x = plotLeft + mark.position * plotW
                val y = cy - sample * amp
                val fade = 1f - age
                val core = (1.1f + mark.strength * 1.7f) * density * fade
                val glow = core * 2.6f
                markGlowPaints[ch]?.let {
                    it.alpha = (62f * fade).toInt().coerceIn(0, 255)
                    canvas.drawCircle(x, y, glow, it)
                }
                markCorePaints[ch]?.let {
                    it.alpha = (238f * fade).toInt().coerceIn(0, 255)
                    canvas.drawCircle(x, y, core, it)
                }
                // Small cross hair so the point reads as a picked feature.
                if (mark.strength > 0.72f) {
                    val arm = core * 2.2f
                    val crossPaint = linePaints[ch]
                    if (crossPaint != null) {
                        canvas.drawLine(x - arm, y, x + arm, y, crossPaint)
                        canvas.drawLine(x, y - arm, x, y + arm, crossPaint)
                    }
                }
            }
        }
        canvas.restore()
    }
}
