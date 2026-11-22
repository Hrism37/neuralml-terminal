package com.neuralml.terminal.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.neuralml.terminal.util.HudKit
import kotlin.math.min

/** Colour buckets for console output. */
enum class ConsoleLevel { OUT, OK, WARN, ERR, SYS }

/**
 * One queued console line. [pauseMs] is the pause printed after the line finishes
 * typing; batching several lines together is how command output is paced.
 */
data class ConsoleLine(
    val text: String,
    val level: ConsoleLevel = ConsoleLevel.OUT,
    val pauseMs: Long = 55L,
)

/**
 * Right-hand interactive terminal.
 *
 * One `TextView` per output line (cheap to append, cheap to re-layout only the
 * active line), a typewriter state machine driven by a single main-thread
 * `Handler`, and an `EditText` input row with a fixed prompt. All scheduling goes
 * through [release] so the Activity can tear down without leaking callbacks.
 */
class ConsoleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private companion object {
        const val MAX_LINES = 220
        const val CHARS_PER_TICK = 1
        const val CHAR_TICK_MS = 10L
    }

    private class Batch(val lines: List<ConsoleLine>, val onComplete: (() -> Unit)?)

    /** Invoked on the main thread after the prompt echo, with the raw command. */
    var onCommand: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Batch>()
    private var activeBatch: Batch? = null
    private var lineIndex = 0
    private var activeLine: ConsoleLine? = null
    private var activeView: TextView? = null
    private val lineBuffer = StringBuilder()
    private var charIndex = 0
    private var scheduled = false
    private var inDispatch = false
    private var released = false

    private val scrollView: ScrollView
    private val content: LinearLayout
    private val inputRow: LinearLayout
    private val promptView: TextView
    private val editText: EditText
    private val cursorView: View
    private var scrollPending = false
    private var compactPrompt = false

    private var titlePx = 12f
    private var bodyPx = 12f
    private var titleBand = 22f
    private var titleBaseline = 15f
    private var paddingLeftPx = 10f

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)

        val density = resources.displayMetrics.density
        val padH = (10f * density).toInt()
        val padV = (6f * density).toInt()

        scrollView = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { focusInput() }
        }
        content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(padH, padV, padH, padV)
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { focusInput() }
        }
        scrollView.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        addView(
            scrollView,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )

        inputRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, 0, padH, (7f * density).toInt())
        }
        promptView = TextView(context).apply {
            text = "> neural-ml@decoder:~$ "
            typeface = Typeface.MONOSPACE
            setTextColor(HudKit.CYAN)
            includeFontPadding = false
            isSingleLine = true
            isClickable = false
        }
        editText = EditText(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            typeface = Typeface.MONOSPACE
            setTextColor(HudKit.CYAN)
            setHintTextColor(HudKit.alpha(HudKit.GREY, 150))
            hint = "输入指令 · help"
            includeFontPadding = false
            isSingleLine = true
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(0, 0, 0, 0)
            setOnEditorActionListener { _, actionId, event ->
                val enterDown = event != null &&
                    event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN
                if (enterDown || actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_GO
                ) {
                    submitInput()
                    true
                } else {
                    false
                }
            }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                    submitInput()
                    true
                } else {
                    false
                }
            }
        }
        cursorView = View(context).apply {
            setBackgroundColor(HudKit.CYAN)
            isClickable = false
        }
        inputRow.addView(promptView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        inputRow.addView(cursorView, LayoutParams(6, 14))
        inputRow.addView(editText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(inputRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        setOnClickListener { focusInput() }

        // When a typed chunk triggers a new wrapped line, the transcript is taller
        // than the last measured pass; correct the scroll position after layout.
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (scrollPending) {
                scrollPending = false
                scrollToBottomNow()
            }
        }
    }

    // ── sizing ───────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // The soft keyboard only changes the height a little; re-laying out the
        // transcript on every IME toggle makes the console jump. Keep sizes then.
        if (oldw == w && oldh > 0 && kotlin.math.abs(h - oldh) < oldh * 0.05f) return
        val density = resources.displayMetrics.density
        paddingLeftPx = 10f * density
        bodyPx = min(HudKit.sp(context, 12.5f), h * 0.030f).coerceAtLeast(9f * density)
        titlePx = min(HudKit.sp(context, 12f), h * 0.052f).coerceAtLeast(8.5f * density)
        titleBand = titlePx * 2.0f
        titleBaseline = titleBand - titlePx * 0.62f

        compactPrompt = resources.configuration.screenWidthDp < 700
        promptView.text = if (compactPrompt) "> " else "> neural-ml@decoder:~$ "
        promptView.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodyPx)
        editText.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodyPx)
        val cursorWidth = (bodyPx * 0.55f).toInt().coerceAtLeast(4)
        val cursorHeight = (bodyPx * 0.92f).toInt().coerceAtLeast(8)
        cursorView.layoutParams = LayoutParams(cursorWidth, cursorHeight).apply {
            marginStart = (bodyPx * 0.18f).toInt()
            marginEnd = (bodyPx * 0.18f).toInt()
            gravity = Gravity.CENTER_VERTICAL
        }
        cursorView.requestLayout()
        scrollView.setPadding(0, titleBand.toInt() + (4f * density).toInt(), 0, 0)
        applyBodySizeToChildren()
    }

    private fun applyBodySizeToChildren() {
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            if (child is TextView) {
                child.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodyPx)
                child.minimumHeight = (bodyPx * 1.18f).toInt()
            }
        }
    }

    // ── chrome ───────────────────────────────────────────────────────────────

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w > 0f && h > 0f) {
            canvas.drawRect(0f, 0f, w, h, HudKit.fill(HudKit.PANEL_FILL))
            HudKit.panel(canvas, 0f, 0f, w, h, HudKit.CYAN)
        }
        super.dispatchDraw(canvas)
        if (w <= 0f || h <= 0f) return
        HudKit.title(canvas, "COMMAND CONSOLE", paddingLeftPx, titleBaseline, titlePx, HudKit.CYAN)

        val meta = "node 0x3C · 5.1.0"
        val metaPaint = HudKit.textPaint(titlePx * 0.72f, HudKit.GREY)
        val titleW = HudKit.textPaint(titlePx, HudKit.CYAN, bold = true).measureText("COMMAND CONSOLE")
        val metaW = metaPaint.measureText(meta)
        if (paddingLeftPx + titleW + 12f + metaW < w - paddingLeftPx) {
            canvas.drawText(meta, w - paddingLeftPx - metaW, titleBaseline, metaPaint)
        }

        // Separator right above the input row.
        val sepY = inputRow.top - 4f * resources.displayMetrics.density
        if (sepY > 0f) {
            canvas.drawRect(paddingLeftPx, sepY, w - paddingLeftPx, sepY + 1f, HudKit.fill(HudKit.alpha(HudKit.CYAN, 75)))
        }
    }

    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (released) return
            cursorView.alpha = if (cursorView.alpha > 0.5f) 0.14f else 1f
            handler.postDelayed(this, 520L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!released) {
            handler.removeCallbacks(blinkRunnable)
            handler.postDelayed(blinkRunnable, 520L)
            if (activeBatch != null || queue.isNotEmpty()) schedule(0L)
        }
    }

    // ── public API used by MainActivity / CommandProcessor ───────────────────

    /** Append output lines; [onComplete] runs after the last line is fully typed. */
    fun enqueue(lines: List<ConsoleLine>, onComplete: (() -> Unit)? = null) {
        if (released) return
        if (lines.isEmpty() && onComplete == null) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { enqueue(lines, onComplete) }
            return
        }
        queue.addLast(Batch(lines, onComplete))
        if (!inDispatch) schedule(0L)
    }

    /** Wipes the transcript and the pending print queue. */
    fun clearScreen() {
        handler.removeCallbacks(typeTick)
        scheduled = false
        queue.clear()
        activeBatch = null
        activeLine = null
        activeView = null
        lineIndex = 0
        charIndex = 0
        lineBuffer.setLength(0)
        inDispatch = false
        scrollPending = false
        content.removeAllViews()
        scrollView.scrollTo(0, 0)
    }

    private val focusRunnable = Runnable {
        if (released || !editText.isAttachedToWindow) return@Runnable
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Focus the input field and raise the soft keyboard. */
    fun focusInput() {
        if (released) return
        editText.requestFocus()
        editText.post(focusRunnable)
    }

    /** Stops all pending typing callbacks. Call from `Activity.onDestroy`. */
    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        editText.removeCallbacks(focusRunnable)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        editText.removeCallbacks(focusRunnable)
        scheduled = false
        super.onDetachedFromWindow()
    }

    // ── typewriter state machine ─────────────────────────────────────────────

    private fun schedule(delayMs: Long) {
        if (released || scheduled) return
        scheduled = true
        handler.postDelayed(typeTick, delayMs)
    }

    private val typeTick = object : Runnable {
        override fun run() {
            scheduled = false
            if (released) return

            val view = activeView
            val line = activeLine
            if (line != null && view != null) {
                if (charIndex < line.text.length) {
                    typeChunk(line, view)
                } else {
                    activeLine = null
                    activeView = null
                    schedule(0L)
                }
                return
            }

            if (activeBatch == null) {
                activeBatch = if (queue.isEmpty()) null else queue.removeFirst()
                lineIndex = 0
            }
            val batch = activeBatch
            if (batch == null) {
                trimTranscript()
                return
            }
            if (lineIndex >= batch.lines.size) {
                activeBatch = null
                val callback = batch.onComplete
                lineIndex = 0
                if (callback != null) {
                    inDispatch = true
                    try {
                        callback()
                    } finally {
                        inDispatch = false
                    }
                }
                if (queue.isNotEmpty()) schedule(0L)
                return
            }

            startLine(batch.lines[lineIndex])
            lineIndex++
            schedule(0L)
        }
    }

    private fun startLine(line: ConsoleLine) {
        val view = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(colorFor(line.level))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, bodyPx)
            includeFontPadding = false
            setLineSpacing(0f, 1.08f)
            setPadding(0, 0, 0, 0)
            isSingleLine = false
            // Long help/status lines wrap naturally inside the 28 % panel.
            setTextIsSelectable(false)
            text = ""
        }
        content.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        activeLine = line
        activeView = view
        charIndex = 0
        lineBuffer.setLength(0)
        scrollPending = true
        trimTranscript()
        autoScroll()
    }

    private fun typeChunk(line: ConsoleLine, view: TextView) {
        val end = min(line.text.length, charIndex + CHARS_PER_TICK)
        lineBuffer.append(line.text, charIndex, end)
        charIndex = end
        view.text = lineBuffer.toString()
        autoScroll()
        if (charIndex < line.text.length) {
            schedule(CHAR_TICK_MS)
        } else {
            scrollToBottomNow()
            schedule(line.pauseMs.coerceAtLeast(16L))
        }
    }

    private fun colorFor(level: ConsoleLevel): Int = when (level) {
        ConsoleLevel.OK -> HudKit.GREEN
        ConsoleLevel.WARN -> HudKit.AMBER
        ConsoleLevel.ERR -> HudKit.RED
        ConsoleLevel.SYS -> HudKit.CYAN
        ConsoleLevel.OUT -> HudKit.TEXT
    }

    private fun autoScroll() {
        scrollPending = true
        scrollToBottomNow()
    }

    private fun scrollToBottomNow() {
        val target = content.height + scrollView.paddingTop + scrollView.paddingBottom - scrollView.height
        if (target > 0) scrollView.scrollTo(0, target)
    }

    private fun trimTranscript() {
        // Trim in blocks so the scroll position does not jump on every new line.
        while (content.childCount > MAX_LINES + 8) {
            content.removeViewAt(0)
        }
    }

    private fun submitInput() {
        if (released) return
        val typed = editText.text?.toString().orEmpty()
        editText.setText("")
        val raw = typed.trim()
        if (raw.isEmpty()) return
        enqueue(
            listOf(ConsoleLine("> neural-ml@decoder:~$ ${raw.take(96)}", ConsoleLevel.SYS, 20L))
        )
        onCommand?.invoke(raw)
    }
}
