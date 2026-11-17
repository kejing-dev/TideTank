package io.embodit.tidetank.domain

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Small immutable vector (domain stays SDK-free so the JVM tests can run it). */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    val length: Float get() = sqrt(x * x + y * y + z * z)
    fun norm(): V3 { val l = length; return if (l < 1e-6f) V3(0f, 0f, 0f) else this * (1f / l) }
    fun distXZ(o: V3) = hypot(x - o.x, z - o.z)
    companion object { val ZERO = V3(0f, 0f, 0f) }
}

/** Axis-aligned box (min / max corners). */
data class Box(val lo: V3, val hi: V3) {
    fun contains(p: V3, pad: Float = 0f) = p.x >= lo.x - pad && p.x <= hi.x + pad && p.y >= lo.y - pad && p.y <= hi.y + pad && p.z >= lo.z - pad && p.z <= hi.z + pad
    fun inside(o: Box, margin: Float = 0f) = lo.x >= o.lo.x + margin && lo.y >= o.lo.y + margin && lo.z >= o.lo.z + margin &&
        hi.x <= o.hi.x - margin && hi.y <= o.hi.y - margin && hi.z <= o.hi.z - margin
    fun intersects(o: Box) = lo.x < o.hi.x && hi.x > o.lo.x && lo.y < o.hi.y && hi.y > o.lo.y && lo.z < o.hi.z && hi.z > o.lo.z
    val centre: V3 get() = V3((lo.x + hi.x) / 2, (lo.y + hi.y) / 2, (lo.z + hi.z) / 2)
    fun corners(): List<V3> = listOf(lo.x, hi.x).flatMap { x -> listOf(lo.y, hi.y).flatMap { y -> listOf(lo.z, hi.z).map { z -> V3(x, y, z) } } }

    /** Segment a -> b crosses the box (slab test), ignoring the first / last [endTrim] metres of the segment. */
    fun hitBySegment(a: V3, b: V3, endTrim: Float = 0f): Boolean {
        val d = b - a
        val len = d.length
        if (len < 1e-6f) return contains(a)
        var t0 = endTrim / len; var t1 = 1f - endTrim / len
        if (t0 >= t1) return false
        val o = floatArrayOf(a.x, a.y, a.z); val dd = floatArrayOf(d.x, d.y, d.z)
        val l = floatArrayOf(lo.x, lo.y, lo.z); val h = floatArrayOf(hi.x, hi.y, hi.z)
        for (k in 0..2) {
            if (abs(dd[k]) < 1e-9f) { if (o[k] < l[k] || o[k] > h[k]) return false; continue }
            var ta = (l[k] - o[k]) / dd[k]; var tb = (h[k] - o[k]) / dd[k]
            if (ta > tb) { val t = ta; ta = tb; tb = t }
            t0 = max(t0, ta); t1 = min(t1, tb)
            if (t0 > t1) return false
        }
        return true
    }

    companion object {
        fun of(cx: Float, cy: Float, cz: Float, w: Float, h: Float, d: Float) = Box(V3(cx - w / 2, cy - h / 2, cz - d / 2), V3(cx + w / 2, cy + h / 2, cz + d / 2))
    }
}

/**
 * Every fixed measure of the volume (design container.volume.math + spatialLayout), metres. Origin = volume centre,
 * +X right, +Y up, +Z toward the user. One place: the scene, the simulation and the layout tests all read these.
 */
