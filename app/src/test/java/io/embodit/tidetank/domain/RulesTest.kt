package io.embodit.tidetank.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * M1 (design verification): the six finding rules on both sides of every edge (0.09 / 0.11 m, wrong tide, wrong food),
 * with all ten chromis hungry (conditions only, never who eats, design conflict); diet; fullness; dry rock; the queue;
 * leaving high tide.
 */
class RulesTest {
    private fun tank(tide: Tide = Tide.M, found: List<Kind> = emptyList()): Tank {
        val t = Tank(seed = 3)
        t.restore(Saved(tide, Food.FLAKES, found.map { Discovery(it, 0L, Tide.M, Food.FLAKES, Spot.OPEN) }))
        return t
    }

    private fun Tank.feed(f: Food, x: Float, z: Float) { setFood(f, "test"); tapFeed(x, z) }
    private fun at(c: V3, d: Float, angle: Double = 0.7) = (c.x + d * cos(angle).toFloat()) to (c.z + d * sin(angle).toFloat())
    private fun Tank.allHungry() = fish.filter { it.kind == Kind.CHROMIS }.all { !it.full(now) && it.bites.isEmpty() }

    @Test fun clownfishPelletsWithinTenCentimetresOfTheAnemoneAnyTide() {
        for (tide in Tide.entries) {
            val t = tank(tide); assertTrue(t.allHungry())
            val (x, z) = at(Geo.ANEMONE, 0.09f); t.feed(Food.PELLETS, x, z)
            assertTrue("$tide 0.09", t.isFound(Kind.CLOWN))
            val u = tank(tide); val (x2, z2) = at(Geo.ANEMONE, 0.11f); u.feed(Food.PELLETS, x2, z2)
            assertFalse("$tide 0.11", u.isFound(Kind.CLOWN))
        }
        val w = tank(); val (x, z) = at(Geo.ANEMONE, 0.05f)
        w.feed(Food.FLAKES, x, z); w.feed(Food.SHRIMP, x, z)
        assertFalse("wrong food", w.isFound(Kind.CLOWN))
    }

    @Test fun hermitCrabLowTidePelletsOnTheRocksOrByTheCrevice() {
        val t = tank(Tide.L); t.feed(Food.PELLETS, -0.20f, 0.05f)
        assertTrue("on the rock slope", t.isFound(Kind.CRAB))
        val u = tank(Tide.L); val (x, z) = at(Geo.CREVICE, 0.09f, 0.0); u.feed(Food.PELLETS, x, z)
        assertTrue("0.09 from the crevice", u.isFound(Kind.CRAB))
        val v = tank(Tide.L); val (x2, z2) = at(Geo.CREVICE, 0.11f, 0.0); v.feed(Food.PELLETS, x2, z2)
        assertFalse("0.11 from the crevice, off the rock", v.isFound(Kind.CRAB))
        for (tide in listOf(Tide.M, Tide.H)) { val w = tank(tide); w.feed(Food.PELLETS, -0.20f, 0.05f); assertFalse("$tide", w.isFound(Kind.CRAB)) }
        val f = tank(Tide.L); f.feed(Food.FLAKES, -0.20f, 0.05f); f.feed(Food.SHRIMP, -0.20f, 0.05f)
        assertFalse("wrong food", f.isFound(Kind.CRAB))
    }

    @Test fun seahorseShrimpInTheSeagrassAtMidOrHighTide() {
        for (tide in listOf(Tide.M, Tide.H)) {
            val t = tank(tide); val (x, z) = at(Geo.GRASS, 0.09f, 2.5); t.feed(Food.SHRIMP, x, z)
            assertTrue("$tide 0.09", t.isFound(Kind.SEAHORSE))
            val u = tank(tide); val (x2, z2) = at(Geo.GRASS, 0.11f, 2.5); u.feed(Food.SHRIMP, x2, z2)
            assertFalse("$tide 0.11", u.isFound(Kind.SEAHORSE))
        }
        val l = tank(Tide.L); l.feed(Food.SHRIMP, Geo.GRASS.x, Geo.GRASS.z); assertFalse("low tide", l.isFound(Kind.SEAHORSE))
        val f = tank(); f.feed(Food.PELLETS, Geo.GRASS.x, Geo.GRASS.z); f.feed(Food.FLAKES, Geo.GRASS.x, Geo.GRASS.z)
        assertFalse("wrong food", f.isFound(Kind.SEAHORSE))
    }

