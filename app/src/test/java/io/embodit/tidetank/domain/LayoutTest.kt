package io.embodit.tidetank.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * M5 / M6 / M11 (design verification): everything inside the volume shrunk by 1 cm (all three tides, the jar's whole
 * travel incl. the 8 corners of the tipped jar), spawn points, boids never out of the water, D25 calibration of the
 * shipped models (tools/model_bounds.json), and the seven-eye sight lines (tags, gauge marks, handle, jar, board,
 * plaque not behind the tank / rock).
 */
class LayoutTest {
    private val inner = Box(V3(Geo.VOLUME.lo.x, Geo.VOLUME.lo.y, Geo.VOLUME.lo.z), V3(Geo.VOLUME.hi.x, Geo.VOLUME.hi.y, Geo.VOLUME.hi.z))
    /** 1 cm of physical margin, in tank units (the scene root is drawn Geo.SCALE x, q1-2). */
    private val margin = Geo.units(0.01f)

    /** Shipped model boxes after calibration (tools/build_models.py writes this file). */
    private val models: JSONObject = JSONObject(File("../tools/model_bounds.json").let { if (it.exists()) it else File("tools/model_bounds.json") }.readText())
    private fun model(name: String, at: V3, yawDeg: Float = 0f): Box {
        val m = models.getJSONObject(name)
        val lo = m.getJSONArray("lo"); val hi = m.getJSONArray("hi")
        val corners = listOf(lo.getDouble(0), hi.getDouble(0)).flatMap { x -> listOf(lo.getDouble(1), hi.getDouble(1)).flatMap { y -> listOf(lo.getDouble(2), hi.getDouble(2)).map { z -> Triple(x.toFloat(), y.toFloat(), z.toFloat()) } } }
        val a = Math.toRadians(yawDeg.toDouble())
        val pts = corners.map { (x, y, z) -> V3(at.x + (x * cos(a) + z * sin(a)).toFloat(), at.y + y, at.z + (-x * sin(a) + z * cos(a)).toFloat()) }
        return Box(V3(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z }), V3(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z }))
    }

    private fun jarBox(c: V3, tilt: Float): Box {
        val w = if (tilt > 45f) Geo.JAR_TIPPED_HALF_W else Geo.JAR_HALF_W
        val h = if (tilt > 45f) Geo.JAR_TIPPED_HALF_H else Geo.JAR_HALF_H
        return Box(V3(c.x - w, c.y - h, c.z - w), V3(c.x + w, c.y + h, c.z + w))
    }

    private fun fixedBoxes(): Map<String, Box> = mapOf(
        "tank" to Geo.TANK, "base" to Geo.BASE, "plaque" to Geo.PLAQUE, "pedestal" to Geo.PEDESTAL, "post" to Geo.POST, "sign" to Geo.SIGN,
        "postModel" to model("post", V3(Geo.POST_X, Geo.POST_BOT, Geo.POST_Z)),
        "rockModel" to model("rock", V3((-Geo.IN_X + Geo.ROCK_TOE_X) / 2, Geo.IN_BOT, 0f)),
        "anemone" to model("anemone", Geo.ANEMONE), "jarHome" to model("jar_body", V3(Geo.JAR_HOME.x, Geo.JAR_HOME.y + Geo.JAR_BOTTOM_OFF, Geo.JAR_HOME.z)),
        "handleTop" to Box.of(Geo.POST_X, Tide.H.handleY, Geo.HANDLE_Z, Geo.HANDLE_HIT_W, Geo.HANDLE_HIT_H, Geo.HANDLE_HIT_D),
        "handleLow" to Box.of(Geo.POST_X, Tide.L.handleY, Geo.HANDLE_Z, Geo.HANDLE_HIT_W, Geo.HANDLE_HIT_H, Geo.HANDLE_HIT_D),
        "gate" to Box(V3(Geo.GATE_X0, Geo.GATE_Y0, -Geo.TANK_Z - 0.012f), V3(Geo.GATE_X1, Geo.GATE_Y1, -Geo.TANK_Z)),
    ) + Geo.GRASS_TUFTS.mapIndexed { i, p -> "grass$i" to model("grass", p, i * 115f) }

    @Test fun everyFixedPartIsInsideTheVolumeLessOneCentimetre() {
        // parts resting on the volume floor (base, pedestal) may touch it; everything else keeps 1 cm on every side
        for ((n, b) in fixedBoxes()) {
            val lifted = if (b.lo.y <= Geo.VOLUME.lo.y + 1e-4f) Box(V3(b.lo.x, Geo.VOLUME.lo.y + margin + 1e-4f, b.lo.z), b.hi) else b
            assertTrue("$n $b", lifted.inside(inner, margin))
        }
    }

    @Test fun theJarsWholeTravelTippedOrNotStaysInsideAndAboveTheRim() {
        var n = 0
        for (i in 0..20) for (j in 0..8) {
            val x = Geo.CARRY_X0 + (Geo.CARRY_X1 - Geo.CARRY_X0) * i / 20; val z = -Geo.CARRY_Z + 2 * Geo.CARRY_Z * j / 8
            for (tilt in listOf(0f, Geo.JAR_TILT_DEG)) {
                val b = jarBox(V3(x, Geo.CARRY_Y, z), tilt)
                // v0.1.3 Full Space: no volume walls any more (the jar tips sideways with the hand and may lean past the old
                // volume edge); what stays: never in the glass, always above the rim
                assertFalse("jar at ($x, $z) tilt $tilt in the glass", b.intersects(Geo.TANK))
                assertTrue("above the rim", b.lo.y > Geo.TANK_TOP)
                n++
            }
        }
        assertTrue(n > 300)
        // lift / return path: straight up from the pedestal, then across above the rim (no glass crossing)
        val up = jarBox(V3(Geo.JAR_HOME.x, Geo.CARRY_Y, Geo.JAR_HOME.z), 0f)
        assertFalse(up.intersects(Geo.TANK)); assertFalse(jarBox(Geo.JAR_HOME, 0f).intersects(Geo.TANK))
    }

    @Test fun theWaterAndEveryCreatureStayInsideAtAllThreeTides() {
        for (tide in Tide.entries) {
            val t = Tank(seed = 11); t.restore(Saved(tide, Food.FLAKES, Kind.entries.map { Discovery(it, 0, tide, Food.FLAKES, Spot.OPEN) }))
            val water = Box(V3(-Geo.IN_X, Geo.IN_BOT, -Geo.IN_Z), V3(Geo.IN_X, t.level, Geo.IN_Z))
            assertTrue(water.inside(Geo.TANK))
            assertTrue(Box(V3(-0.01f, Geo.TAG_BOTTOM, Geo.TAG_Z - 0.001f), V3(0.01f, Geo.TAG_BOTTOM + Geo.TAG_H, Geo.TAG_Z + 0.001f)).inside(inner, margin))
        }
    }

    @Test fun everyCreatureIsBornInTheWater() {
        for (seed in 1..20) for (tide in Tide.entries) {
            val t = Tank(seed = seed); t.restore(Saved(tide, Food.FLAKES, Kind.entries.map { Discovery(it, 0, tide, Food.FLAKES, Spot.OPEN) }))
            repeat(15) { t.tick(1f / 30) }            // 0.5 s
            for (f in t.fish) if (f.kind != Kind.CRAB) assertTrue("seed $seed $tide ${f.kind} ${f.pos}", t.inWater(f.pos))
        }
    }

    @Test fun boidsNeverLeaveTheWaterInAThousandStepsEvenWhileTheTideMoves() {
        val t = Tank(seed = 5); t.restore(Saved(Tide.H, Food.FLAKES, Kind.entries.map { Discovery(it, 0, Tide.H, Food.FLAKES, Spot.OPEN) }))
        var worst = 1f
        for (i in 0 until 1000) {
            if (i % 150 == 0) t.setTide(Tide.entries[(i / 150) % 3], "test")
            if (i % 37 == 0) t.tapFeed(((i * 13) % 50 - 25) / 100f, ((i * 7) % 24 - 12) / 100f)
            t.tick(Tank.STEP)
            for (f in t.fish) if (f.kind != Kind.CRAB && f.alpha > 0f) {
                assertTrue("step $i ${f.kind}${f.idx} ${f.pos} level ${t.level}", t.inWater(f.pos))
                worst = minOf(worst, t.level - f.pos.y)
            }
        }
        assertTrue(worst >= 0f)
    }

    @Test fun calibratedModelsAreCentredFacingPlusXAndRealSize() {
        val want = mapOf("chromis" to (0 to 0.050), "clown" to (0 to 0.070), "tang" to (0 to 0.100), "crab" to (0 to 0.045), "seahorse" to (1 to 0.080), "jelly" to (0 to 0.080))
        for ((n, w) in want) {
            val m = models.getJSONObject(n); val lo = m.getJSONArray("lo"); val hi = m.getJSONArray("hi")
            val ext = (hi.getDouble(w.first) - lo.getDouble(w.first))
            assertEquals("$n size", w.second, ext, w.second * 0.1)
            if (n != "crab") for (k in 0..2) assertEquals("$n centre axis $k", 0.0, (lo.getDouble(k) + hi.getDouble(k)) / 2, 0.01)
            assertTrue("$n budget", m.getInt("tris") <= 4000)
        }
        // fish bodies are longest along X = the swim direction (head +X, checked on the previews in ui-audit)
        for (n in listOf("chromis", "clown", "tang")) {
            val m = models.getJSONObject(n); val lo = m.getJSONArray("lo"); val hi = m.getJSONArray("hi")
            assertTrue(n, hi.getDouble(0) - lo.getDouble(0) > hi.getDouble(2) - lo.getDouble(2))
        }
                // design budget.triangles: drawn total <= 60k (10 chromis share one mesh but each instance is drawn)
        val tri = { n: String -> models.getJSONObject(n).getInt("tris") }
        val drawn = models.keys().asSequence().sumOf { tri(it) } + 9 * tri("chromis") + tri("clown") + 2 * tri("grass")
        assertTrue("drawn triangles $drawn", drawn <= 60_000)
    }

    // ---------------------------------------------------------------- D35 / D41 sight lines (M6)
    private val glassOpaque: List<Pair<String, Box>> by lazy {
        // opaque things that could hide a target: the rock and the base; the tank walls are glass but the design asks tags /
        // marks / handle / jar never sit behind the tank box either
        listOf("rock" to Box(V3(-Geo.IN_X, Geo.IN_BOT, -Geo.IN_Z), V3(Geo.ROCK_TOE_X, 0.08f, Geo.IN_Z)), "base" to Geo.BASE, "tank" to Geo.TANK)
    }

    private fun visible(eye: V3, target: V3, except: Set<String> = emptySet()) = glassOpaque.filter { it.first !in except }.none { (_, b) -> b.hitBySegment(eye, target, 0.005f) }

    @Test fun tagsGaugeMarksHandleBoardAndPlaqueAreNotBehindTheTankFromAnyEye() {
        val targets = mutableListOf<Pair<String, V3>>()
        for (x in listOf(-0.23f, 0f, 0.23f)) for (dx in listOf(-0.08f, 0.08f)) for (dy in listOf(0f, Geo.TAG_H)) targets += "tag $x" to V3(x + dx, Geo.TAG_BOTTOM + dy, Geo.TAG_Z)
        for (t in Tide.entries) targets += "mark $t" to Geo.gaugeAt(t)
        for (t in Tide.entries) targets += "handle $t" to V3(Geo.POST_X, t.handleY, Geo.HANDLE_Z)
        targets += "sign" to Geo.SIGN_C
        targets += "jar" to Geo.JAR_HOME
        for (x in listOf(-0.26f, 0.26f)) for (y in listOf(Geo.PLAQUE.lo.y, Geo.PLAQUE.hi.y)) targets += "plaque" to V3(x, y, 0.181f)
        for (eye in Geo.EYES) for ((n, p) in targets) {
            val skip = if (n == "plaque") setOf("base") else emptySet()
            assertTrue("$n $p from $eye", visible(eye, p, skip))
        }
    }

    @Test fun theCarriedJarNeverHidesTheTankMiddleFromTheSeatedEye() {
        val eye = Geo.EYES[0]; val mid = V3(0f, -0.06f, 0f)
        for (i in 0..20) for (j in 0..8) {
            val c = V3(Geo.CARRY_X0 + (Geo.CARRY_X1 - Geo.CARRY_X0) * i / 20, Geo.CARRY_Y, -Geo.CARRY_Z + 2 * Geo.CARRY_Z * j / 8)
            assertFalse("jar at $c", jarBox(c, Geo.JAR_TILT_DEG).hitBySegment(eye, mid))
        }
    }

    @Test fun targetsAreBigEnoughAtTheDesignDistance() {
        // D17: >= 4 cm and >= 2 deg at the seated eye (0.85 m)
        for ((n, s) in listOf("jar" to Geo.JAR_HIT_W, "handle" to Geo.HANDLE_HIT_H, "sign" to 0.045f)) {
            val deg = Math.toDegrees(2 * atan2(s / 2.0, 0.85)).toFloat()
            assertTrue("$n ${s * 100} cm", s >= 0.04f); assertTrue("$n $deg deg", deg >= 2f)
        }
        // tag text height >= 0.5 deg at the seated eye (D13 3D words)
        assertTrue(Math.toDegrees(atan2(Geo.TAG_TEXT_H.toDouble(), 0.85)) >= 0.5)
        assertTrue(abs(Geo.tagWidth("Green chromis") - 0.163f) < 0.02f && Geo.tagWidth("Green chromis") <= Geo.TAG_MAX_W)
    }

    // ---------------------------------------------------------------- v0.1.3 Full Space (D54 主体撑满 / 门 C 改)
    @Test fun theTankIsAStageObjectBigInTheView() {
        // the manifest opens a Mixed Stage, not a volume (Shared Space volumes get no hand / controller poses)
        val manifest = File("src/main/AndroidManifest.xml").let { if (it.exists()) it else File("app/src/main/AndroidManifest.xml") }.readText()
        assertTrue(manifest.contains("pico.spatial.stage.id") && manifest.contains("pico.spatial.stage.style\" android:value=\"1\""))
        assertTrue("no volume container left", !manifest.contains("windowcontainer.style"))
        // D54: the tank spans >= 25 deg from the eye (placed TANK_DIST ahead): it is the subject, not a toy
        val eye = V3(0f, 1.6f, 0f)
        val root = ControlPlacement.placeTank(eye, V3(0f, 0f, -1f))!!
        val l = root.toStage(V3(-Geo.TANK_X, Geo.TANK_TOP, Geo.TANK_Z)); val r = root.toStage(V3(Geo.TANK_X, Geo.TANK_TOP, Geo.TANK_Z))
        val deg = Math.toDegrees(atan2(r.x - eye.x.toDouble(), (eye.z - r.z).toDouble()) - atan2(l.x - eye.x.toDouble(), (eye.z - l.z).toDouble())).toFloat()
        assertTrue("tank front $deg deg", deg >= 25f)
    }

    @Test fun everythingKeepsOnePhysicalCentimetreFromTheVolumeWalls() {
        // same boxes as above, measured in physical metres
        val vol = Box(V3(-Geo.phys(Geo.VOL_W) / 2, -Geo.phys(Geo.VOL_H) / 2, -Geo.phys(Geo.VOL_D) / 2), V3(Geo.phys(Geo.VOL_W) / 2, Geo.phys(Geo.VOL_H) / 2, Geo.phys(Geo.VOL_D) / 2))
        for ((n, b) in fixedBoxes() + Tide.entries.associate { "gauge $it" to Geo.gaugeAt(it).let { g -> Box.of(g.x, g.y, g.z, Geo.GAUGE_HIT_W, Geo.GAUGE_HIT_H, Geo.GAUGE_HIT_D) } }) {
            val p = Box(b.lo * Geo.SCALE, b.hi * Geo.SCALE)
            val lifted = if (b.lo.y <= Geo.VOLUME.lo.y + 1e-4f) Box(V3(p.lo.x, vol.lo.y + 0.0101f, p.lo.z), p.hi) else p
            assertTrue("$n $p", lifted.inside(vol, 0.01f))
        }
    }

    @Test fun gaugePlatesAndHandleAreTappableTargetsAtTheSystemDistance() {
        // D17: >= 4 cm and >= 2 deg from the eye (v0.1.3: the tank is placed TANK_DIST ahead; the far side of the post is
        // ~0.91 m away since v0.1.4 put the tank 0.88 m ahead, use 1.0 m), gaps >= 1.5 cm between the plates
        for ((n, s) in listOf("gauge w" to Geo.GAUGE_HIT_W, "gauge h" to Geo.GAUGE_HIT_H, "handle" to Geo.HANDLE_HIT_H, "jar" to Geo.JAR_HIT_W)) {
            val m = Geo.phys(s); val deg = Math.toDegrees(2 * atan2(m / 2.0, 1.0)).toFloat()
            assertTrue("$n ${m * 100} cm", m >= 0.04f); assertTrue("$n $deg deg", deg >= 2f)
        }
        val ys = Tide.entries.map { it.handleY }
        for (i in 1 until ys.size) assertTrue("gap", Geo.phys(ys[i] - ys[i - 1] - Geo.GAUGE_HIT_H) >= 0.015f)
        // a plate never sits inside the handle's collider depth (the handle is in front, the plate stays reachable)
        val handleBack = Geo.HANDLE_Z - Geo.HANDLE_HIT_D / 2; val plateFront = Geo.gaugeAt(Tide.M).z + Geo.GAUGE_HIT_D / 2
        assertTrue("$plateFront < $handleBack", plateFront <= handleBack + 1e-4f)
    }
}
