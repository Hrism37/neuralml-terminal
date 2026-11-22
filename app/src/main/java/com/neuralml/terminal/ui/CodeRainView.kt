package com.neuralml.terminal.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.neuralml.terminal.util.HudKit
import java.util.Random

/**
 * Full-screen, very dim digital rain behind the whole UI.
 *
 * Geometry is built once per size change; each frame just walks the columns and
 * draws the head + a short fading trail. Glyph lookups use a fixed string, so no
 * substring / object allocation happens in `onDraw`.
 */
class CodeRainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        const val MAX_COLUMNS = 190
        const val GLYPHS = "01<>/\\|=+*#\$%&ABCDEFXYZ0123456789{}[]()~^"
    }

    private val rng = Random(0x4E45554CL)

    private var columns = 0
    private var rows = 0
    private var cellW = 0f
    private var lineH = 0f
    private var phases = FloatArray(0)
    private var speeds = FloatArray(0)
    private var trails = IntArray(0)
    private var headPaint: Paint? = null
    private var trailPaints: Array<Paint> = emptyArray()
    private var startMs = 0L
    private var descent = 0f

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        build(w, h)
    }

    private fun build(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val density = resources.displayMetrics.density
        val textSize = (10.5f * density).coerceAtLeast(8f)
        cellW = textSize * 0.66f
        lineH = textSize * 1.28f
        columns = ((w / cellW).toInt() + 1).coerceIn(1, MAX_COLUMNS)
        rows = ((h / lineH).toInt() + 1).coerceIn(1, 240)

        phases = FloatArray(columns) { rng.nextFloat() * (rows + 48f) }
        speeds = FloatArray(columns) { 2.4f + rng.nextFloat() * 5.2f }
        trails = IntArray(columns) { 10 + rng.nextInt(9) }

        headPaint = HudKit.textPaint(textSize, HudKit.alpha(HudKit.CYAN, 104), bold = true)
        trailPaints = arrayOf(
            HudKit.textPaint(textSize, HudKit.alpha(HudKit.GREEN, 64)),
            HudKit.textPaint(textSize, HudKit.alpha(HudKit.GREEN, 40)),
            HudKit.textPaint(textSize, HudKit.alpha(HudKit.GREEN, 20)),
        )
        descent = headPaint?.fontMetrics?.descent ?: 0f
        startMs = SystemClock.uptimeMillis()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (columns == 0 || rows == 0) return
        val head = headPaint ?: return
        if (trailPaints.size < 3) return

        val now = SystemClock.uptimeMillis()
        val seconds = (now - startMs) / 1000f
        val tick = now / 170L

        for (col in 0 until columns) {
            val trail = trails[col]
            val headRow = ((phases[col] + seconds * speeds[col]) % (rows + trail + 2f)) - trail
            if (headRow < -trail || headRow > rows + 1) continue
            val x = col * cellW
            for (k in 0..trail) {
                val rowF = headRow - k
                if (rowF < 0f || rowF >= rows) continue
                val row = rowF.toInt()
                val idx = (((col * 73856093) xor (row * 19349663) xor (tick.toInt() * 83492791)) and 0x7FFFFFFF) % GLYPHS.length
                val paint = when {
                    k == 0 -> head
                    k <= 2 -> trailPaints[0]
                    k <= 6 -> trailPaints[1]
                    else -> trailPaints[2]
                }
                canvas.drawText(GLYPHS, idx, idx + 1, x, (rowF + 1f) * lineH - descent * 0.25f, paint)
            }
        }
    }
}
