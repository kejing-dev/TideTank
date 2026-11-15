package io.embodit.tidetank.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import io.embodit.tidetank.ui.control.VolumeDrag
import org.junit.Test

/** B20 / user 2026-11-14 「音量条点了没反应」: taps at 0 / 50 / 100 % and short drags change the stop. */
class VolumeDragTest {
    /** A tap = one onValueChange at the touch point, then onValueChangeFinished (what the Slider sends). */
    private fun tap(d: VolumeDrag, frac: Float): Int { d.move(frac * 2f); d.finish(); return d.level }

    @Test fun tapsAtZeroHalfAndFullReachEveryStopFromEveryStop() {
        for (start in 0..2) for ((frac, want) in listOf(0f to 0, 0.5f to 1, 1f to 2)) {
            val d = VolumeDrag(start)
            assertEquals("from $start tap ${frac * 100} %", want, tap(d, frac))
            assertEquals(want.toFloat(), d.pos, 0f) // the thumb snapped onto the stop
        }
    }

    @Test fun theChangeIsReportedSoTheSoundVolumeFollows() {
        val d = VolumeDrag(2)
        val live = d.move(0.95f) // finger down at ~47 %
        assertEquals(1, live)
        assertNull(d.finish()) // already on 1, nothing more to report
        assertEquals(1, d.level)
    }

    @Test fun shortDragsMoveOneStopInTheirDirection() {
        for (start in 0..2) for (dir in listOf(-1, 1)) {
            val d = VolumeDrag(start)
            d.move(start + dir * 0.1f); d.move(start + dir * 0.2f)
            d.finish()
            val want = (start + dir).coerceIn(0, 2)
            assertEquals("from $start drag ${dir * 0.2f}", want, d.level)
            assertEquals(want.toFloat(), d.pos, 0f)
        }
    }

    @Test fun aTinyWobbleKeepsTheStop() {
        val d = VolumeDrag(1)
        d.move(1.01f); d.move(0.995f)
        assertNull(d.finish())
        assertEquals(1, d.level)
    }

    @Test fun aTapNextToAStopGoesToThatStopNotPastIt() {
        for ((start, frac, want) in listOf(Triple(1, 0.55f, 1), Triple(1, 0.45f, 1), Triple(0, 0.04f, 0), Triple(2, 0.96f, 2), Triple(0, 0.6f, 1))) {
            assertEquals("from $start tap ${frac * 100} %", want, tap(VolumeDrag(start), frac))
        }
    }

    /** Emulator 2026-11-14 (real adb swipes on the window): the Slider drags relative to the thumb; 28 px ended at +0.047. */
    @Test fun theSmallestRealDragStillMoves() {
        for ((start, path, want) in listOf(Triple(0, listOf(0.01f, 0.047f), 1), Triple(1, listOf(1.01f, 1.041f), 2), Triple(2, listOf(1.99f, 1.955f), 1))) {
            val d = VolumeDrag(start)
            path.forEach { d.move(it) }
            d.finish()
            assertEquals("$start $path", want, d.level)
        }
    }

    /** A pinch-tap at 52 % with a little hand jitter (several onValueChange) stays on 50 %. */
    @Test fun aJitteryTapIsStillATap() {
        val d = VolumeDrag(0)
        d.move(1.04f); d.move(1.045f); d.move(1.035f)
        d.finish()
        assertEquals(1, d.level)
    }

    @Test fun longDragsSnapToTheNearestStop() {
        val d = VolumeDrag(0)
        for (v in listOf(0.3f, 0.8f, 1.4f, 1.8f)) d.move(v)
        d.finish()
        assertEquals(2, d.level)
    }

    @Test fun externalChangesAreFollowedWhenIdle() {
        val d = VolumeDrag(2)
        d.sync(0)
        assertEquals(0, d.level); assertEquals(0f, d.pos, 0f)
        d.move(0.4f); d.sync(2) // during a drag the finger wins
        assertEquals(0, d.level)
    }
}
