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
import io.embodit.tidetank.platform.Startup
import io.embodit.tidetank.platform.TAG
import io.embodit.tidetank.ui.Containers
import io.embodit.tidetank.ui.tank.input.BoardInputConfig
import io.embodit.tidetank.ui.tank.input.BoardInputListener
import io.embodit.tidetank.ui.tank.input.BoardInputLog
import io.embodit.tidetank.ui.tank.input.InputSource
import io.embodit.tidetank.ui.tank.input.Offset3M
import io.embodit.tidetank.ui.tank.input.TargetResolver
import io.embodit.tidetank.ui.tank.input.boardInput
import kotlin.math.hypot
import kotlinx.coroutines.launch

/** Runs the Stage's per-frame input (StageRig) and motion (TankScene) inside the ECS (never in Compose). */
class TankFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        Active.rig?.frame(context.deltaTime)
        SceneHolder.scene?.onFrame(context.deltaTime)
    }
}

internal object SceneHolder {
    @Volatile var scene: TankScene? = null
}

/**
 * v0.1.3 Full Space (门 C 改, D53 补): the Mixed Stage. One full-size SpatialView holds the tank (placed yaw-only in front
 * of the head by StageRig); the system pointer (eye-hand, hand ray, controller ray) reaches the jar, the handle, the gauge
 * plates, the 「面板」 board and the tank bottom through the factory board-input template (D50); controllers and hands are
 * read per frame by StageRig (the jar toggle). The control window is opened from here through the level gate.
 */
@Composable
fun TankScreen() {
    val context = LocalContext.current
    Startup.mark("TankScreen compose")
    val graph = remember(context) { Graph.from(context) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val scene = remember { TankScene(context.applicationContext, graph, scope) }
    val rig = remember(scene) { StageRig(graph, scene) }
    val listener = remember(graph, scene) { TankListener(graph, scene, rig) }
    val converter = com.pico.spatial.ui.platform.LocalPhysicalLengthConverter.current
    scene.metersPerPx = with(converter) { converter.dpToLength(1f.toDp(), com.pico.spatial.ui.platform.LengthUnit.Meters) }
    // D41 更正: while a controller holds the jar, hide the Stage's controller / upper-limb rendering (the only SDK knob,
    // clay-forge); Default again when the jar goes home and when the Stage goes
    val limb = com.pico.spatial.ui.platform.LocalStageUpperLimbRenderModelManager.current

    DisposableEffect(navigator) {
        graph.window.attach(
            open = { navigator.openWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG, android.os.Bundle()) },
            close = { navigator.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) },
            closeStage = { graph.scope.launch { runCatching { navigator.closeStage() }.onFailure { Log.w(TAG, "EXIT closeStage: $it") } } },
        )
        graph.window.open("start")
        onDispose { graph.window.detach() }
    }

    LaunchedEffect(rig.holdingByController) {
        val hidden = rig.holdingByController
        val r = runCatching { limb.setUpperLimbRenderMode(if (hidden) com.pico.spatial.ui.platform.ability.UpperLimbRenderMode.Hidden else com.pico.spatial.ui.platform.ability.UpperLimbRenderMode.Default) }
        Log.i(TAG, "LIMB ${if (hidden) "Hidden why=jar on a controller" else "Default why=jar home"} ok=${r.isSuccess}")
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.onBackground(); rig.stop() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { graph.onForeground(); rig.start() }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    Log.i(TAG, "LIFECYCLE resume panel=${graph.window.panelState}")
                    if (graph.window.panelState == "ON_DESTROY") graph.window.open("resume")
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        Active.listener = listener
        Active.rig = rig
        rig.start()
        registerSystem<TankFrameSystem>()
        onDispose {
            unregisterSystem<TankFrameSystem>()
            rig.stop()
            runCatching { limb.setUpperLimbRenderMode(com.pico.spatial.ui.platform.ability.UpperLimbRenderMode.Default) }
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            if (Active.listener === listener) Active.listener = null
            if (Active.rig === rig) Active.rig = null
            scene.dispose()
        }
    }

    LaunchedEffect(settings) { scene.applySettings(settings) }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .boardInput(remember { TargetResolver.byName(Names::target) }, listener, BoardInputConfig(dragStartM = 0.012f, logTag = TAG)),
        initial = { content, _ ->
            Startup.mark("SpatialView initial")
            listener.content = content
            content.addEntity(scene.root)
            scene.build()
        },
    )
}

