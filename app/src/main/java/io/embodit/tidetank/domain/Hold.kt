package io.embodit.tidetank.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A unit quaternion (x, y, z, w), SDK-free so the hold rules run on the JVM. */
data class Q4(val x: Float, val y: Float, val z: Float, val w: Float) {
    fun rotate(v: V3): V3 {
        val ux = y * v.z - z * v.y; val uy = z * v.x - x * v.z; val uz = x * v.y - y * v.x
        val tx = 2 * ux; val ty = 2 * uy; val tz = 2 * uz
        return V3(v.x + w * tx + (y * tz - z * ty), v.y + w * ty + (z * tx - x * tz), v.z + w * tz + (x * ty - y * tx))
    }
    operator fun times(o: Q4) = Q4(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )
    fun conj() = Q4(-x, -y, -z, w)
    fun normalized(): Q4 { val l = sqrt(x * x + y * y + z * z + w * w); return if (l < 1e-9f) ID else Q4(x / l, y / l, z / l, w / l) }
    /** -Z of the pose (controllers and the head look along -Z). */
    val forward: V3 get() = rotate(V3(0f, 0f, -1f))

    companion object {
        val ID = Q4(0f, 0f, 0f, 1f)
        fun axisAngle(axis: V3, deg: Float): Q4 {
            val a = axis.norm(); val h = Math.toRadians(deg.toDouble()).toFloat() / 2
            val s = sin(h)
            return Q4(a.x * s, a.y * s, a.z * s, cos(h))
        }
    }
}

/** Where the tank root sits in the Stage: position (metres) and yaw (radians, about +Y). */
data class RootPose(val pos: V3, val yaw: Float, val scale: Float = Geo.SCALE) {
    /** Stage metres -> tank units. */
    fun toTank(p: V3): V3 = rotY(p - pos, -yaw) * (1f / scale)
    /** Tank units -> Stage metres. */
    fun toStage(u: V3): V3 = pos + rotY(u * scale, yaw)
    /** A Stage direction / displacement -> tank units (no translation). */
    fun dirToTank(d: V3): V3 = rotY(d, -yaw) * (1f / scale)

    companion object {
        fun rotY(v: V3, a: Float): V3 { val c = cos(a); val s = sin(a); return V3(v.x * c + v.z * s, v.y, -v.x * s + v.z * c) }
    }
}

/** Who holds the jar: one controller, one hand, or the system pointer (eye-hand / hand ray without a tracked anchor). */
enum class Holder { CTRL_L, CTRL_R, HAND_L, HAND_R, POINTER;
    val isController get() = this == CTRL_L || this == CTRL_R
}

/** How it was picked: touched (follows the hand in 6DoF) or from afar (the hand's translation moves it over the water, X / Z). */
enum class HoldMode { DIRECT, RAY }

/**
 * profile D53 补「手持工具按一下拿起、再按一下放回」(2026-11-16, 门 C 改): the food jar is a toggle, not a drag.
 * Pure rules, unit-tested (HoldTest): what a press does, the twist (roll) of the wrist / controller since the pick, the
 * pour rate from that roll, and the ray-mode mapping (1:1 hand translation -> jar X / Z on the carry plane).
 */
object JarHold {
    /** Physical metres: a controller tip / pinch point this close to the jar centre counts as touching it. */
    const val REACH_M = 0.10f
    /** Physical metres: radius of the jar's pick sphere for an own controller ray. */
    const val RAY_R_M = 0.07f
    /** Controller tip ahead of the tracked grip point. */
    const val TIP_M = 0.04f
    /** Roll past this starts the pour; at [POUR_FULL_DEG] the pour is fastest. */
    const val POUR_START_DEG = 60f
    const val POUR_FULL_DEG = 110f
    /** A system tap on the jar this soon after a press the trackers already used is the same press (Full Space sends both). */
    const val SWALLOW_MS = 1500L
    /** Ray mode: hand metres -> jar metres. */
    const val RAY_GAIN = 1f

    enum class Act { NONE, PICK_DIRECT, PICK_RAY, PUT_BACK }

    /**
     * One rising edge of grip / trigger (controller) or pinch (hand). [holder] = who holds the jar now (null = on its
     * pedestal). A press by the holder (or any press while the system pointer holds it) puts it back; nobody else can
     * take it out of a hand.
     */
    fun decide(source: Holder, holder: Holder?, nearJar: Boolean, rayOnJar: Boolean): Act = when {
        holder != null -> if (holder == source || holder == Holder.POINTER) Act.PUT_BACK else Act.NONE
        nearJar -> Act.PICK_DIRECT
        rayOnJar -> Act.PICK_RAY
        else -> Act.NONE
    }

    /** Signed twist of [q] about [axis], degrees (-180, 180]. */
    fun twistDeg(q: Q4, axis: V3): Float {
        val a = axis.norm()
        val d = q.x * a.x + q.y * a.y + q.z * a.z
        var ang = Math.toDegrees(2.0 * atan2(d.toDouble(), q.w.toDouble())).toFloat()
        while (ang > 180f) ang -= 360f
        while (ang <= -180f) ang += 360f
        return ang
    }

    /** Roll since the pick: the twist of (now x pick^-1) about the pose's forward at the pick. */
    fun rollSinceDeg(now: Q4, atPick: Q4): Float = twistDeg((now * atPick.conj()).normalized(), atPick.forward)

    /** Pour speed 0..1 from the roll: 0 under [POUR_START_DEG], then 0.35 rising to 1 at [POUR_FULL_DEG]. */
    fun pourRate(rollDeg: Float): Float {
        val r = abs(rollDeg)
        if (r < POUR_START_DEG) return 0f
        return 0.35f + 0.65f * ((r - POUR_START_DEG) / (POUR_FULL_DEG - POUR_START_DEG)).coerceIn(0f, 1f)
    }

    /** Ray mode: the jar (tank units, X / Z) = where it was at the pick + the anchor's Stage translation since, 1:1 physical. */
    fun rayFollow(jarAtPick: V3, anchorAtPick: V3, anchorNow: V3, root: RootPose): Pair<Float, Float> {
        val d = root.dirToTank((anchorNow - anchorAtPick) * RAY_GAIN)
        return (jarAtPick.x + d.x) to (jarAtPick.z + d.z)
    }

    /** Direct mode: the jar (tank units) = the anchor's Stage point + the offset taken at the pick. */
    fun directFollow(anchorNow: V3, offsetStage: V3, root: RootPose): V3 = root.toTank(anchorNow + offsetStage)

    /** Ray o + t d (unit d) passes within [r] of [c] in front of o. */
    fun rayHits(o: V3, d: V3, c: V3, r: Float): Boolean {
        val oc = c - o
        val t = oc.x * d.x + oc.y * d.y + oc.z * d.z
        if (t < 0f) return false
        val p = o + d * t
        return (c - p).length <= r
    }
}