object Geo {
    /**
     * QA #1 q1-2 (D54): the tank filled 67 % of the old 0.90 m volume and the system parks volumes ~1.75 m away (19.5 deg).
     * The scene root is drawn [SCALE] x bigger and the volume is [VOL_W] x [SCALE] wide (manifest 1.19 x 0.78 x 0.64 m):
     * the tank is 0.84 m (70.6 % of the volume), 27 deg at 1.75 m. Every measure below stays in tank units (the design's
     * 60 cm tank); physical metres = tank units x [SCALE]. The jar pedestal and the tide post moved 0.5 cm inward so the
     * volume could shrink to 0.85 units around them.
     */
    // v0.1.3 (Full Space, 门 C 改): the tank sits 0.62 m from the eye on the user's own floor plan, so it is drawn at the
    // design's real size again (60 cm); 1.4 x only made sense inside a Shared Space volume parked 1.75 m away.
    const val SCALE = 1.0f
    // volume 0.85 x 0.56 x 0.46 tank units (physical 1.19 x 0.784 x 0.644 m)
    const val VOL_W = 0.85f; const val VOL_H = 0.56f; const val VOL_D = 0.46f
    val VOLUME = Box(V3(-VOL_W / 2, -VOL_H / 2, -VOL_D / 2), V3(VOL_W / 2, VOL_H / 2, VOL_D / 2))
    /** Physical metres <-> tank units. */
    fun phys(units: Float) = units * SCALE
    fun units(metres: Float) = metres / SCALE

    // tank (outer 0.60 x 0.36 x 0.30, 6 mm glass, open top)
    const val TANK_X = 0.30f; const val TANK_BOT = -0.24f; const val TANK_TOP = 0.12f; const val TANK_Z = 0.15f
    const val GLASS = 0.006f
    const val IN_X = 0.294f; const val IN_BOT = -0.234f; const val IN_Z = 0.144f
    val TANK = Box(V3(-TANK_X, TANK_BOT, -TANK_Z), V3(TANK_X, TANK_TOP, TANK_Z))
    val BASE = Box(V3(-0.32f, -0.28f, -0.17f), V3(0.32f, -0.24f, 0.17f))
    /** Brass plaque on the base front (3D words, design plaque). */
    val PLAQUE = Box(V3(-0.26f, -0.2695f, 0.170f), V3(0.26f, -0.2405f, 0.18f))
    const val PLAQUE_W = 0.52f; const val PLAQUE_H = 0.030f

    // sand / rock
    const val SAND_Y = -0.21f
    const val ROCK_TOE_X = -0.06f
    /** Rock slope surface (design: y = 0.08 - 1.24 (x + 0.294)), only left of the toe. */
    fun rockY(x: Float): Float = 0.08f - 1.24f * (x + IN_X)
    /** Bottom under (x, z): the rock slope left of the toe, the sand elsewhere. */
    fun floorY(x: Float): Float = if (x < ROCK_TOE_X) max(rockY(x), SAND_Y) else SAND_Y
    val CREVICE = V3(-0.12f, -0.15f, 0.04f)
    val ANEMONE = V3(0.00f, SAND_Y, -0.07f)
    const val ANEMONE_H = 0.07f
    val GRASS = V3(0.19f, SAND_Y, -0.02f)
    const val GRASS_R = 0.07f
    val GRASS_TUFTS = listOf(V3(0.19f, SAND_Y, -0.06f), V3(0.15f, SAND_Y, 0.02f), V3(0.235f, SAND_Y, 0.01f))
    val SEAHORSE_HANG = V3(0.18f, -0.15f, 0.0f)

    // sea gate (back wall, upper right) + the deep-blue board behind it
    const val GATE_X0 = 0.10f; const val GATE_X1 = 0.26f; const val GATE_Y0 = -0.04f; const val GATE_Y1 = 0.08f
    val GATE_IN = V3(0.18f, 0.02f, -0.12f)