/** For the debug broadcast (debug builds): the live listener. */
internal object Active {
    @Volatile var listener: TankListener? = null
    @Volatile var rig: StageRig? = null
}

/**
 * System pointer input -> tank (design input): a tap on the jar = the jar toggle (JarInput.systemTap, D53 补); the handle
 * follows a drag vertically; a tap on the bottom feeds at the converted hit point (cell centre when the system gives none).
 */
internal class TankListener(private val graph: Graph, private val scene: TankScene, private val rig: StageRig? = null) : BoardInputListener<String> {
    @Volatile var content: SpatialViewContent? = null
    private var scale = 1f
    private var drag: String? = null

    /** The view's local px -> Stage metres -> tank units (the root's pose, StageRig). */
    private fun toVolume(position: Offset3D): Vector3? = content?.let { c ->
        runCatching { c.convertPosition(Vector3(position.x, position.y, position.z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace) }.getOrNull()
            ?.let { w -> val u = (scene.pose ?: return@let null).toTank(V3(w.x, w.y, w.z)); Vector3(u.x, u.y, u.z) }
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
        route(target, position?.let { toVolume(it) }?.let { it.x to it.z }, source.label)
    }

    /** Same path for real taps, replayed hits and the debug broadcast. A tap on the jar is the jar toggle (D53 补). */
    fun route(target: String?, hitXZ: Pair<Float, Float>?, source: String = "debug") {
        when {
            target == null -> Unit
            target == Names.JAR -> (rig?.jar ?: return).systemTap(android.os.SystemClock.elapsedRealtime(), source)
            target == Names.SIGN -> graph.window.recall("sign")
            target == Names.HANDLE -> graph.tank.handleTap()
            Names.tideOf(target) != null -> graph.tank.gaugeTap(Names.tideOf(target)!!)
            target.startsWith("floor:") -> {
                val (x, z) = hitXZ ?: Names.centreOf(target) ?: return
                graph.tank.tapFeed(x, z, "tap:$target")
            }
        }
    }

    /**
     * Full Space (v0.1.3): the brass handle is dragged by the system pointer (vertical only). The jar is no drag any more
     * (D53 补): a drag that starts on it is not taken, so the template ends it as one tap (DRAG->TAP) = one toggle press.
     */
    override fun onDragStart(target: String, source: InputSource): Boolean {
        drag = when (target) {
            Names.HANDLE -> if (graph.tank.handleGrab()) Names.HANDLE else null
            else -> null
        }
        BoardInputLog.write(TAG, "INPUT drag start src=${source.label} target=$target taken=${drag != null}")
        return drag != null
    }

    override fun onDragMove(totalM: Offset3M) {
        when (drag) {
            Names.HANDLE -> graph.tank.handleMove(totalM.y * scale)
        }
    }

    override fun onDragEnd() = end()

    override fun onDragCancel() = end()

    private fun end() {
        when (drag) {
            Names.HANDLE -> graph.tank.handleRelease()
        }
        drag = null
    }

    /** Hover is drawn by the system (HoverEffectComponent on the jar, handle, gauge plates and board); nothing in the game changes. */
    override fun onHover(target: String?, source: InputSource) {}

    companion object {
        /** Hand travel (physical m) -> tank units: x content/physical per px, / the root scale. */
        fun dragScale(perPxEntity: Float, perPxPhysical: Float): Float =
            (if (perPxPhysical > 0f) (perPxEntity / perPxPhysical).coerceIn(0.3f, 3f) else 1f) / Geo.SCALE
    }
}
