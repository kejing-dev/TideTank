package io.embodit.tidetank.domain

import kotlin.math.abs

/**
 * D9 (prompts/snippets/native-window-move.md §7 item 9): a window opened while the head is pitched opens pitched,
 * so every openWindowContainer first asks here and only opens once the head has been level (|pitch| <= 10°) for
 * 300 ms. Pure Kotlin: the caller feeds the time and the head pitch (degrees, + up; null when there is no valid
 * head pose). With no valid head pose for [noHeadMs] it opens anyway, so the window is never stuck.
 *
 * Tide Tank v0.1.3 (Full Space) opens its one Planar control window from the Mixed Stage, so every open (first, reopen
 * after ×, [叫回面前], the 「面板」 board) waits for a level head (same as clay-forge / spatial-calc).
 */
class LevelGate(val maxPitchDeg: Float = 10f, val dwellMs: Long = 300L, val noHeadMs: Long = 5000L) {
    var pending: String? = null; private set
    private var askedMs = 0L
    private var levelSinceMs = -1L
    private var noHeadSinceMs = -1L
    val waiting: Boolean get() = pending != null

    fun ask(why: String, now: Long) {
        if (pending == null) askedMs = now
        pending = why
        levelSinceMs = -1L
        noHeadSinceMs = -1L
    }

    fun cancel() { pending = null; levelSinceMs = -1L; noHeadSinceMs = -1L }

    fun waitedMs(now: Long): Long = if (pending == null) 0L else now - askedMs

    /** Returns why to open now, or null to keep waiting. */
    fun step(now: Long, pitchDeg: Float?): String? {
        val why = pending ?: return null
        if (pitchDeg == null || !pitchDeg.isFinite()) {
            levelSinceMs = -1L
            if (noHeadSinceMs < 0L) noHeadSinceMs = now
            if (now - noHeadSinceMs < noHeadMs) return null
            cancel()
            return "$why/nohead"
        }
        noHeadSinceMs = -1L
        if (abs(pitchDeg) > maxPitchDeg) { levelSinceMs = -1L; return null }
        if (levelSinceMs < 0L) levelSinceMs = now
        if (now - levelSinceMs < dwellMs) return null
        cancel()
        return why
    }

    fun hintWanted(pitchDeg: Float?): Boolean = pending != null && pitchDeg != null && abs(pitchDeg) > maxPitchDeg
}

/**
 * Settings [叫回面前] (profile D29): not sampled at the press (the head faces the button then). After the window
 * is put away, wait until the head has turned less than 2° for 0.6 s, or 3 s at most, then take that pose.
 */
class RecallSampler(val stillDeg: Float = 2f, val stillMs: Long = 600L, val maxMs: Long = 3000L) {
    private var startMs = -1L
    private var anchorYaw = 0f
    private var anchorPitch = 0f
    private var stillSince = -1L
    val waiting get() = startMs >= 0

    fun start(now: Long) { startMs = now; stillSince = -1L }
    fun cancel() { startMs = -1L }

    /** Feed the head yaw / pitch (degrees); returns true once the pose may be taken. */
    fun step(now: Long, yawDeg: Float, pitchDeg: Float): Boolean {
        if (startMs < 0) return false
        val moved = kotlin.math.abs(angDiff(yawDeg, anchorYaw)) > stillDeg ||
            kotlin.math.abs(pitchDeg - anchorPitch) > stillDeg
        if (stillSince < 0 || moved) { stillSince = now; anchorYaw = yawDeg; anchorPitch = pitchDeg }
        if (now - stillSince >= stillMs || now - startMs >= maxMs) { startMs = -1L; return true }
        return false
    }
}

private fun angDiff(a: Float, b: Float): Float { var d = a - b; while (d > 180f) d -= 360f; while (d <= -180f) d += 360f; return d }

