package io.embodit.tidetank.ui.tank

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.DrawOrderGroup
import com.pico.spatial.core.ecs.DrawOrderGroupComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.tidetank.domain.Creatures
import io.embodit.tidetank.domain.Food
import io.embodit.tidetank.domain.Geo
import io.embodit.tidetank.domain.Kind
import io.embodit.tidetank.domain.Language
import io.embodit.tidetank.domain.Particle
import io.embodit.tidetank.domain.Settings
import io.embodit.tidetank.domain.Tank
import io.embodit.tidetank.domain.Tide
import io.embodit.tidetank.platform.Graph
import io.embodit.tidetank.platform.TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!
internal fun Entity.at(x: Float, y: Float, z: Float) = transform().setPosition(Vector3(x, y, z))
internal fun Entity.walk(f: (Entity) -> Unit) { f(this); for (c in getChildren()) c.walk(f) }
internal fun rgb4(rgb: Int, a: Float = 1f) = Color4((rgb shr 16 and 255) / 255f, (rgb shr 8 and 255) / 255f, (rgb and 255) / 255f, a)
internal fun yawQ(rad: Float) = Quat(Vector3(0f, 1f, 0f), rad)
internal fun rad(deg: Float) = (deg * PI / 180).toFloat()

/** Hit holder names (one place; resolved by name for the board-input template and the hit-replay unit test). */
object Names {
    const val JAR = "jar"
    const val HANDLE = "handle"
    const val SIGN = "sign"
    const val ANEMONE = "floor:anemone"
    const val GRASS = "floor:grass"
    fun sand(i: Int) = "floor:sand:$i"
    fun rock(i: Int) = "floor:rock:$i"
    /** Targets: "jar", "handle", "sign", or "floor" (a tap there feeds at the hit point / cell centre). */
    fun target(name: String): String? = when {
        name == JAR || name == HANDLE || name == SIGN -> name
        name.startsWith("floor:") -> name
        else -> null
    }

    /** Sand cells: 4 x 3 over x -0.06..+0.294, z -0.144..+0.144 (design input.tapFeed). */
    const val SAND_NX = 4; const val SAND_NZ = 3
    fun sandCell(i: Int): Pair<Float, Float> {
        val cw = (Geo.IN_X - Geo.ROCK_TOE_X) / SAND_NX; val cd = 2 * Geo.IN_Z / SAND_NZ
        return (Geo.ROCK_TOE_X + cw * (i % SAND_NX + 0.5f)) to (-Geo.IN_Z + cd * (i / SAND_NX + 0.5f))
    }
    /** Rock steps (3 along the slope). */
    fun rockCell(i: Int): Triple<Float, Float, Float> {
        val x0 = -Geo.IN_X; val w = (Geo.ROCK_TOE_X - x0) / 3
        val cx = x0 + w * (i + 0.5f)
        return Triple(cx, Geo.rockY(cx), w)
    }
    /** Fallback landing point of a floor target (the hit point is used when the system gives it). */
    fun centreOf(target: String): Pair<Float, Float>? = when {
        target == ANEMONE -> Geo.ANEMONE.x to Geo.ANEMONE.z
        target == GRASS -> Geo.GRASS.x to Geo.GRASS.z
        target.startsWith("floor:sand:") -> target.removePrefix("floor:sand:").toIntOrNull()?.let { sandCell(it) }
        target.startsWith("floor:rock:") -> target.removePrefix("floor:rock:").toIntOrNull()?.let { rockCell(it).first to 0f }
        else -> null
    }
}

/**
 * Every entity of the volume (design spatialLayout.hierarchy):
 * ```
 * root (volume centre)
 * ├ tankRoot: base (walnut), plaque (brass words), glass x5, sand bed, rock / anemone / 3 grass tufts (Tripo), sea gate (deep board + grille)
 * ├ waterRoot: water body (scaled to the level)
 * ├ creatureRoot: 10 chromis, 2 clownfish, crab, seahorse, tang, jelly (Tripo, calibrated in tools/build_models.py)
 * ├ foodRoot: 120 particle slots (flake / pellet / shrimp child each), bubbles, the dashed drop column
 * ├ jarRoot: pedestal, "jar" holder (collider) -> jar body + lid (lid colour = food)
 * ├ tideRoot: post (Tripo), gauge marks, float, "handle" holder (brass ring + bar), "sign" holder (「面板」 board)
 * ├ tagRoot: one name tag per kind
 * └ keyLight
 * ```
 * Compose never drives motion: [onFrame] runs from the ECS system, ticks the tank and copies its state here.
 */
internal class TankScene(private val context: Context, private val graph: Graph, private val scope: CoroutineScope) {
    private val tank: Tank = graph.tank
    val root: Entity = Entity().apply { setName("root") }
    private val all = ArrayList<Resource>()
    private fun <T : Resource> T.keep(): T { toGlobal(); synchronized(all) { all += this }; return this }

