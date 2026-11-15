package io.embodit.tidetank.ui

import io.embodit.tidetank.domain.Geo
import io.embodit.tidetank.domain.Saved
import io.embodit.tidetank.domain.Tank
import io.embodit.tidetank.domain.Tide
import io.embodit.tidetank.ui.tank.Names
import io.embodit.tidetank.ui.tank.TankListener
import io.embodit.tidetank.ui.tank.input.DragTotal
import io.embodit.tidetank.ui.tank.input.parseLoggedPath
import io.embodit.tidetank.ui.tank.input.resolvePath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * QA #1 q1-1 (D53): the brass handle and the 落 / 平 / 涨 plates are tap targets. A tap on the handle = the next stop
 * (L -> M -> H -> L); a tap on a plate = that stop; the drag still works through DragTotal (accumulated, D53). The hit
 * chains are replayed as the system reports them.
 */
class TideTapTest {
    private fun r(path: String) = resolvePath(parseLoggedPath(path), Names::target)

    private fun settle(t: Tank) { repeat(30 * 12) { t.tick(1f / 30) } }

    @Test fun platesAndHandleResolveFromTheirMeshes() {
        for (tide in Tide.entries) {
            assertEquals(Names.gauge(tide), r("gaugeWords:${tide.name} < tide:${tide.name} < tideRoot < root"))
            assertEquals(Names.gauge(tide), r("tide:${tide.name} < tideRoot < root"))
            assertEquals(tide, Names.tideOf(Names.gauge(tide)))
        }
        assertEquals(Names.HANDLE, r("handleRing < handle < tideRoot < root"))
        assertEquals(Names.HANDLE, r("handleStem < handle < tideRoot < root"))
        assertEquals(null, Names.tideOf("tide:X"))
    }

    @Test fun tappingTheHandleCyclesLowMidHighLow() {
        val t = Tank(seed = 3); t.restore(Saved(Tide.L))
        val seen = mutableListOf<Tide>()
        repeat(4) { seen += t.handleTap(); settle(t) }
        assertEquals(listOf(Tide.M, Tide.H, Tide.L, Tide.M), seen)
        assertEquals(Tide.M.level, t.level, 1e-4f)
        assertEquals(Tide.M.handleY, t.handleY, 1e-3f)
    }

    @Test fun tappingAPlateJumpsStraightToThatStop() {
        val t = Tank(seed = 4); t.restore(Saved(Tide.M))
        t.gaugeTap(Tide.H); settle(t)
        assertEquals(Tide.H, t.tide); assertEquals(Tide.H.level, t.level, 1e-4f)
        t.gaugeTap(Tide.L); settle(t)
        assertEquals(Tide.L, t.tide); assertEquals(Tide.L.level, t.level, 1e-4f)
        t.gaugeTap(Tide.L); settle(t)                // same stop: nothing moves
        assertEquals(Tide.L, t.tide)
    }

    @Test fun theHandleDragStillWorksWithAccumulatedDeltasAndTheRootScale() {
        val t = Tank(seed = 5); t.restore(Saved(Tide.L))
        // 60 events of 2 mm of hand travel = 12 cm physical; the root is drawn SCALE x, 1:1 content per physical metre
        val k = TankListener.dragScale(1f, 1f)
        assertEquals(1f / Geo.SCALE, k, 1e-6f)
        val d = DragTotal(); d.reset()
        t.handleGrab()
        repeat(60) { d.add(io.embodit.tidetank.ui.tank.input.Offset3M(0f, 0.002f, 0f)); t.handleMove(d.y * k) }
        t.handleRelease()
        // 12 cm of hand = 8.6 tank-cm up from L (-0.14): past the L / M midpoint + hysteresis -> M
        assertEquals(Tide.M, t.tide)
    }
}
