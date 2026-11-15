package io.embodit.tidetank.ui.tank

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.tidetank.domain.Geo
import io.embodit.tidetank.domain.V3
import io.embodit.tidetank.platform.Graph
import io.embodit.tidetank.platform.TAG
import io.embodit.tidetank.ui.tank.input.BoardInputConfig
import io.embodit.tidetank.ui.tank.input.BoardInputListener
import io.embodit.tidetank.ui.tank.input.BoardInputLog
import io.embodit.tidetank.ui.tank.input.InputSource
import io.embodit.tidetank.ui.tank.input.Offset3M
import io.embodit.tidetank.ui.tank.input.TargetResolver
import io.embodit.tidetank.ui.tank.input.boardInput
import kotlin.math.hypot

/** Runs the volume's per-frame motion inside the ECS (never in Compose). */
class TankFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.scene?.onFrame(context.deltaTime)
    }
}

internal object SceneHolder {
    @Volatile var scene: TankScene? = null
}

/**
 * The tank volume (default container). Opens the control window beside it, shows the tank and routes the jar, the
 * handle, the 「面板」 board and the tank bottom through the factory board-input template (controller ray, hand ray and
 * eye-hand alike, D50; system pointer only, no head pose, D1).
 */
@Composable
fun TankScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val scene = remember { TankScene(context.applicationContext, graph, scope) }
    val listener = remember(graph, scene) { TankListener(graph, scene) }
    val converter = com.pico.spatial.ui.platform.LocalPhysicalLengthConverter.current
    scene.metersPerPx = with(converter) { converter.dpToLength(1f.toDp(), com.pico.spatial.ui.platform.LengthUnit.Meters) }

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.open("start")
        onDispose { graph.window.detach() }
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> graph.onForeground()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    Log.i(TAG, "LIFECYCLE resume panel=${graph.window.panelState}")
                    if (graph.window.panelState == "ON_DESTROY") graph.window.open("resume")
                }
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> graph.window.onVolumeLifecycle("ON_DESTROY")
                androidx.lifecycle.Lifecycle.Event.ON_CREATE -> graph.window.onVolumeLifecycle("ON_CREATE")
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        Active.listener = listener
        registerSystem<TankFrameSystem>()
        onDispose {
            unregisterSystem<TankFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            if (Active.listener === listener) Active.listener = null
            scene.dispose()
        }
    }

    LaunchedEffect(settings) { scene.applySettings(settings) }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .boardInput(remember { TargetResolver.byName(Names::target) }, listener, BoardInputConfig(dragStartM = 0.012f, logTag = TAG)),
        initial = { content, _ ->
            listener.content = content
            content.addEntity(scene.root)
            scene.build()
        },
    )
}

/** For the debug broadcast (debug builds): the live listener. */
internal object Active {
    @Volatile var listener: TankListener? = null
}

/**
 * 3D input -> tank (design input). Drag mapping (design frame.assumptions fallback: the drag carries no ray here): the
 * jar follows the hand on its carry plane, left / right = x, up / toward = away from you (z = start + dZ - dY), scaled by
 * the view's own content-metres per physical metre at the press so a system-scaled volume maps the same. The handle only
 * follows vertically. A tap on the bottom feeds at the converted hit point (cell centre when the system gives none).
 */
internal class TankListener(private val graph: Graph, private val scene: TankScene) : BoardInputListener<String> {
    @Volatile var content: SpatialViewContent? = null
    private var scale = 1f
    private var drag: String? = null
    private var jarFrom = V3.ZERO

