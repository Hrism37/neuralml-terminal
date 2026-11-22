package com.neuralml.terminal.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import com.neuralml.terminal.core.SimulationEngine
import com.neuralml.terminal.util.HudKit
import java.util.Locale
import java.util.Random
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-width status bar (~8 % of the screen height).
 *
 * Left:  product title with an occasional glitch flicker (34 % of width).
 * Mid:   clock, E2E channel lock, signal strength, target link state (38 %).
 * Right: CPU / NPU / MEM / inference FPS gauges (28 %).
 *
 * Every group is measured first and then scaled down if the bar is narrow, so
 * the three zones never overlap on phones or split-screen windows.
 */
class TopStatusBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var engine: SimulationEngine? = null
    private val rng = Random()
    private val lockBody = RectF()

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
        if (w < 80f || h < 18f) return

        HudKit.panel(canvas, 0f, 0f, w, h, HudKit.CYAN)

        val density = resources.displayMetrics.density
        val pad = min(10f * density, h * 0.10f)
        val titleSize = (h * 0.31f).coerceIn(6f * density, 15f * density)
        val smallSize = (h * 0.17f).coerceIn(6f * density, 11f * density)
        val valueSize = (h * 0.24f).coerceIn(6.5f * density, 13.5f * density)
        val baseline = h * 0.59f

        val now = SystemClock.uptimeMillis()
        if (now >= nextGlitchAt) {
            glitchUntil = now + 80L + rng.nextInt(70)
            nextGlitchAt = now + 4500L + rng.nextInt(4500)
        }
        val glitching = now < glitchUntil

        // Spec proportions: 34 % title · 38 % link telemetry · 28 % host gauges.
        val leftEnd = w * 0.34f
        val midEnd = w * 0.72f

        // ── left: glitching product title ────────────────────────────────────
        val title = "NEURAL-ML DECODER TERMINAL V5.1.0"
        var ts = titleSize
        val available = leftEnd - pad * 2f
        var titlePaint = HudKit.textPaint(ts, HudKit.CYAN, bold = true)
        if (titlePaint.measureText(title) > available) {
            ts = (ts * available / titlePaint.measureText(title)).coerceAtLeast(5.5f * density)
            titlePaint = HudKit.textPaint(ts, HudKit.CYAN, bold = true)
        }
        val fittedTitle: CharSequence = if (titlePaint.measureText(title) > available) {
            TextUtils.ellipsize(title, titlePaint, available, TextUtils.TruncateAt.END)
        } else {
            title
        }
        canvas.save()
        canvas.clipRect(0f, 0f, leftEnd, h)
        if (glitching) {
            canvas.drawText(fittedTitle.toString(), pad + 2.5f, baseline + 1.5f, HudKit.textPaint(ts, HudKit.alpha(HudKit.RED, 150), bold = true))
            canvas.drawText(fittedTitle.toString(), pad - 2f, baseline - 1.5f, HudKit.textPaint(ts, HudKit.alpha(HudKit.GREEN, 130), bold = true))
        }
        canvas.drawText(fittedTitle, 0, fittedTitle.length, pad, baseline, titlePaint)
        val subPaint = HudKit.textPaint(min(titleSize * 0.56f, smallSize), HudKit.GREY)
        val sub = "脑控解码终端 · LOCAL NODE"
        if (subPaint.measureText(sub) < available) {
            canvas.drawText(sub, pad, h * 0.92f, subPaint)
        }
        canvas.restore()

        // Segment separators.
        canvas.drawLine(leftEnd, h * 0.2f, leftEnd, h * 0.8f, HudKit.stroke(HudKit.alpha(HudKit.CYAN, 55), 1f))
        canvas.drawLine(midEnd, h * 0.2f, midEnd, h * 0.8f, HudKit.stroke(HudKit.alpha(HudKit.CYAN, 55), 1f))

        // ── middle: clock · e2e · signal · link (scaled to fit) ──────────────
        val midLeft = leftEnd + pad
        val midAvail = midEnd - leftEnd - pad * 2f
        val lockW0 = smallSize * 0.62f
        val barW0 = smallSize * 0.42f
        val gap0 = smallSize * 0.22f
        val clockW0 = HudKit.textPaint(valueSize, HudKit.CYAN, bold = true).measureText("00:00:00")
        val e2eW0 = HudKit.textPaint(smallSize, HudKit.GREEN).measureText("E2E·AES-256")
        val signalW0 = 5f * (barW0 + gap0)
        val linkW0 = smallSize * 8.4f
        val spacing0 = pad * 0.9f
        val chainW0 = clockW0 + lockW0 + e2eW0 + signalW0 + linkW0 + spacing0 * 4f
        val chainScale = if (chainW0 > midAvail && chainW0 > 1f) (midAvail / chainW0).coerceIn(0.15f, 1f) else 1f

        val cSmall = smallSize * chainScale
        val cValue = valueSize * chainScale
        val cLockW = lockW0 * chainScale
        val cBarW = barW0 * chainScale
        val cGap = gap0 * chainScale
        val spacing = spacing0 * chainScale

        var x = midLeft
        canvas.save()
        canvas.clipRect(midLeft, 0f, midEnd - pad, h)
        val clockPaint = HudKit.textPaint(cValue, HudKit.CYAN, bold = true)
        canvas.drawText(e.state.clock, x, baseline, clockPaint)
        x += clockPaint.measureText(e.state.clock) + spacing

        val lockColor = if (e.state.encrypted) HudKit.GREEN else HudKit.AMBER
        val lockY = baseline - cSmall * 0.88f
        lockBody.set(x, lockY + cSmall * 0.36f, x + cLockW, lockY + cSmall * 0.90f)
        canvas.drawRect(lockBody, HudKit.stroke(lockColor, 1.2f))
        canvas.drawArc(
            x + cLockW * 0.20f, lockY + cSmall * 0.02f,
            x + cLockW * 0.80f, lockY + cSmall * 0.58f,
            180f, 180f, false, HudKit.stroke(lockColor, 1.2f)
        )
        x += cLockW + spacing * 0.6f
        val e2eText = if (e.state.encrypted) "E2E·AES-256" else "E2E·DEGRADED"
        val e2ePaint = HudKit.textPaint(cSmall, lockColor)
        canvas.drawText(e2eText, x, baseline, e2ePaint)
        x += e2ePaint.measureText(e2eText) + spacing

        HudKit.signalBars(canvas, x, baseline - cSmall * 1.0f, cBarW, cGap, cSmall * 1.0f, e.state.signalBars, HudKit.GREEN)
        x += 5f * (cBarW + cGap) + spacing

        val linkText = e.state.linkLabel
        val linkColor = when {
            linkText.contains("SECURE") -> HudKit.GREEN
            linkText.contains("RECAL") || linkText.contains("JITTER") -> HudKit.AMBER
            else -> HudKit.CYAN
        }
        val linkPaint = HudKit.textPaint(cSmall, linkColor)
        val linkMax = midEnd - pad - x
        if (linkMax > 16f) {
            canvas.save()
            canvas.clipRect(x, 0f, midEnd - pad, h)
            if ((now / 620L) % 2L == 0L) {
                canvas.drawCircle(x + cSmall * 0.20f, baseline - cSmall * 0.34f, cSmall * 0.20f, HudKit.fill(linkColor))
            }
            val linkAvail = (midEnd - pad) - (x + cSmall * 0.62f)
            val shown = TextUtils.ellipsize(linkText, linkPaint, linkAvail, TextUtils.TruncateAt.END)
            canvas.drawText(shown, 0, shown.length, x + cSmall * 0.62f, baseline, linkPaint)
            canvas.restore()
        }
        canvas.restore()

        // ── right: four host gauges (single row or 2×2 on narrow bars) ──────
        val gaugeStart = midEnd + pad
        val gaugeAvail = w - gaugeStart - pad
        if (gaugeAvail < 10f) return
        val barHeight = (h * 0.075f).coerceIn(2f, 5f)
        val slot = gaugeAvail / 4f

        if (slot >= cValue * 4.6f) {
            val barTop = h * 0.70f
            drawGauge(canvas, gaugeStart, slot, "CPU", String.format(Locale.US, "%.1f%%", e.state.cpu), e.state.cpu / 100f, HudKit.CYAN, cSmall, cValue, baseline, barTop, barHeight)
            drawGauge(canvas, gaugeStart + slot, slot, "NPU", String.format(Locale.US, "%.1f%%", e.state.npu), e.state.npu / 100f, HudKit.GREEN, cSmall, cValue, baseline, barTop, barHeight)
            drawGauge(canvas, gaugeStart + slot * 2f, slot, "MEM", String.format(Locale.US, "%.1f%%", e.state.mem), e.state.mem / 100f, HudKit.AMBER, cSmall, cValue, baseline, barTop, barHeight)
            drawGauge(canvas, gaugeStart + slot * 3f, slot, "FPS", "${e.state.fps.roundToInt()} fps", (e.state.fps / 80f).coerceIn(0f, 1f), HudKit.BLUE, cSmall, cValue, baseline, barTop, barHeight)
        } else {
            // Two rows of two gauges.
            val cellW = gaugeAvail / 2f
            val rowH = h / 2f
            val barTop1 = rowH * 0.86f
            val barTop2 = rowH + rowH * 0.86f
            drawGauge(canvas, gaugeStart, cellW, "CPU", String.format(Locale.US, "%.1f%%", e.state.cpu), e.state.cpu / 100f, HudKit.CYAN, cSmall, cValue, rowH * 0.66f, barTop1, barHeight)
            drawGauge(canvas, gaugeStart + cellW, cellW, "NPU", String.format(Locale.US, "%.1f%%", e.state.npu), e.state.npu / 100f, HudKit.GREEN, cSmall, cValue, rowH * 0.66f, barTop1, barHeight)
            drawGauge(canvas, gaugeStart, cellW, "MEM", String.format(Locale.US, "%.1f%%", e.state.mem), e.state.mem / 100f, HudKit.AMBER, cSmall, cValue, rowH + rowH * 0.66f, barTop2, barHeight)
            drawGauge(canvas, gaugeStart + cellW, cellW, "FPS", "${e.state.fps.roundToInt()} fps", (e.state.fps / 80f).coerceIn(0f, 1f), HudKit.BLUE, cSmall, cValue, rowH + rowH * 0.66f, barTop2, barHeight)
        }
    }

    private fun drawGauge(
        canvas: Canvas,
        left: Float,
        slot: Float,
        label: String,
        valueText: String,
        fraction: Float,
        color: Int,
        smallSize: Float,
        valueSize: Float,
        textBaseline: Float,
        barTop: Float,
        barHeight: Float,
    ) {
        val labelPaint = HudKit.textPaint(smallSize, HudKit.GREY, bold = true)
        val valuePaint = HudKit.textPaint(valueSize * 0.84f, color, bold = true)
        canvas.drawText(label, left, textBaseline, labelPaint)
        val labelW = labelPaint.measureText(label)
        val valueX = left + labelW + smallSize * 0.40f
        val maxRight = left + slot - smallSize * 0.2f
        canvas.save()
        canvas.clipRect(left, 0f, maxRight, height.toFloat())
        canvas.drawText(valueText, valueX, textBaseline, valuePaint)
        canvas.restore()
        val barLeft = left
        val barRight = left + slot - smallSize * 0.35f
        HudKit.bar(canvas, barLeft, barTop, barRight, barTop + barHeight, fraction, color)
    }
}
