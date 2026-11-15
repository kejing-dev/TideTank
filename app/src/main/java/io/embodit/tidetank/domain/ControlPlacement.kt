package io.embodit.tidetank.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * q2-1 (QA #2): where the control window opens. One placement for every open path (start, resume, settings
 * [重开控制窗], the 「面板」 board, the volume coming back): Main.kt reads [side] / [offsetDp].
 *
 * v0.1.1 used Placement.right(volume). With the volume at 1.19 m the system solved the window to (0.848, 0, -1.248) m,
 * yawed 32.8 deg: its outer edge sat ~43 deg right of the eye line, past the ~37 deg edge of the default view. Shared
 * Space only lets an app place a window left / right / top / bottom of another window (SDK 6.1.9 Placement; the offset
 * is unsigned, a negative one overflows). Left / right cannot fit a 1.19 m volume plus any >= 330 dp window; top puts the
 * window above the eyes. So v0.1.2 opens it BELOW the volume, centred under the tank, and the window is 600 x 440 dp
 * (was 600 x 600; pages re-spaced, blank still <= 25 %) so its bottom edge stays inside the default view.
 *
 * The numbers below are the system's own solve on the emulator (logcat WorldSpaceNative ContainerTransformSolver, eye
 * at the origin) and the capture's measured field of view; [ControlPlacementTest] checks the four corners for a standing
 * (1.6 m) and a seated (1.2 m) eye. Shared Space places the volume relative to the head, so the eye-space angles are the
 * same for both; the eye height only changes the floor clearance.
 */
object ControlPlacement {
    enum class Side { RIGHT, BOTTOM, LEFT }

    val SIDE = Side.BOTTOM
    /** Gap between the volume and the window, dp (the system already leaves room for the TabBar pill). */
    const val OFFSET_DP = 0f
    /** What Main.kt passes (the debug build can try another side / offset with `placeside` / `placeoff`). */
    @Volatile var side = SIDE
    @Volatile var offsetDp = OFFSET_DP

    // ---- window (Main.kt / ControlWindow.kt)
    const val WINDOW_W_DP = 600f
    const val WINDOW_H_DP = 440f
    /** The native TabBar pill above the window top edge. */
    const val TABBAR_DP = 64f
    /** System controls (dots / x / handle) under a window's bottom edge (profile D8 §2). */
    const val SYSTEM_BAR_DP = 60f
    /** Physical metres per dp of the control window as placed (default-view capture: 440 dp = 0.351 m at 1.45 m). */
    const val M_PER_DP = 0.0008f

    // ---- the volume as the system places it (manifest 1.19 x 0.784 x 0.644 m; logcat anchorContainerPosition)
    val VOLUME_CENTRE = V3(0f, -0.061f, -1.749f)
    const val VOLUME_W = 1.19f
    const val VOLUME_H = 0.784f
    const val VOLUME_D = 0.644f

    /** Solved window centre (eye space, metres) and its pitch / yaw toward the eye, per side (logcat containerPosition). */
    data class Solve(val centre: V3, val pitchDeg: Float, val yawDeg: Float)
    fun solve(s: Side): Solve = when (s) {
        Side.BOTTOM -> Solve(V3(0f, -0.731f, -1.251f), -29.0f, 0f)       // v0.1.2, 600 x 440, offset 0
        Side.RIGHT -> Solve(V3(0.848f, 0f, -1.248f), 0f, -32.8f)         // v0.1.1, 600 x 600
        Side.LEFT -> Solve(V3(-0.848f, 0f, -1.248f), 0f, 32.8f)
    }

    // ---- default view (one eye of the emulator capture: pinhole f fitted to the solved window and volume)
    const val HALF_FOV_H_DEG = 37f
    const val HALF_FOV_V_DEG = 39f
    /** Every corner stays this far inside the edge (capture-to-capture head drift, headset frustum). */
    const val MARGIN_DEG = 1.5f
    /** profile D14: nothing on the panel more than 40 deg below the eye line. */
    const val MAX_DOWN_DEG = 40f

    /** Window corners (+ TabBar above / system bar below when [withChrome]) in eye space, metres (x right, y up, -z ahead). */
    fun corners(s: Side = side, wDp: Float = WINDOW_W_DP, hDp: Float = if (s == Side.BOTTOM) WINDOW_H_DP else 600f, withChrome: Boolean = false): List<V3> {
        val sv = solve(s)
        val hw = wDp * M_PER_DP / 2
        val top = hDp * M_PER_DP / 2 + if (withChrome) TABBAR_DP * M_PER_DP else 0f
        val bot = -hDp * M_PER_DP / 2 - if (withChrome) SYSTEM_BAR_DP * M_PER_DP else 0f
        val p = Math.toRadians(sv.pitchDeg.toDouble()); val y = Math.toRadians(sv.yawDeg.toDouble())
        return listOf(-hw to top, hw to top, -hw to bot, hw to bot).map { (lx, ly) ->
            // local (lx, ly, 0) -> pitch about x -> yaw about y -> + centre
            val py = ly * cos(p); val pz = ly * sin(p)
            val wx = lx * cos(y) + pz * sin(y); val wz = -lx * sin(y) + pz * cos(y)
            V3(sv.centre.x + wx.toFloat(), sv.centre.y + py.toFloat(), sv.centre.z + wz.toFloat())
        }
    }

    /** (yaw, pitch) of an eye-space point, degrees; yaw + = right, pitch + = up. */
    fun angles(p: V3): Pair<Float, Float> {
        val ahead = -p.z
        val yaw = Math.toDegrees(atan2(p.x.toDouble(), ahead.toDouble())).toFloat()
        val pitch = Math.toDegrees(atan2(p.y.toDouble(), sqrt((p.x * p.x + ahead * ahead).toDouble()))).toFloat()
        return yaw to pitch
    }

    fun inDefaultView(p: V3): Boolean {
        val (yaw, pitch) = angles(p)
        return -p.z > 0f && kotlin.math.abs(yaw) <= HALF_FOV_H_DEG - MARGIN_DEG && kotlin.math.abs(pitch) <= HALF_FOV_V_DEG - MARGIN_DEG
    }

    /** Lowest point of the volume's content (front-bottom edge of the walnut base), eye space. */
    fun tankBaseFrontBottom(): V3 = V3(0f, VOLUME_CENTRE.y - VOLUME_H / 2, VOLUME_CENTRE.z + VOLUME_D / 2)
}
