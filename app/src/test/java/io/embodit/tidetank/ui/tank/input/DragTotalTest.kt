package io.embodit.tidetank.ui.tank.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D53 (SDK 6.1.9): detectSpatialDragGesture's dragAmount is one event's delta, so BoardInput sums it with DragTotal
 * and hands onDragMove the travel since the press.
 */
class DragTotalTest {

    @Test
    fun sixtyEventsOfTwoMillimetresTotalTwelveCentimetres() {
        val t = DragTotal()
        var last = Offset3M(0f, 0f, 0f)
        repeat(60) { last = t.add(Offset3M(0.002f, 0f, 0f)) }
        assertEquals(0.12f, last.x, 1e-4f)
        assertEquals(0f, last.y, 1e-6f)
        assertEquals(0f, last.z, 1e-6f)
        assertEquals(0.12f, last.length, 1e-4f)
        assertEquals(60, t.events)
    }

    @Test
    fun everyAxisAddsUpAndTheTotalGrowsEachEvent() {
        val t = DragTotal()
        var prev = 0f
        repeat(60) {
            val d = t.add(Offset3M(0.002f, -0.001f, 0.0005f))
            assertTrue("total must grow, event $it", d.length > prev)
            prev = d.length
        }
        assertEquals(0.12f, t.x, 1e-4f)
        assertEquals(-0.06f, t.y, 1e-4f)
        assertEquals(0.03f, t.z, 1e-4f)
    }

    @Test
    fun resetReturnsToZeroForTheNextPress() {
        val t = DragTotal()
        repeat(60) { t.add(Offset3M(0.002f, 0.002f, 0.002f)) }
        t.reset()
        assertEquals(0f, t.x, 0f); assertEquals(0f, t.y, 0f); assertEquals(0f, t.z, 0f)
        assertEquals(0, t.events)
        val d = t.add(Offset3M(0.002f, 0f, 0f))
        assertEquals(0.002f, d.x, 1e-6f)
        assertEquals(1, t.events)
    }

    @Test
    fun aDragOnlyStartsOnceTheSumPassesTheThreshold() {
        // 2 mm steps never exceed dragStartM one by one; the sum must (the old per-event check never started a drag)
        val t = DragTotal()
        val start = BoardInputConfig().dragStartM
        var crossedAt = -1
        repeat(60) { i -> if (crossedAt < 0 && t.add(Offset3M(0.002f, 0f, 0f)).length > start) crossedAt = i }
        assertTrue("step should be below the threshold for this test", 0.002f <= start)
        assertTrue("sum never crossed dragStartM=$start", crossedAt in 0 until 60)
    }
}
