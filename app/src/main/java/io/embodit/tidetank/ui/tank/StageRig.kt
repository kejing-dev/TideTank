package io.embodit.tidetank.ui.tank

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.embodit.tidetank.domain.ControlPlacement
import io.embodit.tidetank.domain.Holder
import io.embodit.tidetank.domain.InputSample
import io.embodit.tidetank.domain.JarHold
import io.embodit.tidetank.domain.JarInput
import io.embodit.tidetank.domain.Q4
import io.embodit.tidetank.domain.RecallSampler
import io.embodit.tidetank.domain.RootPose
import io.embodit.tidetank.domain.V3
import io.embodit.tidetank.platform.Controllers
import io.embodit.tidetank.platform.Graph
import io.embodit.tidetank.platform.Hands
import io.embodit.tidetank.platform.HeadTracker
import io.embodit.tidetank.platform.Pose
import io.embodit.tidetank.platform.TAG
import io.embodit.tidetank.ui.tank.input.BoardInputLog
import kotlin.math.abs
import kotlin.math.atan2

/**
 * v0.1.3 Full Space (门 C 改, D53 补): everything the Stage reads per frame, on the ECS frame (TankFrameSystem).
 *  - the head: places the tank yaw-only under the window (ControlPlacement.placeTank; first valid pose, re-aimed for
 *    2 s then locked, 手语老师 v0.1.2), [叫回面前] (RecallSampler, D29), the level gate of the control window (D9), the
 *    tip turning to the eye. Never to aim or pick (D1).
 *  - controllers + hands: grip / trigger / pinch presses and poses -> [JarInput] (the jar toggle); stick x turns the
 *    tank about its own vertical axis while the jar is home (profile D default interaction).
 */
internal class StageRig(private val graph: Graph, val scene: TankScene) {
    val head = HeadTracker()
    val controllers = Controllers()
    val hands = Hands()
    val jar = JarInput(graph.tank) { BoardInputLog.write(TAG, it) }
    /** A controller holds the jar: the Stage hides the controller model / ray (D41 更正, tool mode shows no ray). */
    var holdingByController by mutableStateOf(false); private set

    private val startMs = SystemClock.elapsedRealtime()
    private var firstValidMs = -1L
    private var locked = false
    private val recall = RecallSampler()
    private var recalling: String? = null
    private var lastSampleLog = 0L

    init {
        graph.window.onRecall = { why -> startRecall(why) }
    }

    fun start() { head.start(); controllers.start(); hands.start() }
    fun stop() { head.stop(); controllers.stop(); hands.stop() }

    fun frame(dt: Float) {
        val now = SystemClock.elapsedRealtime()
        val hp = head.pose()
        graph.window.stepFrame(hp?.pitchDeg)
        scene.levelHint = graph.window.levelHint && scene.placed
        placementFrame(now, hp)
        val root = scene.pose ?: return
        hp?.let { scene.eyeLocal = root.toTank(it.position) }
        jar.root = root

        val (cl, cr) = controllers.poses()
        val (hl, hr) = hands.sample()
        val samples = ArrayList<InputSample>(4)
        for ((k, p) in listOf(0 to cl, 1 to cr)) {
            val presses = controllers.edges.take(k)
            if (p != null || presses > 0) samples += InputSample(if (k == 0) Holder.CTRL_L else Holder.CTRL_R, p?.let { it.position + it.forward * JarHold.TIP_M }, p?.forward, p?.rotation ?: Q4.ID, presses)
        }
        for ((k, h) in listOf(0 to hl, 1 to hr)) {
            val presses = hands.take(k)
            if (h != null) samples += InputSample(if (k == 0) Holder.HAND_L else Holder.HAND_R, h.point, null, h.wrist, presses)
        }
        jar.frame(now, samples)
        val byCtrl = graph.tank.holder?.isController == true
        if (byCtrl != holdingByController) holdingByController = byCtrl
        if (graph.tank.holder != null && now - lastSampleLog >= 500) {
            lastSampleLog = now
            BoardInputLog.write(TAG, "JAR hold by=${graph.tank.holder} mode=${graph.tank.holdMode} jar=(%.3f, %.3f, %.3f) roll=%.0f tilt=%.0f poured=%d".format(graph.tank.jar.x, graph.tank.jar.y, graph.tank.jar.z, jar.roll, graph.tank.jarTilt, graph.tank.pouredThisHold))
        }

        // profile D default: stick x turns the tank about its own vertical axis (60 deg/s) while the jar is home
        val sx = controllers.stick
        if (graph.tank.holder == null && abs(sx) > 0.5f) scene.place(root.copy(yaw = root.yaw - sx * STICK_DEG_S * dt * DEG))
    }

