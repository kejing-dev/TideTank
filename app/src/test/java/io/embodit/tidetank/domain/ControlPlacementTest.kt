package io.embodit.tidetank.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.1.3 Full Space (门 C 改, D53 补 / D54 坐姿 / D9 placeUpright / D14 / D29): the tank is placed yaw-only from the
 * head pose, under the control window that the system opens straight ahead of a level head.
 */
class ControlPlacementTest {
    private fun fwd(yawDeg: Float, pitchDeg: Float = 0f): V3 {
        val y = Math.toRadians(yawDeg.toDouble()); val p = Math.toRadians(pitchDeg.toDouble())
        return V3((-sin(y) * cos(p)).toFloat(), sin(p).toFloat(), (-cos(y) * cos(p)).toFloat())
    }

    @Test fun tankUnderTheWindowStandingAndSeated() {
        for (eyeY in listOf(1.6f, 1.2f)) for (yaw in listOf(0f, 90f, 180f, -90f, 33f)) {
            val eye = V3(0.3f, eyeY, -0.2f)
            val root = ControlPlacement.placeTank(eye, fwd(yaw))!!
            val bad = ControlPlacement.tankClearsWindow(eye, root)
            assertTrue("eye $eyeY yaw $yaw: $bad", bad.isEmpty())
            assertTrue("centre in the default view", ControlPlacement.tankCentreInView(eye, root))
            // base off the floor (seated 1.2 m eye: the tank still stands on something like a low table)
            val baseY = root.toStage(Geo.BASE.lo).y
            assertTrue("eye $eyeY base at $baseY", baseY >= 0.45f)
        }
    }

    @Test fun placeUprightIgnoresPitchAndRoll() {
        val eye = V3(0f, 1.6f, 0f)
        val level = ControlPlacement.placeTank(eye, fwd(20f))!!
        for (pitch in listOf(-30f, 30f)) {
            val p = ControlPlacement.placeTank(eye, fwd(20f, pitch))!!
            assertEquals("yaw", level.yaw, p.yaw, 1e-4f)
            assertEquals("height", level.pos.y, p.pos.y, 1e-4f)
            assertEquals("distance", ControlPlacement.TANK_DIST, kotlin.math.hypot(p.pos.x - eye.x, p.pos.z - eye.z), 1e-3f)
        }
        // straight down / up: no horizontal heading -> no placement (keep the last one)
        assertTrue(ControlPlacement.placeTank(eye, V3(0f, -1f, 0f)) == null)
    }

    @Test fun fourDirectionsTheFrontFacesTheUser() {
        for (yaw in listOf(0f, 90f, 180f, 270f)) {
            val eye = V3(1f, 1.6f, 2f)
            val root = ControlPlacement.placeTank(eye, fwd(yaw))!!
            // the tank's +Z (front glass, plaque) points back at the eye within 10 deg (profile D10 四方位)
            val front = root.toStage(V3(0f, 0f, 1f)) - root.toStage(V3.ZERO)
            val toEye = V3(eye.x - root.pos.x, 0f, eye.z - root.pos.z).norm()
            val cosA = (front.x * toEye.x + front.z * toEye.z) / front.length
            assertTrue("yaw $yaw cos $cosA", cosA > cos(Math.toRadians(10.0)).toFloat())
        }
    }

    @Test fun jarIsWithinReach() {
        // D53 补 direct pick without walking (B17): the jar on its pedestal <= 0.85 m from the left shoulder (v0.1.4 q4-1
        // pushed the tank to 0.88 m so the whole tank is in view: an arm + controller + a small lean; ray / eye-hand anywhere)
        val eye = V3(0f, 1.6f, 0f)
        val root = ControlPlacement.placeTank(eye, fwd(0f))!!
        val shoulder = eye + V3(-0.18f, -0.25f, 0f)
        val d = (root.toStage(Geo.JAR_HOME) - shoulder).length
        assertTrue("jar $d m from the shoulder", d <= 0.85f)
        // and not in the face (D14: >= 0.4 m from the head)
        assertTrue((root.toStage(Geo.JAR_HOME) - eye).length >= 0.4f)
    }

