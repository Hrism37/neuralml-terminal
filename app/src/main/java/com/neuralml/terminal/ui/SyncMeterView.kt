package com.neuralml.terminal.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import com.neuralml.terminal.core.SimulationEngine
import com.neuralml.terminal.util.HudKit

/**
 * Two slim progress meters overlaid at the bottom of the center visualization:
 *  - 神经同步率  NEURO SYNC     (engine.state.neuroSync, 72..99 %)
 *  - ML 解码置信度 DECODE CONF   (engine.state.decodeConfidence)
 *
 * Deliberately very compact so it floats above the wireframe brain without
 * hiding it. Non-interactive: never steals focus from the console.
 */
class SyncMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var engine: SimulationEngine? = null

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
        if (w < 80f || h < 24f) return

        val density = resources.displayMetrics.density
        val pad = 8f * density
        val textSize = (h * 0.20f).coerceIn(9f * density, 13f * density)

        // Translucent plate so the numbers stay readable over the brain.
        canvas.drawRect(0f, 0f, w, h, HudKit.fill(HudKit.alpha(HudKit.BLACK, 168)))
        canvas.drawRect(0f, 0f, w, h, HudKit.stroke(HudKit.alpha(HudKit.CYAN, 105), 1f))
        canvas.drawRect(0f, 0f, w, 1f, HudKit.fill(HudKit.alpha(HudKit.CYAN, 170)))

        val labelPaint = HudKit.textPaint(textSize, HudKit.TEXT)
        val valuePaint = HudKit.textPaint(textSize, HudKit.CYAN, bold = true)
        val valuePaint2 = HudKit.textPaint(textSize, HudKit.GREEN, bold = true)

        val rowHeight = (h - pad * 2f) / 2f
        drawRow(
            canvas, pad, pad, w - pad, rowHeight,
            "NEURO SYNC · 神经同步率", e.state.neuroSync / 100f, e.state.neuroSync,
            HudKit.CYAN, labelPaint, valuePaint
        )
        drawRow(
            canvas, pad, pad + rowHeight, w - pad, rowHeight,
            "DECODE CONF · 解码置信度", e.state.decodeConfidence / 100f, e.state.decodeConfidence,
            HudKit.GREEN, labelPaint, valuePaint2
        )
    }

    private fun drawRow(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        rowHeight: Float,
        label: String,
        fraction: Float,
        percent: Float,
        accent: Int,
        labelPaint: android.text.TextPaint,
        valuePaint: android.text.TextPaint,
    ) {
        val baseline = top + rowHeight * 0.46f
        canvas.drawText(label, left, baseline, labelPaint)
        val valueText = "${HudKit.oneDecimal(percent)}%"
        val valueWidth = valuePaint.measureText(valueText)
        canvas.drawText(valueText, right - valueWidth, baseline, valuePaint)

        val barTop = top + rowHeight * 0.60f
        val barBottom = (top + rowHeight * 0.88f).coerceAtMost(top + rowHeight - 1f)
        HudKit.bar(canvas, left, barTop, right, barBottom, fraction, accent)
    }
}
