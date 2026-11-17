package io.embodit.tidetank.platform

import android.util.Log
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.tracking.hand.HandTrackingProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import io.embodit.tidetank.domain.Q4
import io.embodit.tidetank.domain.V3
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.asin

/** A tracked pose in Stage space. */
data class Pose(val position: V3, val rotation: Q4) {
    val forward: V3 get() = rotation.forward
    val pitchDeg: Float get() = Math.toDegrees(asin(forward.y.coerceIn(-1f, 1f).toDouble())).toFloat()
}

/**
 * HMD pose (clay-forge HeadTracker). Uses (ui-audit headpose table): placeUpright of the tank (cold start, [叫回面前]), the
 * level gate before the control window opens, the 3D tip facing the eye. Never to aim, pick or move the jar (D1).
 * A pose at the origin with the identity rotation is the tracker's "not yet" and is invalid (手语老师 v0.1.2).
 */
class HeadTracker {
    private var provider: HMDTrackingProvider? = null
    private var last: Pose? = null
    var validRun = 0; private set
    @Volatile var injected: Pose? = null

    fun start() {
        if (provider != null) return
        val p = HMDTrackingProvider()
        val r = runCatching { p.start() }
        Log.i(TAG, "TRACK hmd start=${r.getOrNull() ?: r.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() { runCatching { provider?.stop() }; provider = null; validRun = 0 }

    fun pose(): Pose? {
        injected?.let { return it }
        val p = provider ?: return last
        val hp = runCatching { p.latestData.hmdPose }.getOrNull() ?: return last
        val q = hp.rotation; val pos = hp.position
        if (pos.x == 0f && pos.y == 0f && pos.z == 0f && q.w == 1f && q.x == 0f && q.y == 0f && q.z == 0f) { validRun = 0; return last }
        if (!(pos.x.isFinite() && pos.y.isFinite() && pos.z.isFinite())) return last
        val s = Pose(V3(pos.x, pos.y, pos.z), Q4(q.x, q.y, q.z, q.w).normalized())
        last = s; validRun++
        return s
    }
}

/** Press edges (grip OR trigger, D53 补) of one hand from the real listener and the debug injection. */
class PressEdges {
    private val real = arrayOf(AtomicBoolean(false), AtomicBoolean(false))
    private val inj = arrayOf(AtomicBoolean(false), AtomicBoolean(false))
    private val injActive = arrayOf(AtomicBoolean(false), AtomicBoolean(false))
    private val presses = arrayOf(AtomicInteger(0), AtomicInteger(0))

    /** One sample of [hand] (0 left, 1 right); true on a rising edge. */
    fun set(hand: Int, down: Boolean, injected: Boolean = false): Boolean {
        val prev = if (injected) { injActive[hand].set(true); inj[hand].getAndSet(down) } else real[hand].getAndSet(down)
        val rising = down && !prev
        if (rising && (injected || !injActive[hand].get())) presses[hand].incrementAndGet()
        return rising
    }
    fun clearInjected(hand: Int) { injActive[hand].set(false); inj[hand].set(false) }
    fun held(hand: Int): Boolean = if (injActive[hand].get()) inj[hand].get() else real[hand].get()
    fun take(hand: Int): Int = presses[hand].getAndSet(0)
}

/**
 * Both controllers: poses from latestData on the frame thread, grip / trigger / thumbstick from the action listener
 * (its own thread -> atomics). A press = the rising edge of grip OR trigger (the user's 「按 grib/trigger 键拿起」).
 * Same approach as clay-forge Controllers / pruning-class ControllerInput.
 */
class Controllers {
    private var provider: ControllerTrackingProvider? = null
    val edges = PressEdges()
    private val stickX = AtomicInteger(0)
    var support: DataProvider.SupportState? = null; private set
    /** Debug `ctrl …`: an injected right controller pose (the emulator has none); its button goes through [edges]. */
    @Volatile var injected: Pose? = null

    private val listener = ControllerTrackingProvider.ControllerActionListener { a: ControllerActionData ->
        val l = runCatching { a.left.gripPressed || a.left.triggerPressed }.getOrDefault(false)
        val r = runCatching { a.right.gripPressed || a.right.triggerPressed }.getOrDefault(false)
        if (edges.set(0, l)) Log.i(TAG, "CTRL press left grip=${runCatching { a.left.gripPressed }.getOrNull()} trigger=${runCatching { a.left.triggerPressed }.getOrNull()}")
        if (edges.set(1, r)) Log.i(TAG, "CTRL press right grip=${runCatching { a.right.gripPressed }.getOrNull()} trigger=${runCatching { a.right.triggerPressed }.getOrNull()}")
        val rx = runCatching { a.right.thumbstickValue.x }.getOrDefault(0f); val lx = runCatching { a.left.thumbstickValue.x }.getOrDefault(0f)
        stickX.set(java.lang.Float.floatToIntBits(if (abs(rx) >= abs(lx)) rx else lx))
    }

    fun start() {
        if (provider != null) return
        runCatching {
            val p = ControllerTrackingProvider()
            p.addControllerActionListener(listener)
            val r = p.start()
            support = p.supportState
            Log.i(TAG, "TRACK controller start=$r support=$support")
            provider = p
        }.onFailure { Log.w(TAG, "TRACK controller unavailable: $it") }
    }

    fun stop() { runCatching { provider?.removeControllerActionListener(listener); provider?.stop() }; provider = null }

    val stick: Float get() = java.lang.Float.intBitsToFloat(stickX.get())
    fun injectStick(x: Float) = stickX.set(java.lang.Float.floatToIntBits(x))

    /** (left, right) poses; null when that controller does not deliver one. */
    fun poses(): Pair<Pose?, Pose?> {
        injected?.let { return null to it }
        val p = provider ?: return null to null
        val s = runCatching { p.supportState }.getOrNull()
        if (s != support) { support = s; Log.i(TAG, "TRACK controller support=$s") }
        if (s != DataProvider.SupportState.SUPPORTED) return null to null
        val d = runCatching { p.latestData }.getOrNull() ?: return null to null
        fun conv(c: com.pico.spatial.tracking.controller.ControllerPose?): Pose? {
            c ?: return null
            val pos = V3(c.position.x, c.position.y, c.position.z)
            if (!(pos.x.isFinite() && pos.y.isFinite() && pos.z.isFinite())) return null
            if (pos == V3.ZERO && c.rotation.w == 1f) return null
            return Pose(pos, Q4(c.rotation.x, c.rotation.y, c.rotation.z, c.rotation.w).normalized())
        }
        return conv(d.left) to conv(d.right)
    }
}

/** One hand: the pinch point (between thumb and index tips), pinched with hysteresis, and the wrist rotation. */
data class HandSample(val point: V3, val pinched: Boolean, val wrist: Q4)

/**
 * Hand tracking (Full Space only): pinch = thumb tip to index tip < 1.5 cm (released > 3 cm, clay-forge Hands); the wrist
 * joint's rotation gives the roll for pouring. A pinch's rising edge is a press, like grip / trigger.
 */
class Hands {
    private var provider: HandTrackingProvider? = null
    var support: DataProvider.SupportState? = null; private set
    private val held = BooleanArray(2)
    private val wasHeld = BooleanArray(2)
    private val presses = IntArray(2)
    /** Debug `hand x y z roll 0|1` (Stage metres): the right hand. */
    @Volatile var injected: HandSample? = null

    fun start() {
        if (provider != null) return
        runCatching {
            val p = HandTrackingProvider()
            val r = p.start()
            support = p.supportState
            Log.i(TAG, "TRACK hand start=$r support=$support")
            provider = p
        }.onFailure { Log.w(TAG, "TRACK hand unavailable: $it") }
    }

    fun stop() { runCatching { provider?.stop() }; provider = null }

    fun take(hand: Int): Int { val n = presses[hand]; presses[hand] = 0; return n }

    /** Read once per frame: (left, right); counts pinch rising edges. */
    fun sample(): Pair<HandSample?, HandSample?> {
        val inj = injected
        val pair = if (inj != null) null to inj else read()
        listOf(pair.first, pair.second).forEachIndexed { k, s ->
            val now = s?.pinched == true
            if (now && !wasHeld[k]) { presses[k]++; Log.i(TAG, "HAND pinch ${if (k == 0) "left" else "right"} at (%.3f, %.3f, %.3f)".format(s!!.point.x, s.point.y, s.point.z)) }
            wasHeld[k] = now
        }
        return pair
    }

    private fun read(): Pair<HandSample?, HandSample?> {
        val p = provider ?: return null to null
        val s = runCatching { p.supportState }.getOrNull()
        if (s != support) { support = s; Log.i(TAG, "TRACK hand support=$s") }
        if (s != DataProvider.SupportState.SUPPORTED) return null to null
        val d = runCatching { p.latestData }.getOrNull() ?: return null to null
        fun j(h: com.pico.spatial.tracking.hand.HandPose?, i: HandJoint.Index): HandJoint? = runCatching { h?.joint(i) }.getOrNull()
        fun one(h: com.pico.spatial.tracking.hand.HandPose?, k: Int): HandSample? {
            val t = j(h, HandJoint.Index.THUMB_TIP)?.position ?: return null
            val i = j(h, HandJoint.Index.INDEX_TIP)?.position ?: return null
            val w = j(h, HandJoint.Index.WRIST)?.rotation ?: j(h, HandJoint.Index.PALM)?.rotation ?: return null
            val tv = V3(t.x, t.y, t.z); val iv = V3(i.x, i.y, i.z)
            if (!tv.x.isFinite() || tv.length < 1e-6f) return null
            val gap = (tv - iv).length
            held[k] = if (held[k]) gap < 0.030f else gap < 0.015f
            return HandSample((tv + iv) * 0.5f, held[k], Q4(w.x, w.y, w.z, w.w).normalized())
        }
        return one(d.left, 0) to one(d.right, 1)
    }
}