    // food jar on its pedestal (left front); carry plane
    val PEDESTAL = Box(V3(-0.415f, -0.28f, 0.07f), V3(-0.335f, -0.22f, 0.15f))
    val JAR_HOME = V3(-0.375f, -0.185f, 0.11f)
    const val JAR_BOTTOM_OFF = -0.035f   // jar model origin (bottom centre) below the jar centre
    const val CARRY_Y = 0.17f
    const val CARRY_X0 = -0.375f; const val CARRY_X1 = 0.27f; const val CARRY_Z = 0.12f
    const val POUR_X = 0.25f; const val POUR_Z = 0.12f
    /** Jar collider 6.5 x 9 x 6.5 cm (design R1-4), centred on the jar centre. */
    const val JAR_HIT_W = 0.065f; const val JAR_HIT_H = 0.09f
    /** Upright jar half extents (5 x 7 cm) and tipped 110 deg (design math: half width 0.042, half height 0.036). */
    const val JAR_HALF_W = 0.025f; const val JAR_HALF_H = 0.035f
    const val JAR_TIPPED_HALF_W = 0.042f; const val JAR_TIPPED_HALF_H = 0.036f
    const val JAR_TILT_DEG = 110f

    // tide post (right front)
    const val POST_X = 0.375f; const val POST_Z = 0.11f; const val POST_BOT = -0.24f; const val POST_TOP = 0.14f
    val POST = Box(V3(0.335f, -0.24f, 0.08f), V3(0.415f, 0.14f, 0.14f))
    const val HANDLE_Z = 0.165f
    /** Handle collider 7 x 6 x 4 cm (design R1-4). */
    const val HANDLE_HIT_W = 0.07f; const val HANDLE_HIT_H = 0.06f; const val HANDLE_HIT_D = 0.04f
    const val HANDLE_RING_R = 0.025f
    /** The small 「面板」 board on top of the post (tap = reopen the control window). */
    val SIGN = Box(V3(0.335f, 0.14f, 0.10f), V3(0.415f, 0.185f, 0.12f))
    val SIGN_C = V3(0.375f, 0.1625f, 0.11f)
    /** Gauge plates 落 / 平 / 涨 (q1-1: tap = jump to that tide): quad 3.4 x 2.2 cm, collider 4.6 x 4.6 x 1 cm (6.4 cm, 2.1 deg at 1.75 m physical), left of the post. */
    const val GAUGE_DX = -0.046f; const val GAUGE_DZ = 0.03f
    const val GAUGE_W = 0.034f; const val GAUGE_H = 0.022f
    const val GAUGE_HIT_W = 0.046f; const val GAUGE_HIT_H = 0.046f; const val GAUGE_HIT_D = 0.010f
    fun gaugeAt(t: Tide) = V3(POST_X + GAUGE_DX, t.handleY, POST_Z + GAUGE_DZ)
    /** Float on the right of the post. */
    const val FLOAT_DX = 0.032f

    // name tags
    const val TAG_H = 0.036f; const val TAG_BOTTOM = 0.15f; const val TAG_Z = 0.08f; const val TAG_MAX_W = 0.18f
    const val TAG_TEXT_H = 0.020f
    /** Glyph width ≈ 0.55 x text height for Latin, 1.0 x for CJK (design: 13 chars x 1.1 cm). */
    fun tagWidth(text: String): Float {
        var w = 0f
        for (c in text) w += if (c.code > 0x2E80) TAG_TEXT_H else TAG_TEXT_H * 0.55f
        return min(TAG_MAX_W, w + 0.02f)
    }

    /** Fish swim box = the water body inset 2 cm (design boids.school), its top following the water. */
    const val INSET = 0.02f
    fun swimTop(level: Float) = level - INSET
    fun swimBottom(x: Float) = floorY(x) + INSET
    /** Leftmost x a fish can be at with the water at [level] (the rock slope rises out of the water on the left). */
    fun minSwimX(level: Float): Float = max(-IN_X + INSET, (0.08f - (level - INSET - 0.012f)) / 1.24f - IN_X)

    // design eye positions (relative to the volume centre) + the low one
    val EYES = listOf(
        V3(0f, 0.08f, 0.85f), V3(0f, 0.53f, 0.85f),
        V3(-0.43f, 0.08f, 0.74f), V3(0.43f, 0.08f, 0.74f),
        V3(-0.43f, 0.53f, 0.74f), V3(0.43f, 0.53f, 0.74f),
        V3(0f, -0.10f, 0.85f),
    )

