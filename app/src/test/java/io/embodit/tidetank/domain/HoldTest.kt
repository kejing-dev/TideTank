package io.embodit.tidetank.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 门 C 改 2026-11-16 (profile D53 补「手持工具按一下拿起、再按一下放回」): the food jar as a toggle, driven the way the
 * Stage drives it (JarInput.frame with controller / hand samples, systemTap for the system pointer). Each test replays a
 * stream of per-frame input events (30 Hz) through the same path as the headset; nothing sets the jar directly.
 */
class HoldTest {
    private val eye = V3(0.2f, 1.55f, 0.1f)
    private val root = ControlPlacement.placeTank(eye, V3(-0.5f, 0f, -0.866f))!!   // a heading 30 deg left: yaw matters
    private val logs = ArrayList<String>()

    private fun rig(): Pair<Tank, JarInput> {
        val t = Tank(seed = 5, log = { logs += it }); t.restore(Saved())
        val j = JarInput(t) { logs += it }; j.root = root
        return t to j
    }

    /** The controller held so its tip is at tank point [p], pointing into the tank, rolled [roll] about its forward. */
    private fun ctrlAt(p: V3, roll: Float = 0f, presses: Int = 0, who: Holder = Holder.CTRL_R): InputSample {
        val base = Q4.axisAngle(V3(0f, 1f, 0f), Math.toDegrees(root.yaw.toDouble()).toFloat())
        val q = (Q4.axisAngle(base.forward, roll) * base).normalized()
        return InputSample(who, root.toStage(p), q.forward, q, presses)
    }

    /** The controller at the user's hip with its ray on tank point [target]. */
    private fun ctrlAim(target: V3, from: V3 = root.toStage(V3(0.18f, 0.02f, 0.55f)), roll: Float = 0f, presses: Int = 0): InputSample {
        val d = (root.toStage(target) - from).norm()
        val yaw = Math.toDegrees(kotlin.math.atan2(-d.x, -d.z).toDouble()).toFloat()
        val pitch = Math.toDegrees(kotlin.math.asin(d.y.toDouble())).toFloat()
        val base = (Q4.axisAngle(V3(0f, 1f, 0f), yaw) * Q4.axisAngle(V3(1f, 0f, 0f), pitch)).normalized()
        val q = (Q4.axisAngle(base.forward, roll) * base).normalized()
        return InputSample(Holder.CTRL_R, from, q.forward, q, presses)
    }

    private fun run(t: Tank, j: JarInput, ms: Long, frames: Int, sample: (Int) -> List<InputSample>): Long {
        var now = ms
        for (i in 0 until frames) { j.frame(now, sample(i)); t.tick(1f / 30); now += 33 }
        return now
    }

    @Test fun pressReleaseKeepsItInTheHandPressAgainPutsItHome() {
        val (t, j) = rig()
        var now = run(t, j, 0, 1) { listOf(ctrlAt(Geo.JAR_HOME, presses = 1)) }
        assertEquals(Holder.CTRL_R, t.holder); assertEquals(HoldMode.DIRECT, t.holdMode)
        // the button is let go (no more presses) and the hand moves up for 2 s: still held, follows
        val up = V3(Geo.JAR_HOME.x, Geo.JAR_HOME.y + 0.10f, Geo.JAR_HOME.z)
        now = run(t, j, now, 60) { listOf(ctrlAt(up)) }
        assertEquals("still in the hand after the release", Holder.CTRL_R, t.holder)
        assertEquals(JarPhase.CARRIED, t.jarPhase)
        assertEquals(up.y, t.jar.y, 0.002f)
        // the second press: back to the pedestal
        now = run(t, j, now, 1) { listOf(ctrlAt(up, presses = 1)) }
        assertNull(t.holder)
        run(t, j, now, 30) { listOf(ctrlAt(up)) }
        assertEquals(JarPhase.HOME, t.jarPhase)
        assertEquals(0f, (t.jar - Geo.JAR_HOME).length, 1e-5f)
    }

