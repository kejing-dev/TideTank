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
import io.embodit.tidetank.ui.Containers
import io.embodit.tidetank.ui.control.ControlWindow
import io.embodit.tidetank.ui.tank.TankScreen
import io.embodit.tidetank.ui.theme.TankTheme

/**
 * Two surfaces in Shared Space (design windowModel volume_plus_control_window): the tank volume (default container,
 * 1.19 x 0.784 x 0.644 m from the manifest = 0.85 x 0.56 x 0.46 tank units x Geo.SCALE) and the control window (Planar 600 x 600 dp, fixed: min = max, R2-5) to its
 * right. Every open uses Placement.right(volume) (D44: Shared Space never promises "in front of you").
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            TankTheme { TankScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 600.dp, height = 600.dp),
            placement = { containers.firstOrNull { it.id == Containers.VOLUME }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            TankTheme {
                Box(Modifier.windowConstraints(minWidth = 600.dp, minHeight = 600.dp, maxWidth = 600.dp, maxHeight = 600.dp)) { ControlWindow() }
            }
        }
    }
