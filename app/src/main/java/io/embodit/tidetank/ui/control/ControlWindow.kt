package io.embodit.tidetank.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.tidetank.BuildConfig
import io.embodit.tidetank.R
import io.embodit.tidetank.domain.Creatures
import io.embodit.tidetank.domain.Food
import io.embodit.tidetank.domain.Kind
import io.embodit.tidetank.domain.Language
import io.embodit.tidetank.domain.Say
import io.embodit.tidetank.domain.Settings
import io.embodit.tidetank.domain.Tide
import io.embodit.tidetank.platform.Graph
import io.embodit.tidetank.platform.TAG
import io.embodit.tidetank.platform.TankUi
import io.embodit.tidetank.ui.Confirm
import io.embodit.tidetank.ui.Page
import io.embodit.tidetank.ui.Ui
import io.embodit.tidetank.ui.theme.TankColors
import java.util.Locale
import kotlin.system.exitProcess

/**
 * The control window (Planar 600 x 600 dp fixed, Placement.right(volume); design panels.control): three pages switched
 * only by the native SpatialUI TabBar (D8): 水缸 / 图鉴 / 设置. The how-to card (S01), the all-found card (S09), the guide
 * detail (S08b) and the confirm rows (S11) replace content in place; no page scrolls; every page is a centred column so
 * the blank stays <= 25 % (D44). Buttons 64 dp with words (B19 / B31), rows >= 48 dp, gaps >= 16 dp.
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val ui by graph.ui.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val nav = LocalSpatialNavigator.current
    DisposableEffect(owner, nav) {
        graph.window.attachPanel(nav)
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Ui.page, Ui.howTo, Ui.detail, Ui.result) {
        val id = when {
            !ui.loaded || !graph.sceneReady -> "S00"
            Ui.page == Page.TANK && Ui.howTo -> "S01"
            Ui.page == Page.TANK && Ui.result -> "S09"
            Ui.page == Page.GUIDE && Ui.detail != null -> "S08b"
            else -> Ui.page.stateId
        }
        Log.i(TAG, "PAGE ${Ui.page} $id")
        if (Ui.page != Page.GUIDE) Ui.detail = null
        if (Ui.page != Page.SETTINGS) Ui.confirm = null
    }
    val zh = isZh(settings)

    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp)) {
        when {
            !ui.loaded || !graph.sceneReady -> Loading()
            Ui.page == Page.TANK && Ui.howTo -> HowToCard(graph)
            Ui.page == Page.TANK && Ui.result -> ResultCard(ui, settings, graph)
            Ui.page == Page.TANK -> TankPage(ui, settings, graph, zh)
            Ui.page == Page.GUIDE -> Ui.detail?.let { DetailPage(it, ui, graph, zh) } ?: GuidePage(ui, zh)
            else -> SettingsPage(settings, graph, context)
        }
    }

    // The native TabBar is its own system window: resolve the labels here, in the app-language context, and pass plain
    // strings in (ui_gates tabbar-lang; an English cold start shows English tabs).
    val tabs = listOf(
        Triple(Page.TANK, R.drawable.ic_waves, stringResource(R.string.tab_tank)),
        Triple(Page.GUIDE, R.drawable.ic_menu_book, stringResource(R.string.tab_guide)),
        Triple(Page.SETTINGS, R.drawable.ic_settings, stringResource(R.string.tab_settings)),
    )
    NativeTabs(tabs)
}

fun isZh(s: Settings): Boolean = when (s.language) {
    Language.ZH -> true
    Language.EN -> false
    Language.SYSTEM -> Locale.getDefault().language == "zh"
}

@Composable
private fun NativeTabs(tabs: List<Triple<Page, Int, String>>) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page; Ui.howTo = false },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = label) },
                supportContent = { Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelMedium) },
            )
        }
    }
}

/**
 * A centred page column. Logs `BLANK <page> content=<dp> blank=<%>` (blank = window height outside the content block, top +
 * bottom, over the 600 dp window; build.md 空白的量法) so the ui-audit table comes from the real layout of every state.
 */