    private var water: Entity? = null
    private var grille: Entity? = null
    private var grilleFaded = false
    private var gateBoard: Entity? = null
    private val fishEnt = HashMap<io.embodit.tidetank.domain.Fish, Entity>()
    private val fishFading = HashMap<Entity, Boolean>()
    private var seahorseMats = ArrayList<PhysicallyBasedMaterial>()
    private var seahorseTint = -1f
    private val slots = ArrayList<Array<Entity>>()
    private val bubbleEnts = ArrayList<Entity>()
    private val dashes = ArrayList<Entity>()
    private var jarHolder: Entity? = null
    private var jarModel: Entity? = null
    private var lidMats = ArrayList<PhysicallyBasedMaterial>()
    private var shownFood: Food? = null
    private var shownGlow = -1f
    private var handleHolder: Entity? = null
    private var floatEnt: Entity? = null
    private var anemone: Entity? = null
    private val grass = ArrayList<Entity>()
    private var plaqueEnt: Entity? = null
    private var plaqueMat: UnlitMaterial? = null
    private var plaqueShown: String? = null
    private val tagEnt = HashMap<Kind, Entity>()
    private val tagMat = HashMap<Kind, UnlitMaterial>()
    private val tagShown = HashMap<Kind, String>()
    private val tagFading = HashMap<Kind, Boolean>()
    private val gaugeMats = ArrayList<Pair<UnlitMaterial, Tide>>()
    private var signMat: UnlitMaterial? = null
    private var shownZh: Boolean? = null
    @Volatile var built = false; private set
    /** Physical metres per view px (drag scale, set by TankScreen). */
    @Volatile var metersPerPx = 0f
    @Volatile var testF = false
    private var frames = 0
    private var fpsT0 = SystemClock.elapsedRealtime()
    @Volatile var fps = 0f; private set
    private var startMs = SystemClock.elapsedRealtime()
    private val placementLogged = BooleanArray(3)

    private val texCache = HashMap<String, TextureResource>()
    private val texBusy = HashSet<String>()

