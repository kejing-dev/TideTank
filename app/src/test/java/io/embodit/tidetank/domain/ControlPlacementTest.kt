package io.embodit.tidetank.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * q2-1 (QA #2): the control window's four corners are inside the default view for a standing and a seated eye, it is
 * no more than 40 deg below the eye line (D14), it does not cover the tank, and the v0.1.1 placement (right of the
 * 1.19 m volume) is shown to fail the same check (the regression this replaces).
 */
class ControlPlacementTest {
    private val eyes = mapOf("standing" to 1.6f, "seated" to 1.2f)

    @Test fun shippedPlacementIsBelowTheVolumeAt600x440() {
        assertEquals(ControlPlacement.Side.BOTTOM, ControlPlacement.SIDE)
        assertEquals(600f, ControlPlacement.WINDOW_W_DP)
        assertEquals(440f, ControlPlacement.WINDOW_H_DP)
    }

    @Test fun fourCornersInTheDefaultViewSeatedAndStanding() {
        for ((name, eyeH) in eyes) {
            // Shared Space places the volume (and so the window) relative to the head: same eye-space corners at any height
            for (c in ControlPlacement.corners(ControlPlacement.Side.BOTTOM)) {
                val (yaw, pitch) = ControlPlacement.angles(c)
                assertTrue("$name corner $c yaw=$yaw pitch=$pitch", ControlPlacement.inDefaultView(c))
                assertTrue("$name corner $pitch deg below > 40 (D14)", -pitch <= ControlPlacement.MAX_DOWN_DEG)
                val floorClear = eyeH + c.y
                assertTrue("$name corner ${floorClear} m above the floor", floorClear > 0.2f)
            }
        }
    }

    @Test fun tabBarAndSystemBarAlsoInView() {
        for (c in ControlPlacement.corners(ControlPlacement.Side.BOTTOM, withChrome = true)) {
            val (_, pitch) = ControlPlacement.angles(c)
            assertTrue("chrome corner pitch=$pitch", kotlin.math.abs(pitch) <= ControlPlacement.HALF_FOV_V_DEG)
        }
    }

    @Test fun windowAndPillAreBelowTheTankNotOverIt() {
        val pillTop = ControlPlacement.corners(ControlPlacement.Side.BOTTOM, withChrome = true).maxOf { ControlPlacement.angles(it).second }
        val tankBottom = ControlPlacement.angles(ControlPlacement.tankBaseFrontBottom()).second
        assertTrue("pill top $pillTop must be below the tank base $tankBottom", pillTop < tankBottom)
    }

    @Test fun windowIsCentredUnderTheTank() {
        val xs = ControlPlacement.corners(ControlPlacement.Side.BOTTOM).map { it.x }
        assertEquals(0f, (xs.min() + xs.max()) / 2, 0.001f)
        assertTrue(xs.max() < ControlPlacement.VOLUME_W / 2)
    }

    @Test fun theOldRightPlacementLeavesTheViewAndBottomIsTheOnlySideThatFits() {
        val right = ControlPlacement.corners(ControlPlacement.Side.RIGHT, hDp = 600f)
        assertFalse("v0.1.1 right placement is cut (QA #2 q2-1)", right.all { ControlPlacement.inDefaultView(it) })
        val left = ControlPlacement.corners(ControlPlacement.Side.LEFT, hDp = 600f)
        assertFalse(left.all { ControlPlacement.inDefaultView(it) })
        // even a 330 dp narrow window beside the volume would not fit with the margin
        assertFalse(ControlPlacement.corners(ControlPlacement.Side.RIGHT, wDp = 400f, hDp = 600f).all { ControlPlacement.inDefaultView(it) })
    }
}
