package com.neuralml.terminal.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint

/**
 * Shared palette + drawing helpers for the terminal UI.
 *
 * All paints are cached: views may call these helpers from `onDraw` without
 * creating new objects on every frame. Everything here is meant for the main
 * thread. Colors are ARGB ints.
 */
object HudKit {

    // ── palette ──────────────────────────────────────────────────────────────
    val BLACK = 0xFF000000.toInt()
    val CYAN = 0xFF00FFFF.toInt()
    val GREEN = 0xFF00FF00.toInt()
    val AMBER = 0xFFFFCC00.toInt()
    val RED = 0xFFFF3333.toInt()
    val BLUE = 0xFF66AAFF.toInt()
    val MAGENTA = 0xFFFF66CC.toInt()
    val TEXT = 0xFFB8F5F5.toInt()
    val GREY = 0xFF8E9E9E.toInt()
    val DIM = 0xFF2E5C5C.toInt()
    val PANEL_FILL = 0xD9050B0B.toInt()

    private val typefaceMono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private val typefaceMonoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private val textPaints = HashMap<Long, TextPaint>()
    private val strokePaints = HashMap<Long, Paint>()
    private val fillPaints = HashMap<Long, Paint>()

    // ── size helpers ─────────────────────────────────────────────────────────

    /** Convert dp to pixels for the current display. */
    fun dp(context: Context, value: Float): Float = value * context.resources.displayMetrics.density

    /** Convert sp to pixels, font scale aware. */
    fun sp(context: Context, value: Float): Float = value * context.resources.displayMetrics.scaledDensity

    fun clamp(value: Float, min: Float, max: Float): Float =
        if (value < min) min else if (value > max) max else value

    fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

    fun alpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    // ── paint factories (cached) ─────────────────────────────────────────────

    fun textPaint(sizePx: Float, color: Int, bold: Boolean = false): TextPaint {
        val sizeKey = (sizePx * 100f).toLong().coerceAtLeast(0L)
        val key = (sizeKey shl 33) or (color.toLong() and 0xFFFFFFFFL) or (if (bold) (1L shl 32) else 0L)
        return textPaints.getOrPut(key) {
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = if (bold) typefaceMonoBold else typefaceMono
                textSize = sizePx.coerceAtLeast(1f)
                this.color = color
                isSubpixelText = true
                isLinearText = true
            }
        }
    }

    fun stroke(color: Int, widthPx: Float = 1.2f): Paint {
        val widthKey = (widthPx * 100f).toLong().coerceIn(0L, 0xFFFFL)
        val key = ((color.toLong() and 0xFFFFFFFFL) shl 16) or widthKey
        return strokePaints.getOrPut(key) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.color = color
                strokeWidth = widthPx.coerceAtLeast(0.5f)
                strokeCap = Paint.Cap.BUTT
            }
        }
    }

    fun fill(color: Int): Paint {
        val key = color.toLong() and 0xFFFFFFFFL
        return fillPaints.getOrPut(key) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = color
            }
        }
    }

    // ── panel chrome ─────────────────────────────────────────────────────────

    /**
     * Draws the standard panel chrome: near-black fill, 1 px fluorescent border,
     * a faint outer glow, and brighter corner ticks.
     */
    fun panel(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, accent: Int = CYAN) {
        canvas.drawRect(left, top, right, bottom, fill(PANEL_FILL))
        // Very subtle glow (2 extra strokes, low alpha) instead of a blur layer.
        canvas.drawRect(left - 1f, top - 1f, right + 1f, bottom + 1f, stroke(alpha(accent, 20), 3.2f))
        canvas.drawRect(left, top, right, bottom, stroke(alpha(accent, 175), 1.2f))

        val tick = 10f
        val paint = stroke(alpha(accent, 235), 1.8f)
        // top-left
        canvas.drawLine(left, top, left + tick, top, paint)
        canvas.drawLine(left, top, left, top + tick, paint)
        // top-right
        canvas.drawLine(right - tick, top, right, top, paint)
        canvas.drawLine(right, top, right, top + tick, paint)
        // bottom-left
        canvas.drawLine(left, bottom - tick, left, bottom, paint)
        canvas.drawLine(left, bottom, left + tick, bottom, paint)
        // bottom-right
        canvas.drawLine(right, bottom - tick, right, bottom, paint)
        canvas.drawLine(right - tick, bottom, right, bottom, paint)
    }

    /** Panel title with a thin accent underline, drawn in-place. */
    fun title(canvas: Canvas, text: String, x: Float, baselineY: Float, sizePx: Float, accent: Int = CYAN) {
        val paint = textPaint(sizePx, accent, bold = true)
        canvas.drawText(text, x, baselineY, paint)
        canvas.drawRect(
            x, baselineY + sizePx * 0.22f,
            x + paint.measureText(text), baselineY + sizePx * 0.22f + 1.1f,
            fill(alpha(accent, 130))
        )
    }

    // ── progress bar ─────────────────────────────────────────────────────────

    /**
     * Horizontal segmented progress bar. [fraction] is clamped to 0..1.
     * Track, fill, top highlight, 1 px border and 10 % ticks.
     */
    fun bar(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        fraction: Float,
        color: Int,
    ) {
        val width = (right - left).coerceAtLeast(1f)
        val height = (bottom - top).coerceAtLeast(1f)
        val f = clamp(fraction, 0f, 1f)
        canvas.drawRect(left, top, right, bottom, fill(alpha(color, 16)))
        if (f > 0f) {
            canvas.drawRect(left, top, left + width * f, bottom, fill(alpha(color, 195)))
            canvas.drawRect(left, top, left + width * f, top + (height * 0.30f).coerceAtLeast(1f), fill(alpha(color, 60)))
        }
        canvas.drawRect(left, top, right, bottom, stroke(alpha(color, 95), 1f))
        val step = width / 10f
        val tickPaint = stroke(alpha(color, 55), 1f)
        var i = 1
        while (i < 10) {
            val x = left + step * i
            canvas.drawLine(x, top + height * 0.55f, x, bottom, tickPaint)
            i++
        }
    }

    /** Small 5-cell signal-strength indicator; [bars] 0..5. */
    fun signalBars(canvas: Canvas, left: Float, top: Float, barWidth: Float, gap: Float, height: Float, bars: Int, color: Int) {
        for (i in 0 until 5) {
            val h = height * (0.30f + 0.175f * i)
            val x = left + i * (barWidth + gap)
            val active = i < bars
            canvas.drawRect(
                x, top + height - h, x + barWidth, top + height,
                fill(if (active) alpha(color, 235) else alpha(color, 45))
            )
        }
    }

    // ── text helpers ─────────────────────────────────────────────────────────

    /** Draws text and returns its width. */
    fun drawText(canvas: Canvas, text: String, x: Float, baselineY: Float, paint: TextPaint): Float {
        canvas.drawText(text, x, baselineY, paint)
        return paint.measureText(text)
    }

    /** `123.4` → `"123.4"`, always one decimal, no allocation-heavy formatters. */
    fun oneDecimal(value: Float): String {
        val scaled = Math.round(value * 10f)
        val abs = Math.abs(scaled)
        val sign = if (scaled < 0) "-" else ""
        return "$sign${abs / 10}.${abs % 10}"
    }
}
