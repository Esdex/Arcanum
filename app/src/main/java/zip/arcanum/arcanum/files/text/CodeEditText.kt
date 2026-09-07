package zip.arcanum.arcanum.files.text

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.LineBackgroundSpan
import android.text.style.StyleSpan
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatEditText
import zip.arcanum.BuildConfig

/**
 * The editing surface (#109), an `EditText` rather than a `BasicTextField`.
 *
 * The Compose text field lays its whole content out in one piece on the main thread. That
 * froze the app for seconds on a 250 KB log and then crashed it, because the laid-out height
 * of four thousand lines - 488049 px - is more than a `Constraints` pair can even hold. The
 * platform's `DynamicLayout`, which is what an `EditText` uses once it has content, re-lays
 * out only the lines an edit touched, and it is what every text editor on Android is built on.
 *
 * Three things are drawn here rather than composed, all of them for the same reason - a file
 * has thousands of lines and a frame has sixteen milliseconds:
 *
 * - **the line numbers**, only for the lines on screen, and numbering LINES OF THE FILE: with
 *   wrapping on, one line covers several rows and is numbered once, against its first row.
 * - **the dots and arrows** standing in for spaces and tabs, again only where they are visible.
 *   Nothing is substituted in the text itself, so the buffer stays exactly what will be saved.
 * - **the colouring**, as spans over a window around what is on screen rather than over the
 *   whole file. Colouring a quarter of a megabyte on every keystroke is the same mistake in a
 *   different place.
 */
class CodeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatEditText(context, attrs) {

    /** Called with the buffer after every change the user makes. */
    var onTextEdited: ((String) -> Unit)? = null

    /** Called whenever undo or redo becomes possible, or stops being. */
    var onHistoryChanged: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null

    var showLineNumbers: Boolean = true
        set(value) { field = value; updateGutterPadding(); invalidate() }

    var showWhitespace: Boolean = false
        set(value) { field = value; invalidate() }

    /**
     * Whether a drag puts the keyboard away.
     *
     * Off unless asked for: a keyboard that closes itself is a surprise. With it on, the
     * moment a finger starts scrolling is the moment reading begins, and half the screen
     * stops being a keyboard.
     */
    var hideImeOnScroll: Boolean = false

    var syntax: Syntax = Syntax.NONE
        set(value) { field = value; scheduleHighlight(0) }

    /** Colour per [TokenKind], by ordinal. */
    var palette: IntArray = IntArray(TokenKind.entries.size)
        set(value) { field = value; scheduleHighlight(0) }

