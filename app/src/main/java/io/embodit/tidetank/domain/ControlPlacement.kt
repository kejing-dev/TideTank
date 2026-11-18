package io.embodit.tidetank.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * v0.1.3 (门 C 改, profile D53 补): the app runs in Full Space (one Mixed Stage) so the jar can follow a hand / controller.
 * Two things are placed:
 *
 * - the tank: [placeTank] from the head pose, yaw only (profile D9 placeUpright: pitch / roll never reach the tank), the
 *   tank centre [TANK_DIST] m ahead on the floor plan, the rim [RIM_BELOW_EYE] m under the eye. The jar on its pedestal
 *   (left front) is then about 0.82 m from the left shoulder: an arm plus a small lean, no walking (D53 补 direct pick,
 *   B17); ray / eye-hand pick reach it from anywhere.
 *   v0.1.4 (QA #4 q4-1): 0.70 / 0.24 put the base's front bottom 50 deg under the eye in the picture (view +-39 deg).
 *   0.88 / 0.14 keeps every corner of the tank, base, post, sign, pedestal and the jar on it inside the view, standing
 *   and seated ([boxesProjected], perspective angles, unit-tested <= 39 deg).
 * - the control window: Full Space opens a Planar window straight ahead of the head at the system distance; the app
 *   cannot move it (pico-cli-feedback「Stage 里…系统窗口不能摆在 Stage 实体旁边」). The tank sits under it: every tank
 *   corner is below the window's bottom edge + the system bar from the eye ([tankClearsWindow], unit-tested standing and
 *   seated, head level / pitched).
 */
object ControlPlacement {
    // ---- window (Main.kt / ControlWindow.kt)
    const val WINDOW_W_DP = 600f
    const val WINDOW_H_DP = 440f
    /** The native TabBar pill above the window top edge. */
    const val TABBAR_DP = 64f
    /** System controls (dots / x / handle) under a window's bottom edge (profile D8 §2). */
    const val SYSTEM_BAR_DP = 60f
    /** Full Space: the system opens a window 1.75 m straight ahead of the eye, 1 dp = 1 mm (clay-forge Layout, spatial-calc). */
    const val WINDOW_DIST = 1.75f
    const val M_PER_DP = 0.001f

    // ---- tank (Stage metres)
    const val TANK_DIST = 0.88f
    const val RIM_BELOW_EYE = 0.14f
    /** The default view (one eye of the emulator capture). */
    const val HALF_FOV_H_DEG = 37f
    const val HALF_FOV_V_DEG = 39f

    /** Where the tank root goes for a head at [eye] looking along [forward]; null while the forward has no horizontal part. */
    fun placeTank(eye: V3, forward: V3, scale: Float = Geo.SCALE): RootPose? {
        val h = sqrt(forward.x * forward.x + forward.z * forward.z)
        if (h < 0.05f || !eye.x.isFinite()) return null
        val fx = forward.x / h; val fz = forward.z / h
        val yaw = atan2(-fx, -fz)
        val y = eye.y - RIM_BELOW_EYE - Geo.TANK_TOP * scale
        return RootPose(V3(eye.x + fx * TANK_DIST, y, eye.z + fz * TANK_DIST), yaw, scale)
    }

    /** Azimuth / elevation (degrees) of [p] from [eye] in a frame whose forward is the tank-facing yaw. */
    fun azEl(eye: V3, yaw: Float, p: V3): Pair<Float, Float> {
        val d = RootPose.rotY(p - eye, -yaw)
        val ahead = -d.z
        val az = Math.toDegrees(atan2(d.x.toDouble(), ahead.toDouble())).toFloat()
        val el = Math.toDegrees(atan2(d.y.toDouble(), sqrt((d.x * d.x + ahead * ahead).toDouble()))).toFloat()
        return az to el
    }

    /** The jar standing on its pedestal (tank units), for the view checks. */
    val JAR_ON_PEDESTAL = Box(Geo.JAR_HOME - V3(Geo.JAR_HALF_W, Geo.JAR_HALF_H, Geo.JAR_HALF_W), Geo.JAR_HOME + V3(Geo.JAR_HALF_W, Geo.JAR_HALF_H, Geo.JAR_HALF_W))
    /** Everything that stands at the tank by default: tank, base, tide post + its board, jar pedestal, jar. */
    val VIEW_BOXES: List<Pair<String, Box>> = listOf("tank" to Geo.TANK, "base" to Geo.BASE, "post" to Geo.POST, "sign" to Geo.SIGN,
        "pedestal" to Geo.PEDESTAL, "jar" to JAR_ON_PEDESTAL)

    /**
     * Perspective (pinhole) angles of [p] for a level head at [eye] facing [yaw]: horizontal = atan(x / depth), vertical =
     * atan(y / depth), depth along the view axis. This is where the point lands in the picture (QA measures the same way);
     * the spherical elevation of [azEl] reads up to 5 deg less for points off to the side.
     */
    fun projected(eye: V3, yaw: Float, p: V3): Pair<Float, Float> {
        val d = RootPose.rotY(p - eye, -yaw)
        val depth = -d.z
        if (depth <= 1e-3f) return 180f to 180f
        return Math.toDegrees(atan2(d.x.toDouble(), depth.toDouble())).toFloat() to Math.toDegrees(atan2(d.y.toDouble(), depth.toDouble())).toFloat()
    }

    /** All 8 corners of every [VIEW_BOXES] box, projected from [eye]: (box name, horizontal deg, vertical deg). */
    fun boxesProjected(eye: V3, root: RootPose): List<Triple<String, Float, Float>> =
        VIEW_BOXES.flatMap { (n, b) -> b.corners().map { c -> projected(eye, root.yaw, root.toStage(c)).let { (h, v) -> Triple(n, h, v) } } }

    /** The window's bottom edge + system bar, degrees below the eye line (window straight ahead at eye height). */
    fun windowBottomDeg(): Float = -Math.toDegrees(atan2(((WINDOW_H_DP / 2 + SYSTEM_BAR_DP) * M_PER_DP).toDouble(), WINDOW_DIST.toDouble())).toFloat()
    fun windowHalfAzDeg(): Float = Math.toDegrees(atan2((WINDOW_W_DP / 2 * M_PER_DP).toDouble(), WINDOW_DIST.toDouble())).toFloat()

    /** Every corner of every tank box (tank units) as seen from [eye]: (az, el). */
    fun tankCornersAzEl(eye: V3, root: RootPose, boxes: List<Box> = listOf(Geo.TANK, Geo.BASE, Geo.PEDESTAL, Geo.POST, Geo.SIGN)): List<Pair<Float, Float>> =
        boxes.flatMap { b -> b.corners().map { azEl(eye, root.yaw, root.toStage(it)) } }

    /** Nothing of the tank in front of the window: every corner inside the window's azimuth band sits under its bottom bar. */
    fun tankClearsWindow(eye: V3, root: RootPose, marginDeg: Float = 1f): List<String> {
        val bottom = windowBottomDeg(); val half = windowHalfAzDeg()
        return tankCornersAzEl(eye, root).filter { (az, el) -> abs(az) <= half + marginDeg && el > bottom - marginDeg }
            .map { (az, el) -> "corner az %.1f el %.1f above the window bottom %.1f".format(az, el, bottom) }
    }

    /** The tank centre is in the default view (cold start: the tank shows first, M9). */
    fun tankCentreInView(eye: V3, root: RootPose): Boolean {
        val (az, el) = azEl(eye, root.yaw, root.toStage(Geo.TANK.centre))
        return abs(az) <= HALF_FOV_H_DEG && abs(el) <= HALF_FOV_V_DEG
    }
}