    @Test fun directFollowsTheControllerSixDof() {
        val (t, j) = rig()
        val grip = Geo.JAR_HOME + V3(0.03f, 0.02f, 0f)   // touching, 3.6 cm off the jar centre
        var now = run(t, j, 0, 1) { listOf(ctrlAt(grip, presses = 1)) }
        assertEquals("no jump at the pick", 0f, (t.jar - Geo.JAR_HOME).length, 1e-4f)
        // lift it over the rim, then 10 cm right, 8 cm up, 5 cm toward the user (tank axes); the pick offset is kept
        val high = grip + V3(0.05f, 0.40f, 0f)
        now = run(t, j, now, 30) { i -> listOf(ctrlAt(grip + (high - grip) * ((i + 1) / 30f))) }
        now = run(t, j, now, 10) { listOf(ctrlAt(high)) }
        val start = t.jar
        val path = (1..30).map { i -> high + V3(0.10f, 0.08f, 0.05f) * (i / 30f) }
        now = run(t, j, now, 30) { listOf(ctrlAt(path[it])) }
        run(t, j, now, 10) { listOf(ctrlAt(path.last())) }
        val moved = t.jar - start
        assertEquals(0.10f, moved.x, 0.005f); assertEquals(0.08f, moved.y, 0.005f); assertEquals(0.05f, moved.z, 0.005f)
    }

    @Test fun rayPickAndTenCentimetresOfControllerMoveTheJarTenCentimetres() {
        for (axis in listOf(V3(1f, 0f, 0f), V3(0f, 0f, -1f))) {
            val (t, j) = rig()
            var now = run(t, j, 0, 1) { listOf(ctrlAim(Geo.JAR_HOME, presses = 1)) }
            assertEquals(HoldMode.RAY, t.holdMode)
            now = run(t, j, now, 15) { listOf(ctrlAim(Geo.JAR_HOME)) }             // lifts to the carry plane
            // move over the water first (the travel box starts at the pedestal), then measure a 10 cm move
            val from0 = root.toStage(V3(0.18f, 0.02f, 0.55f))
            val over = from0 + RootPose.rotY(V3(0.35f, 0f, -0.05f), root.yaw)
            now = run(t, j, now, 30) { i -> listOf(ctrlAim(Geo.JAR_HOME, from = from0 + (over - from0) * ((i + 1) / 30f))) }
            now = run(t, j, now, 10) { listOf(ctrlAim(Geo.JAR_HOME, from = over)) }
            val a = t.jar
            // the controller's own translation (Stage metres, along the tank's axis), 10 cm in 30 frames; the ray swings, the jar must not follow the ray
            val d = RootPose.rotY(axis * 0.10f, root.yaw)
            now = run(t, j, now, 30) { i -> listOf(ctrlAim(Geo.JAR_HOME, from = over + d * ((i + 1) / 30f))) }
            run(t, j, now, 10) { listOf(ctrlAim(Geo.JAR_HOME, from = over + d)) }
            val moved = Geo.phys((t.jar - a).length)
            assertEquals("axis $axis moved $moved m", 0.10f, moved, 0.005f)
            assertEquals("stays on the carry plane", Geo.CARRY_Y, t.jar.y, 1e-4f)
        }
    }

    @Test fun replayedPathAccumulates() {
        // 60 events of 2 mm each = 12 cm, accumulated (no per-event reset, D53 拖拽累加)
        val (t, j) = rig()
        var now = run(t, j, 0, 1) { listOf(ctrlAt(Geo.JAR_HOME, presses = 1)) }
        val p0 = Geo.JAR_HOME + V3(0f, 0.25f, 0f)
        now = run(t, j, now, 20) { listOf(ctrlAt(p0)) }
        val a = t.jar
        now = run(t, j, now, 60) { i -> listOf(ctrlAt(p0 + V3(0.002f * (i + 1), 0f, 0f))) }
        run(t, j, now, 10) { listOf(ctrlAt(p0 + V3(0.12f, 0f, 0f))) }
        assertEquals(0.12f, t.jar.x - a.x, 0.003f)
    }

    private fun pouredAt(roll: Float): Int {
        val (t, j) = rig()
        var now = run(t, j, 0, 1) { listOf(ctrlAt(Geo.JAR_HOME, presses = 1)) }
        val over = V3(0.05f, 0.22f, 0f)
        now = run(t, j, now, 30) { i -> listOf(ctrlAt(Geo.JAR_HOME + (over - Geo.JAR_HOME) * ((i + 1) / 30f))) }
        // turn the wrist to [roll] over 0.3 s, hold 2 s
        run(t, j, now, 70) { i -> listOf(ctrlAt(over, roll = roll * ((i + 1) / 9f).coerceAtMost(1f))) }
        return t.pouredThisHold
    }