    @Test fun blueTangThreeFlakePinchesAtHighTideAndTheCountResetsWhenTheTideLeaves() {
        val t = tank(Tide.H)
        t.feed(Food.FLAKES, 0.05f, 0f); t.feed(Food.FLAKES, 0.05f, 0f)
        assertFalse("two is not enough", t.isFound(Kind.TANG))
        t.setTide(Tide.M, "test"); assertEquals(0, t.highTideFlakeCount)
        t.setTide(Tide.H, "test"); t.feed(Food.FLAKES, 0.05f, 0f); t.feed(Food.FLAKES, 0.05f, 0f)
        assertFalse("count restarted", t.isFound(Kind.TANG))
        t.feed(Food.FLAKES, -0.1f, 0.1f)
        assertTrue("third pinch anywhere", t.isFound(Kind.TANG))
        val m = tank(Tide.M); repeat(5) { m.feed(Food.FLAKES, 0f, 0f) }; assertFalse("mid tide", m.isFound(Kind.TANG))
    }

    @Test fun moonJellyShrimpInOpenWaterAtHighTide() {
        val t = tank(Tide.H); t.feed(Food.SHRIMP, -0.11f, -0.05f)
        assertTrue("|x| 0.11, far from the grass", t.isFound(Kind.JELLY))
        assertTrue("one shrimp of the pinch is marked for the jelly", t.particles.any { it.alive && it.forJelly })
        val u = tank(Tide.H); u.feed(Food.SHRIMP, -0.13f, -0.05f); assertFalse("|x| 0.13", u.isFound(Kind.JELLY))
        val v = tank(Tide.M); v.feed(Food.SHRIMP, 0f, 0f); assertFalse("mid tide", v.isFound(Kind.JELLY))
        val w = tank(Tide.H); w.feed(Food.FLAKES, 0f, 0f); assertFalse("flakes", w.isFound(Kind.JELLY))
    }

    @Test fun chromisAreFoundByTheirFirstBite() {
        val t = tank()
        assertFalse(t.isFound(Kind.CHROMIS))
        t.feed(Food.FLAKES, 0.05f, 0f)
        repeat(30 * 30) { t.tick(1f / 30) }
        assertTrue("ten hungry chromis eat a floating flake within 30 s", t.isFound(Kind.CHROMIS))
    }

    @Test fun oneSweepCanFindTwoAndTheyQueueThreeSecondsEach() {
        val t = tank(Tide.H)
        t.feed(Food.SHRIMP, Geo.GRASS.x, Geo.GRASS.z)     // seahorse
        t.feed(Food.SHRIMP, 0.0f, 0.05f)                 // moon jelly
        assertTrue(t.isFound(Kind.SEAHORSE) && t.isFound(Kind.JELLY))
        t.tick(0.05f)
        assertEquals(Kind.SEAHORSE, t.current?.kind)
        var n = 0
        while (t.current?.kind == Kind.SEAHORSE && n < 100) { t.tick(0.05f); n++ }
        assertTrue("about 3 s ($n)", n in 58..62)
        t.tick(0.05f)
        assertEquals(Kind.JELLY, t.current?.kind)
    }

    @Test fun theSixthFindShowsTheAllFoundCardAfterTheQueue() {
        val t = tank(Tide.H, listOf(Kind.CHROMIS, Kind.CLOWN, Kind.CRAB, Kind.SEAHORSE, Kind.TANG))
        var all = 0; t.onAllFound = { all++ }
        t.feed(Food.SHRIMP, 0f, 0.05f)
        repeat(70) { t.tick(0.05f) }
        assertEquals(1, all); assertTrue(t.say is Say.AllFound)
    }