    /**
     * The colour of the caret, the selection and its handles.
     *
     * The platform paints all four from the theme of the Context, which for a view living
     * inside Compose is AppCompat's rather than the app's own - so they are set here from the
     * same primary colour everything else on the screen uses. `minSdk` is 29, which is where
     * these setters begin, so there is no older path to keep.
     */
    var accentColor: Int = 0
        set(value) {
            field = value
            val caretWidth = (2 * resources.displayMetrics.density).toInt().coerceAtLeast(2)
            textCursorDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setSize(caretWidth, 0)
                setColor(value)
            }
            // A selection you can still read the text through.
            highlightColor = ColorUtils.setAlphaComponent(value, SELECTION_ALPHA)
            tinted(textSelectHandle, value)?.let { setTextSelectHandle(it) }
            tinted(textSelectHandleLeft, value)?.let { setTextSelectHandleLeft(it) }
            tinted(textSelectHandleRight, value)?.let { setTextSelectHandleRight(it) }
            invalidate()
        }

    var gutterTextColor: Int = 0x80FFFFFF.toInt()
        set(value) { field = value; numberPaint.color = value; invalidate() }

    var gutterBackground: Int = 0
        set(value) { field = value; gutterPaint.color = value; invalidate() }

    /** The panel behind a Markdown file's front matter, in edit mode. */
    var frontMatterBackground: Int = 0
        set(value) { field = value; scheduleHighlight(0) }

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gutterPaint = Paint()
    private val markPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds      = Rect()

    /** Visual row -> line number of the file, or 0 for a row that is the wrap of one. */
    private var rowNumbers = IntArray(0)
    private var rowNumbersDirty = true

    private var gutterWidth = 0
    private var applyingSpans = false

    private val highlightPass = Runnable { highlightWindow() }

    // ── Undo and redo ─────────────────────────────────────────────────────────
    /*
     * The platform keeps an undo history of its own, but offers no way to ask whether there
     * is anything in it - so the buttons could never be greyed out honestly. This one is
     * ours: each change is what was there, what replaced it, and where; typing is folded into
     * one entry while it stays in one place, so undo takes back a word rather than a letter.
     */
    private class Edit(val start: Int, val before: CharSequence, val after: CharSequence, val at: Long)

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private var applyingHistory = false
    private var removedText: CharSequence = ""

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun undo() = step(undoStack, redoStack, undoing = true)
    fun redo() = step(redoStack, undoStack, undoing = false)

    private fun step(from: ArrayDeque<Edit>, to: ArrayDeque<Edit>, undoing: Boolean) {
        val edit = from.removeLastOrNull() ?: return
        val editable = text ?: return
        val had = if (undoing) edit.after else edit.before
        val put = if (undoing) edit.before else edit.after
        val end = edit.start + had.length
        if (edit.start > editable.length || end > editable.length) {
            // The buffer is not what this entry was recorded against; the history is no
            // longer about this text, so it is dropped rather than applied blindly.
            from.clear(); to.clear(); notifyHistory(); return
        }
        applyingHistory = true
        try {
            editable.replace(edit.start, end, put)
            setSelection((edit.start + put.length).coerceIn(0, editable.length))
        } finally {
            applyingHistory = false
        }
        to.addLast(edit)
        userScrolled = false          // the change is what should be looked at now
        onTextEdited?.invoke(editable.toString())
        scheduleHighlight(0)
        notifyHistory()
    }

    private fun record(start: Int, before: CharSequence, after: CharSequence) {
        val now = SystemClock.elapsedRealtime()
        val last = undoStack.lastOrNull()
        // One entry per run of typing: single characters, appended where the last one ended,
        // without a pause long enough to count as a second thought.
        val continues = last != null && before.isEmpty() && last.before.isEmpty() &&
            after.length == 1 && now - last.at < TYPING_RUN_MS &&
            start == last.start + last.after.length && after[0] != '\n'
        if (continues) {
            val merged = Edit(last!!.start, last.before,
                              StringBuilder(last.after).append(after), now)
            undoStack.removeLast()
            undoStack.addLast(merged)
        } else {
            undoStack.addLast(Edit(start, before, after, now))
            while (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
        }
        redoStack.clear()
        notifyHistory()
    }

    private fun notifyHistory() = onHistoryChanged?.invoke(canUndo, canRedo)

    init {
        // A text editor is not a form field: no suggestions rewriting what was typed, no
        // capitalisation of a line of code, and the top of the text is where it starts.
        setHorizontallyScrolling(false)
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        setTextIsSelectable(true)
        includeFontPadding = false
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (applyingSpans || applyingHistory) return
                removedText = s?.subSequence(start, start + count)?.toString().orEmpty()
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (applyingSpans || applyingHistory) return
                record(start, removedText, s?.subSequence(start, start + count)?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {
                if (applyingSpans || applyingHistory) return
                if (!isCursorVisible) isCursorVisible = true
                userScrolled = false      // typing means the caret is what matters again
                rowNumbersDirty = true
                onTextEdited?.invoke(s?.toString().orEmpty())
                scheduleHighlight(HIGHLIGHT_DELAY_MS)
            }
        })
    }

    /**
     * Puts a file into the editor, once.
     *
     * Kept apart from the ordinary setText so the caller cannot do it twice by accident: the
     * text belongs to this view from here on, and writing it again would take the cursor and
     * the selection with it.
     */
    fun setDocument(text: String) {
        val started = SystemClock.elapsedRealtime()
        applyingSpans = true
        setText(text)
        applyingSpans = false
        // A file that has just been opened has no history: undoing to a state this file was
        // never in would be a fine way to lose it.
        undoStack.clear()
        redoStack.clear()
        notifyHistory()
        rowNumbersDirty = true
        scheduleHighlight(0)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "opened: ${text.length} chars in " +
                "${SystemClock.elapsedRealtime() - started} ms")
        }
    }

    // TextView has no getter for this below API 34, so the state is kept here rather than
    // asked for - and it is what decides whether a long line wraps or scrolls sideways.
    private var wrapping = true

    fun setWordWrap(wrap: Boolean) {
        if (wrap == wrapping) return
        wrapping = wrap
        setHorizontallyScrolling(!wrap)
        rowNumbersDirty = true
        requestLayout()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, after: Int) {
        super.onTextChanged(text, start, before, after)
        rowNumbersDirty = true
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        // Scrolling brings text into view that was never coloured - the window follows.
        scheduleHighlight(HIGHLIGHT_DELAY_MS)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rowNumbersDirty = true
    }

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        if (layout != null && (showLineNumbers || showWhitespace)) {
            if (rowNumbersDirty) rebuildRowNumbers()
            val first = layout.getLineForVertical(scrollY)
            val last  = layout.getLineForVertical(scrollY + height)
            if (showLineNumbers) drawNumbers(canvas, first, last)
            if (showWhitespace)  drawWhitespace(canvas, first, last)
        }
        super.onDraw(canvas)
    }

    private fun drawNumbers(canvas: Canvas, first: Int, last: Int) {
        // Pinned to the left edge of what is visible, so it stays put while the text scrolls
        // sideways.
        canvas.drawRect(
            scrollX.toFloat(), scrollY.toFloat(),
            (scrollX + gutterWidth).toFloat(), (scrollY + height).toFloat(),
            gutterPaint
        )
        for (row in first..last) {
            val number = rowNumbers.getOrNull(row) ?: continue
            if (number == 0) continue           // a wrapped continuation is not a new line
            val baseline = getLineBounds(row, bounds)
            val label = number.toString()
            val x = scrollX + gutterWidth - NUMBER_GAP_PX * resources.displayMetrics.density -
                numberPaint.measureText(label)
            canvas.drawText(label, x, baseline.toFloat(), numberPaint)
        }
    }

    /**
     * A dot for a space, an arrow for a tab, drawn where the character sits.
     *
     * Only over the rows on screen: asking the layout where every space of a large file is
     * would cost more than the text does.
     */
    private fun drawWhitespace(canvas: Canvas, first: Int, last: Int) {
        val layout = layout ?: return
        val text   = text ?: return
        markPaint.color = gutterTextColor
        markPaint.textSize = textSize * 0.9f
        val left = compoundPaddingLeft
        for (row in first..last) {
            val start = layout.getLineStart(row)
            val end   = layout.getLineEnd(row)
            val baseline = getLineBounds(row, bounds)
            for (i in start until minOf(end, text.length)) {
                val c = text[i]
                if (c != ' ' && c != '\t') continue
                val x = layout.getPrimaryHorizontal(i) + left
                canvas.drawText(if (c == ' ') "·" else "→", x, baseline.toFloat(), markPaint)
            }
        }
    }

    /**
     * Which file line each row on screen belongs to.
     *
     * Built once per change rather than counted per frame: counting the newlines before a row
     * costs the whole file above it, which for a scroll to the end is the whole file.
     */
    private fun rebuildRowNumbers() {
        val layout = layout ?: return
        val text = text ?: return
        val rows = layout.lineCount
        if (rowNumbers.size < rows) rowNumbers = IntArray(rows)
        var fileLine = 1
        for (row in 0 until rows) {
            val start = layout.getLineStart(row)
            val isFileLineStart = start == 0 || (start <= text.length && text[start - 1] == '\n')
            rowNumbers[row] = if (isFileLineStart) fileLine++ else 0
        }
        for (row in rows until rowNumbers.size) rowNumbers[row] = 0
        rowNumbersDirty = false
        updateGutterPadding(fileLine - 1)
    }

    private fun updateGutterPadding(lines: Int = -1) {
        if (!showLineNumbers) {
            if (gutterWidth != 0) { gutterWidth = 0; applyPadding() }
            return
        }
        numberPaint.textSize = textSize
        numberPaint.typeface = typeface
        numberPaint.color = gutterTextColor
        val digits = maxOf(2, (if (lines > 0) lines else 99).toString().length)
        val width = (numberPaint.measureText("8".repeat(digits)) +
            NUMBER_GAP_PX * 2 * resources.displayMetrics.density).toInt()
        if (width != gutterWidth) { gutterWidth = width; applyPadding() }
    }

    private fun applyPadding() {
        // The same breathing space on all four sides, and no more: it used to be twelve times
        // this at the bottom, from the first version where the text sat under a floating
        // button, and that block of nothing showed up over the keyboard - padding belongs to
        // the content and scrolls with it. Room for the keyboard is not this view's job, the
        // screen keeps the whole field above it.
        val pad = (8 * resources.displayMetrics.density).toInt()
        setPadding(gutterWidth + pad, pad, pad, 0)
        requestLayout()
    }

    // ── Marking up text ───────────────────────────────────────────────────────
    /*
     * What the row of buttons over the keyboard does. All three go through the Editable, so
     * each one is a single entry in the undo history and reaches the view model the same way
     * typing does.
     */

    /** Wraps the selection, or opens an empty pair at the caret and puts it in the middle. */
    fun wrapSelection(prefix: String, suffix: String = prefix) {
        val e = text ?: return
        val a = selectionStart.coerceIn(0, e.length)
        val b = selectionEnd.coerceIn(0, e.length)
        val from = minOf(a, b)
        val to   = maxOf(a, b)
        val inner = e.subSequence(from, to).toString()
        // Already wrapped: take the marks off again, so the button is a toggle.
        val outerFrom = from - prefix.length
        val outerTo   = to + suffix.length
        if (outerFrom >= 0 && outerTo <= e.length &&
            e.subSequence(outerFrom, from).toString() == prefix &&
            e.subSequence(to, outerTo).toString() == suffix) {
            e.replace(outerFrom, outerTo, inner)
            setSelection(outerFrom, outerFrom + inner.length)
            return
        }
        e.replace(from, to, prefix + inner + suffix)
        if (inner.isEmpty()) setSelection(from + prefix.length)
        else setSelection(from + prefix.length, to + prefix.length)
    }

    /**
     * Puts [prefix] at the start of the line the caret is on, or takes it off again.
     *
     * The other list markers are cleared first, so turning a bullet into a checkbox is one
     * tap rather than a tap and a tidy-up.
     */
    fun toggleLinePrefix(prefix: String) {
        val e = text ?: return
        val pos = selectionStart.coerceIn(0, e.length)
        var lineStart = pos
        while (lineStart > 0 && e[lineStart - 1] != '\n') lineStart--
        var lineEnd = pos
        while (lineEnd < e.length && e[lineEnd] != '\n') lineEnd++

        val line = e.subSequence(lineStart, lineEnd).toString()
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val body = line.substring(indent.length)

        val replacement = when {
            body.startsWith(prefix) -> indent + body.removePrefix(prefix)
            else -> indent + prefix + body.removePrefix(stripMarker(body))
        }
        e.replace(lineStart, lineEnd, replacement)
        setSelection((lineStart + replacement.length).coerceIn(0, e.length))
    }

    /** Whatever list or heading marker the line already carries, so it can be swapped. */
    private fun stripMarker(body: String): String =
        MARKERS.firstOrNull { body.startsWith(it) } ?: ""

    /**
     * Flips the checkbox whose bracket is at [offset] - the tap in the rendered view.
     *
     * Checked against the source rather than trusted: the rendering it came from may be a
     * moment old, and writing an x into the middle of a word is not a thing to risk.
     */
    fun toggleTaskAt(offset: Int) {
        val e = text ?: return
        if (offset !in 0 until e.length) return
        if (offset < 1 || e[offset - 1] != '[') return
        if (offset + 1 >= e.length || e[offset + 1] != ']') return
        val replacement = when (e[offset]) {
            ' ' -> "x"
            'x', 'X' -> " "
            else -> return
        }
        e.replace(offset, offset + 1, replacement)
    }

    // ── Flinging ──────────────────────────────────────────────────────────────
    /*
     * A text view scrolls while a finger drags it and stops dead when the finger lifts: it is
     * not a scrolling container, so nothing carries the movement on. Everything else in the
     * app glides, so this reads as the editor being stuck to the glass.
     *
     * The gesture itself is left to the platform - it places the cursor, starts a selection,
     * and moves the text under the drag. Only the release is ours: the speed the finger had is
     * handed to a scroller, and each frame moves the view where the scroller says.
     */
    private val scroller = OverScroller(context)
    private var tracker: VelocityTracker? = null
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    /**
     * The gesture, and the one piece of it that cannot be left to the platform.
     *
     * Since Android 12 a sideways drag inside an editable text view **moves the caret** - it
     * is the system's cursor-drag gesture. With wrapping off there is also a text to pan
     * sideways, and the two mean opposite things for the same finger: dragging to see the end
     * of a long line dragged the cursor along instead, which is what Esdex hit on a log file.
     *
     * So a drag that turns out to be horizontal is taken over here: the platform is sent a
     * cancel - which also drops the tap it was about to turn into a caret move - and the view
     * is scrolled by hand from there to the end of the gesture. Everything else stays the
     * platform's: taps place the cursor, long presses select, vertical drags scroll.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A touch stops a fling in flight, the way it does in any list.
                if (!scroller.isFinished) scroller.forceFinished(true)
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                tracker?.addMovement(event)
                downX = event.x
                downY = event.y
                lastX = event.x
                panningSideways = false
                draggedAtAll = false
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                // A finger that has begun to drag is scrolling, and a caret blinking through
                // moving text is both noise and a redraw of the line it sits on, every half
                // second, for the whole gesture. It comes back on the next tap - or on the
                // next keystroke, since the keyboard can still be open.
                if (!draggedAtAll &&
                    (kotlin.math.abs(event.x - downX) > touchSlop ||
                     kotlin.math.abs(event.y - downY) > touchSlop)) {
                    draggedAtAll = true
                    userScrolled = true
                    if (isCursorVisible) isCursorVisible = false
                    // Focus is kept, so a tap back into the text brings the keyboard with it.
                    if (hideImeOnScroll) {
                        ViewCompat.getWindowInsetsController(this)
                            ?.hide(WindowInsetsCompat.Type.ime())
                    }
                }
                if (!panningSideways && maxScrollX() > 0) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    // Decided once, on the first movement worth calling a direction, and it
                    // has to be clearly sideways: a drag that is merely not vertical belongs
                    // to the platform.
                    if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                        panningSideways = true
                        lastX = event.x
                        parent?.requestDisallowInterceptTouchEvent(true)
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
                if (panningSideways) {
                    val dx = lastX - event.x
                    lastX = event.x
                    scrollTo((scrollX + dx.toInt()).coerceIn(0, maxScrollX()), scrollY)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                val panned = panningSideways
                tracker?.let { t ->
                    t.addMovement(event)
                    t.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    fling((-t.xVelocity).toInt(), (-t.yVelocity).toInt())
                }
                releaseTracker()
                panningSideways = false
                // A touch that never became a drag is a tap: it places the caret, so the
                // caret has to be there to see - and following it is wanted again.
                if (!draggedAtAll) {
                    isCursorVisible = true
                    userScrolled = false
                }
                if (panned) return true
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseTracker()
                panningSideways = false
            }
        }
        return super.onTouchEvent(event)
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var panningSideways = false
    private var draggedAtAll = false

    /**
     * Whether the view is where the user put it rather than where the caret is.
     *
     * A text view keeps its caret on screen: it calls [bringPointIntoView] on a touch, and
     * that scrolls back to wherever the caret happens to be. Scroll sideways to read the end
     * of a long line, lift the finger, put it down again - and the next touch snapped the
     * text back to the caret and started over, which is exactly what Esdex saw.
     *
     * So while the view has been scrolled by hand, that pull is refused. It is allowed again
     * the moment the caret is meant to be looked at: a tap that places it, or a keystroke.
     */
    private var userScrolled = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    override fun bringPointIntoView(offset: Int): Boolean =
        if (userScrolled) false else super.bringPointIntoView(offset)

    private fun releaseTracker() {
        tracker?.recycle()
        tracker = null
    }

    private fun fling(velocityX: Int, velocityY: Int) {
        val maxY = maxScrollY()
        val maxX = maxScrollX()
        val vy = if (maxY > 0 && kotlin.math.abs(velocityY) > minFlingVelocity) velocityY else 0
        val vx = if (maxX > 0 && kotlin.math.abs(velocityX) > minFlingVelocity) velocityX else 0
        if (vx == 0 && vy == 0) return
        scroller.fling(scrollX, scrollY, vx, vy, 0, maxX, 0, maxY)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        scrollTo(
            scroller.currX.coerceIn(0, maxScrollX()),
            scroller.currY.coerceIn(0, maxScrollY())
        )
        postInvalidateOnAnimation()
    }

    private fun maxScrollY(): Int {
        val l = layout ?: return 0
        return maxOf(0, l.height + paddingTop + paddingBottom - height)
    }

    private fun maxScrollX(): Int {
        val l = layout ?: return 0
        if (wrapping) return 0
        return maxOf(0, l.width + compoundPaddingLeft + compoundPaddingRight - width)
    }

    private fun tinted(drawable: Drawable?, color: Int): Drawable? {
        val d = drawable ?: return null
        // A mutated wrap, so tinting the handle here cannot reach into the shared constant
        // state and colour every other text field in the app.
        return DrawableCompat.wrap(d.mutate()).also { DrawableCompat.setTint(it, color) }
    }

    private fun scheduleHighlight(delayMs: Long) {
        removeCallbacks(highlightPass)
        postDelayed(highlightPass, delayMs)
    }

    /**
     * Colours a window around what is on screen.
     *
     * The window is whole lines and reaches a screenful either side, so a scroll or a keypress
     * finds most of what it needs already done. Only our own spans are cleared - a selection,
     * a spelling mark or anything the platform put there is left alone.
     */
    private fun highlightWindow() {
        val editable = text ?: return
        val layout = layout ?: return
        if (syntax == Syntax.NONE) { clearOurSpans(editable, 0, editable.length); return }

        val firstRow = layout.getLineForVertical(scrollY - height)
        val lastRow  = layout.getLineForVertical(scrollY + height * 2)
        val from = layout.getLineStart(firstRow)
        val to   = minOf(layout.getLineEnd(lastRow), editable.length)
        if (to <= from) return

        // A Markdown file may open with front matter, and that block is not prose: it is the
        // note's properties, and it reads as such in every editor that knows about it. It is
        // coloured by its own rules, and the generic ones are kept out of it - a value like
        // "tags: one, two" is not a list, and a date is not a number to be highlighted.
        val frontEnd = if (syntax == Syntax.MARKDOWN) frontMatterEnd(editable) else 0

        applyingSpans = true
        try {
            clearOurSpans(editable, from, to)
            if (frontEnd > from) paintFrontMatter(editable, minOf(frontEnd, editable.length))
            for (token in SyntaxHighlighter.tokens(editable, from, to, syntax)) {
                if (token.start < frontEnd) continue
                val color = palette.getOrNull(token.kind.ordinal) ?: continue
                editable.setSpan(HlColor(color), token.start, token.end,
                                 Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val style = when (token.kind) {
                    TokenKind.HEADING            -> android.graphics.Typeface.BOLD
                    TokenKind.KEYWORD,
                    TokenKind.EMPHASIS           -> android.graphics.Typeface.BOLD
                    TokenKind.COMMENT            -> android.graphics.Typeface.ITALIC
                    else                         -> null
                }
                if (style != null) {
                    editable.setSpan(HlStyle(style), token.start, token.end,
                                     Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        } finally {
            applyingSpans = false
        }
    }

    private fun clearOurSpans(editable: Editable, from: Int, to: Int) {
        editable.getSpans(from, to, HlColor::class.java).forEach { editable.removeSpan(it) }
        editable.getSpans(from, to, HlStyle::class.java).forEach { editable.removeSpan(it) }
        editable.getSpans(from, to, HlBackground::class.java).forEach { editable.removeSpan(it) }
    }

    /**
     * Where the front matter ends, or 0 when the file does not open with any.
     *
     * Only ever at the very start of the file, and only when it closes - three dashes on the
     * first line and nothing else after them is a horizontal rule, not an unterminated block,
     * and colouring the rest of the note as properties would be a strange way to say so.
     */
    private fun frontMatterEnd(text: CharSequence): Int {
        if (text.length < 8) return 0
        var i = 0
        while (i < text.length && text[i] != '\n') i++
        if (text.subSequence(0, i).toString().trim() != "---") return 0
        var lineStart = i + 1
        while (lineStart < text.length) {
            var lineEnd = lineStart
            while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
            if (text.subSequence(lineStart, lineEnd).toString().trim() == "---") return lineEnd
            lineStart = lineEnd + 1
        }
        return 0
    }

    /** The block itself: a panel behind it, muted fences, keys apart from their values. */
    private fun paintFrontMatter(editable: Editable, end: Int) {
        if (frontMatterBackground != 0) {
            editable.setSpan(HlBackground(frontMatterBackground), 0, end,
                             Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val comment = palette.getOrNull(TokenKind.COMMENT.ordinal) ?: return
        val key     = palette.getOrNull(TokenKind.ATTR.ordinal) ?: return
        val value   = palette.getOrNull(TokenKind.STRING.ordinal) ?: return

        var lineStart = 0
        while (lineStart < end) {
            var lineEnd = lineStart
            while (lineEnd < end && editable[lineEnd] != '\n') lineEnd++
            val line = editable.subSequence(lineStart, lineEnd).toString()
            if (line.trim() == "---") {
                editable.setSpan(HlColor(comment), lineStart, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                val colon = line.indexOf(':')
                if (colon > 0) {
                    editable.setSpan(HlColor(key), lineStart, lineStart + colon,
                                     Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    editable.setSpan(HlStyle(android.graphics.Typeface.BOLD), lineStart, lineStart + colon,
                                     Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (lineStart + colon + 1 < lineEnd) {
                        editable.setSpan(HlColor(value), lineStart + colon + 1, lineEnd,
                                         Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            lineStart = lineEnd + 1
        }
    }

    /** Ours, so they can be found and removed without touching anyone else's. */
    private class HlColor(color: Int) : ForegroundColorSpan(color)
    private class HlStyle(style: Int) : StyleSpan(style)

    /** Fills the whole width of each line it covers, which is what makes it read as a panel. */
    private class HlBackground(private val color: Int) : LineBackgroundSpan {
        override fun drawBackground(
            canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int,
            baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, lineNumber: Int
        ) {
            val previous = paint.color
            paint.color = color
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
            paint.color = previous
        }
    }

    private companion object {
        const val TAG = "TextEditor"
        const val HIGHLIGHT_DELAY_MS = 120L
        const val NUMBER_GAP_PX = 8f
        const val HISTORY_LIMIT = 500
        const val TYPING_RUN_MS = 900L
        val MARKERS = listOf("- [ ] ", "- [x] ", "- [X] ", "- ", "* ", "+ ", "> ",
                             "###### ", "##### ", "#### ", "### ", "## ", "# ")
        const val SELECTION_ALPHA = 0x50
    }
}
