package dev.xiaoman.hands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * What the pet is doing, mapped to the row order the ChatGPT / Codex desktop pet uses:
 * 0 idle, 1 running right, 2 running left, 3 waving, 4 jumping, 5 failed, 6 waiting,
 * 7 running (the agent is working), 8 review (a turn finished); rows 9-10 are look directions.
 *
 * [fallbackRow] covers hand-made atlases that skip a row, so a simplified sheet still animates.
 */
enum class PetState(val row: Int, val fallbackRow: Int) {
    IDLE(0, 0),
    RUN_RIGHT(1, 0),
    RUN_LEFT(2, 0),
    WAVING(3, 0),
    JUMPING(4, 0),
    FAILED(5, 0),
    WAITING(6, 0),
    WORKING(7, 1),
    REVIEW(8, 0),
}

/**
 * A tiny draggable pixel pet shown above every app.
 *
 * Uses [TYPE_ACCESSIBILITY_OVERLAY] so no SYSTEM_ALERT_WINDOW permission is needed; the context
 * passed in must be the connected [XiaomanAccessibilityService]. The window stays small,
 * non-focusable and touch-transparent so the pet does not block buttons underneath.
 */
class PetOverlay(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(WindowManager::class.java)

    private var root: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var sheet: PetSpriteSheet? = null
    private var currentFrame: Bitmap? = null
    private var animatorJob: Runnable? = null

    private var state: PetState = PetState.IDLE
    private var frameIndex: Int = 0

    /** Frames left before a reaction (done/failed) hands back to idle; idle and working never count down. */
    private var reactionFramesLeft = 0

    /** State to return to once a drag ends, so dragging does not overwrite what the round is doing. */
    private var stateBeforeDrag: PetState? = null

    /** Clicking the pet (not dragging) triggers this. */
    var onClick: () -> Unit = {}
    /** Dragged to a new resting place so the host can persist coordinates. */
    var onRestAt: (xDp: Float, yDp: Float) -> Unit = { _, _ -> }

    /** Load (or reload) the sprite sheet and start animating. */
    fun setSheet(newSheet: PetSpriteSheet?) {
        if (newSheet === sheet) return
        sheet?.close()
        sheet = newSheet
        frameIndex = 0
        if (isShowing) {
            // A different pack can have differently sized frames, so the window has to follow.
            resizeToSheet()
            scheduleFrame()
        }
    }

    val isShowing: Boolean get() = root?.isAttachedToWindow == true

    fun show(xDp: Float? = null, yDp: Float? = null) = onMain {
        if (root != null) return@onMain
        val density = context.resources.displayMetrics.density
        val frame = framePx()
        val view = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(android.R.color.transparent)
        }
        val lp = WindowManager.LayoutParams(
            frame, frame,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "Xiaoman pet"
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Absolute top-start coordinates only. With a bottom-end window, x/y meant "distance from
            // the right/bottom edge", while the drag handler writes screen pixels into them - the very
            // first drag then flew to a corner and persisted 0,0.
            gravity = Gravity.TOP or Gravity.START
            val metrics = wm.currentWindowMetrics
            val maxX = (metrics.bounds.width() - frame).coerceAtLeast(0)
            val maxY = (metrics.bounds.height() - frame).coerceAtLeast(0)
            if (xDp != null && yDp != null) {
                // A saved spot can fall outside the screen after a rotation or a resolution change.
                x = (xDp * density).toInt().coerceIn(0, maxX)
                y = (yDp * density).toInt().coerceIn(0, maxY)
            } else {
                val bars = metrics.windowInsets
                    .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
                    .bottom
                x = (maxX - dp(16)).coerceIn(0, maxX)
                y = (maxY - bars - dp(16)).coerceIn(0, maxY)
            }
        }
        attachGesture(view, lp, density)
        try {
            wm.addView(view, lp)
            root = view
            layoutParams = lp
        } catch (e: RuntimeException) {
            android.util.Log.w(TAG, "cannot show pet: ${e.message}")
            root = null
            layoutParams = null
            return@onMain
        }
        scheduleFrame()
    }

    fun hide() = onMain {
        stopAnim()
        val view = root ?: return@onMain
        root = null
        layoutParams = null
        recycleFrame()
        runCatching { wm.removeViewImmediate(view) }
    }

    fun setState(next: PetState) {
        if (next == state) return
        state = next
        frameIndex = 0
        if (isShowing) scheduleFrame()
    }

    /**
     * Hides the pet's input surface while [block] runs (e.g. screenshot) so the underlying app
     * receives all touches and the pet does not cover the capture target.
     */
    suspend fun <T> hiddenWhile(settleMs: Long = 100, block: suspend () -> T): T {
        var hidden: Triple<View, Int, WindowManager.LayoutParams>? = null
        withContext(coroutineContext) {
            // Switch to main so we can touch the view tree; if we are already on main this is a no-op.
            if (Looper.myLooper() == Looper.getMainLooper()) {
                hidden = captureHidden()
            } else {
                main.postSync { hidden = captureHidden() }
            }
        }
        if (hidden == null) return block()
        try {
            delay(settleMs)
            return block()
        } finally {
            withContext(NonCancellable + coroutineContext) {
                main.postSync {
                    val h = hidden ?: return@postSync
                    val (view, flags, lp) = h
                    lp.flags = flags
                    runCatching { wm.updateViewLayout(view, lp) }
                    scheduleFrame()
                }
            }
        }
    }

    private fun captureHidden(): Triple<View, Int, WindowManager.LayoutParams>? {
        val view = root
        val lp = layoutParams
        if (view == null || lp == null) return null
        val flags = lp.flags
        lp.flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(view, lp) }
        stopAnim()
        return Triple(view, flags, lp)
    }

    private fun scheduleFrame() {
        stopAnim()
        val view = root ?: return
        val sheet = sheet ?: return
        // Older or hand-trimmed atlases may lack the row we want; walk the chain instead of freezing.
        val row = usableRow(sheet, state)
        val count = sheet.frameCount(row)
        if (count == 0) return
        val rate = when (state) {
            PetState.IDLE -> 450L
            PetState.WORKING, PetState.RUN_RIGHT, PetState.RUN_LEFT -> 160L
            PetState.WAVING, PetState.JUMPING -> 240L
            PetState.REVIEW -> 280L
            PetState.FAILED -> 300L
            PetState.WAITING -> 320L
        }
        // A reaction is a one-off: play it through once, then go back to standing around. Looping it
        // forever made the pet look like it was still busy long after the round had finished.
        reactionFramesLeft = if (state == PetState.REVIEW || state == PetState.FAILED ||
            state == PetState.JUMPING || state == PetState.WAVING
        ) count else 0
        val tick = object : Runnable {
            override fun run() {
                val v = root
                val s = sheet
                if (v != null && s != null && v.isAttachedToWindow && s.isUsable()) {
                    val idx = frameIndex % count
                    val next = s.frame(row, idx)
                    if (next != null) {
                        recycleFrame()
                        v.setImageBitmap(next)
                        currentFrame = next
                    }
                    frameIndex = (frameIndex + 1) % count
                    if (reactionFramesLeft > 0) {
                        reactionFramesLeft--
                        if (reactionFramesLeft == 0) {
                            setState(PetState.IDLE)
                            return
                        }
                    }
                    animatorJob = this
                    main.postDelayed(this, rate)
                }
            }
        }
        animatorJob = tick
        main.post(tick)
    }

    /** First row in [state]'s fallback chain that actually has frames. */
    private fun usableRow(sheet: PetSpriteSheet, state: PetState): Int {
        for (candidate in intArrayOf(state.row, state.fallbackRow, IDLE_ROW)) {
            if (sheet.frameCount(candidate) > 0) return candidate
        }
        return state.row
    }

    private fun stopAnim() {
        animatorJob?.let { main.removeCallbacks(it) }
        animatorJob = null
    }

    private fun recycleFrame() {
        currentFrame?.let { runCatching { it.recycle() } }
        currentFrame = null
    }

    private fun attachGesture(view: View, lp: WindowManager.LayoutParams, density: Float) {
        var startX = 0f
        var startY = 0f
        var touchX = 0
        var touchY = 0
        var dx = 0f
        var dy = 0f
        var moved = false
        view.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX
                    startY = ev.rawY
                    touchX = lp.x
                    touchY = lp.y
                    dx = 0f
                    dy = 0f
                    moved = false
                    stateBeforeDrag = state
                }
                MotionEvent.ACTION_MOVE -> {
                    dx = ev.rawX - startX
                    dy = ev.rawY - startY
                    if (!moved && (kotlin.math.abs(dx) > dpTouchSlop() || kotlin.math.abs(dy) > dpTouchSlop())) moved = true
                    if (moved) {
                        // Dragging left or right plays that direction's run row, as the desktop pet does.
                        setState(if (dx > 0f) PetState.RUN_RIGHT else PetState.RUN_LEFT)
                        lp.x = (touchX + dx).toInt()
                        lp.y = (touchY + dy).toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val params = layoutParams
                    if (moved && params != null) {
                        val bounds = wm.currentWindowMetrics.bounds
                        val maxX = bounds.width() - v.width
                        val maxY = bounds.height() - v.height
                        params.x = params.x.coerceIn(0, maxX)
                        params.y = params.y.coerceIn(0, maxY)
                        runCatching { wm.updateViewLayout(v, params) }
                        onRestAt(params.x / density, params.y / density)
                    }
                    stateBeforeDrag?.let { setState(it) }
                    stateBeforeDrag = null
                    if (!moved && ev.action == MotionEvent.ACTION_UP) onClick()
                }
            }
            true
        }
    }

    /** Side length of the pet window for the current sheet (1:1 with the source frame pixels). */
    private fun framePx(): Int {
        val density = context.resources.displayMetrics.density
        val base = sheet?.frameWidth?.toFloat() ?: return dp(72)
        return (base * density / 2f).toInt().coerceAtLeast(24)
    }

    private fun resizeToSheet() = onMain {
        val view = root ?: return@onMain
        val lp = layoutParams ?: return@onMain
        val side = framePx()
        if (lp.width == side && lp.height == side) return@onMain
        lp.width = side
        lp.height = side
        runCatching { wm.updateViewLayout(view, lp) }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    private fun dpTouchSlop(): Float = context.resources.displayMetrics.density * 8f

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun Handler.postSync(block: () -> Unit) {
        if (Looper.myLooper() == looper) {
            block()
            return
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var caught: Throwable? = null
        post {
            try { block() } catch (t: Throwable) { caught = t } finally { latch.countDown() }
        }
        latch.await()
        caught?.let { throw it }
    }

    private companion object {
        /** Row used when the requested action has no frames in this sheet. */
        const val IDLE_ROW = 0
        const val TAG = "Xiaoman"
    }
}