    @Test fun dietChromisSkipPelletsShrimpInTheGrassAndTheJellysShrimp() {
        val t = tank(Tide.H)
        val p = Particle(0).apply { alive = true; stage = Particle.Stage.WATER; pos = V3(-0.1f, 0f, 0f) }
        p.food = Food.PELLETS; assertFalse(t.eats(Kind.CHROMIS, p))
        p.food = Food.FLAKES; assertTrue(t.eats(Kind.CHROMIS, p))
        p.food = Food.SHRIMP; assertTrue(t.eats(Kind.CHROMIS, p))
        p.forJelly = true; assertFalse("marked for the jelly", t.eats(Kind.CHROMIS, p))
        p.forJelly = false; p.pos = V3(Geo.GRASS.x, 0f, Geo.GRASS.z + 0.05f); assertFalse("in the seagrass", t.eats(Kind.CHROMIS, p))
        p.food = Food.FLAKES; assertTrue(t.eats(Kind.TANG, p)); p.food = Food.PELLETS; assertFalse(t.eats(Kind.TANG, p))
    }

    @Test fun aFishEatsFourBitesInSixtySecondsThenIgnoresFood() {
        val t = tank()
        val f = t.fish.first { it.kind == Kind.CHROMIS }
        fun flake() = Particle(99).apply { alive = true; stage = Particle.Stage.WATER; food = Food.FLAKES; pos = f.pos }
        repeat(3) { t.bite(f, flake()) }
        assertFalse("3 bites: still hungry", f.full(t.now))
        t.bite(f, flake())
        assertTrue("4 bites: full", f.full(t.now))
        // a full fish does not go for food: a flake right at its mouth stays for the next second
        val p = t.particles[0].apply { alive = true; stage = Particle.Stage.WATER; food = Food.FLAKES; pos = f.pos; age = 0f }
        t.fish.filter { it !== f }.forEach { it.fullUntil = 1e9f }
        repeat(30) { t.tick(1f / 30) }
        assertTrue("5th bite not taken", p.alive)
        assertTrue("full for 60 s", f.full(t.now + 58f) && !f.full(t.now + 61f))
    }

    @Test fun pelletsOnTheDryRockRollToTheWaterline() {
        val t = tank(Tide.L)
        t.feed(Food.PELLETS, -0.27f, 0f)       // rock top is out of the water at low tide
        repeat(15) { t.tick(1f / 30) }
        assertTrue(t.particles.any { it.alive && it.stage == Particle.Stage.ROLL })
        repeat(60) { t.tick(1f / 30) }
        assertTrue("rolled into the water and sinking", t.particles.filter { it.alive }.all { it.stage == Particle.Stage.WATER || it.stage == Particle.Stage.LEFT })
        assertTrue(t.isFound(Kind.CRAB))
    }

    @Test fun leavingHighTideFadesTheVisitorsThenClosesTheGateThenDrops() {
        val t = tank(Tide.H, listOf(Kind.TANG, Kind.JELLY))
        assertTrue(t.present(Kind.TANG) && t.gateOpen)
        t.setTide(Tide.M, "test")
        t.tick(0.1f)
        assertEquals(TidePhase.VISITORS_OUT, t.tidePhase); assertFalse(t.present(Kind.TANG)); assertEquals(Tide.H.level, t.level, 1e-6f)
        repeat(6) { t.tick(0.1f) }
        assertEquals(TidePhase.GRILLE_CLOSE, t.tidePhase); assertEquals(Tide.H.level, t.level, 1e-6f)
        repeat(6) { t.tick(0.1f) }
        assertEquals(TidePhase.MOVING, t.tidePhase); assertEquals(1f, t.grille, 1e-6f)
        repeat(30) { t.tick(0.1f) }
        assertEquals(Tide.M.level, t.level, 1e-5f); assertEquals(TidePhase.IDLE, t.tidePhase)
    }

    @Test fun theHandleSnapsWithHysteresisAndJitterNeverFlipsIt() {
        for (start in Tide.entries) for (amp in listOf(0.004f, 0.008f)) {
            var flips = 0
            for (i in 0 until 200) { val y = start.handleY + amp * sin(i * 0.9f); if (Tide.snap(y, start) != start) flips++ }
            assertEquals("$start ±${amp * 1000} mm", 0, flips)
        }
        assertEquals(Tide.H, Tide.snap(0.04f, Tide.M))
        assertEquals(Tide.M, Tide.snap(0.02f, Tide.M))
        assertEquals(Tide.L, Tide.snap(-0.11f, Tide.M))
        assertEquals(Tide.L, Tide.snap(-0.14f, Tide.H))
        // through the real drag calls
        val t = tank(Tide.M); t.handleGrab(); t.handleMove(0.07f); t.handleRelease()
        assertEquals(Tide.H, t.tide)
    }