    /** The view's local px -> volume metres (root frame). */
    private fun toVolume(position: Offset3D): Vector3? = content?.let { c ->
        runCatching { c.convertPosition(Vector3(position.x, position.y, position.z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace) }.getOrNull()
            // the scene root is drawn Geo.SCALE x (q1-2): content metres -> tank units
            ?.let { Vector3(Geo.units(it.x), Geo.units(it.y), Geo.units(it.z)) }
    }

    override fun onPress(target: String?, source: InputSource, position: Offset3D) {
        val c = content ?: return
        runCatching {
            val p = c.convertPosition(Vector3(position.x, position.y, position.z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
            val q = c.convertPosition(Vector3(position.x + 1000f, position.y, position.z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
            val perPxEntity = hypot(q.x - p.x, hypot(q.y - p.y, q.z - p.z)) / 1000f
            val perPxPhysical = scene.metersPerPx
            // physical hand metres -> content metres (system resize) -> tank units (root drawn Geo.SCALE x, q1-2)
            scale = dragScale(perPxEntity, perPxPhysical)
            BoardInputLog.write(TAG, "INPUT press src=${source.label} target=$target at=(${"%.3f".format(p.x)}, ${"%.3f".format(p.y)}, ${"%.3f".format(p.z)}) scale=${"%.2f".format(scale)}")
        }.onFailure { Log.w(TAG, "INPUT press convert failed: $it") }
    }

    override fun onTap(target: String?, source: InputSource, position: Offset3D?) {
        BoardInputLog.write(TAG, "INPUT tap src=${source.label} target=$target")
        route(target, position?.let { toVolume(it) }?.let { it.x to it.z })
    }

    /** Same path for real taps, replayed hits and the debug broadcast. */
    fun route(target: String?, hitXZ: Pair<Float, Float>?) {
        when {
            target == null -> Unit
            target == Names.JAR -> graph.tank.nextFood("jar-tap")
            target == Names.SIGN -> graph.window.recall("sign")
            target == Names.HANDLE -> graph.tank.handleTap()
            Names.tideOf(target) != null -> graph.tank.gaugeTap(Names.tideOf(target)!!)
            target.startsWith("floor:") -> {
                val (x, z) = hitXZ ?: Names.centreOf(target) ?: return
                graph.tank.tapFeed(x, z, "tap:$target")
            }
        }
    }

    // gate:allow shared-drag 门 B 用户通过的设计包选定 Shared Space 桌面体积缸（container.why，「实现站不得改」）；点缸底撒食 + 面板潮位分段是同一件事的快捷路径（D43）
    override fun onDragStart(target: String, source: InputSource): Boolean {
        drag = when (target) {
            Names.JAR -> if (graph.tank.jarGrab()) Names.JAR else null
            Names.HANDLE -> if (graph.tank.handleGrab()) Names.HANDLE else null
            else -> null
        }
        jarFrom = V3(Geo.JAR_HOME.x, 0f, Geo.JAR_HOME.z)
        BoardInputLog.write(TAG, "INPUT drag start src=${source.label} target=$target taken=${drag != null}")
        return drag != null
    }

    override fun onDragMove(totalM: Offset3M) {
        when (drag) {
            Names.JAR -> graph.tank.jarMove(jarFrom.x + totalM.x * scale * JAR_GAIN, jarFrom.z + (totalM.z - totalM.y) * scale * JAR_GAIN)
            Names.HANDLE -> graph.tank.handleMove(totalM.y * scale)
        }
    }

    override fun onDragEnd() = end()

    override fun onDragCancel() = end()

    private fun end() {
        when (drag) {
            Names.JAR -> graph.tank.jarRelease()
            Names.HANDLE -> graph.tank.handleRelease()
        }
        drag = null
    }

    /** Hover is drawn by the system (HoverEffectComponent on the jar, handle, gauge plates and board); nothing in the game changes. */
    override fun onHover(target: String?, source: InputSource) {}

    /** Debug: a real drag through the same calls ([pts] = jar targets x, z or handle travel). */
    fun injectJar(pts: List<Pair<Float, Float>>, release: Boolean) {
        if (!graph.tank.jarGrab()) return
        pts.forEach { (x, z) -> graph.tank.jarMove(x, z) }
        if (release) graph.tank.jarRelease()
    }

    companion object {
        /** The jar travels 0.65 m; a comfortable hand sweep is ~0.4 m: a small gain (1.5x) on the hand travel. */
        const val JAR_GAIN = 1.5f

        /** Hand travel (physical m) -> tank units: x content/physical per px, / the root scale. */
        fun dragScale(perPxEntity: Float, perPxPhysical: Float): Float =
            (if (perPxPhysical > 0f) (perPxEntity / perPxPhysical).coerceIn(0.3f, 3f) else 1f) / Geo.SCALE
    }
}
