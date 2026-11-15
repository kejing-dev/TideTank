package io.embodit.tidetank.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.tidetank.domain.Language
import io.embodit.tidetank.platform.Graph
import java.util.Locale

/** Deep sea teal + brass (design appIcon #0E5E6F); text roles measured on the window glass (D13, ui-audit contrast table). */
object TankColors {
    /** Deep sea teal: primary buttons and the selected segment (white text 7.3:1). */
    val Primary = Color(0xFF0E5E6F)
    val OnPrimary = Color(0xFFFFFFFF)
    /** Pale sea card behind the status line and the guide cards (ink text 13:1). */
    val Card = Color(0xFFE3F1F2)
    val Brass = Color(0xFF8A6420)
    val Error = Color(0xFFB3261E)
    val OnError = Color(0xFFFFFFFF)
    val Ink = Color(0xFF12232A)
    /** Secondary / small text on the window glass (>= 4.5:1 on the darkest measured glass #B4B3B1). */
    val InkSecondary = Color(0xFF34444B)
    val Silhouette = Color(0xFF5B6E75)
}

/**
 * PicoTheme with all 16 roles assigned (Cloud Air pattern). The language setting localises resources below this
 * point through a ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun TankTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = system.fillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = TankColors.OnPrimary,
        labelPrimary = TankColors.Ink,
        labelSecondary = TankColors.InkSecondary,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = TankColors.Error,
        alert = TankColors.Brass,
        passable = TankColors.Brass,
        interaction = TankColors.Primary,
        dividerLine = system.dividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
