package io.embodit.tidetank.domain

/**
 * One input source in one frame (Stage space). [point] = controller tip / pinch point (null when the source delivers
 * no pose, e.g. the emulator's mouse-as-trigger); [dir] = the controller's pointing direction (null for a hand: hand ray
 * and eye-hand picks come through the system tap); [rot] = controller / wrist rotation; [presses] = rising edges of grip
 * OR trigger (controller) or pinch (hand) since the last frame.
 */
data class InputSample(val who: Holder, val point: V3?, val dir: V3?, val rot: Q4, val presses: Int = 0)

/**
 * profile D53 补 (门 C 改 2026-11-16): the food jar as a toggle. Every frame [frame] gets the tracked controllers / hands;
 * a press next to the jar picks it up (direct, follows in 6DoF), a press with the controller ray on it picks it up from
 * afar (ray: the hand's translation moves it over the water, 1:1), any later press by the holder puts it back. Letting go
 * of a button does nothing. The system pointer's tap (eye-hand, hand ray, controller ray) arrives via [systemTap]; Full
 * Space sends a tap for the same press the trackers already used, so taps within [JarHold.SWALLOW_MS] of a tracker toggle
 * are swallowed. The debug broadcast feeds [frame] / [systemTap] too (no shortcut, build.md 2026-10-29).
 */
class JarInput(private val tank: Tank, private val log: (String) -> Unit = {}) {
    @Volatile var root: RootPose = RootPose(V3(0f, 0f, -0.62f), 0f)
    private var anchorAtPick = V3.ZERO
    private var rotAtPick = Q4.ID
    private var jarAtPick = V3.ZERO
    private var offset = V3.ZERO
    private var swallowUntil = Long.MIN_VALUE
    private var lastPress: Holder? = null
    private var lastPressAt = Long.MIN_VALUE
    private val last = HashMap<Holder, InputSample>()
    /** Last roll handed to the tank (log / tests). */
    var roll = 0f; private set

    fun frame(nowMs: Long, sources: List<InputSample>) = synchronized(this) {
        for (s in sources) { if (s.point != null) last[s.who] = s }
        for (s in sources) repeat(s.presses) { press(s, nowMs) }
        follow(sources)
    }

    private fun press(s: InputSample, now: Long) {
        lastPress = s.who; lastPressAt = now
        val jarStage = root.toStage(tank.jar)
        val near = s.point != null && (s.point - jarStage).length <= JarHold.REACH_M
        val ray = s.point != null && s.dir != null && JarHold.rayHits(s.point, s.dir.norm(), jarStage, JarHold.RAY_R_M)
        val act = JarHold.decide(s.who, tank.holder, near, ray)
        log("JAR press by=${s.who} near=$near ray=$ray holder=${tank.holder} -> $act")
        when (act) {
            JarHold.Act.PICK_DIRECT -> { pick(s, HoldMode.DIRECT); swallowUntil = now + JarHold.SWALLOW_MS }
            JarHold.Act.PICK_RAY -> { pick(s, HoldMode.RAY); swallowUntil = now + JarHold.SWALLOW_MS }
            JarHold.Act.PUT_BACK -> { tank.jarPutBack("press ${s.who}"); swallowUntil = now + JarHold.SWALLOW_MS }
            JarHold.Act.NONE -> Unit
        }
    }

    private fun pick(s: InputSample, mode: HoldMode) {
        if (!tank.jarPick(s.who, mode, "press")) return
        anchorAtPick = s.point ?: V3.ZERO
        rotAtPick = s.rot
        jarAtPick = tank.jar
        offset = root.toStage(tank.jar) - anchorAtPick
        roll = 0f
    }

    /**
     * A system tap on the jar ([source] = the board-input label: gaze / hand ray / controller / mouse). Returns what it did.
     * Picking from a tap anchors the jar to the source that pressed last (a tracked hand / controller), or to the
     * system pointer when nothing is tracked (then it stays over the water until the next press / tap puts it back).
     */
    fun systemTap(nowMs: Long, source: String): String = synchronized(this) {
        val what = when {
            nowMs < swallowUntil -> "swallowed (same press as a tracker toggle)"
            tank.holder != null -> { tank.jarPutBack("tap $source"); swallowUntil = nowMs + 300; "put back" }
            else -> {
                val who = lastPress?.takeIf { nowMs - lastPressAt <= JarHold.SWALLOW_MS && last[it] != null }
                val s = who?.let { last[it] } ?: InputSample(Holder.POINTER, null, null, Q4.ID)
                pick(s, HoldMode.RAY)
                swallowUntil = nowMs + 300
                "picked (ray) anchored to ${s.who}"
            }
        }
        log("JAR tap src=$source -> $what")
        what
    }

    private fun follow(sources: List<InputSample>) {
        val h = tank.holder ?: return
        val s = sources.firstOrNull { it.who == h && it.point != null } ?: return
        val p = s.point!!
        when (tank.holdMode) {
            HoldMode.DIRECT -> tank.jarFollow(JarHold.directFollow(p, offset, root))
            HoldMode.RAY -> { val (x, z) = JarHold.rayFollow(jarAtPick, anchorAtPick, p, root); tank.jarMove(x, z) }
        }
        roll = JarHold.rollSinceDeg(s.rot, rotAtPick)
        tank.jarSetRoll(roll)
    }
}
