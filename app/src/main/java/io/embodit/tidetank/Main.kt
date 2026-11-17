package io.embodit.tidetank

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
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
 * v0.1.3 (门 C 改, profile D53 补): Full Space. One Mixed Stage (passthrough) holds the tank, placed yaw-only in front of
 * the head with the rim under the eye (ControlPlacement.placeTank), so the jar can be touched and follow a hand /
 * controller (Shared Space volumes get no hand / controller poses: tracking providers answer NOT_IN_FULL_SPACE). The
 * control window is one Planar WindowContainer 600 x 440 dp (fixed: min = max) with the native TabBar, opened from the
 * Stage through the level gate; the system puts it straight ahead and the tank sits under it. Moving / closing it is
 * the system's (D21).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            TankTheme { TankScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = ControlPlacement.WINDOW_W_DP.dp, height = ControlPlacement.WINDOW_H_DP.dp),
        ) {
            TankTheme {
                Box(Modifier.windowConstraints(minWidth = ControlPlacement.WINDOW_W_DP.dp, minHeight = ControlPlacement.WINDOW_H_DP.dp, maxWidth = ControlPlacement.WINDOW_W_DP.dp, maxHeight = ControlPlacement.WINDOW_H_DP.dp)) { Startup.mark("ControlWindow compose"); ControlWindow() }
            }
        }
    }
