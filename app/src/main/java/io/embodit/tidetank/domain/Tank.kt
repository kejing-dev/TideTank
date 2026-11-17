package io.embodit.tidetank.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** A found creature (design dataModel.tank.discovered). */
data class Discovery(val kind: Kind, val at: Long, val tide: Tide, val food: Food, val spot: Spot)

/** What the app saves (files/tank.json): positions are never saved, fish are born again in the water. */
data class Saved(val tide: Tide = Tide.M, val food: Food = Food.FLAKES, val discovered: List<Discovery> = emptyList())

/** Sounds the simulation asks for (played by the platform). */
enum class Sfx { PICK, SHAKE_FLAKES, SHAKE_PELLETS, SHAKE_SHRIMP, PLOP, BITE, TIDE, CLICK, GATE, DISCOVER, ALLFOUND }

enum class TankState(val id: String) { IDLE("S02"), CARRYING("S03"), FEEDING("S04"), TIDE_MOVING("S05") }

enum class JarPhase { HOME, LIFTING, CARRIED, RETURNING }

enum class TidePhase { IDLE, VISITORS_OUT, GRILLE_CLOSE, MOVING, GRILLE_OPEN }

/** One food particle of the pool (design findingRules.pool: 120). */
class Particle(val id: Int) {
    var alive = false
    var food = Food.FLAKES
    var pos = V3.ZERO
    var vel = V3.ZERO
    /** AIR (falling from the jar), ROLL (on dry rock toward the water), WATER (in the water), LEFT (on the bottom, fading). */
    var stage = Stage.AIR
    var age = 0f
    var leftAge = 0f
    var forJelly = false
    var pinch = 0L
    var restY = 0f
    var bornAt = 0L
    enum class Stage { AIR, ROLL, WATER, LEFT }
}

/** One creature instance (chromis x10 share a mesh, the rest are single). */
class Fish(val kind: Kind, val idx: Int) {
    var pos = V3.ZERO
    var prev = V3.ZERO
    var vel = V3.ZERO
    var yaw = 0f
    /** 0 = hidden, 1 = shown (fades). */
    var alpha = 0f
    var shown = false
    val bites = ArrayList<Float>()
    var fullUntil = -1f
    /** Crab outing: target and phase (0 home hidden, 1 going, 2 eating, 3 back). */
    var phase = 0
    var target: V3? = null
    var timer = 0f
    var lean = 0f
    var swimT = 0f
    fun full(now: Float) = now < fullUntil
}

/** One entry of the discovery queue (design findingRules.queue: 3 s each, one at a time). */
data class Pending(val kind: Kind)

/** A tag above a creature (design spatialLayout.tags): shown 3 s, then fades. */
data class Tag(val kind: Kind, var t: Float)

/**
 * The tank: the single state source (design stateModel). Ticked from the volume's ECS system on the render thread and
 * called from the window / 3D input / debug broadcast on the main thread: every public call takes [lock]. Time is the
 * simulation's own clock ([now], seconds) so the JVM tests drive it with [tick].
 */
