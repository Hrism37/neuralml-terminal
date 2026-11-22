package com.neuralml.terminal.ui

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import com.neuralml.terminal.core.LogEntry
import com.neuralml.terminal.core.LogLevel
import com.neuralml.terminal.core.SimulationEngine
import com.neuralml.terminal.util.HudKit
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Full-width bottom bar: SYSTEM & ML LOG (~15 % of the screen height).
 *
 * Shows as many of the newest lines as fit, newest at the bottom, so the feed
 * reads like it scrolls upward. The entry cache is only rebuilt when the log
 * bus revision changes instead of copying the whole backlog every frame.
 */
class LogPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var engine: SimulationEngine? = null
    private val cache = ArrayList<LogEntry>(32)
    private var cachedRevision = -1L

    private val infoColor = 0xFFAEBDBD.toInt() // light grey per spec

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
        if (w < 100f || h < 32f) return

        HudKit.panel(canvas, 0f, 0f, w, h, HudKit.CYAN)

        val density = resources.displayMetrics.density
        val pad = min(8f * density, h * 0.055f)
        val titleSize = (h * 0.135f).coerceIn(6f * density, 12.5f * density)
        val titleBaseline = pad + titleSize
        HudKit.title(canvas, "SYSTEM & ML LOG", pad, titleBaseline, titleSize, HudKit.CYAN)

        val titleW = HudKit.textPaint(titleSize, HudKit.CYAN, bold = true).measureText("SYSTEM & ML LOG")
        val legendSize = (titleSize * 0.66f).coerceIn(5.5f * density, 9.5f * density)

        // Right-aligned legend: ERROR / WARN / INFO.
        var legendLeft = w - pad
        val legendParts = arrayOf("ERROR" to HudKit.RED, "WARN" to HudKit.AMBER, "INFO" to HudKit.GREY)
        for (part in legendParts) {
            val p = HudKit.textPaint(legendSize, part.second)
            val pw = p.measureText(part.first)
            legendLeft -= pw
            canvas.drawText(part.first, legendLeft, titleBaseline, p)
            legendLeft -= pad * 0.7f
        }
        val legendLeftEdge = legendLeft

        // Subtitle only if it does not collide with the legend.
        val subtitlePaint = HudKit.textPaint(titleSize * 0.70f, HudKit.GREY)
        val subtitle = "系统与 ML 运行日志"
        val subtitleX = pad + titleW + pad
        val subtitleW = subtitlePaint.measureText(subtitle)
        if (subtitleX + subtitleW < legendLeftEdge - pad) {
            canvas.drawText(subtitle, subtitleX, titleBaseline, subtitlePaint)
            val now = SystemClock.uptimeMillis()
            if ((now / 700L) % 2L == 0L) {
                canvas.drawCircle(subtitleX + subtitleW + pad * 0.8f, titleBaseline - titleSize * 0.28f, legendSize * 0.30f, HudKit.fill(HudKit.RED))
            }
        }

        // Revision counter sits left of the legend, dim, on the title line.
        val revText = "REV ${e.logBus.revision()}"
        val revPaint = HudKit.textPaint(legendSize, HudKit.DIM)
        val revW = revPaint.measureText(revText)
        if (subtitleX + subtitleW + pad * 2f + revW < legendLeftEdge) {
            canvas.drawText(revText, legendLeftEdge - pad * 1.4f - revW, titleBaseline, revPaint)
        }

        val contentTop = titleBaseline + titleSize * 0.62f
        val contentBottom = h - pad
        val contentH = contentBottom - contentTop
        if (contentH < 10f) return

        val revision = e.logBus.revision()
        if (revision != cachedRevision) {
            cachedRevision = revision
            cache.clear()
            cache.addAll(e.logBus.snapshot(24))
        }

        val textSize = (contentH / 6.2f).coerceIn(5.5f * density, 11f * density)
        val lineH = textSize * 1.42f
        val maxLines = max(1, floor(contentH / lineH).toInt())
        val startIndex = max(0, cache.size - maxLines)
        val count = cache.size - startIndex

        if (count <= 0) {
            canvas.drawText(
                "> telemetry stream idle · waiting for channel ...",
                pad, contentTop + lineH, HudKit.textPaint(textSize, HudKit.DIM)
            )
            return
        }

        val timePaint = HudKit.textPaint(textSize, HudKit.GREY)
        val revPaint2 = HudKit.textPaint(textSize * 0.85f, HudKit.DIM)

        canvas.save()
        canvas.clipRect(pad, contentTop, w - pad, contentBottom)
        for (i in 0 until count) {
            val entry = cache[startIndex + i]
            val baseline = contentTop + lineH * (i + 1) - lineH * 0.28f
            val levelColor = when (entry.level) {
                LogLevel.INFO -> 0
                LogLevel.WARN -> HudKit.AMBER
                LogLevel.ERROR -> HudKit.RED
            }
            if (levelColor != 0) {
                canvas.drawRect(
                    pad, baseline - lineH * 0.62f, pad + 2f * density, baseline + lineH * 0.16f,
                    HudKit.fill(HudKit.alpha(levelColor, 150))
                )
            }
            var x = pad + 4f * density
            x += HudKit.drawText(canvas, entry.time, x, baseline, timePaint) + pad * 0.6f
            val source = "[${entry.source}]"
            val sourcePaint = when (entry.level) {
                LogLevel.ERROR -> HudKit.textPaint(textSize, HudKit.RED)
                LogLevel.WARN -> HudKit.textPaint(textSize, HudKit.AMBER)
                LogLevel.INFO -> HudKit.textPaint(textSize, HudKit.GREEN)
            }
            x += HudKit.drawText(canvas, source, x, baseline, sourcePaint) + pad * 0.6f
            val textPaint = when (entry.level) {
                LogLevel.INFO -> HudKit.textPaint(textSize, infoColor)
                LogLevel.WARN -> HudKit.textPaint(textSize, HudKit.AMBER)
                LogLevel.ERROR -> HudKit.textPaint(textSize, HudKit.RED)
            }
            val available = (w - pad) - x
            if (available > textSize * 2f) {
                val shown = TextUtils.ellipsize(entry.text, textPaint, available, TextUtils.TruncateAt.END)
                canvas.drawText(shown, 0, shown.length, x, baseline, textPaint)
            }
        }
        canvas.restore()
    }
}