    @Test fun jarPostAndBoardAreInTheDefaultViewAtColdStart() {
        // M9 / D44 default view: the tank centre, the jar on its pedestal, the handle and the 「面板」 board are on screen
        // without turning the head (FOV +-37 / +-39 deg), standing and seated -- perspective angles, as the picture shows them
        for (eyeY in listOf(1.6f, 1.2f)) {
            val eye = V3(0f, eyeY, 0f)
            val root = ControlPlacement.placeTank(eye, fwd(0f))!!
            for ((n, p) in listOf("jar" to Geo.JAR_HOME, "handle" to V3(Geo.POST_X, Tide.M.handleY, Geo.HANDLE_Z), "board" to Geo.SIGN_C, "tank" to Geo.TANK.centre)) {
                val (h, v) = ControlPlacement.projected(eye, root.yaw, root.toStage(p))
                assertTrue("$n h $h v $v", abs(h) <= ControlPlacement.HALF_FOV_H_DEG && abs(v) <= ControlPlacement.HALF_FOV_V_DEG)
            }
        }
    }

    @Test fun everyCornerOfEveryBoxIsInTheViewStandingAndSeated() {
        // QA #4 q4-1 (D54 坐姿 / D14 / default view): all 8 corners of the tank, base, tide post, its board, the jar pedestal
        // and the jar on it, projected through a pinhole camera at the eye (not 4 centre points, not spherical elevation),
        // stay within +-37 deg across and +-39 deg up / down, for a standing (1.6 m) and a seated (1.2 m) eye at any heading.
        val worst = mutableMapOf<String, Float>()
        for (eyeY in listOf(1.6f, 1.2f)) for (yaw in listOf(0f, 90f, 180f, -90f, 33f)) {
            val eye = V3(0.3f, eyeY, -0.2f)
            val root = ControlPlacement.placeTank(eye, fwd(yaw))!!
            val corners = ControlPlacement.boxesProjected(eye, root)
            assertEquals("6 boxes x 8 corners", 48, corners.size)
            for ((n, h, v) in corners) {
                assertTrue("eye $eyeY yaw $yaw $n h $h", abs(h) <= ControlPlacement.HALF_FOV_H_DEG)
                assertTrue("eye $eyeY yaw $yaw $n v $v", abs(v) <= ControlPlacement.HALF_FOV_V_DEG)
                worst[n] = minOf(worst[n] ?: 0f, v)
            }
        }
        println("PROJ lowest corner per box (deg): " + worst.entries.joinToString { "%s %.1f".format(it.key, it.value) })
        // the regression itself: v0.1.3 (0.70 m / rim 0.24 m) put the base's front bottom 50.4 deg down
        val eye = V3(0f, 1.6f, 0f)
        val old = RootPose(V3(0f, 1.6f - 0.24f - Geo.TANK_TOP, -0.70f), 0f)
        assertTrue(ControlPlacement.boxesProjected(eye, old).any { abs(it.third) > 50f })
    }

    @Test fun aCarriedJarAndItsTipStayUnderTheWindow() {
        // Full Space system windows draw over Stage content: the jar on its carry plane (and the tip beside it) must stay
        // below the window's bottom bar or the window hides them (emulator v0.1.3 first try: tip cut by the window)
        val eye = V3(0f, 1.6f, 0f)
        val root = ControlPlacement.placeTank(eye, fwd(0f))!!
        for (x in listOf(Geo.CARRY_X0, 0f, Geo.CARRY_X1)) for (z in listOf(-Geo.CARRY_Z, 0f, Geo.CARRY_Z)) {
            val top = V3(x + 0.20f, Geo.CARRY_Y + Geo.JAR_HALF_H + 0.02f, z)   // jar top / tip card top edge, right of the jar
            val (az, el) = ControlPlacement.azEl(eye, root.yaw, root.toStage(top))
            assertTrue("($x, $z) az $az el $el vs ${ControlPlacement.windowBottomDeg()}", abs(az) > ControlPlacement.windowHalfAzDeg() || el < ControlPlacement.windowBottomDeg() - 1f)
        }
    }

    @Test fun windowPanelStaysAboveFortyDegreesDown() {
        // D14: nothing on the panel more than 40 deg under the eye line (window straight ahead at eye height)
        assertTrue(abs(ControlPlacement.windowBottomDeg()) <= 40f)
    }

    @Test fun recallSamplerWaitsForAStillHead() {
        // D29: [叫回面前] is pressed while looking at the button; the pose is taken once the head is still in front
        val r = RecallSampler()
        r.start(0)
        var t = 0L; var yaw = -35f
        var taken = false
        while (t < 3000 && !taken) {
            yaw = if (t < 600) -35f + t / 600f * 35f else 0f
            taken = r.step(t, yaw, 0f)
            t += 16
        }
        assertTrue(taken)
        assertEquals(0f, yaw, 2f)
        assertNotNull(ControlPlacement.placeTank(V3(0f, 1.6f, 0f), fwd(yaw)))
    }
}