    private fun placementFrame(now: Long, hp: Pose?) {
        if (hp == null) {
            // no head pose for 5 s: put the tank in front of the Stage origin at a seated eye (never stuck, D1)
            if (!scene.placed && now - startMs > NO_HEAD_MS) {
                ControlPlacement.placeTank(V3(0f, 1.2f, 0f), V3(0f, 0f, -1f))?.let { scene.place(it); Log.i(TAG, "PLACE nohead fallback") }
                locked = true
            }
            return
        }
        if (!locked) {
            if (firstValidMs < 0) firstValidMs = now
            ControlPlacement.placeTank(hp.position, hp.forward)?.let { scene.place(it) }
            if (now - firstValidMs >= SETTLE_MS) { locked = true; Log.i(TAG, "PLACE locked after ${now - firstValidMs} ms of valid head pose") }
        }
        recalling?.let { why ->
            val f = hp.forward
            if (recall.step(now, Math.toDegrees(atan2(-f.x, -f.z).toDouble()).toFloat(), hp.pitchDeg)) {
                recalling = null
                ControlPlacement.placeTank(hp.position, hp.forward)?.let { scene.place(it) }
                Log.i(TAG, "RECALL placed why=$why")
                graph.window.open("recall")
            }
        }
    }

    /** Settings [叫回面前] / the 「面板」 board: put the window away, wait for a still head, then tank + window in front. */
    private fun startRecall(why: String) {
        Log.i(TAG, "RECALL start why=$why panel=${graph.window.panelOpen}")
        if (graph.window.panelOpen) graph.window.closeControlOnly()
        recalling = why
        recall.start(SystemClock.elapsedRealtime())
    }

    // ------------------------------------------------------------------ debug (the same per-frame path as real input)
    /** `ctrl x y z roll press`: the right controller tip at tank point (x, y, z), pointing into the tank, rolled [roll]. */
    fun injectCtrl(tip: V3, rollDeg: Float, press: Boolean?) {
        val root = scene.pose ?: return
        val base = yawPitch(root.yaw, 0f)
        val f = base.forward
        val q = (Q4.axisAngle(f, rollDeg) * base).normalized()
        controllers.injected = Pose(root.toStage(tip) - f * JarHold.TIP_M, q)
        press?.let { controllers.edges.set(1, it, injected = true) }
    }

    /** `ctrlaim x y z press`: the right controller held at the user's right hip, its ray on tank point (x, y, z). */
    fun injectAim(target: V3, press: Boolean?) {
        val root = scene.pose ?: return
        val from = root.toStage(V3(0.18f, 0.02f, 0.55f))
        val d = (root.toStage(target) - from).norm()
        controllers.injected = Pose(from, yawPitch(atan2(-d.x, -d.z), Math.asin(d.y.toDouble().coerceIn(-1.0, 1.0)).toFloat()))
        press?.let { controllers.edges.set(1, it, injected = true) }
    }

    /** `ctrlmove dx dy dz` (Stage metres) / `ctrlroll deg`: move / roll the injected controller. */
    fun moveCtrl(d: V3) { controllers.injected?.let { controllers.injected = it.copy(position = it.position + d) } }
    fun rollCtrl(deg: Float) { controllers.injected?.let { p -> val f = p.forward; controllers.injected = p.copy(rotation = (Q4.axisAngle(f, deg) * p.rotation).normalized()) } }
    fun pressCtrl(down: Boolean) = controllers.edges.set(1, down, injected = true)
    fun ctrlOff() { controllers.injected = null; controllers.edges.clearInjected(1) }

    /** `hand x y z roll pinch`: the right hand's pinch point at tank point (x, y, z), wrist rolled [roll]. */
    fun injectHand(p: V3, rollDeg: Float, pinch: Boolean) {
        val root = scene.pose ?: return
        val base = yawPitch(root.yaw, 0f)
        hands.injected = io.embodit.tidetank.platform.HandSample(root.toStage(p), pinch, (Q4.axisAngle(base.forward, rollDeg) * base).normalized())
    }
    fun handOff() { hands.injected = null }
    fun injectHead(eye: V3, yawDeg: Float, pitchDeg: Float) { head.injected = Pose(eye, yawPitch(yawDeg * DEG, pitchDeg * DEG)) }
    fun headOff() { head.injected = null }

    private fun yawPitch(yaw: Float, pitch: Float): Q4 = (Q4.axisAngle(V3(0f, 1f, 0f), Math.toDegrees(yaw.toDouble()).toFloat()) * Q4.axisAngle(V3(1f, 0f, 0f), Math.toDegrees(pitch.toDouble()).toFloat())).normalized()

    companion object {
        const val SETTLE_MS = 2000L
        const val NO_HEAD_MS = 5000L
        const val STICK_DEG_S = 60f
        const val DEG = (Math.PI / 180).toFloat()
        @Suppress("unused") private val unused: RootPose? = null
    }
}
