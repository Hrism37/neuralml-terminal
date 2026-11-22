package com.neuralml.terminal.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.neuralml.terminal.util.HudKit
import kotlin.math.min

/**
 * Full-screen boot self-test overlay (~3 s).
 *
 * Timeline: ASCII brain logo fades in line by line, then the self-test script is
 * typed out, a progress bar fills, and a blinking SYSTEM READY ends the sequence.
 * Drives itself with a 33 ms Handler tick (it is not part of the activity's
 * animated view list) and calls [onFinished] exactly once.
 */
class BootOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val DURATION_MS = 3000L
        private const val ART_START_MS = 100L
        private const val ART_STEP_MS = 56L
        private const val SCRIPT_START_MS = 620L
        private const val SCRIPT_STEP_MS = 232L
        private const val TYPE_CHAR_MS = 2.0f
        private const val PROGRESS_START_MS = 900L
    }

    /** Called once when the boot sequence has finished. */
    var onFinished: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var startMs = 0L
    private var running = false
    private var finished = false

    private val art = arrayOf(
        "              ,-~~~~-,",
        "          ,-~'        '~-,",
        "        ,~'   ,-~~~-.    '~,",
        "       /    ,~'     '~,    \\",
        "      |    /    ,-.    \\    |",
        "      |   |    (   )    |   |",
        "       \\   \\    '-'    /   /",
        "        '~, '~.     .~' ,~'",
        "          '~-, '~-~' ,-~'",
        "             '~-.._..-~'",
    )

    private val script = arrayOf(
        "SELF-TEST 01/10  electrode array ........... OK",
        "SELF-TEST 02/10  bio-amplifier 4CH ......... OK",
        "SELF-TEST 03/10  neural model v3.7.2 ....... OK",
        "SELF-TEST 04/10  tensor runtime ............ OK",
        "SELF-TEST 05/10  e2e channel AES-256 ....... OK",
        "SELF-TEST 06/10  calibration matrix ........ OK",
        "SELF-TEST 07/10  frame sync 30Hz ........... OK",
        "SELF-TEST 08/10  link handshake ............ OK",
        "SELF-TEST 09/10  神经链路同步 .............. OK",
        "SELF-TEST 10/10  operator UI bind .......... OK",
    )

    private var textSize = 12f
    private var lineH = 15f
    private var charW = 7f
    private var blockLeft = 0f
    private var blockTop = 0f
    private var blockWidth = 0f
    private var blockHeight = 0f
    private var titlePaint: Paint? = null
    private var artPaint: Paint? = null
    private var scriptPaint: Paint? = null
    private var okPaint: Paint? = null
    private var dimPaint: Paint? = null
    private var readyPaint: Paint? = null
    private var framePaint: Paint? = null
    private var titleText = "NEURAL-ML DECODER TERMINAL · BOOT SELF-TEST"
    private var bootPctKey = Int.MIN_VALUE
    private var bootPctText = ""

    init {
        isClickable = false
        isFocusable = false
        setBackgroundColor(Color.BLACK)
    }

    fun startBoot() {
        if (finished) return
        if (running) return
        running = true
        startMs = SystemClock.uptimeMillis()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            val elapsed = SystemClock.uptimeMillis() - startMs
            if (elapsed >= DURATION_MS) {
                finishBoot()
            } else {
                handler.postDelayed(this, 33L)
            }
        }
    }

    private fun finishBoot() {
        if (finished) return
        finished = true
        running = false
        handler.removeCallbacks(ticker)
        invalidate()
        onFinished?.invoke()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        running = false
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val density = resources.displayMetrics.density
        val maxLineLen = script.maxOf { it.length }.coerceAtLeast(titleText.length).toFloat()

        var ts = min(w * 0.026f, h * 0.0265f)
        ts = ts.coerceIn(6.5f * density, 17f * density)

        // Fit horizontally first.
        charW = ts * 0.615f
        if (maxLineLen * charW > w * 0.90f) {
            ts *= (w * 0.90f) / (maxLineLen * charW)
            charW = ts * 0.615f
        }
        // Then fit vertically (title + art + script + progress + status ≈ 26 rows).
        val rows = (1 + art.size + script.size + 4.6f)
        if (rows * ts * 1.42f > h * 0.90f) {
            ts *= (h * 0.90f) / (rows * ts * 1.42f)
        }
        textSize = ts.coerceAtLeast(6f)
        lineH = textSize * 1.42f
        charW = textSize * 0.615f

        blockWidth = maxLineLen * charW
        blockLeft = (w - blockWidth) / 2f
        blockHeight = rows * lineH
        blockTop = ((h - blockHeight) / 2f).coerceAtLeast(h * 0.04f)

        titlePaint = HudKit.textPaint(textSize * 1.06f, HudKit.CYAN, bold = true)
        artPaint = HudKit.textPaint(textSize, HudKit.alpha(HudKit.CYAN, 185))
        scriptPaint = HudKit.textPaint(textSize, HudKit.GREY)
        okPaint = HudKit.textPaint(textSize, HudKit.GREEN)
        dimPaint = HudKit.textPaint(textSize * 0.92f, HudKit.DIM)
        readyPaint = HudKit.textPaint(textSize * 1.10f, HudKit.GREEN, bold = true)
        framePaint = Paint().apply {
            style = Paint.Style.STROKE
            color = Color.argb(95, 0, 255, 255)
            strokeWidth = 1.2f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val titlePaint = titlePaint ?: return
        val elapsed = if (startMs == 0L) 0L else (SystemClock.uptimeMillis() - startMs)

        // Terminal frame around the boot block.
        framePaint?.let {
            canvas.drawRect(blockLeft - 14f, blockTop - 14f, blockLeft + blockWidth + 14f, blockTop + blockHeight + 16f, it)
        }

        // Title.
        val titleW = titlePaint.measureText(titleText)
        canvas.drawText(titleText, (w - titleW) / 2f, blockTop + textSize, titlePaint)

        var y = blockTop + lineH * 2.0f
        // ASCII brain logo, line by line.
        artPaint?.let { paint ->
            for (i in art.indices) {
                val lineStart = ART_START_MS + i * ART_STEP_MS
                if (elapsed < lineStart) break
                val line = art[i]
                val lw = paint.measureText(line)
                canvas.drawText(line, (w - lw) / 2f, y, paint)
                y += lineH * 0.86f
            }
        }
        y += lineH * 0.55f

        // Typed self-test script.
        val scriptPaint = scriptPaint ?: return
        val dimPaint = dimPaint ?: return
        val okPaint = okPaint ?: return
        for (i in script.indices) {
            val lineStart = SCRIPT_START_MS + i * SCRIPT_STEP_MS
            if (elapsed < lineStart) break
            val text = script[i]
            val reveal = (((elapsed - lineStart) / TYPE_CHAR_MS).toInt()).coerceIn(0, text.length)
            // Dim placeholder dots for the whole line, typed text on top.
            canvas.drawText(text, 0, reveal, blockLeft, y, if (reveal >= text.length) okPaint else scriptPaint)
            if (reveal < text.length) {
                // Unrevealed tail in dim to avoid layout jumps.
                canvas.drawText(text, reveal, text.length, blockLeft + scriptPaint.measureText(text, 0, reveal), y, dimPaint)
            }
            y += lineH
        }

        // Progress bar.
        val progressTop = blockTop + blockHeight - lineH * 2.1f
        val progressFraction = (((elapsed - PROGRESS_START_MS).toFloat() / (DURATION_MS - PROGRESS_START_MS)).coerceIn(0f, 1f))
        HudKit.bar(canvas, blockLeft, progressTop, blockLeft + blockWidth, progressTop + textSize * 0.42f, progressFraction, HudKit.CYAN)

        val pct = (progressFraction * 100f).toInt()
        if (pct != bootPctKey) {
            bootPctKey = pct
            bootPctText = "BOOT ${pct.toString().padStart(3, ' ')}%"
        }
        canvas.drawText(bootPctText, blockLeft, progressTop - lineH * 0.32f, dimPaint)

        // Status line: SYSTEM READY blinks in the last 300 ms.
        val statusBaseline = blockTop + blockHeight - lineH * 0.25f
        if (elapsed >= 2880L) {
            val ready = "SYSTEM READY · NEURAL CORE ONLINE"
            val readyPaint = readyPaint ?: return
            if ((elapsed / 180L) % 2L == 0L) {
                canvas.drawText(ready, blockLeft, statusBaseline, readyPaint)
            }
        } else {
            canvas.drawText("SELF-TEST IN PROGRESS ...", blockLeft, statusBaseline, dimPaint)
        }
    }
}