@Composable
private fun PageColumn(gap: Int = 20, forId: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val id = forId ?: pageId()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().onGloballyPositioned { c ->
                val h = with(density) { c.size.height.toDp().value }
                Log.i(TAG, "BLANK $id content=%.0f blank=%.1f%%".format(h, (WINDOW_DP - h) / WINDOW_DP * 100))
            },
            verticalArrangement = Arrangement.spacedBy(gap.dp), content = content,
        )
    }
}

private const val WINDOW_DP = 600f

private fun pageId(): String = when {
    Ui.page == Page.TANK && Ui.howTo -> "S01"
    Ui.page == Page.TANK && Ui.result -> "S09"
    Ui.page == Page.GUIDE && Ui.detail != null -> "S08b"
    Ui.page == Page.SETTINGS && Ui.confirm != null -> "S11-${Ui.confirm}"
    else -> Ui.page.stateId
}

/** S00 (design: spinner + 「在放水……」): the how-to picture and lines are already readable while the tank fills (D44 blank). */
@Composable
private fun Loading() {
    PageColumn(16, "S00") {
        Image(painterResource(R.drawable.howto_scene), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(196.dp).clip(RoundedCornerShape(20.dp)))
        HowRow(R.drawable.ic_touch, stringResource(R.string.how_to_feed), stringResource(R.string.how_to_feed_ways), "s00/feed")
        HowRow(R.drawable.ic_swap_vert, stringResource(R.string.how_to_tide), stringResource(R.string.how_to_tide_ways), "s00/tide")
        HowRow(R.drawable.ic_menu_book, stringResource(R.string.how_to_find), stringResource(R.string.how_to_find_ways), "s00/find")
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator()
            Spacer(Modifier.width(16.dp))
            Txt(stringResource(R.string.s00), "s00/line", style = PicoTheme.typography.titleMedium, color = TankColors.Ink)
        }
    }
}

/** Row 1: one sentence, what to do now / what just happened / a clue (design panels.pages.tank). */
@Composable
private fun StatusCard(say: Say, zh: Boolean) {
    Box(Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(20.dp)).background(TankColors.Card).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
        Txt(say.panel.get(zh), "tank/line", style = PicoTheme.typography.titleMedium, color = TankColors.Ink, maxLines = 2)
    }
}