    @Test fun rollZeroPoursNothingSeventyPoursMoreTurnPoursMore() {
        val p0 = pouredAt(0f); val p45 = pouredAt(45f); val p70 = pouredAt(70f); val p100 = pouredAt(100f); val pNeg = pouredAt(-80f)
        assertEquals("0 deg", 0, p0)
        assertEquals("45 deg (under the 60 deg start)", 0, p45)
        assertTrue("70 deg pours ($p70)", p70 > 0)
        assertTrue("100 deg pours more than 70 ($p100 vs $p70)", p100 > p70)
        assertTrue("the other way round pours too ($pNeg)", pNeg > 0)
    }

    @Test fun rollIsMeasuredAboutTheForwardAxisSinceThePick() {
        val pick = Q4.axisAngle(V3(0f, 1f, 0f), 30f) * Q4.axisAngle(V3(1f, 0f, 0f), -20f)
        for (r in listOf(-120f, -70f, 0f, 45f, 70f, 110f)) {
            val now = (Q4.axisAngle(pick.forward, r) * pick).normalized()
            assertEquals("roll $r", r, JarHold.rollSinceDeg(now, pick), 0.5f)
        }
        // pointing elsewhere (yaw / pitch) is no roll
        val swung = (Q4.axisAngle(V3(0f, 1f, 0f), 40f) * pick).normalized()
        assertTrue(abs(JarHold.rollSinceDeg(swung, pick)) < 15f)
    }

    @Test fun foodLandsStraightUnderTheMouth() {
        val (t, j) = rig()
        var now = run(t, j, 0, 1) { listOf(ctrlAt(Geo.JAR_HOME, presses = 1)) }
        val over = V3(0.05f, 0.22f, 0.02f)
        now = run(t, j, now, 30) { i -> listOf(ctrlAt(Geo.JAR_HOME + (over - Geo.JAR_HOME) * ((i + 1) / 30f))) }
        logs.clear()
        run(t, j, now, 40) { listOf(ctrlAt(over, roll = 100f)) }
        val pinch = logs.first { it.startsWith("PINCH") && "why=pour" in it }
        val (x, z) = Regex("at \\((-?[0-9.]+), (-?[0-9.]+)\\)").find(pinch)!!.destructured
        val m = t.jarMouth()
        assertEquals(m.x, x.toFloat(), 0.015f); assertEquals(m.z, z.toFloat(), 0.015f)
        assertTrue("mouth is to the side the hand turned", m.x > t.jar.x)
    }

    @Test fun tipShowsAtThePickAndGoesAfterTheFirstPutBack() {
        val (t, j) = rig()
        assertFalse(t.tipShown)
        var now = run(t, j, 0, 1) { listOf(ctrlAt(Geo.JAR_HOME, presses = 1)) }
        assertTrue("shown when picked", t.tipShown)
        now = run(t, j, now, 30) { listOf(ctrlAt(Geo.JAR_HOME + V3(0f, 0.2f, 0f))) }
        assertTrue("still shown while held", t.tipShown)
        now = run(t, j, now, 1) { listOf(ctrlAt(Geo.JAR_HOME + V3(0f, 0.2f, 0f), presses = 1)) }
        assertFalse("gone after the put-back", t.tipShown)
        now = run(t, j, now, 30) { listOf(ctrlAt(Geo.JAR_HOME)) }
        run(t, j, now, 1) { listOf(ctrlAt(Geo.JAR_HOME, presses = 1)) }
        assertEquals(Holder.CTRL_R, t.holder)
        assertFalse("learned: not again this session", t.tipShown)
    }

