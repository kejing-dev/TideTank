package io.embodit.tidetank.platform

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.embodit.tidetank.domain.LevelGate

/**
 * v0.1.3 Full Space (clay-forge / beat-pad WindowCtl, native-window-move.md §3.3, §3.5, §7 item 9): the control window
 * is opened from the Stage; every open first asks the [LevelGate] (head level for 300 ms) and the Stage frame feeds the
 * head pitch. The system puts a Full Space window straight ahead of the head: [叫回面前] re-places the tank under the
 * current heading (StageRig) and reopens the window (close, wait for ON_DESTROY, open). Exit closes the window and the Stage.
 */
class WindowCtl {
    val gate = LevelGate()
    var levelHint by mutableStateOf(false); private set
    var panelState by mutableStateOf("never")
        private set
    val panelOpen: Boolean get() = panelState in setOf("ON_CREATE", "ON_START", "ON_RESUME", "ON_PAUSE")
    @Volatile var openSeq = 0; private set

    private var openFn: (() -> Unit)? = null
    private var closeFn: (() -> Unit)? = null
    private var closeStageFn: (() -> Unit)? = null
    private var reopenPending = false
    private var lastStepMs = 0L
    private var exiting = false
    private val main = Handler(Looper.getMainLooper())
    /** [叫回面前] / the 「面板」 board: the Stage re-places the tank (set by StageRig). */
    var onRecall: ((String) -> Unit)? = null

    fun attach(open: () -> Unit, close: () -> Unit, closeStage: () -> Unit) { openFn = open; closeFn = close; closeStageFn = closeStage }
    fun detach() { openFn = null; closeFn = null; closeStageFn = null; gate.cancel(); main.removeCallbacks(watchdog) }

    /** A level-gated open (straight ahead of the head once it is level). */
    fun open(why: String) {
        gate.ask(why, SystemClock.elapsedRealtime())
        Log.i(TAG, "WINDOW ask why=$why state=$panelState")
        main.removeCallbacks(watchdog); main.postDelayed(watchdog, 3000)
    }

    /** Close if open, wait for ON_DESTROY, then a level-gated open ahead of the head. */
    fun reopen(why: String = "reopen") {
        if (!panelOpen) { open(why); return }
        reopenPending = true
        Log.i(TAG, "WINDOW close why=$why (reopen)")
        runCatching { closeFn?.invoke() }.onFailure { Log.w(TAG, "WINDOW close: $it") }
    }

    /** Settings [叫回面前] / the 「面板」 board: tank back under the current heading, window reopened in front. */
    fun recall(why: String) {
        Log.i(TAG, "WINDOW recall why=$why open=$panelOpen")
        onRecall?.invoke(why) ?: reopen(why)
    }

    fun onPanelLifecycle(event: String) {
        panelState = event
        Log.i(TAG, "WINDOW lifecycle $event")
        if (event == "ON_DESTROY" && reopenPending && !exiting) { reopenPending = false; main.postDelayed({ open("reopen") }, 250) }
    }

    /** Every Stage frame: the head pitch in degrees (null when there is no valid head pose). */
    fun stepFrame(pitchDeg: Float?) {
        val now = SystemClock.elapsedRealtime()
        lastStepMs = now
        if (!gate.waiting) { if (levelHint) levelHint = false; return }
        val hint = gate.hintWanted(pitchDeg)
        if (hint != levelHint) levelHint = hint
        gate.step(now, pitchDeg)?.let { why -> levelHint = false; openNow(why, pitchDeg) }
    }

    private fun openNow(why: String, pitch: Float?) {
        main.removeCallbacks(watchdog)
        openSeq++
        Log.i(TAG, "WINDOW open why=$why pitch=$pitch")
        runCatching { openFn?.invoke() }.onFailure { Log.e(TAG, "WINDOW open failed", it) }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!gate.waiting) return
            val now = SystemClock.elapsedRealtime()
            val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > 15_000 else now - lastStepMs > 3000
            if (stalled) { val why = gate.pending!!; gate.cancel(); levelHint = false; openNow("$why/noframes", null) } else main.postDelayed(this, 3000)
        }
    }

    /** Debug: the system [×] on the control window. */
    fun closeControlOnly() { runCatching { closeFn?.invoke() } }

    /** Exit (D26 / D44): close the control window and the Stage before the task is removed. */
    fun closeAll() {
        exiting = true
        gate.cancel()
        runCatching { closeFn?.invoke() }.onFailure { Log.w(TAG, "EXIT close control: $it") }
        runCatching { closeStageFn?.invoke() }.onFailure { Log.w(TAG, "EXIT close stage: $it") }
        Log.i(TAG, "EXIT closed the control window and the Stage")
    }
}
