package io.embodit.tidetank

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.tidetank.domain.ControlPlacement
import io.embodit.tidetank.platform.Startup
import io.embodit.tidetank.ui.Containers
import io.embodit.tidetank.ui.control.ControlWindow
import io.embodit.tidetank.ui.tank.TankScreen
import io.embodit.tidetank.ui.theme.TankTheme

/**
 * Two surfaces in Shared Space (design windowModel volume_plus_control_window): the tank volume (default container,
 * 1.19 x 0.784 x 0.644 m from the manifest = 0.85 x 0.56 x 0.46 tank units x Geo.SCALE) and the control window (Planar 600 x 440 dp since v0.1.2, fixed: min = max, R2-5)
 * below it (v0.1.2, q2-1: Placement.right pushed it out of the default view). Every open uses the same placement (D44:
 * Shared Space never promises "in front of you").
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            TankTheme { TankScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = ControlPlacement.WINDOW_W_DP.dp, height = ControlPlacement.WINDOW_H_DP.dp),
            // q2-1: one placement for every open path (ControlPlacement, unit-tested): below the volume, centred under the tank
            placement = {
                containers.firstOrNull { it.id == Containers.VOLUME }?.let {
                    when (ControlPlacement.side) {
                        ControlPlacement.Side.BOTTOM -> Placement.bottom(it, ControlPlacement.offsetDp.dp)
                        ControlPlacement.Side.RIGHT -> Placement.right(it, ControlPlacement.offsetDp.dp)
                        ControlPlacement.Side.LEFT -> Placement.left(it, ControlPlacement.offsetDp.dp)
                    }
                } ?: Placement.none()
            },
        ) {
            TankTheme {
                Box(Modifier.windowConstraints(minWidth = ControlPlacement.WINDOW_W_DP.dp, minHeight = ControlPlacement.WINDOW_H_DP.dp, maxWidth = ControlPlacement.WINDOW_W_DP.dp, maxHeight = ControlPlacement.WINDOW_H_DP.dp)) { Startup.mark("ControlWindow compose"); ControlWindow() }
            }
        }
    }