    @Test fun systemTapOfTheSamePressIsSwallowedEyeHandAnchorsToThePinchingHand() {
        val (t, j) = rig()
        // controller ray press picks (tracker), the system's tap for that press arrives 200 ms later: swallowed
        var now = run(t, j, 0, 1) { listOf(ctrlAim(Geo.JAR_HOME, presses = 1)) }
        assertTrue(j.systemTap(now + 200, "controller").startsWith("swallowed"))
        assertEquals(Holder.CTRL_R, t.holder)
        now = run(t, j, now + 2000, 1) { listOf(ctrlAim(Geo.JAR_HOME, presses = 1)) }
        assertNull(t.holder)
        now = run(t, j, now, 20) { emptyList() }
        // eye-hand: the right hand pinches far from the jar (nothing), the system tap on the jar picks, anchored to that hand
        val hand = { p: V3, presses: Int -> InputSample(Holder.HAND_R, root.toStage(p), null, Q4.axisAngle(V3(0f, 1f, 0f), Math.toDegrees(root.yaw.toDouble()).toFloat()), presses) }
        val far = V3(0.2f, 0.3f, 0.45f)
        now = run(t, j, now + 2000, 1) { listOf(hand(far, 1)) }
        assertNull(t.holder)
        assertTrue(j.systemTap(now + 150, "eye-hand").startsWith("picked"))
        assertEquals(Holder.HAND_R, t.holder); assertEquals(HoldMode.RAY, t.holdMode)
        // the next pinch of that hand puts it back; its system tap is swallowed
        now = run(t, j, now + 1000, 1) { listOf(hand(far, 1)) }
        assertNull(t.holder)
        assertTrue(j.systemTap(now + 150, "eye-hand").startsWith("swallowed"))
        assertNull(t.holder)
    }

    @Test fun nobodyTakesTheJarOutOfAnotherHand() {
        assertEquals(JarHold.Act.NONE, JarHold.decide(Holder.CTRL_L, Holder.CTRL_R, nearJar = true, rayOnJar = true))
        assertEquals(JarHold.Act.PUT_BACK, JarHold.decide(Holder.CTRL_R, Holder.CTRL_R, nearJar = false, rayOnJar = false))
        assertEquals(JarHold.Act.PUT_BACK, JarHold.decide(Holder.HAND_L, Holder.POINTER, nearJar = false, rayOnJar = false))
        assertEquals(JarHold.Act.PICK_DIRECT, JarHold.decide(Holder.HAND_L, null, nearJar = true, rayOnJar = true))
        assertEquals(JarHold.Act.PICK_RAY, JarHold.decide(Holder.CTRL_L, null, nearJar = false, rayOnJar = true))
        assertEquals(JarHold.Act.NONE, JarHold.decide(Holder.CTRL_L, null, nearJar = false, rayOnJar = false))
    }

    @Test fun aHeldJarNeverGoesIntoTheWaterOrThroughTheGlass() {
        val (t, j) = rig()
        var now = run(t, j, 0, 1) { listOf(ctrlAt(Geo.JAR_HOME, presses = 1)) }
        // the hand dives into the middle of the tank
        now = run(t, j, now, 40) { i -> listOf(ctrlAt(Geo.JAR_HOME + (V3(0f, -0.1f, 0f) - Geo.JAR_HOME) * ((i + 1) / 40f))) }
        run(t, j, now, 10) { listOf(ctrlAt(V3(0f, -0.1f, 0f))) }
        assertTrue("jar bottom over the rim (${t.jar.y})", t.jar.y - Geo.JAR_HALF_H >= Geo.TANK_TOP)
    }

    @Test fun handPinchNearTheJarPicksDirectAndFollows() {
        val (t, j) = rig()
        val base = Q4.axisAngle(V3(0f, 1f, 0f), Math.toDegrees(root.yaw.toDouble()).toFloat())
        val hand = { p: V3, roll: Float, presses: Int -> InputSample(Holder.HAND_L, root.toStage(p), null, (Q4.axisAngle(base.forward, roll) * base).normalized(), presses) }
        var now = run(t, j, 0, 1) { listOf(hand(Geo.JAR_HOME + V3(0f, 0.04f, 0f), 0f, 1)) }
        assertEquals(Holder.HAND_L, t.holder); assertEquals(HoldMode.DIRECT, t.holdMode)
        val over = V3(0f, 0.25f, 0f)
        now = run(t, j, now, 30) { i -> listOf(hand(Geo.JAR_HOME + V3(0f, 0.04f, 0f) + (over - Geo.JAR_HOME) * ((i + 1) / 30f), 0f, 0)) }
        run(t, j, now, 60) { listOf(hand(over + V3(0f, 0.04f, 0f), -85f, 0)) }
        assertTrue("wrist roll pours (${t.pouredThisHold})", t.pouredThisHold > 0)
    }
}
