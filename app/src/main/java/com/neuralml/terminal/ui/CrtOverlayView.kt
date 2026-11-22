package com.neuralml.terminal.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.neuralml.terminal.util.HudKit
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Non-interactive CRT overlay: horizontal scanlines that slowly drift, an
 * extremely subtle whole-screen breathing flicker, a faint rolling refresh band
 * and a soft vignette. Never consumes touch events.
 */
class CrtOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var scanPaint: Paint? = null
    private var flickerPaint: Paint? = null
    private var bandPaint: Paint? = null
    private var vignettePaint: Paint? = null

    private var scanStep = 3f
    private var bandHeight = 0f
    private var startMs = 0L
    private var started = false

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val density = resources.displayMetrics.density
        scanStep = (1.7f * density).coerceIn(2f, 4.2f)
        bandHeight = h * 0.055f

        scanPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.argb(50, 0, 0, 0)
        }
        flickerPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.argb(1, 0, 0, 0)
        }
        bandPaint = Paint().apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, 0f, 0f, bandHeight,
                intArrayOf(
                    Color.argb(0, 0, 255, 255),
                    Color.argb(9, 0, 255, 255),
                    Color.argb(0, 0, 255, 255),
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        val cx = w / 2f
        val cy = h / 2f
        val radius = hypot(cx, cy).coerceAtLeast(1f)
        vignettePaint = Paint().apply {
            style = Paint.Style.FILL
            shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(Color.TRANSPARENT, Color.argb(22, 0, 0, 0), Color.argb(58, 0, 0, 0)),
                floatArrayOf(0.55f, 0.90f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        startMs = SystemClock.uptimeMillis()
        started = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!started) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.uptimeMillis()
        val t = (now - startMs) / 1000f

        val lines = scanPaint ?: return
        val offset = (t * 7f) % scanStep
        var y = -scanStep + offset
        while (y < h) {
            canvas.drawRect(0f, y, w, y + 1f, lines)
            y += scanStep
        }

        // Very slow rolling refresh band.
        bandPaint?.let { paint ->
            val bandH = bandHeight
            if (bandH > 2f) {
                val bandY = ((t * 0.045f) % 1.15f) * (h + bandH) - bandH
                canvas.save()
                canvas.translate(0f, bandY)
                canvas.drawRect(0f, 0f, w, bandH, paint)
                canvas.restore()
            }
        }

        // Barely visible breathing flicker (needs software layer for transparency).
        flickerPaint?.let { paint ->
            val alpha = (2.2f + 1.8f * sin(t * 1.4f)).toInt().coerceIn(0, 4)
            if (alpha > 0) {
                paint.color = Color.argb(alpha, 0, 0, 0)
                canvas.drawRect(0f, 0f, w, h, paint)
            }
        }

        // Edge vignette keeps focus on the centre panels.
        vignettePaint?.let { canvas.drawRect(0f, 0f, w, h, it) }
    }
}