    @Test fun clueGoesToTheFirstUnfoundAndTurnsPlainAfterThree() {
        val t = tank(found = listOf(Kind.CHROMIS))
        repeat(4) { t.hintNow() }
        val s = t.say as Say.Hint
        assertEquals(Kind.CLOWN, s.kind)
        assertEquals(Creatures.of(Kind.CLOWN).plain, s.panel)
    }

    @Test fun clueIsAlwaysAConditionThatCanBeReached() {
        // every clue's recipe works from any tide (design rulesEnforced): replay each plain recipe
        val recipes = mapOf(
            Kind.CLOWN to Triple(Tide.L, Food.PELLETS, Geo.ANEMONE), Kind.CRAB to Triple(Tide.L, Food.PELLETS, V3(-0.2f, 0f, 0f)),
            Kind.SEAHORSE to Triple(Tide.M, Food.SHRIMP, Geo.GRASS), Kind.JELLY to Triple(Tide.H, Food.SHRIMP, V3(0f, 0f, 0.05f)),
        )
        for ((k, r) in recipes) for (from in Tide.entries) {
            val t = tank(from); t.setTide(r.first, "test"); t.feed(r.second, r.third.x, r.third.z)
            assertTrue("$k from $from", t.isFound(k))
        }
    }

    @Test fun nameTagDodgesTheCarriedJar() {
        val t = tank()
        assertEquals(0.1f, t.tagX(0.1f, 0.05f)!!, 1e-6f)
        t.jarGrab(); repeat(20) { t.tick(1f / 30) }; t.jarMove(0.1f, 0f); repeat(60) { t.tick(1f / 30) }
        val x = t.tagX(0.1f, 0.05f)!!
        val jw = if (t.jarTilt > 45f) Geo.JAR_TIPPED_HALF_W else Geo.JAR_HALF_W
        assertTrue("gap >= 2 cm", x - 0.05f >= t.jar.x + jw + 0.02f - 1e-4f || x + 0.05f <= t.jar.x - jw - 0.02f + 1e-4f)
        assertTrue("inside the tank width", kotlin.math.abs(x) + 0.05f <= Geo.TANK_X + 1e-4f)
    }

    @Test fun theJarPoursOnlyOverTheWaterAndAtMostTwelvePinches() {
        var pinches = 0
        val t = Tank(seed = 3, log = { if (it.startsWith("PINCH") && "why=pour" in it) pinches++ }); t.restore(Saved())
        t.jarGrab(); repeat(15) { t.tick(1f / 30) }
        t.jarMove(-0.36f, 0.1f); repeat(90) { t.tick(1f / 30) }
        assertEquals("beside the tank: upright, nothing", 0, pinches)
        t.jarMove(0.1f, 0f); repeat(30 * 6) { t.tick(1f / 30) }
        assertTrue("12 pinches max ($pinches)", pinches <= Tank.PINCHES)
        assertTrue(pinches >= 10)
        t.jarRelease(); repeat(30) { t.tick(1f / 30) }
        assertEquals(JarPhase.HOME, t.jarPhase); assertEquals(Tank.PINCHES, t.pinchesLeft)
    }

    @Test fun tappingTheJarCyclesTheFood() {
        val t = tank()
        t.nextFood("t"); assertEquals(Food.PELLETS, t.food)
        t.nextFood("t"); assertEquals(Food.SHRIMP, t.food)
        t.nextFood("t"); assertEquals(Food.FLAKES, t.food)
    }

    @Test fun resetEmptiesTheGuideAndGoesToMidTide() {
        val t = tank(Tide.H, Kind.entries.toList())
        t.reset()
        assertEquals(0, t.found); assertEquals(Tide.M, t.tide); assertNull(t.current)
        assertNotNull(t.say as? Say.Start)
    }
}