class Tank(
    private val seed: Int = 7,
    private val onSfx: (Sfx, V3) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = {},
    private val wallMs: () -> Long = { System.currentTimeMillis() },
) {
    val lock = Any()
    private val rnd = Random(seed)
    var now = 0f; private set

    // ---------------------------------------------------------------- state
    var tide = Tide.M; private set
    var level = Tide.M.level; private set
    var tidePhase = TidePhase.IDLE; private set
    private var tideFrom = Tide.M.level
    private var tideT = 0f
    var grille = 1f; private set            // 1 closed .. 0 open
    var food = Food.FLAKES; private set
    var reducedMotion = false
    val discovered = LinkedHashMap<Kind, Discovery>()
    val queue = ArrayDeque<Pending>()
    var current: Pending? = null; private set
    private var currentT = 0f
    var highTideFlakeCount = 0; private set
    val particles = List(POOL) { Particle(it) }
    val fish: List<Fish> = Kind.entries.flatMap { k -> (0 until k.count).map { Fish(k, it) } }
    val tags = ArrayList<Tag>()
    var bubbles = ArrayList<FloatArray>()      // x, y, z, t
        private set
    private var pinchSeq = 0L

    // jar
    var jarPhase = JarPhase.HOME; private set
    var jar = Geo.JAR_HOME; private set
    var jarTilt = 0f; private set             // degrees, 0 upright .. 110
    private var jarTarget = Geo.JAR_HOME
    private var jarT = 0f
    private var jarFrom = Geo.JAR_HOME
    private var pourClock = 0f
    var pinchesLeft = PINCHES; private set
    var jarShake = 0f; private set
    var jarGlow = 0f; private set

    // tide handle
    var handleY = Tide.M.handleY; private set
    var handleHeld = false; private set
    private var handleStart = 0f

    // words
    var say: Say = Say.Start; private set
    private var lastFindAt = 0f
    private var hintCount = HashMap<Kind, Int>()
    private var nextHintAt = HINT_S
    var firstRun = false
    /** S01 how-to card should close when the jar is picked up (design S01 exit). */
    var onFirstCarry: (() -> Unit)? = null
    var onAllFound: (() -> Unit)? = null
    var onChanged: (() -> Unit)? = null
    private var allFoundShown = false
    private var stepAcc = 0f

    init { spawnAll() }

    // ---------------------------------------------------------------- queries
    val state: TankState
        get() = when {
            jarPhase != JarPhase.HOME -> TankState.CARRYING
            tidePhase != TidePhase.IDLE -> TankState.TIDE_MOVING
            particles.any { it.alive && it.stage != Particle.Stage.LEFT } -> TankState.FEEDING
            else -> TankState.IDLE
        }
    val found: Int get() = discovered.size
    fun isFound(k: Kind) = k in discovered
    val gateOpen: Boolean get() = grille < 0.05f
    fun present(k: Kind): Boolean = fish.any { it.kind == k && it.shown }

    // ---------------------------------------------------------------- spawning (design spawnSafety)
    private fun spawnAll() {
        for (f in fish) spawn(f)
    }

    private fun spawn(f: Fish) {
        f.pos = when (f.kind) {
            Kind.CHROMIS -> randomInWater(top = true)
            Kind.CLOWN -> Geo.ANEMONE + V3(if (f.idx == 0) 0.03f else -0.03f, 0.06f, 0.02f)
            Kind.CRAB -> Geo.CREVICE
            Kind.SEAHORSE -> Geo.SEAHORSE_HANG
            Kind.TANG, Kind.JELLY -> Geo.GATE_IN.copy(y = min(Geo.GATE_IN.y, Geo.swimTop(level)))
        }
        f.prev = f.pos
        f.vel = V3(rnd.nextFloat() - 0.5f, 0f, rnd.nextFloat() - 0.5f).norm() * 0.06f
        f.shown = when (f.kind) {
            Kind.CHROMIS, Kind.SEAHORSE -> true
            Kind.CLOWN -> isFound(Kind.CLOWN)
            Kind.CRAB -> false
            Kind.TANG, Kind.JELLY -> false
        }
        f.alpha = if (f.shown) 1f else 0f
    }

    fun randomInWater(top: Boolean = false): V3 {
        val x0 = Geo.minSwimX(level); val x = x0 + rnd.nextFloat() * (Geo.IN_X - Geo.INSET - x0)
        val z = (rnd.nextFloat() * 2 - 1) * (Geo.IN_Z - Geo.INSET)
        val lo = Geo.swimBottom(x); val hi = Geo.swimTop(level)
        val y0 = if (top) lo + (hi - lo) * 0.45f else lo
        return V3(x, y0 + (hi - y0) * rnd.nextFloat(), z).let { clampWater(it) }
    }

    fun clampWater(p: V3): V3 {
        val x = p.x.coerceIn(Geo.minSwimX(level), Geo.IN_X - Geo.INSET)
        val z = p.z.coerceIn(-Geo.IN_Z + Geo.INSET, Geo.IN_Z - Geo.INSET)
        val lo = Geo.swimBottom(x); val hi = max(lo, Geo.swimTop(level))
        return V3(x, p.y.coerceIn(lo, hi), z)
    }

    /** Inside the water body (walls, floor / rock, surface). */
    fun inWater(p: V3, pad: Float = 0.001f): Boolean =
        abs(p.x) <= Geo.IN_X + pad && abs(p.z) <= Geo.IN_Z + pad && p.y >= Geo.floorY(p.x) - pad && p.y <= level + pad

    // ---------------------------------------------------------------- restore / reset
    fun restore(s: Saved) = synchronized(lock) {
        tide = s.tide; level = s.tide.level; handleY = s.tide.handleY; food = s.food
        discovered.clear(); s.discovered.forEach { discovered[it.kind] = it }
        grille = if (tide == Tide.H) 0f else 1f
        tidePhase = TidePhase.IDLE
        particles.forEach { it.alive = false }
        spawnAll()
        if (tide == Tide.H) visitorsIn(instant = true)
        allFoundShown = discovered.size == 6
        say = if (s.discovered.isNotEmpty()) Say.Back(found) else Say.Start
        lastFindAt = now; nextHintAt = now + HINT_S
        log("RESTORE tide=$tide food=$food found=${discovered.keys}")
        changed()
    }

    fun snapshot(): Saved = synchronized(lock) { Saved(tide, food, discovered.values.toList()) }

    /** S11 [重置] (design): water to mid tide, guide empty, fish born again. */
    fun reset() = synchronized(lock) {
        discovered.clear(); queue.clear(); current = null; tags.clear(); hintCount.clear()
        highTideFlakeCount = 0; allFoundShown = false
        tide = Tide.M; level = Tide.M.level; handleY = Tide.M.handleY; grille = 1f; tidePhase = TidePhase.IDLE
        food = Food.FLAKES; jarPhase = JarPhase.HOME; jar = Geo.JAR_HOME; jarTilt = 0f; pinchesLeft = PINCHES; holder = null; jarRoll = 0f; tipShown = false
        particles.forEach { it.alive = false }
        fish.forEach { it.bites.clear(); it.fullUntil = -1f; it.phase = 0 }
        spawnAll()
        say = Say.Start; lastFindAt = now; nextHintAt = now + HINT_S
        log("RESET")
        changed()
    }

    // ---------------------------------------------------------------- food choice (T3)
    fun setFood(f: Food, why: String) = synchronized(lock) {
        if (f == food) return@synchronized
        food = f
        say = Say.SwitchFood(f)
        log("FOOD $f via $why")
        changed()
    }

    fun nextFood(why: String) = setFood(synchronized(lock) { food.next() }, why)

    // ---------------------------------------------------------------- the jar (T1, profile D53 补: a toggle)
    /** Who holds the jar (null = on its pedestal or on its way back) and how it was picked. */
    var holder: Holder? = null; private set
    var holdMode = HoldMode.RAY; private set
    /** Roll of the holding hand / controller since the pick, degrees (signed: the side the mouth tips to). */
    var jarRoll = 0f; private set
    /** The 3D tip beside the jar: shown at a pick until the first put-back (then it stays away this session). */
    var tipShown = false; private set
    var tipLearned = false; private set
    /** Pinches poured since the last pick (unit tests read the amount). */
    var pouredThisHold = 0; private set

    /**
     * Grip / trigger / pinch on the jar: picked up. [DIRECT] = touched, it follows the hand in 6DoF from where it is;
     * [RAY] = from afar, it rises to the carry plane and the hand's translation moves it over the water. Letting go of
     * the button does nothing; [jarPutBack] (the next press) takes it home.
     */
    fun jarPick(by: Holder, mode: HoldMode, why: String = "input"): Boolean = synchronized(lock) {
        if (holder != null && (jarPhase == JarPhase.CARRIED || jarPhase == JarPhase.LIFTING)) return@synchronized false
        holder = by; holdMode = mode; jarRoll = 0f; pouredThisHold = 0
        jarFrom = jar; jarT = 0f
        if (mode == HoldMode.DIRECT) { jarPhase = JarPhase.CARRIED; jarTarget = jar }
        else { jarPhase = JarPhase.LIFTING; jarTarget = V3(jar.x, Geo.CARRY_Y, jar.z) }
        pinchesLeft = PINCHES; pourClock = 0f
        if (!tipLearned) tipShown = true
        say = Say.Carry
        onSfx(Sfx.PICK, jar)
        log("JAR pick by=$by mode=$mode why=$why tip=$tipShown")
        if (firstRun) { firstRun = false; onFirstCarry?.invoke() }
        changed()
        true
    }

    /** Kept for the debug broadcast: a ray-mode pick by the system pointer. */
    fun jarGrab(): Boolean = jarPick(Holder.POINTER, HoldMode.RAY, "grab") || holder != null

    /** Ray mode: where the hand wants the jar on the carry plane (clamped to the travel box over the tank). */
    fun jarMove(x: Float, z: Float) = synchronized(lock) {
        if (jarPhase != JarPhase.CARRIED && jarPhase != JarPhase.LIFTING) return@synchronized
        val (cx, cz) = Geo.clampCarry(x, z)
        jarTarget = V3(cx, Geo.CARRY_Y, cz)
    }

    /** Direct mode: the hand's point (tank units), clamped so the jar never goes into the water or through the glass. */
    fun jarFollow(p: V3) = synchronized(lock) {
        if (jarPhase != JarPhase.CARRIED || holdMode != HoldMode.DIRECT) return@synchronized
        jarTarget = Geo.clampDirect(p)
    }

    /** The roll of the holding hand / controller since the pick (JarHold.rollSinceDeg). */
    fun jarSetRoll(deg: Float) = synchronized(lock) { if (holder != null) jarRoll = deg.coerceIn(-180f, 180f) }

    /** The second press: the jar goes back to its pedestal; the first put-back retires the 3D tip. */
    fun jarPutBack(why: String = "input") = synchronized(lock) {
        if (jarPhase == JarPhase.HOME || jarPhase == JarPhase.RETURNING) return@synchronized
        jarPhase = JarPhase.RETURNING; jarT = 0f; jarFrom = jar
        log("JAR put back by=$holder why=$why at (%.3f, %.3f, %.3f) poured=$pouredThisHold".format(jar.x, jar.y, jar.z))
        holder = null; jarRoll = 0f
        if (tipShown) { tipShown = false; tipLearned = true }
        changed()
    }

    fun jarRelease() = jarPutBack("release")

    /** Mouth of the tipped jar (tank units): the jar centre moved toward the side it tips to. */
    fun jarMouth(): V3 {
        val t = Math.toRadians(jarTilt.toDouble()).toFloat()
        val side = if (jarRoll < 0f) -1f else 1f
        return V3(jar.x + side * Geo.JAR_HALF_H * sin(t), jar.y + Geo.JAR_HALF_H * cos(t), jar.z)
    }

    private fun jarFrame(dt: Float) {
        when (jarPhase) {
            JarPhase.HOME -> { jar = Geo.JAR_HOME; jarTilt = approach(jarTilt, 0f, 400f * dt) }
            JarPhase.LIFTING -> {
                jarT += dt
                val k = (jarT / LIFT_S).coerceIn(0f, 1f)
                jar = V3(jarFrom.x, lerp(jarFrom.y, Geo.CARRY_Y, ease(k)), jarFrom.z)
                if (k >= 1f) jarPhase = JarPhase.CARRIED
            }
            JarPhase.CARRIED -> {
                // follow the hand (direct: 6DoF point, ray: carry plane); a speed cap keeps a tracking glitch from teleporting it
                val d = jarTarget - jar
                val step = (if (holdMode == HoldMode.DIRECT) 4f else 2.5f) * dt
                jar = if (d.length <= step) jarTarget else jar + d.norm() * step
                // the jar tips with the hand's roll (no auto-tip any more): pour past 60 deg, faster the more it turns
                jarTilt = approach(jarTilt, abs(jarRoll).coerceAtMost(Geo.JAR_TILT_DEG), 400f * dt)
                val mouth = jarMouth()
                val over = Geo.overWater(mouth.x, mouth.z) && mouth.y > level
                val rate = JarHold.pourRate(jarTilt)
                if (over && rate > 0f && pinchesLeft > 0) {
                    pourClock += dt * rate
                    if (pourClock >= POUR_EVERY) { pourClock = 0f; pouredThisHold++; pinch(mouth.x, mouth.z, "pour", dropY = mouth.y - 0.01f) }
                } else pourClock = min(pourClock, POUR_EVERY * 0.6f)
                if (pinchesLeft == 0 && jarShake <= 0f && over && rate > 0f) { jarShake = 0.4f; pinchesLeft = -1 }
            }
            JarPhase.RETURNING -> {
                jarT += dt
                jarTilt = approach(jarTilt, 0f, 500f * dt)
                val half = RETURN_S / 2
                val top = max(jarFrom.y, Geo.CARRY_Y)
                jar = if (jarT < half) {
                    val k = ease((jarT / half).coerceIn(0f, 1f))
                    V3(lerp(jarFrom.x, Geo.JAR_HOME.x, k), lerp(jarFrom.y, top, k), lerp(jarFrom.z, Geo.JAR_HOME.z, k))
                } else {
                    val k = ease(((jarT - half) / half).coerceIn(0f, 1f))
                    V3(Geo.JAR_HOME.x, lerp(top, Geo.JAR_HOME.y, k), Geo.JAR_HOME.z)
                }
                if (jarT >= RETURN_S) { jarPhase = JarPhase.HOME; jar = Geo.JAR_HOME; pinchesLeft = PINCHES; changed() }
            }
        }
        if (jarShake > 0f) jarShake -= dt
        // S01 / first run: the jar glows every 2 s until it is first used (design S01 scene)
        jarGlow = if (found == 0 && jarPhase == JarPhase.HOME) (0.5f + 0.5f * sin(now * PI.toFloat())).coerceAtLeast(0f) else 0f
    }

    // ---------------------------------------------------------------- feeding (T1 pour, T2 tap)
    /** T2: a tap on the tank bottom = one pinch of the current food straight above the hit point. */
    fun tapFeed(x: Float, z: Float, why: String = "tap") = synchronized(lock) {
        val cx = x.coerceIn(-Geo.IN_X + 0.01f, Geo.IN_X - 0.01f); val cz = z.coerceIn(-Geo.IN_Z + 0.01f, Geo.IN_Z - 0.01f)
        pinch(cx, cz, why, dropY = Geo.TANK_TOP + 0.02f)
        changed()
    }

    /** One pinch: [Food.perPinch] particles drop from (x, dropY, z); the finding rules run on the pinch (design findingRules._note). */
    private fun pinch(x: Float, z: Float, why: String, dropY: Float = Geo.CARRY_Y - 0.04f) {
        pinchSeq++
        if (pinchesLeft > 0 && why == "pour") pinchesLeft--
        val f = food
        onSfx(when (f) { Food.FLAKES -> Sfx.SHAKE_FLAKES; Food.PELLETS -> Sfx.SHAKE_PELLETS; Food.SHRIMP -> Sfx.SHAKE_SHRIMP }, V3(x, dropY, z))
        val spot = spotOf(x, z)
        // the jelly gets one marked shrimp of this pinch (design moonJelly)
        val jellyPinch = rules(f, x, z)
        repeat(f.perPinch) { i ->
            val p = obtain() ?: return@repeat
            p.alive = true; p.food = f; p.stage = Particle.Stage.AIR; p.age = 0f; p.leftAge = 0f; p.pinch = pinchSeq
            p.forJelly = jellyPinch && i == 0
            p.pos = V3(x + (rnd.nextFloat() - 0.5f) * 0.024f, dropY, z + (rnd.nextFloat() - 0.5f) * 0.024f).let { V3(it.x.coerceIn(-Geo.IN_X + 0.005f, Geo.IN_X - 0.005f), it.y, it.z.coerceIn(-Geo.IN_Z + 0.005f, Geo.IN_Z - 0.005f)) }
            p.vel = V3(0f, 0f, 0f)
        }
        if (say !is Say.Found) say = Say.Feeding(f)
        log("PINCH #$pinchSeq $f at (%.3f, %.3f) spot=$spot tide=$tide why=$why htf=$highTideFlakeCount".format(x, z))
    }

    /**
     * The hard finding rules (design findingRules), on the pinch's landing point. Returns true when this pinch calls the jelly
     * (one of its shrimp is marked for it). Conditions only, never who eats (design conflict).
     */
    private fun rules(f: Food, x: Float, z: Float): Boolean {
        val p = V3(x, 0f, z)
        var jelly = false
        if (f == Food.PELLETS && p.distXZ(Geo.ANEMONE) <= 0.10f) find(Kind.CLOWN, f, x, z)
        if (tide == Tide.L && f == Food.PELLETS && (x < Geo.ROCK_TOE_X || p.distXZ(Geo.CREVICE) <= 0.10f)) {
            if (isFound(Kind.CRAB)) crabOut(V3(x, 0f, z)) else find(Kind.CRAB, f, x, z)
        }
        if ((tide == Tide.M || tide == Tide.H) && f == Food.SHRIMP && p.distXZ(Geo.GRASS) <= 0.10f) find(Kind.SEAHORSE, f, x, z)
        if (tide == Tide.H && f == Food.FLAKES) {
            highTideFlakeCount++
            if (highTideFlakeCount >= 3) find(Kind.TANG, f, x, z)
        }
        if (tide == Tide.H && f == Food.SHRIMP && abs(x) <= 0.12f && p.distXZ(Geo.GRASS) > 0.10f) { find(Kind.JELLY, f, x, z); jelly = true }
        return jelly
    }

    private fun find(k: Kind, f: Food, x: Float, z: Float) {
        if (isFound(k)) { arrive(k); return }
        if (queue.any { it.kind == k } || current?.kind == k) return
        discovered[k] = Discovery(k, wallMs(), tide, f, spotOf(x, z))
        queue.addLast(Pending(k))
        lastFindAt = now; nextHintAt = now + HINT_S
        arrive(k)
        log("FOUND $k (${discovered.size}/6) tide=$tide food=$f at (%.3f, %.3f)".format(x, z))
        changed()
    }

    /** Make [k] come out (it may already be there). */
    private fun arrive(k: Kind) {
        when (k) {
            Kind.CLOWN -> fish.filter { it.kind == k }.forEach { it.shown = true }
            Kind.CRAB -> crabOut(Geo.CREVICE + V3(0.04f, 0f, 0.02f))
            Kind.SEAHORSE -> Unit
            Kind.TANG, Kind.JELLY -> if (tide == Tide.H) fish.filter { it.kind == k && !it.shown }.forEach { f ->
                f.pos = Geo.GATE_IN.copy(y = min(Geo.GATE_IN.y, Geo.swimTop(level))); f.prev = f.pos; f.shown = true
            }
            Kind.CHROMIS -> Unit
        }
    }

    private fun crabOut(at: V3) {
        val c = fish.first { it.kind == Kind.CRAB }
        if (c.phase != 0) return
        c.shown = true; c.phase = 1; c.timer = 0f
        val xw = (0.08f - level) / 1.24f - Geo.IN_X + 0.012f   // the waterline on the rock: the crab stays under water
        val tx = at.x.coerceIn(max(-0.27f, xw), -0.02f); val tz = at.z.coerceIn(-0.12f, 0.12f)
        // the crab stays on the rock face under water (it never climbs out)
        c.target = V3(tx, Geo.floorY(tx), tz)
        c.pos = Geo.CREVICE; c.prev = c.pos
    }

    private fun obtain(): Particle? {
        particles.firstOrNull { !it.alive }?.let { return it }
        // pool full (design pool): oldest leftover -> oldest floating flake -> oldest shrimp
        val alive = particles.filter { it.alive }
        return alive.filter { it.stage == Particle.Stage.LEFT }.maxByOrNull { it.leftAge }
            ?: alive.filter { it.food == Food.FLAKES }.maxByOrNull { it.age }
            ?: alive.filter { it.food == Food.SHRIMP }.maxByOrNull { it.age }
            ?: alive.maxByOrNull { it.age }
    }

    // ---------------------------------------------------------------- tide (T4 / T5)
    fun handleGrab(): Boolean = synchronized(lock) { handleHeld = true; handleStart = handleY; log("HANDLE grab"); true }

    /** [dy] = vertical travel since the press (content metres). The handle follows only vertically. */
    fun handleMove(dy: Float) = synchronized(lock) {
        if (!handleHeld) return@synchronized
        handleY = (handleStart + dy).coerceIn(Tide.L.handleY, Tide.H.handleY)
    }

    fun handleRelease() = synchronized(lock) {
        if (!handleHeld) return@synchronized
        handleHeld = false
        val t = Tide.snap(handleY, tide)
        log("HANDLE release y=%.3f -> $t".format(handleY))
        onSfx(Sfx.CLICK, V3(Geo.POST_X, handleY, Geo.HANDLE_Z))
        setTideLocked(t, "handle")
    }

    fun setTide(t: Tide, why: String) = synchronized(lock) { setTideLocked(t, why) }

    /** QA #1 q1-1 (D53): a tap on the brass handle = the next stop (L -> M -> H -> L), same click as a drag release. */
    fun handleTap(): Tide = synchronized(lock) {
        val t = nextTide(tide)
        log("HANDLE tap $tide -> $t")
        onSfx(Sfx.CLICK, V3(Geo.POST_X, handleY, Geo.HANDLE_Z))
        setTideLocked(t, "handle-tap")
        t
    }

    /** q1-1: a tap on a gauge plate (落 / 平 / 涨) = straight to that stop. */
    fun gaugeTap(t: Tide) = synchronized(lock) {
        log("GAUGE tap $t (was $tide)")
        onSfx(Sfx.CLICK, Geo.gaugeAt(t))
        setTideLocked(t, "gauge-tap")
    }

    private fun setTideLocked(t: Tide, why: String) {
        log("TIDE $tide -> $t via $why")
        if (t == tide && tidePhase == TidePhase.IDLE) { changed(); return }
        val leavingHigh = tide == Tide.H && t != Tide.H
        tide = t
        if (t != Tide.H) highTideFlakeCount = 0
        tideFrom = level; tideT = 0f
        tidePhase = if (leavingHigh || grille < 1f && t != Tide.H) TidePhase.VISITORS_OUT else TidePhase.MOVING
        if (tidePhase == TidePhase.MOVING) onSfx(Sfx.TIDE, V3(0f, level, 0f))
        say = Say.TideSay(t)
        changed()
    }

    private fun tideFrame(dt: Float) {
        if (!handleHeld) handleY = approach(handleY, tide.handleY, 0.25f * dt)
        val moveS = if (reducedMotion) 0.5f else TIDE_S
        when (tidePhase) {
            TidePhase.IDLE -> Unit
            TidePhase.VISITORS_OUT -> {
                tideT += dt
                for (f in fish) if (f.kind == Kind.TANG || f.kind == Kind.JELLY) f.shown = false
                if (tideT >= FADE_S) { tidePhase = TidePhase.GRILLE_CLOSE; tideT = 0f; onSfx(Sfx.GATE, Geo.GATE_IN) }
            }
            TidePhase.GRILLE_CLOSE -> {
                tideT += dt; grille = (tideT / FADE_S).coerceIn(0f, 1f)
                if (tideT >= FADE_S) { grille = 1f; tidePhase = TidePhase.MOVING; tideT = 0f; tideFrom = level; onSfx(Sfx.TIDE, V3(0f, level, 0f)) }
            }
            TidePhase.MOVING -> {
                tideT += dt
                val k = (tideT / moveS).coerceIn(0f, 1f)
                level = lerp(tideFrom, tide.level, ease(k))
                if (k >= 1f) {
                    level = tide.level; tideT = 0f
                    if (tide == Tide.H) { tidePhase = TidePhase.GRILLE_OPEN; onSfx(Sfx.GATE, Geo.GATE_IN) } else { tidePhase = TidePhase.IDLE; changed() }
                }
            }
            TidePhase.GRILLE_OPEN -> {
                tideT += dt; grille = 1f - (tideT / FADE_S).coerceIn(0f, 1f)
                if (tideT >= FADE_S) { grille = 0f; tidePhase = TidePhase.IDLE; visitorsIn(false); changed() }
            }
        }
    }

    /** At high tide with the gate open, the found visitors come in through the gate. */
    private fun visitorsIn(instant: Boolean) {
        for (f in fish) if ((f.kind == Kind.TANG || f.kind == Kind.JELLY) && isFound(f.kind)) {
            f.pos = if (instant) randomInWater(top = true) else Geo.GATE_IN.copy(y = min(Geo.GATE_IN.y, Geo.swimTop(level)))
            f.prev = f.pos; f.shown = true; if (instant) f.alpha = 1f
        }
    }

    // ---------------------------------------------------------------- per frame
    /** Advance [dt] seconds: boids step at 30 Hz (design boids.step), the rest per frame. */
    /** Debug `hold`: freeze the simulation for a still (QA screenshots, D36). */
    @Volatile var frozen = false

    fun tick(dt: Float) = synchronized(lock) {
        if (frozen) return@synchronized
        val d = dt.coerceIn(0f, 0.1f)
        now += d
        jarFrame(d)
        tideFrame(d)
        stepAcc += d
        while (stepAcc >= STEP) {
            stepAcc -= STEP
            for (f in fish) f.prev = f.pos
            particlesStep(STEP)
            fishStep(STEP)
        }
        queueFrame(d)
        hintFrame()
        tags.removeAll { it.t -= d; it.t <= -0.5f }
        bubbles.removeAll { b -> b[3] += d; b[1] += 0.12f * d; b[3] > 0.6f || b[1] > level }
        for (f in fish) f.alpha = approach(f.alpha, if (f.shown) 1f else 0f, d / FADE_S)
    }

    /** 0..1 between the last two boid steps, for render interpolation. */
    val alphaStep: Float get() = (stepAcc / STEP).coerceIn(0f, 1f)

    private fun particlesStep(dt: Float) {
        for (p in particles) {
            if (!p.alive) continue
            p.age += dt
            when (p.stage) {
                Particle.Stage.AIR -> {
                    p.vel = V3(0f, p.vel.y - 9.8f * dt, 0f)
                    val ny = p.pos.y + p.vel.y * dt
                    val rock = if (p.pos.x < Geo.ROCK_TOE_X) Geo.rockY(p.pos.x) else -1f
                    if (rock > level && ny <= rock) {
                        // lands on the dry rock: rolls down the slope to the waterline (0.5 s), then sinks (design rock)
                        p.pos = p.pos.copy(y = rock); p.stage = Particle.Stage.ROLL; p.vel = V3.ZERO
                    } else if (ny <= level) {
                        p.pos = p.pos.copy(y = level); enterWater(p)
                    } else p.pos = p.pos.copy(y = ny)
                }
                Particle.Stage.ROLL -> {
                    // slope y = 0.08 - 1.24 (x + 0.294): rolling toward +x; the waterline x where rockY = level
                    val xw = Geo.IN_X.let { (0.08f - level) / 1.24f - it }
                    val nx = approach(p.pos.x, xw + 0.004f, 0.25f * dt)
                    p.pos = V3(nx, Geo.rockY(nx), p.pos.z)
                    if (p.pos.x >= xw) { p.pos = p.pos.copy(y = level); enterWater(p) }
                }
                Particle.Stage.WATER -> waterStep(p, dt)
                Particle.Stage.LEFT -> {
                    p.leftAge += dt
                    if (p.leftAge > LEFTOVER_S) p.alive = false
                }
            }
        }
        // at most 40 leftovers: the oldest fade first (design leftovers)
        val left = particles.filter { it.alive && it.stage == Particle.Stage.LEFT }
        if (left.size > MAX_LEFT) left.sortedByDescending { it.leftAge }.take(left.size - MAX_LEFT).forEach { it.alive = false }
    }

    private fun enterWater(p: Particle) {
        p.stage = Particle.Stage.WATER; p.age = 0f
        p.restY = when (p.food) {
            Food.FLAKES -> level - 0.003f
            Food.PELLETS -> Geo.floorY(p.pos.x) + 0.002f
            Food.SHRIMP -> { val lo = Geo.floorY(p.pos.x) + 0.03f; lo + (level - 0.03f - lo) * (0.35f + 0.3f * rnd.nextFloat()) }
        }
        if (p.id % 3 == 0) onSfx(Sfx.PLOP, p.pos)
    }

    private fun waterStep(p: Particle, dt: Float) {
        val floor = Geo.floorY(p.pos.x) + 0.002f
        when (p.food) {
            Food.FLAKES -> {
                // float and drift; after 20 s nobody ate it -> leftover (sinks to the bottom)
                p.restY = level - 0.003f
                val drift = V3(sin(now * 0.7f + p.id) * 0.004f, 0f, cos(now * 0.5f + p.id) * 0.004f)
                p.pos = clampParticle(V3(p.pos.x + drift.x * dt, p.restY, p.pos.z + drift.z * dt))
                if (p.age > FLAKE_S) { p.food = Food.FLAKES; p.stage = Particle.Stage.LEFT; p.pos = p.pos.copy(y = floor) }
            }
            Food.PELLETS -> {
                val ny = max(floor, p.pos.y - SINK * dt)
                p.pos = p.pos.copy(y = ny)
                if (ny <= floor + 1e-4f) { p.stage = Particle.Stage.LEFT; p.leftAge = 0f }
            }
            Food.SHRIMP -> {
                // swims down to its depth, then hangs and jitters (design: 悬着乱蹦)
                val ty = if (p.pos.y > p.restY + 0.002f) max(p.restY, p.pos.y - SINK * 0.7f * dt) else p.restY + sin(now * 9f + p.id) * 0.002f
                val jx = sin(now * 7f + p.id * 1.3f) * 0.006f * dt; val jz = cos(now * 6f + p.id) * 0.006f * dt
                p.pos = clampParticle(V3(p.pos.x + jx, ty, p.pos.z + jz))
                if (p.age > SHRIMP_S) { p.stage = Particle.Stage.LEFT; p.pos = p.pos.copy(y = floor); p.leftAge = 0f }
            }
        }
        // the water drops: food above the new surface follows it down
        if (p.pos.y > level) p.pos = p.pos.copy(y = level - 0.003f)
    }

    private fun clampParticle(v: V3) = V3(v.x.coerceIn(-Geo.IN_X + 0.004f, Geo.IN_X - 0.004f), v.y, v.z.coerceIn(-Geo.IN_Z + 0.004f, Geo.IN_Z - 0.004f))

    // ---------------------------------------------------------------- creatures
    /** Diet (design findingRules.diet): who goes for this particle. */
    fun eats(k: Kind, p: Particle, f: Fish? = null): Boolean {
        if (!p.alive || p.stage != Particle.Stage.WATER && !(k == Kind.CRAB && p.stage == Particle.Stage.LEFT)) return false
        val nearGrass = p.pos.distXZ(Geo.GRASS) <= 0.10f
        return when (k) {
            Kind.CHROMIS -> (p.food == Food.FLAKES || p.food == Food.SHRIMP && !nearGrass) && !p.forJelly
            Kind.CLOWN -> (p.food == Food.PELLETS || p.food == Food.SHRIMP) && p.pos.distXZ(Geo.ANEMONE) <= 0.12f
            Kind.CRAB -> p.food == Food.PELLETS || p.stage == Particle.Stage.LEFT
            Kind.SEAHORSE -> p.food == Food.SHRIMP && p.pos.distXZ(Geo.GRASS) <= 0.15f && isFound(Kind.SEAHORSE)
            Kind.TANG -> p.food == Food.FLAKES
            Kind.JELLY -> p.food == Food.SHRIMP && (p.forJelly || abs(p.pos.x) <= 0.12f && !nearGrass)
        }
    }

    internal fun bite(f: Fish, p: Particle) {
        p.alive = false
        f.bites.removeAll { now - it > FULL_WINDOW }
        f.bites += now
        if (f.bites.size >= MAX_BITES) { f.fullUntil = now + FULL_WINDOW; f.bites.clear() }
        onSfx(Sfx.BITE, f.pos)
        repeat(3 + rnd.nextInt(3)) { bubbles.add(floatArrayOf(f.pos.x + (rnd.nextFloat() - 0.5f) * 0.01f, f.pos.y + 0.008f, f.pos.z + (rnd.nextFloat() - 0.5f) * 0.01f, -it * 0.05f)) }
        if (f.kind == Kind.CHROMIS && !isFound(Kind.CHROMIS)) find(Kind.CHROMIS, p.food, p.pos.x, p.pos.z)
        if (fish.filter { it.shown && it.kind != Kind.CRAB }.all { it.full(now) }) say = Say.Full
    }

    private fun fishStep(dt: Float) {
        val school = fish.filter { it.kind == Kind.CHROMIS }
        for (f in fish) {
            when (f.kind) {
                Kind.CHROMIS -> boid(f, school, dt)
                Kind.CLOWN -> clown(f, dt)
                Kind.CRAB -> crab(f, dt)
                Kind.SEAHORSE -> seahorse(f, dt)
                Kind.TANG -> tang(f, dt)
                Kind.JELLY -> jelly(f, dt)
            }
            // never out of the water (design tideClamp: pressed back smoothly when the water drops)
            if (f.kind != Kind.CRAB) {
                // never out of the water (design tideClamp): above the comfort top it is pressed back down over ~0.25 s,
                // and it can never pass the surface itself
                var p = f.pos
                val top = Geo.swimTop(level)
                if (p.y > top) p = p.copy(y = p.y + (top - p.y) * min(1f, dt / 0.25f))
                val x = p.x.coerceIn(Geo.minSwimX(level), Geo.IN_X - Geo.INSET)
                val z = p.z.coerceIn(-Geo.IN_Z + Geo.INSET, Geo.IN_Z - Geo.INSET)
                f.pos = V3(x, p.y.coerceIn(Geo.swimBottom(x), max(Geo.swimBottom(x), level - 0.006f)), z)
            }
            val v = f.vel
            if (v.x * v.x + v.z * v.z > 1e-6f) f.yaw = atan2(-v.z, v.x)
            f.swimT += dt * (2f + 20f * v.length)
        }
    }

    private fun nearestFood(f: Fish, range: Float): Particle? {
        var best: Particle? = null; var bd = range
        for (p in particles) if (eats(f.kind, p, f)) { val d = (p.pos - f.pos).length; if (d < bd) { bd = d; best = p } }
        return best
    }

    private fun steer(f: Fish, desired: V3, maxSpeed: Float, accel: Float, dt: Float) {
        val dv = desired - f.vel
        val a = if (dv.length > accel * dt) dv.norm() * (accel * dt) else dv
        var v = f.vel + a
        if (v.length > maxSpeed) v = v.norm() * maxSpeed
        f.vel = v
        f.pos = f.pos + v * dt
    }

    private fun boid(f: Fish, school: List<Fish>, dt: Float) {
        val full = f.full(now)
        val speed = (if (full) 0.5f else 1f) * (0.06f + 0.06f * ((f.idx * 37 % 10) / 10f))
        var sep = V3.ZERO; var ali = V3.ZERO; var coh = V3.ZERO; var n = 0
        for (o in school) {
            if (o === f) continue
            val d = o.pos - f.pos; val l = d.length
            if (l < 0.04f && l > 1e-5f) sep = sep - d.norm() * (0.04f - l) * 25f
            if (l < 0.08f) ali = ali + o.vel
            if (l < 0.12f) { coh = coh + o.pos; n++ }
        }
        var desired = f.vel.norm() * speed
        if (n > 0) desired = desired + (coh * (1f / n) - f.pos) * 0.6f + ali * (0.4f / max(1, n))
        desired = desired + sep
        // depth preference: the upper 60 % of the water
        val lo = Geo.swimBottom(f.pos.x); val hi = Geo.swimTop(level)
        val prefLo = lo + (hi - lo) * 0.4f
        if (f.pos.y < prefLo) desired = desired + V3(0f, 0.04f, 0f)
        // obstacles: anemone, grass, rock (spheres) and the walls (soft push)
        desired = desired + avoid(f.pos)
        val food = if (!full) nearestFood(f, FORAGE_R) else null
        var max = speed
        if (food != null) {
            desired = (food.pos - f.pos).norm() * SPRINT; max = SPRINT
            if ((food.pos - f.pos).length < EAT_R) bite(f, food)
        } else if (jarPhase == JarPhase.CARRIED && Geo.overWater(jar.x, jar.z) && !full) {
            // wait under the jar (design R1-12)
            val t = V3(jar.x, level - 0.03f, jar.z)
            if ((t - f.pos).length < FORAGE_R) desired = desired * 0.3f + (t - f.pos).norm() * (speed * 1.4f)
        }
        steer(f, desired, max, 0.5f, dt)
    }

    private fun avoid(p: V3): V3 {
        var a = V3.ZERO
        fun ball(c: V3, r: Float) { val d = p - c; val l = d.length; if (l < r && l > 1e-5f) a = a + d.norm() * ((r - l) * 3f) }
        ball(Geo.ANEMONE + V3(0f, 0.035f, 0f), 0.07f)
        ball(Geo.GRASS + V3(0f, 0.05f, 0f), 0.09f)
        val m = 0.04f
        if (p.x > Geo.IN_X - m) a = a + V3(-1f, 0f, 0f) * 0.1f
        if (p.x < -Geo.IN_X + m) a = a + V3(1f, 0f, 0f) * 0.1f
        if (p.z > Geo.IN_Z - m) a = a + V3(0f, 0f, -1f) * 0.1f
        if (p.z < -Geo.IN_Z + m) a = a + V3(0f, 0f, 1f) * 0.1f
        if (p.y < Geo.swimBottom(p.x) + m) a = a + V3(0.03f, 0.1f, 0f)
        if (p.y > Geo.swimTop(level) - 0.01f) a = a + V3(0f, -0.08f, 0f)
        return a
    }

    private fun clown(f: Fish, dt: Float) {
        if (!f.shown) { f.pos = Geo.ANEMONE + V3(0f, 0.035f, 0f); f.vel = V3.ZERO; return }
        val food = if (!f.full(now)) nearestFood(f, 0.15f) else null
        if (food != null) {
            steer(f, (food.pos - f.pos).norm() * 0.15f, 0.15f, 0.6f, dt)
            if ((food.pos - f.pos).length < EAT_R) bite(f, food)
        } else {
            // circle within 12 cm of the anemone
            val a = now * 0.8f + f.idx * PI.toFloat()
            val t = Geo.ANEMONE + V3(cos(a) * 0.07f, 0.06f + 0.015f * sin(now * 1.3f + f.idx), sin(a) * 0.05f)
            steer(f, (t - f.pos) * 1.5f, 0.07f, 0.3f, dt)
        }
    }

    private fun crab(f: Fish, dt: Float) {
        val home = Geo.CREVICE
        when (f.phase) {
            0 -> { f.pos = home; f.vel = V3.ZERO; f.shown = false }
            1 -> {
                f.timer += dt
                val t = f.target ?: home
                val d = t - f.pos
                if (d.length < 0.01f || f.timer > 6f) { f.phase = 2; f.timer = 0f } else { f.vel = d.norm() * 0.02f; f.pos = f.pos + f.vel * dt; f.pos = f.pos.copy(y = Geo.floorY(f.pos.x)) }
                // pellets within reach get eaten on the way
                nearestFood(f, 0.03f)?.let { bite(f, it) }
            }
            2 -> {
                f.timer += dt
                nearestFood(f, 0.05f)?.let { bite(f, it) }
                if (f.timer > 2.5f) { f.phase = 3; f.timer = 0f }
            }
            3 -> {
                val d = home - f.pos
                if (d.length < 0.008f) { f.phase = 0; f.shown = false } else { f.vel = d.norm() * 0.025f; f.pos = f.pos + f.vel * dt; f.pos = f.pos.copy(y = Geo.floorY(f.pos.x)) }
            }
        }
    }

    private fun seahorse(f: Fish, dt: Float) {
        val food = if (isFound(Kind.SEAHORSE) && !f.full(now)) nearestFood(f, 0.10f) else null
        val anchor = Geo.SEAHORSE_HANG.copy(y = min(Geo.SEAHORSE_HANG.y, Geo.swimTop(level)))
        if (food != null) {
            f.lean = approach(f.lean, 25f, 60f * dt)
            val t = anchor + (food.pos - anchor) * 0.5f
            steer(f, (t - f.pos) * 2f, 0.03f, 0.2f, dt)
            if ((food.pos - f.pos).length < 0.035f) bite(f, food)
        } else {
            f.lean = approach(f.lean, 0f, 40f * dt)
            val sway = V3(sin(now * 0.9f) * 0.004f, sin(now * 0.6f) * 0.003f, 0f)
            steer(f, (anchor + sway - f.pos) * 2f, 0.02f, 0.2f, dt)
        }
        f.vel = V3(0.0001f, 0f, 0f) // faces +X (sideways to the user), the model's own profile
    }

    private fun tang(f: Fish, dt: Float) {
        if (!f.shown) return
        val food = if (!f.full(now)) nearestFood(f, FORAGE_R) else null
        if (food != null) {
            steer(f, (food.pos - f.pos).norm() * 0.2f, 0.2f, 0.5f, dt)
            if ((food.pos - f.pos).length < EAT_R) bite(f, food)
        } else {
            // figure 8 in the open middle water
            val a = now * 0.35f
            val mid = (Geo.swimBottom(0f) + Geo.swimTop(level)) / 2
            val t = V3(sin(a) * 0.17f, mid + 0.02f * sin(a * 2f), sin(2 * a) * 0.06f)
            steer(f, (t - f.pos) * 1.2f, if (f.full(now)) 0.05f else 0.09f, 0.3f, dt)
        }
    }

    private fun jelly(f: Fish, dt: Float) {
        if (!f.shown) return
        val food = nearestFood(f, FORAGE_R)
        val target = food?.pos ?: V3(0.05f * sin(now * 0.15f), (Geo.swimBottom(0f) + Geo.swimTop(level)) * 0.5f + 0.02f, -0.03f)
        steer(f, (target - f.pos).norm() * 0.03f, 0.03f, 0.1f, dt)
        if (food != null && (food.pos - f.pos).length < 0.03f) bite(f, food)
    }

    // ---------------------------------------------------------------- discoveries, hints
    private fun queueFrame(dt: Float) {
        val c = current
        if (c == null) {
            val n = queue.removeFirstOrNull() ?: return
            current = n; currentT = 0f
            say = Say.Found(n.kind)
            onSfx(Sfx.DISCOVER, fish.first { it.kind == n.kind }.pos)
            showTag(n.kind)
            log("DISCOVER show ${n.kind}")
            changed()
            return
        }
        currentT += dt
        if (currentT >= FOUND_S) {
            current = null
            if (queue.isEmpty()) {
                if (discovered.size == 6 && !allFoundShown) {
                    allFoundShown = true; say = Say.AllFound
                    onSfx(Sfx.ALLFOUND, V3.ZERO)
                    onAllFound?.invoke()
                } else if (discovered.size == 1 && c.kind == Kind.CHROMIS) say = Say.AfterFirst
            }
            changed()
        }
    }

    /** A name tag over [k] for 3 s (also from the guide detail, design S08b). */
    fun showTag(k: Kind) {
        tags.removeAll { it.kind == k }
        tags += Tag(k, TAG_S)
    }

    fun showTagFromGuide(k: Kind): Boolean = synchronized(lock) { if (present(k)) { showTag(k); true } else false }

    private fun hintFrame() {
        if (discovered.size == 6 || now < nextHintAt || current != null) return
        val k = Kind.entries.firstOrNull { !isFound(it) } ?: return
        val n = (hintCount[k] ?: 0) + 1
        hintCount[k] = n
        say = Say.Hint(k, n > 3)
        nextHintAt = now + HINT_S
        log("HINT $k #$n")
        changed()
    }

    /** Debug / guide: force the hint clock (design: 60 s without a new find). */
    fun hintNow() = synchronized(lock) { nextHintAt = now; hintFrame() }

    private fun changed() { onChanged?.invoke() }

    // ---------------------------------------------------------------- name tag placement (design tags, R1-6)
    /** Tag centre x for a creature at [x] with tag half width [half]; dodges the carried jar; null = wait. */
    fun tagX(x: Float, half: Float): Float? {
        val lim = Geo.TANK_X - half
        val cx = x.coerceIn(-lim, lim)
        if (jarPhase == JarPhase.HOME) return cx
        val jw = if (jarTilt > 45f) Geo.JAR_TIPPED_HALF_W else Geo.JAR_HALF_W
        val jh = if (jarTilt > 45f) Geo.JAR_TIPPED_HALF_H else Geo.JAR_HALF_H
        val tagLo = Geo.TAG_BOTTOM; val tagHi = Geo.TAG_BOTTOM + Geo.TAG_H
        if (jar.y + jh < tagLo || jar.y - jh > tagHi) return cx
        fun clear(c: Float) = c - half >= jar.x + jw + 0.02f || c + half <= jar.x - jw - 0.02f
        if (clear(cx)) return cx
        val right = jar.x + jw + 0.02f + half; val left = jar.x - jw - 0.02f - half
        return when {
            right <= lim -> right
            left >= -lim -> left
            else -> null
        }
    }

    companion object {
        const val POOL = 120
        const val STEP = 1f / 30f
        const val PINCHES = 12
        const val POUR_EVERY = 0.25f
        const val LIFT_S = 0.3f
        const val RETURN_S = 0.4f
        const val TIDE_S = 2.5f
        const val FADE_S = 0.5f
        const val FOUND_S = 3f
        const val TAG_S = 3f
        const val HINT_S = 60f
        const val FLAKE_S = 20f
        const val SHRIMP_S = 25f
        const val LEFTOVER_S = 20f
        const val MAX_LEFT = 40
        const val SINK = 0.06f
        const val FORAGE_R = 0.35f
        const val SPRINT = 0.25f
        const val EAT_R = 0.012f
        const val MAX_BITES = 4
        const val FULL_WINDOW = 60f

        fun nextTide(t: Tide): Tide = Tide.entries[(t.ordinal + 1) % Tide.entries.size]
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        fun ease(t: Float) = t * t * (3 - 2 * t)
        fun approach(v: Float, target: Float, step: Float) = if (v < target) min(target, v + step) else max(target, v - step)
    }
}
