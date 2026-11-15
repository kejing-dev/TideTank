package io.embodit.tidetank.ui

import io.embodit.tidetank.domain.Geo
import io.embodit.tidetank.domain.Kind
import io.embodit.tidetank.domain.Saved
import io.embodit.tidetank.domain.Tank
import io.embodit.tidetank.ui.tank.Names
import io.embodit.tidetank.ui.tank.input.parseLoggedPath
import io.embodit.tidetank.ui.tank.input.resolvePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hit replay (build.md 命中回放, board-input README): real hit chains as the system reports them (the GLB mesh node first,
 * then its parents) resolve to our targets; every floor target resolves to a landing point inside the tank; nothing in the
 * water / glass is a target.
 */
class HitReplayTest {
    private fun r(path: String) = resolvePath(parseLoggedPath(path), Names::target)

    @Test fun meshNodesResolveUpToTheirHolders() {
        assertEquals(Names.JAR, r("Mesh_0 < jarBody < jarModel < jar < jarRoot < root"))
        assertEquals(Names.JAR, r("Mesh_0 < jarLid < jarModel < jar < jarRoot < root"))
        assertEquals(Names.HANDLE, r("handleRing < handle < tideRoot < root"))
        assertEquals(Names.SIGN, r("signWords < sign < tideRoot < root"))
        assertEquals(Names.ANEMONE, r("Mesh_0 < anemoneModel < floor:anemone < floorTargets < root"))
        assertEquals(Names.GRASS, r("Mesh_0 < grass2 < floor:grass < floorTargets < root"))
        assertEquals(Names.sand(7), r("floor:sand:7 < floorTargets < root"))
        assertEquals(Names.rock(1), r("floor:rock:1 < floorTargets < root"))
    }

    @Test fun waterGlassAndCreaturesAreNotTargets() {
        for (p in listOf("water < waterRoot < root", "glassFront < tankRoot < root", "Mesh_0 < fish:chromis:3 < creatureRoot < root", "postModel < tideRoot < root", "<none>")) assertNull(p, r(p))
    }

    @Test fun everyFloorCellFeedsInsideTheTankAndRunsTheRules() {
        val cells = (0 until Names.SAND_NX * Names.SAND_NZ).map { Names.sand(it) } + (0..2).map { Names.rock(it) } + listOf(Names.ANEMONE, Names.GRASS)
        for (c in cells) {
            val (x, z) = assertNotNullPair(Names.centreOf(c))
            assertTrue("$c x $x", kotlin.math.abs(x) <= Geo.IN_X); assertTrue("$c z $z", kotlin.math.abs(z) <= Geo.IN_Z)
            val t = Tank(seed = 1); t.restore(Saved()); t.tapFeed(x, z, "replay $c")
            assertTrue(t.particles.any { it.alive })
        }
        // the anemone cell with pellets = the clownfish rule (same path as a real tap)
        val t = Tank(seed = 1); t.restore(Saved()); t.setFood(io.embodit.tidetank.domain.Food.PELLETS, "t")
        val (x, z) = Names.centreOf(Names.ANEMONE)!!; t.tapFeed(x, z)
        assertTrue(t.isFound(Kind.CLOWN))
    }

    private fun assertNotNullPair(p: Pair<Float, Float>?): Pair<Float, Float> { assertNotNull(p); return p!! }
}