    fun clampCarry(x: Float, z: Float): Pair<Float, Float> = x.coerceIn(CARRY_X0, CARRY_X1) to z.coerceIn(-CARRY_Z, CARRY_Z)
    fun overWater(x: Float, z: Float) = abs(x) <= POUR_X && abs(z) <= POUR_Z

    /** Direct hold (D53 补): the jar follows the hand anywhere near the tank, but never into the water or through the glass. */
    const val DIRECT_X = 0.50f; const val DIRECT_Z0 = -0.25f; const val DIRECT_Z1 = 0.40f; const val DIRECT_Y1 = 0.50f
    fun clampDirect(p: V3): V3 {
        val x = p.x.coerceIn(-DIRECT_X, DIRECT_X); val z = p.z.coerceIn(DIRECT_Z0, DIRECT_Z1)
        var y = p.y.coerceIn(JAR_HOME.y, DIRECT_Y1)
        if (abs(x) < TANK_X + JAR_HALF_W && abs(z) < TANK_Z + JAR_HALF_W) y = max(y, TANK_TOP + JAR_HALF_H + 0.01f)
        return V3(x, y, z)
    }
}

enum class Tide(val level: Float, val handleY: Float) {
    L(-0.09f, -0.14f), M(0.02f, -0.03f), H(0.10f, 0.06f);

    companion object {
        /** Hysteresis on release (design T4: 1.5 cm): leave [current] only when past the midpoint by 1.5 cm. */
        const val HYST = 0.015f

        fun snap(y: Float, current: Tide): Tide {
            val cy = current.handleY
            // stay unless the handle is beyond the half-way point to a neighbour + HYST
            val up = entries.getOrNull(current.ordinal + 1)
            val down = entries.getOrNull(current.ordinal - 1)
            var t = current
            if (up != null && y > (cy + up.handleY) / 2 + HYST) {
                t = up
                val up2 = entries.getOrNull(up.ordinal + 1)
                if (up2 != null && y > (up.handleY + up2.handleY) / 2 + HYST) t = up2
            } else if (down != null && y < (cy + down.handleY) / 2 - HYST) {
                t = down
                val d2 = entries.getOrNull(down.ordinal - 1)
                if (d2 != null && y < (down.handleY + d2.handleY) / 2 - HYST) t = d2
            }
            return t
        }

        fun parse(s: String?): Tide? = entries.firstOrNull { it.name.equals(s?.trim(), true) }
    }
}

enum class Food(val key: String, val perPinch: Int) {
    FLAKES("flakes", 3), PELLETS("pellets", 2), SHRIMP("shrimp", 3);

    fun next(): Food = entries[(ordinal + 1) % entries.size]

    companion object { fun parse(s: String?): Food? = entries.firstOrNull { it.key.equals(s?.trim(), true) || it.name.equals(s?.trim(), true) } }
}

/** The six creatures, in hint order (design findingRules.hints). */
enum class Kind(val key: String, val count: Int) {
    CHROMIS("chromis", 10), CLOWN("clownfish", 2), CRAB("hermitCrab", 1), SEAHORSE("seahorse", 1), TANG("blueTang", 1), JELLY("moonJelly", 1);

    companion object { fun parse(s: String?): Kind? = entries.firstOrNull { it.key.equals(s?.trim(), true) || it.name.equals(s?.trim(), true) } }
}

/** Where a pinch landed, for 「怎么遇到的」 (design guide card). */
enum class Spot { ANEMONE, ROCKS, GRASS, OPEN }

fun spotOf(x: Float, z: Float): Spot {
    val p = V3(x, 0f, z)
    return when {
        p.distXZ(Geo.ANEMONE) <= 0.10f -> Spot.ANEMONE
        x < Geo.ROCK_TOE_X || p.distXZ(Geo.CREVICE) <= 0.10f -> Spot.ROCKS
        p.distXZ(Geo.GRASS) <= 0.10f -> Spot.GRASS
        else -> Spot.OPEN
    }
}