    suspend fun build() {
        val t0 = SystemClock.elapsedRealtime()
        val io = Dispatchers.IO
        coroutineScope {
            val meshes = async(io) {
                mapOf(
                    "base" to Meshes.slab(0.64f, 0.04f, 0.34f, 1.5f), "shadow" to Meshes.slab(0.66f, 0.002f, 0.36f),
                    "glassFB" to Meshes.slab(0.60f, 0.36f, Geo.GLASS), "glassLR" to Meshes.slab(Geo.GLASS, 0.36f, 0.30f - 2 * Geo.GLASS),
                    "glassBot" to Meshes.slab(0.588f, Geo.GLASS, 0.288f),
                    "water" to Meshes.slab(2 * Geo.IN_X - 0.002f, 1f, 2 * Geo.IN_Z - 0.002f), "sand" to Meshes.slab(2 * Geo.IN_X, 0.024f, 2 * Geo.IN_Z, 1.5f),
                    "pedestal" to Meshes.slab(0.10f, 0.06f, 0.10f, 1.5f), "deep" to Meshes.slab(0.17f, 0.13f, 0.004f, 5.8f),
                    "bar" to Meshes.rod(0.0022f, 0.12f, 12), "barH" to Meshes.rod(0.0025f, 0.165f, 12),
                    "ring" to Meshes.torus(Geo.HANDLE_RING_R - 0.004f, 0.004f, sides = 16, seg = 64), "stem" to Meshes.rod(0.0035f, 0.03f, 12),
                    "chip" to Meshes.disc(0.0045f, 0f, 12), "pellet" to Meshes.bead(0.003f, 0.003f, 0.003f, 10, 6),
                    "shrimp" to Meshes.shrimp(), "bubble" to Meshes.bead(0.0018f, 0.0018f, 0.0018f, 8, 6), "dash" to Meshes.rod(0.0012f, 0.012f, 8),
                    "float" to Meshes.bead(0.006f, 0.006f, 0.006f), "quad" to Meshes.quad(1f, 1f, leftAnchored = false),
                    "sign" to Meshes.slab(0.08f, 0.045f, 0.012f, 1.5f),
                ).mapValues { (k, m) -> m.create(k).keep() }
            }
            val shapes = async(io) {
                val phys = PhysicsMaterialResource().keep()
                phys to mapOf(
                    "jar" to ShapeResource.createBox(Vector3(Geo.JAR_HIT_W, Geo.JAR_HIT_H, Geo.JAR_HIT_W)).keep(),
                    "handle" to ShapeResource.createBox(Vector3(Geo.HANDLE_HIT_W, Geo.HANDLE_HIT_H, Geo.HANDLE_HIT_D)).keep(),
                    "sign" to ShapeResource.createBox(Vector3(0.08f, 0.045f, 0.03f)).keep(),
                    "sand" to ShapeResource.createBox(Vector3((Geo.IN_X - Geo.ROCK_TOE_X) / Names.SAND_NX, 0.01f, 2 * Geo.IN_Z / Names.SAND_NZ)).keep(),
                    "rock" to ShapeResource.createBox(Vector3((Geo.ROCK_TOE_X + Geo.IN_X) / 3, 0.02f, 2 * Geo.IN_Z)).keep(),
                    "anemone" to ShapeResource.createBox(Vector3(0.09f, 0.07f, 0.09f)).keep(),
                    "grass" to ShapeResource.createBox(Vector3(0.14f, 0.11f, 0.12f)).keep(),
                )
            }
            val textures = async(io) {
                fun tex(name: String) = runCatching { context.assets.open("textures/$name").use { BitmapFactory.decodeStream(it) }.let { TextureResource.create(it).keep() } }
                    .onFailure { Log.w(TAG, "TEX $name: $it") }.getOrNull()
                mapOf("walnut" to tex("walnut.jpg"), "sand" to tex("sand.jpg"), "deep" to tex("deep.jpg"))
            }
            val glbNames = listOf("chromis", "clown", "tang", "crab", "seahorse", "jelly", "rock", "anemone", "grass", "jar_body", "jar_lid", "post")
            val glbs = glbNames.associateWith { n -> async { runCatching { Entity.loadSuspend("asset://models/$n.glb") }.onFailure { Log.e(TAG, "MODEL $n", it) }.getOrNull() } }
            awaitAll(meshes, shapes, textures)
            val ms = meshes.await(); val (phys, sh) = shapes.await(); val tx = textures.await()

            fun pbr(rgb: Int, rough: Float, metal: Float = 0f, tex: TextureResource? = null) = PhysicallyBasedMaterial.create().apply {
                setBaseColor(rgb4(rgb)); setRoughness(rough); setMetallic(metal); tex?.let { setBaseColorTexture(it) }
            }.keep()
            fun clear(rgb: Int, a: Float, rough: Float) = PhysicallyBasedMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(rgb4(rgb, a)); setRoughness(rough); setMetallic(0f); setCullingMode(MaterialCullingMode.NONE); setDepthWrite(false)
            }.keep()
            fun unlitText() = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply { setBaseColor(Color4(1f, 1f, 1f, 1f)); setCullingMode(MaterialCullingMode.NONE); setApplyToneMapping(false) }.keep()
            fun model(mesh: String, mat: Material, name: String, parent: Entity, x: Float, y: Float, z: Float) =
                ModelEntity(ms.getValue(mesh), mat).apply { setName(name); parent.addChild(this); at(x, y, z) }
            fun hit(name: String, shape: ShapeResource, parent: Entity, x: Float, y: Float, z: Float, hover: Boolean = true) = Entity().apply {
                setName(name)
                components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = phys))
                components.set(InteractableComponent())
                if (hover) components.set(HoverEffectComponent())
                parent.addChild(this); at(x, y, z)
            }

            val walnut = pbr(0xFFFFFF, 0.7f, tex = tx["walnut"])
            val brass = pbr(0xC9A24E, 0.32f, 0.85f)
            // ---- tank root: base, contact shadow, glass, sand, gate
            val tankRoot = Entity().apply { setName("tankRoot"); root.addChild(this) }
            model("shadow", pbr(0x1A1612, 1f), "shadow", tankRoot, 0f, -0.279f, 0f)
            model("base", walnut, "base", tankRoot, 0f, -0.26f, 0f)
            val order = DrawOrderGroup.create()
            val glass = clear(0xDDF2F6, 0.10f, 0.04f)
            // design: back glass, then the water, then the front glass (DrawOrderGroup keeps the see-through layers in order)
            model("glassFB", glass, "glassBack", tankRoot, 0f, -0.06f, -Geo.TANK_Z + Geo.GLASS / 2).components.set(DrawOrderGroupComponent(order, 0))
            model("glassLR", glass, "glassLeft", tankRoot, -Geo.TANK_X + Geo.GLASS / 2, -0.06f, 0f).components.set(DrawOrderGroupComponent(order, 1))
            model("glassLR", glass, "glassRight", tankRoot, Geo.TANK_X - Geo.GLASS / 2, -0.06f, 0f).components.set(DrawOrderGroupComponent(order, 1))
            model("glassBot", glass, "glassBottom", tankRoot, 0f, Geo.TANK_BOT + Geo.GLASS / 2, 0f).components.set(DrawOrderGroupComponent(order, 1))
            model("glassFB", glass, "glassFront", tankRoot, 0f, -0.06f, Geo.TANK_Z - Geo.GLASS / 2).components.set(DrawOrderGroupComponent(order, 4))
            model("sand", pbr(0xFFFFFF, 0.95f, tex = tx["sand"]), "sandBed", tankRoot, 0f, Geo.SAND_Y - 0.012f, 0f)
            gateBoard = model("deep", pbr(0xFFFFFF, 0.9f, tex = tx["deep"]), "seaBoard", tankRoot, (Geo.GATE_X0 + Geo.GATE_X1) / 2, (Geo.GATE_Y0 + Geo.GATE_Y1) / 2, -Geo.TANK_Z - 0.01f)
            val gr = Entity().apply { setName("grille"); tankRoot.addChild(this); at(0f, 0f, -Geo.IN_Z + 0.006f) }
            for (i in 0..7) model("bar", brass, "grilleBar$i", gr, Geo.GATE_X0 + 0.01f + i * 0.02f, Geo.GATE_Y0, 0f)
            for (yy in listOf(Geo.GATE_Y0 + 0.002f, Geo.GATE_Y1 - 0.002f)) model("barH", brass, "grilleRail", gr, Geo.GATE_X0 - 0.0025f, yy, 0f).transform().setQuaternion(Quat(Vector3(0f, 0f, 1f), rad(-90f)))
            grille = gr

            // ---- water (scaled to the level each frame; no collider: rays pass to the bottom, R1-7)
            water = model("water", clear(0x1E8FA0, 0.18f, 0.08f), "water", Entity().apply { setName("waterRoot"); root.addChild(this) }, 0f, 0f, 0f).also {
                it.components.set(DrawOrderGroupComponent(order, 2))
            }

            // ---- floor targets (taps feed there; R1-7 / R2-9)
            val floor = Entity().apply { setName("floorTargets"); root.addChild(this) }
            for (i in 0 until Names.SAND_NX * Names.SAND_NZ) { val (x, z) = Names.sandCell(i); hit(Names.sand(i), sh.getValue("sand"), floor, x, Geo.SAND_Y - 0.004f, z, hover = false) }
            for (i in 0 until 3) {
                val (x, y, _) = Names.rockCell(i)
                hit(Names.rock(i), sh.getValue("rock"), floor, x, y - 0.012f, 0f, hover = false).transform().setQuaternion(Quat(Vector3(0f, 0f, 1f), -kotlin.math.atan(1.24f)))
            }
            anemone = glbs.getValue("anemone").await()?.apply { setName("anemoneModel") }
            hit(Names.ANEMONE, sh.getValue("anemone"), floor, Geo.ANEMONE.x, Geo.ANEMONE.y + 0.035f, Geo.ANEMONE.z, hover = false).also { h -> anemone?.let { h.addChild(it); it.at(0f, -0.035f, 0f) } }
            val grassHit = hit(Names.GRASS, sh.getValue("grass"), floor, Geo.GRASS.x, Geo.GRASS.y + 0.055f, Geo.GRASS.z, hover = false)
            glbs.getValue("grass").await()?.let { g0 ->
                Geo.GRASS_TUFTS.forEachIndexed { i, p ->
                    val g = if (i == 0) g0 else g0.clone(Entity.CloneOptions(true, true)) ?: return@forEachIndexed
                    g.setName("grass$i"); grassHit.addChild(g); g.at(p.x - Geo.GRASS.x, -0.055f, p.z - Geo.GRASS.z)
                    g.transform().setQuaternion(yawQ(rad(i * 115f)))
                    grass += g
                }
            }
            glbs.getValue("rock").await()?.let { r -> r.setName("rockModel"); tankRoot.addChild(r); r.at((-Geo.IN_X + Geo.ROCK_TOE_X) / 2 - 0.001f, Geo.IN_BOT, 0f) }

            // ---- creatures
            val creatureRoot = Entity().apply { setName("creatureRoot"); root.addChild(this) }
            val loaded = HashMap<Kind, Entity?>()
            loaded[Kind.CHROMIS] = glbs.getValue("chromis").await(); loaded[Kind.CLOWN] = glbs.getValue("clown").await()
            loaded[Kind.TANG] = glbs.getValue("tang").await(); loaded[Kind.CRAB] = glbs.getValue("crab").await()
            loaded[Kind.SEAHORSE] = glbs.getValue("seahorse").await(); loaded[Kind.JELLY] = glbs.getValue("jelly").await()
            val used = HashSet<Kind>()
            for (f in tank.fish) {
                val src = loaded[f.kind] ?: continue
                val e = if (used.add(f.kind)) src else src.clone(Entity.CloneOptions(true, true)) ?: continue
                val holder = Entity().apply { setName("fish:${f.kind.key}:${f.idx}"); creatureRoot.addChild(this) }
                holder.addChild(e)
                holder.enabled = false
                fishEnt[f] = holder
            }
            loaded[Kind.SEAHORSE]?.walk { n -> n.components[ModelComponent::class.java]?.materials?.forEach { m -> (m as? PhysicallyBasedMaterial)?.let { seahorseMats += it } } }

            // ---- food pool, bubbles, drop column
            val foodRoot = Entity().apply { setName("foodRoot"); root.addChild(this) }
            val flakeMat = pbr(0x7A8A3A, 0.8f); val pelletMat = pbr(0xB0602A, 0.6f); val shrimpMat = pbr(0xF0907A, 0.5f)
            val leftMat = pbr(0x6B5A3C, 0.9f)
            for (i in 0 until Tank.POOL) {
                val holder = Entity().apply { setName("p$i"); foodRoot.addChild(this); enabled = false }
                val a = ModelEntity(ms.getValue("chip"), flakeMat).apply { setName("f"); holder.addChild(this) }
                val b = ModelEntity(ms.getValue("pellet"), pelletMat).apply { setName("q"); holder.addChild(this) }
                val c = ModelEntity(ms.getValue("shrimp"), shrimpMat).apply { setName("s"); holder.addChild(this); transform().setScaleVector(Vector3(1.5f, 1.5f, 1.5f)) }
                val d = ModelEntity(ms.getValue("pellet"), leftMat).apply { setName("l"); holder.addChild(this); transform().setScaleVector(Vector3(0.8f, 0.5f, 0.8f)) }
                slots += arrayOf(holder, a, b, c, d)
            }
            val bubbleMat = clear(0xE8FBFF, 0.55f, 0.1f)
            repeat(30) { bubbleEnts += ModelEntity(ms.getValue("bubble"), bubbleMat).apply { setName("bubble$it"); foodRoot.addChild(this); enabled = false } }
            val dashMat = UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(rgb4(0xF6F1E0)); setApplyToneMapping(false) }.keep()
            repeat(12) { dashes += ModelEntity(ms.getValue("dash"), dashMat).apply { setName("dash$it"); foodRoot.addChild(this); enabled = false } }

            // ---- jar on its pedestal
            val jarRoot = Entity().apply { setName("jarRoot"); root.addChild(this) }
            model("pedestal", walnut, "pedestal", jarRoot, Geo.PEDESTAL.centre.x, Geo.PEDESTAL.centre.y, Geo.PEDESTAL.centre.z)
            val jh = hit(Names.JAR, sh.getValue("jar"), jarRoot, Geo.JAR_HOME.x, Geo.JAR_HOME.y, Geo.JAR_HOME.z)
            val jm = Entity().apply { setName("jarModel"); jh.addChild(this); at(0f, Geo.JAR_BOTTOM_OFF, 0f) }
            glbs.getValue("jar_body").await()?.let { it.setName("jarBody"); jm.addChild(it) }
            glbs.getValue("jar_lid").await()?.let { lid ->
                lid.setName("jarLid"); jm.addChild(lid)
                lid.walk { n -> n.components[ModelComponent::class.java]?.materials?.forEach { m -> (m as? PhysicallyBasedMaterial)?.let { lidMats += it } } }
            }
            jarHolder = jh; jarModel = jm

            // ---- tide post, gauge marks, float, handle, sign
            val tideRoot = Entity().apply { setName("tideRoot"); root.addChild(this) }
            glbs.getValue("post").await()?.let { p -> p.setName("postModel"); tideRoot.addChild(p); p.at(Geo.POST_X, Geo.POST_BOT, Geo.POST_Z) }
            for (t in Tide.entries) {
                val m = unlitText()
                gaugeMats += m to t
                model("quad", m, "gauge:${t.name}", tideRoot, Geo.POST_X - 0.046f, t.handleY, Geo.POST_Z + 0.03f).transform().setScaleVector(Vector3(0.034f, 0.022f, 1f))
            }
            floatEnt = model("float", pbr(0xC0392B, 0.4f), "gaugeFloat", tideRoot, Geo.POST_X + 0.04f, gaugeY(tank.level), Geo.POST_Z + 0.01f)
            val hh = hit(Names.HANDLE, sh.getValue("handle"), tideRoot, Geo.POST_X, tank.handleY, Geo.HANDLE_Z)
            model("ring", brass, "handleRing", hh, 0f, 0f, 0f).transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), rad(90f)))
            model("stem", brass, "handleStem", hh, 0f, Geo.HANDLE_RING_R - 0.002f, -0.03f).transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), rad(90f)))
            handleHolder = hh
            val sg = hit(Names.SIGN, sh.getValue("sign"), tideRoot, Geo.SIGN_C.x, Geo.SIGN_C.y, Geo.SIGN_C.z)
            model("sign", walnut, "signBoard", sg, 0f, 0f, 0f)
            signMat = unlitText()
            model("quad", signMat!!, "signWords", sg, 0f, 0f, 0.0065f).transform().setScaleVector(Vector3(0.074f, 0.039f, 1f))

            // ---- plaque and name tags
            plaqueMat = unlitText()
            plaqueEnt = model("quad", plaqueMat!!, "plaque", tankRoot, 0f, Geo.PLAQUE.centre.y, 0.1705f).also { it.transform().setScaleVector(Vector3(Geo.PLAQUE_W, Geo.PLAQUE_H, 1f)) }
            val tagRoot = Entity().apply { setName("tagRoot"); root.addChild(this) }
            for (k in Kind.entries) { val m = unlitText(); tagMat[k] = m; tagEnt[k] = model("quad", m, "tag:${k.key}", tagRoot, 0f, Geo.TAG_BOTTOM + Geo.TAG_H / 2, Geo.TAG_Z).apply { enabled = false } }

            buildLight()
        }
        built = true
        startMs = SystemClock.elapsedRealtime()
        android.os.Handler(android.os.Looper.getMainLooper()).post { graph.sceneReady = true }
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms fish=${fishEnt.size} slots=${slots.size}")
        prewarm()
    }

    /** Gauge float height for a water level (L / M / H marks line up with the handle marks). */
    private fun gaugeY(level: Float): Float {
        val l = Tide.L; val m = Tide.M; val h = Tide.H
        return if (level <= m.level) l.handleY + (level - l.level) / (m.level - l.level) * (m.handleY - l.handleY)
        else m.handleY + (level - m.level) / (h.level - m.level) * (h.handleY - m.handleY)
    }

    /** Key light (design lighting: warm, from the upper left front). Intensity is lux-scale (build.md 2026-11-12). */
    private fun buildLight() {
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            light.components.set(DirectionalLightComponent(Color4(1f, 0xEE / 255f, 0xD8 / 255f, 1f), LIGHT_LUX, true))
            val q = Quat(Vector3(0f, 1f, 0f), rad(-30f)).times(Quat(Vector3(1f, 0f, 0f), rad(-55f)))
            light.transform().setQuaternion(q)
            root.addChild(light)
        }.onFailure { Log.w(TAG, "PROBE light unavailable: $it") }
    }

    fun isZh(s: Settings): Boolean = when (s.language) {
        Language.ZH -> true
        Language.EN -> false
        Language.SYSTEM -> Locale.getDefault().language == "zh"
    }

    /** Pre-renders the fixed words of the current language off the main thread. */
    private fun prewarm() {
        val zh = isZh(graph.settings.value)
        scope.launch(Dispatchers.IO) {
            for (k in Kind.entries) texture(Creatures.of(k).name.get(zh), 't')
            texture(tankPlaque(zh), 'p')
        }
    }

    private fun tankPlaque(zh: Boolean) = synchronized(tank.lock) { tank.say.plaque.get(zh) }

    /** Cached texture; null while it is being made on IO. kind: 't' tag, 'p' plaque, 'g' gauge mark, 's' sign. */
    private fun texture(text: String, kind: Char): TextureResource? {
        val k = "$text|$kind|$testF"
        synchronized(texCache) { texCache[k]?.let { return it }; if (!texBusy.add(k)) return null }
        val make = {
            val bmp = when {
                testF -> TextTex.fTest(0.12f, 0.03f)
                kind == 't' -> Geo.tagWidth(text).let { w -> TextTex.tag(text, w, Geo.TAG_H, Geo.TAG_TEXT_H) }
                kind == 'p' -> TextTex.plate(text, Geo.PLAQUE_W, Geo.PLAQUE_H, 0.020f)
                kind == 'g' -> text.split("|").let { TextTex.plate(it[0], 0.034f, 0.022f, 0.012f, it.getOrNull(1)) }
                else -> text.split("|").let { TextTex.plate(it[0], 0.074f, 0.039f, 0.018f, it.getOrNull(1)) }
            }
            val t = runCatching { TextureResource.create(bmp).keep() }.onFailure { Log.w(TAG, "TEX $text: $it") }.getOrNull()
            synchronized(texCache) { texBusy.remove(k); if (t != null) texCache[k] = t }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { scope.launch(Dispatchers.IO) { make() }; return null }
        make()
        return synchronized(texCache) { texCache[k] }
    }

    fun applySettings(s: Settings) {
        if (built) { prewarm(); shownZh = null; plaqueShown = null; tagShown.clear() }
    }

    // ------------------------------------------------------------------ per frame
    fun onFrame(dt: Float) {
        if (!built) return
        frames++
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - fpsT0 >= 5000) { fps = frames * 1000f / (nowMs - fpsT0); Log.i(TAG, "PERF fps=${"%.1f".format(fps)} state=${tank.state} particles=${tank.particles.count { it.alive }}"); frames = 0; fpsT0 = nowMs }
        if (graph.loaded) tank.tick(dt)
        val zh = isZh(graph.settings.value)
        val plaqueText: String
        synchronized(tank.lock) {
            val a = tank.alphaStep
            // water body and gauge
            water?.let { w ->
                val h = tank.level - Geo.IN_BOT
                w.at(0f, Geo.IN_BOT + h / 2, 0f); w.transform().setScaleVector(Vector3(1f, h, 1f))
            }
            floatEnt?.at(Geo.POST_X + 0.04f, gaugeY(tank.level), Geo.POST_Z + 0.01f)
            handleHolder?.at(Geo.POST_X, tank.handleY, Geo.HANDLE_Z)
            // grille: fades out (open) / in (closed), never moves (design seaGate)
            grille?.let { g ->
                val o = tank.grille
                g.enabled = o > 0.01f
                if (o < 0.999f && o > 0.01f) { g.components.set(OpacityControllerComponent(o.coerceIn(0.02f, 1f))); grilleFaded = true }
                else if (grilleFaded) { g.components.remove(OpacityControllerComponent::class.java); grilleFaded = false }
            }
            // creatures
            for ((f, e) in fishEnt) {
                val vis = f.alpha > 0.01f
                if (e.enabled != vis) e.enabled = vis
                if (!vis) continue
                val p = f.prev + (f.pos - f.prev) * a
                e.at(p.x, p.y, p.z)
                val wiggle = if (f.kind == Kind.CHROMIS || f.kind == Kind.CLOWN || f.kind == Kind.TANG) rad(6f) * sin(f.swimT) else 0f
                var q = yawQ(f.yaw + wiggle)
                if (f.kind == Kind.SEAHORSE) q = q.times(Quat(Vector3(0f, 0f, 1f), rad(-f.lean)))
                if (f.kind == Kind.JELLY) {
                    val s = 1f + 0.06f * sin(tank.now * 2 * PI.toFloat())
                    e.transform().setScaleVector(Vector3(s, 2f - s, s))
                }
                e.transform().setQuaternion(q)
                val fading = f.alpha < 0.999f
                if (fading) { e.components.set(OpacityControllerComponent(f.alpha.coerceIn(0.02f, 1f))); fishFading[e] = true }
                else if (fishFading[e] == true) { e.components.remove(OpacityControllerComponent::class.java); fishFading[e] = false }
            }
            // the seahorse is the colour of the grass until found
            val tint = if (tank.isFound(Kind.SEAHORSE)) 1f else 0f
            if (tint != seahorseTint) {
                seahorseTint = tint
                val c = if (tint > 0.5f) Color4(1f, 1f, 1f, 1f) else Color4(0.55f, 0.72f, 0.38f, 1f)
                seahorseMats.forEach { runCatching { it.setBaseColor(c) } }
            }
            // food
            for (p in tank.particles) {
                val s = slots[p.id]
                if (s[0].enabled != p.alive) s[0].enabled = p.alive
                if (!p.alive) continue
                s[0].at(p.pos.x, p.pos.y, p.pos.z)
                val left = p.stage == Particle.Stage.LEFT
                s[1].enabled = !left && p.food == Food.FLAKES
                s[2].enabled = !left && p.food == Food.PELLETS
                s[3].enabled = !left && p.food == Food.SHRIMP
                s[4].enabled = left
                if (p.food == Food.SHRIMP && !left) s[3].transform().setQuaternion(yawQ(p.id * 1.7f + sin(tank.now * 8f + p.id) * 0.6f))
            }
            for ((i, b) in bubbleEnts.withIndex()) {
                val d = tank.bubbles.getOrNull(i)
                val on = d != null && d[3] >= 0f
                if (b.enabled != on) b.enabled = on
                if (on) b.at(d!![0], d[1], d[2])
            }
            // jar
            jarHolder?.let { j ->
                val jp = tank.jar
                val shake = if (tank.jarShake > 0f) 0.004f * sin(tank.jarShake * 60f) else 0f
                j.at(jp.x + shake, jp.y, jp.z)
                // tips toward the tank's back-left: the mouth points down and a little forward (design 110 deg)
                jarModel?.transform()?.setQuaternion(Quat(Vector3(1f, 0f, 0f), rad(-tank.jarTilt)))
                jarModel?.at(0f, Geo.JAR_BOTTOM_OFF * kotlin.math.cos(rad(tank.jarTilt)), -Geo.JAR_BOTTOM_OFF * sin(rad(tank.jarTilt)))
            }
            if (tank.food != shownFood) {
                shownFood = tank.food
                val c = when (tank.food) { Food.FLAKES -> rgb4(0x3FA34D); Food.PELLETS -> rgb4(0xE07B24); Food.SHRIMP -> rgb4(0xE86FA0) }
                lidMats.forEach { runCatching { it.setBaseColor(c) } }
            }
            val glow = (tank.jarGlow * 10).toInt() / 10f
            if (glow != shownGlow) { shownGlow = glow; val k = glow * 0.6f; lidMats.forEach { runCatching { it.setEmissiveColor(Color4(0.60f * k, 0.94f * k, 0.66f * k, 1f)) } } }
            // the dashed drop column under a tipped jar
            val pouring = tank.jarTilt > 90f && Geo.overWater(tank.jar.x, tank.jar.z)
            val top = tank.jar.y - 0.04f; val bot = tank.level
            for ((i, d) in dashes.withIndex()) {
                val y = top - i * 0.024f
                val on = pouring && y - 0.012f > bot
                if (d.enabled != on) d.enabled = on
                if (on) d.at(tank.jar.x, y - 0.012f, tank.jar.z)
            }
            // plants sway (whole model, design: no rig)
            anemone?.transform()?.setQuaternion(Quat(Vector3(0f, 0f, 1f), rad(3f) * sin(tank.now * 0.8f)))
            for ((i, g) in grass.withIndex()) g.transform().setQuaternion(yawQ(rad(i * 115f)).times(Quat(Vector3(1f, 0f, 0f), rad(4f) * sin(tank.now * 0.7f + i))))
            plaqueText = tank.say.plaque.get(zh)
            // name tags (design tags + R1-6: dodge the carried jar)
            for (k in Kind.entries) {
                val e = tagEnt[k] ?: continue
                val tg = tank.tags.firstOrNull { it.kind == k }
                val f = tank.fish.firstOrNull { it.kind == k && it.alpha > 0.5f }
                if (tg == null || f == null) { if (e.enabled) e.enabled = false; continue }
                val text = Creatures.of(k).name.get(zh)
                val w = Geo.tagWidth(text)
                val x = tank.tagX(f.pos.x, w / 2)
                if (x == null) { if (e.enabled) e.enabled = false; continue }
                if (tagShown[k] != text) { val t = texture(text, 't') ?: continue; tagMat[k]?.setBaseColorTexture(t); tagShown[k] = text }
                e.enabled = true
                e.at(x, Geo.TAG_BOTTOM + Geo.TAG_H / 2, Geo.TAG_Z)
                e.transform().setScaleVector(Vector3(w, Geo.TAG_H, 1f))
                val al = (tg.t / 0.5f + 1f).coerceIn(0f, 1f)
                if (al < 0.999f) { e.components.set(OpacityControllerComponent(al.coerceIn(0.02f, 1f))); tagFading[k] = true }
                else if (tagFading[k] == true) { e.components.remove(OpacityControllerComponent::class.java); tagFading[k] = false }
            }
        }
        if (plaqueText != plaqueShown) texture(plaqueText, 'p')?.let { plaqueMat?.setBaseColorTexture(it); plaqueShown = plaqueText; Log.i(TAG, "PLAQUE \"$plaqueText\"") }
        if (shownZh != zh) {
            var ok = true
            for ((m, t) in gaugeMats) {
                val label = when (t) { Tide.L -> if (zh) "落|LOW" else "LOW|落"; Tide.M -> if (zh) "平|MID" else "MID|平"; Tide.H -> if (zh) "涨|HIGH" else "HIGH|涨" }
                texture(label, 'g')?.let { m.setBaseColorTexture(it) } ?: run { ok = false }
            }
            texture(if (zh) "面板|Panel" else "Panel|面板", 's')?.let { signMat?.setBaseColorTexture(it) } ?: run { ok = false }
            if (ok) shownZh = zh
        }
        placementLog(nowMs)
    }

    /** Start-up check (design spawnSafety / M9): at 1 / 3 / 5 s, every creature's position, in the water or not, shown. */
    private fun placementLog(nowMs: Long) {
        val t = (nowMs - startMs) / 1000f
        for ((i, s) in listOf(1f, 3f, 5f).withIndex()) {
            if (t >= s && !placementLogged[i]) {
                placementLogged[i] = true
                synchronized(tank.lock) {
                    val rows = tank.fish.joinToString(" ") { f -> "${f.kind.key}${f.idx}=(%.3f,%.3f,%.3f)%s%s".format(f.pos.x, f.pos.y, f.pos.z, if (tank.inWater(f.pos) || f.kind == Kind.CRAB) "" else "!OUT", if (f.shown) "" else "~hidden") }
                    Log.i(TAG, "PLACEMENT t=${s.toInt()}s level=%.3f inWater=%d/%d %s".format(tank.level, tank.fish.count { tank.inWater(it.pos) || it.kind == Kind.CRAB }, tank.fish.size, rows))
                }
            }
        }
    }

    fun refreshText() { plaqueShown = null; tagShown.clear(); shownZh = null }

    fun dispose() {
        synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() }
    }

    companion object {
        const val LIGHT_LUX = 1600f
        @Suppress("unused") private fun nearly(a: Float, b: Float) = abs(a - b) < 1e-4f
        @Suppress("unused") private val unusedMesh: MeshResource? = null
    }
}