@Composable
private fun TankPage(ui: TankUi, settings: Settings, graph: Graph, zh: Boolean) {
    PageColumn {
        StatusCard(ui.say, zh)
        // gate:allow button-gap 小标题和它下面的分段控件是一组（不是并排按钮）
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(stringResource(R.string.tide), "tank/tide-label", style = PicoTheme.typography.labelLarge, color = TankColors.InkSecondary)
            Segments(listOf(Tide.L to stringResource(R.string.tide_l), Tide.M to stringResource(R.string.tide_m), Tide.H to stringResource(R.string.tide_h)),
                ui.tide, "tank/tide", onSelect = { graph.tank.setTide(it, "panel") }, modifier = Modifier.fillMaxWidth(), height = 56.dp)
        }
        // gate:allow button-gap 小标题和它下面的分段控件是一组（不是并排按钮）
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(stringResource(R.string.food), "tank/food-label", style = PicoTheme.typography.labelLarge, color = TankColors.InkSecondary)
            Segments(listOf(Food.FLAKES to stringResource(R.string.food_flakes), Food.PELLETS to stringResource(R.string.food_pellets), Food.SHRIMP to stringResource(R.string.food_shrimp)),
                ui.food, "tank/food", onSelect = { graph.tank.setFood(it, "panel") }, modifier = Modifier.fillMaxWidth(), height = 56.dp)
        }
        // row 4: guide progress + the latest card (tap = guide page)
        Row(
            Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(20.dp)).background(TankColors.Card).clickable { Ui.page = Page.GUIDE; Ui.detail = ui.latest }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(stringResource(R.string.guide_count, ui.discovered.size), "tank/guide-count", style = PicoTheme.typography.titleMedium, color = TankColors.Ink)
            Spacer(Modifier.weight(1f))
            val k = ui.latest
            if (k != null) {
                Thumb(k, true, Modifier.size(56.dp))
                Spacer(Modifier.width(12.dp))
                Txt(Creatures.of(k).name.get(zh), "tank/guide-latest", style = PicoTheme.typography.labelLarge, color = TankColors.Ink)
            } else Txt(stringResource(R.string.guide_none_yet), "tank/guide-none", style = PicoTheme.typography.labelLarge, color = TankColors.InkSecondary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton(stringResource(R.string.btn_how), "tank/how", onClick = { Ui.howTo = true }, icon = R.drawable.ic_help, modifier = Modifier.weight(1f))
            MuteButton(settings, graph, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MuteButton(settings: Settings, graph: Graph, modifier: Modifier) {
    ToggleButton(stringResource(R.string.btn_mute), "mute", on = settings.muted, icon = if (settings.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
        onClick = { graph.settings.update { it.copy(sound = !it.sound) }; Log.i(TAG, "MUTE ${settings.sound}") }, modifier = modifier)
}

/** S01 / [怎么玩]: picture + three lines (each: what to do, how with a hand / a controller) + [知道了] (B19 how_to_*). */
@Composable
private fun HowToCard(graph: Graph) {
    PageColumn(16) {
        Image(painterResource(R.drawable.howto_scene), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(196.dp).clip(RoundedCornerShape(20.dp)))
        HowRow(R.drawable.ic_touch, stringResource(R.string.how_to_feed), stringResource(R.string.how_to_feed_ways), "howto/feed")
        HowRow(R.drawable.ic_swap_vert, stringResource(R.string.how_to_tide), stringResource(R.string.how_to_tide_ways), "howto/tide")
        HowRow(R.drawable.ic_menu_book, stringResource(R.string.how_to_find), stringResource(R.string.how_to_find_ways), "howto/find")
        PrimaryButton(stringResource(R.string.btn_got_it), "howto/ok", onClick = { Ui.howTo = false; graph.finishFirstRun() }, icon = R.drawable.ic_check, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun HowRow(icon: Int, line: String, ways: String, tag: String) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp), tint = TankColors.Primary)
        Spacer(Modifier.width(14.dp))
        Column {
            Txt(line, "$tag/line", style = PicoTheme.typography.labelLarge, color = TankColors.Ink)
            Txt(ways, "$tag/ways", style = PicoTheme.typography.bodySmall, color = TankColors.InkSecondary)
        }
    }
}

/** S09: everything found (design panels.pages.result). */
@Composable
private fun ResultCard(ui: TankUi, settings: Settings, graph: Graph) {
    PageColumn(20) {
        Txt(stringResource(R.string.guide_all), "result/title", style = PicoTheme.typography.headlineSmall, color = TankColors.Ink, align = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth())
        Txt(stringResource(R.string.result_sub), "result/sub", style = PicoTheme.typography.bodyMedium, color = TankColors.InkSecondary, align = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth())
        // six cards, 3 x 2 (picture + name), so the card fills the window (blank <= 25 %, D44)
        for (row in Kind.entries.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (k in row) Column(Modifier.weight(1f).height(100.dp).clip(RoundedCornerShape(16.dp)).background(TankColors.Card).padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Thumb(k, k in ui.discovered, Modifier.weight(1f).fillMaxWidth())
                    Txt(Creatures.of(k).name.get(isZh(settings)), "result/name-${k.key}", style = PicoTheme.typography.labelMedium, color = TankColors.Ink)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PrimaryButton(stringResource(R.string.btn_keep), "result/keep", onClick = { Ui.result = false }, icon = R.drawable.ic_water_drop, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_reset), "result/reset", onClick = { Ui.result = false; Ui.page = Page.SETTINGS; Ui.confirm = Confirm.RESET }, icon = R.drawable.ic_refresh, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton(stringResource(R.string.btn_how), "result/how", onClick = { Ui.result = false; Ui.howTo = true }, icon = R.drawable.ic_help, modifier = Modifier.weight(1f))
            MuteButton(settings, graph, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Thumb(k: Kind, seen: Boolean, modifier: Modifier) {
    Image(painterResource(thumbOf(k)), contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier,
        colorFilter = if (seen) null else ColorFilter.tint(TankColors.Silhouette, BlendMode.SrcIn))
}

private fun thumbOf(k: Kind) = when (k) {
    Kind.CHROMIS -> R.drawable.guide_chromis; Kind.CLOWN -> R.drawable.guide_clownfish; Kind.CRAB -> R.drawable.guide_hermitcrab
    Kind.SEAHORSE -> R.drawable.guide_seahorse; Kind.TANG -> R.drawable.guide_bluetang; Kind.JELLY -> R.drawable.guide_moonjelly
}

/** S08: the guide, 3 x 2 cards (picture / silhouette, name, how you met). */
@Composable
private fun GuidePage(ui: TankUi, zh: Boolean) {
    PageColumn(16) {
        Txt(if (ui.discovered.size == 6) stringResource(R.string.guide_all) else stringResource(R.string.guide_count, ui.discovered.size), "guide/title",
            style = PicoTheme.typography.titleLarge, color = TankColors.Ink, modifier = Modifier.height(40.dp))
        for (row in Kind.entries.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (k in row) GuideCard(k, ui.discovered[k], zh, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GuideCard(k: Kind, d: io.embodit.tidetank.domain.Discovery?, zh: Boolean, modifier: Modifier) {
    Column(
        modifier.height(200.dp).clip(RoundedCornerShape(20.dp)).background(TankColors.Card).clickable { Ui.detail = k }.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Thumb(k, d != null, Modifier.height(100.dp).fillMaxWidth())
        if (d != null) {
            Txt(Creatures.of(k).name.get(zh), "guide/name-${k.key}", style = PicoTheme.typography.labelLarge, color = TankColors.Ink, align = TextAlign.Center, maxLines = 2)
            Txt(Say.how(d, zh), "guide/how-${k.key}", style = PicoTheme.typography.bodySmall, color = TankColors.InkSecondary, align = TextAlign.Center, maxLines = 2)
        } else {
            Txt(stringResource(R.string.not_seen), "guide/unseen-${k.key}", style = PicoTheme.typography.labelLarge, color = TankColors.InkSecondary, align = TextAlign.Center)
        }
    }
}

/** S08b: guide detail in place (design guideDetail): seen = name, latin name, how you met, fun fact; unseen = silhouette + clue. */
@Composable
private fun DetailPage(k: Kind, ui: TankUi, graph: Graph, zh: Boolean) {
    val d = ui.discovered[k]
    val info = Creatures.of(k)
    // a seen creature that is in the tank gets its name tag for 3 s (design S08b)
    LaunchedEffect(k, d != null) { if (d != null) graph.tank.showTagFromGuide(k) }
    PageColumn(12) {
        Box(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(20.dp)).background(TankColors.Card).padding(12.dp), contentAlignment = Alignment.Center) {
            Thumb(k, d != null, Modifier.fillMaxSize())
        }
        if (d != null) {
            Txt(info.name.get(zh) + "  ·  " + info.name.get(!zh), "detail/name", style = PicoTheme.typography.titleLarge, color = TankColors.Ink)
            Txt(info.sci, "detail/sci", style = PicoTheme.typography.bodySmall, color = TankColors.InkSecondary)
            Txt(stringResource(R.string.how_label) + "：" + Say.how(d, zh), "detail/how", style = PicoTheme.typography.labelLarge, color = TankColors.Ink)
            Txt(info.fact.get(zh), "detail/fact", style = PicoTheme.typography.bodyMedium, color = TankColors.Ink, maxLines = 2)
            if (k !in ui.present) Txt(stringResource(R.string.not_here), "detail/not-here", style = PicoTheme.typography.bodySmall, color = TankColors.InkSecondary)
            Txt(stringResource(R.string.fact_source), "detail/source", style = PicoTheme.typography.bodySmall, color = TankColors.InkSecondary)
        } else {
            Txt(stringResource(R.string.not_seen), "detail/unseen", style = PicoTheme.typography.titleLarge, color = TankColors.Ink)
            Txt(stringResource(R.string.clue_label) + "：" + info.clue.get(zh), "detail/clue", style = PicoTheme.typography.bodyLarge, color = TankColors.Ink, maxLines = 3)
        }
        SecondaryButton(stringResource(R.string.btn_back), "detail/back", onClick = { Ui.detail = null }, icon = R.drawable.ic_arrow_back, modifier = Modifier.fillMaxWidth())
    }
}

/** S10 settings (design settings: 7 rows, [退出应用] on the first screen) + S11 in-place confirm rows. */
@Composable
private fun SettingsPage(settings: Settings, graph: Graph, context: Context) {
    PageColumn(12) {
        OptionRow(stringResource(R.string.set_language), "settings/lang", labelWidth = 100.dp) {
            Segments(listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)),
                settings.language, "settings/lang", onSelect = { l -> graph.settings.update { it.copy(language = l) }; Log.i(TAG, "LANG $l") }, modifier = Modifier.weight(1f), height = 56.dp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.weight(1f)) { SwitchRow(stringResource(R.string.set_sound), "settings/sound", settings.sound, { v -> graph.settings.update { it.copy(sound = v) } }) }
            Box(Modifier.weight(1f)) { SwitchRow(stringResource(R.string.set_pump), "settings/pump", settings.pump, { v -> graph.settings.update { it.copy(pump = v) } }) }
        }
        OptionRow(stringResource(R.string.set_volume), "settings/volume", labelWidth = 100.dp) {
            VolumeSlider(settings.volume, onChange = { v -> graph.settings.update { it.copy(volume = v) } }, modifier = Modifier.weight(1f))
        }
        SwitchRow(stringResource(R.string.set_reduced), "settings/reduced", settings.reducedMotion, { v -> graph.settings.update { it.copy(reducedMotion = v) } })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton(stringResource(R.string.how_to_settings), "settings/how", onClick = { Ui.page = Page.TANK; Ui.howTo = true }, icon = R.drawable.ic_help, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_reopen), "settings/reopen", onClick = { graph.window.reopen() }, icon = R.drawable.ic_open_in_new, modifier = Modifier.weight(1f))
        }
        Txt(stringResource(R.string.reopen_note), "settings/reopen-note", style = PicoTheme.typography.bodySmall, color = TankColors.InkSecondary)
        when (Ui.confirm) {
            Confirm.QUIT -> ConfirmRow(stringResource(R.string.confirm_exit), stringResource(R.string.btn_confirm_exit), R.drawable.ic_logout, "confirm/exit") { exitApp(context, graph) }
            Confirm.RESET -> ConfirmRow(stringResource(R.string.confirm_reset), stringResource(R.string.btn_confirm_reset), R.drawable.ic_refresh, "confirm/reset") {
                graph.resetTank(); Ui.confirm = null; Ui.page = Page.TANK; Ui.result = false
            }
            null -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(stringResource(R.string.btn_reset), "settings/reset", onClick = { Ui.confirm = Confirm.RESET }, icon = R.drawable.ic_refresh, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_exit), "settings/exit", onClick = { Ui.confirm = Confirm.QUIT }, danger = true, icon = R.drawable.ic_logout, modifier = Modifier.weight(1f))
            }
        }
        Txt(stringResource(R.string.about, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.bodySmall, color = TankColors.InkSecondary)
    }
}

@Composable
private fun ConfirmRow(question: String, action: String, icon: Int, tag: String, onYes: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(TankColors.Card).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(question, "$tag/q", style = PicoTheme.typography.labelLarge, color = TankColors.Ink, maxLines = 2, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        SecondaryButton(stringResource(R.string.btn_cancel), "$tag/cancel", onClick = { Ui.confirm = null })
        Spacer(Modifier.width(16.dp))
        SecondaryButton(action, "$tag/yes", onClick = onYes, danger = true, icon = icon)
    }
}

internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT save + release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.onBackground() }
    runCatching { graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    Handler(Looper.getMainLooper()).postDelayed({
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
        }
        Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 500)
    }, 300)
}
