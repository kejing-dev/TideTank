package io.embodit.tidetank.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.embodit.tidetank.domain.Food
import io.embodit.tidetank.domain.Geo
import io.embodit.tidetank.domain.Kind
import io.embodit.tidetank.domain.Language
import io.embodit.tidetank.domain.Tide
import io.embodit.tidetank.ui.Confirm
import io.embodit.tidetank.ui.Page
import io.embodit.tidetank.ui.Ui
import io.embodit.tidetank.ui.tank.Active
import io.embodit.tidetank.ui.tank.Names
import io.embodit.tidetank.ui.tank.SceneHolder

/**
 * QA / self-test hooks (debug builds only; design verification.debug):
 * `adb shell am broadcast -a io.embodit.tidetank.DEBUG -p io.embodit.tidetank --es cmd <cmd> --es a "<arg>"`.
 * Every command goes through the same calls as the 3D input and the window (tap -> TankListener.route, carry -> jarGrab /
 * jarMove / jarRelease, tide -> setTide, handle -> handleGrab / handleMove / handleRelease); `find` sets the real
 * conditions (tide, food, where) and feeds, it never marks a creature found by itself. `hold on` only freezes the clock.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val arg = intent.getStringExtra("a")?.trim('"', ' ').orEmpty()
        val g = Graph.from(context)
        appContext = context.applicationContext
        main.post { runCatching { run(context, g, cmd, arg) }.onFailure { Log.e(TAG, "DEBUG $cmd failed", it) } }
    }

    private fun f(s: String?, d: Float) = s?.toFloatOrNull() ?: d

    private fun tap(g: Graph, target: String, x: Float, z: Float) {
        val l = Active.listener
        if (l != null) l.route(target, x to z) else g.tank.tapFeed(x, z, "debug")
    }

    /** The real recipe for each creature (design findingRules), fed with taps through the same route as a real tap. */
    private fun recipe(g: Graph, k: Kind) {
        val t = g.tank
        when (k) {
            Kind.CHROMIS -> { t.setFood(Food.FLAKES, "debug"); tap(g, Names.sand(5), 0.10f, 0.0f) }
            Kind.CLOWN -> { t.setFood(Food.PELLETS, "debug"); tap(g, Names.ANEMONE, Geo.ANEMONE.x + 0.03f, Geo.ANEMONE.z + 0.02f) }
            Kind.CRAB -> { t.setTide(Tide.L, "debug"); t.setFood(Food.PELLETS, "debug"); main.postDelayed({ tap(g, Names.rock(2), -0.10f, 0.05f) }, 3200) }
            Kind.SEAHORSE -> { if (t.tide == Tide.L) t.setTide(Tide.M, "debug"); t.setFood(Food.SHRIMP, "debug"); main.postDelayed({ tap(g, Names.GRASS, Geo.GRASS.x - 0.02f, Geo.GRASS.z + 0.03f) }, 300) }
            Kind.TANG -> { t.setTide(Tide.H, "debug"); t.setFood(Food.FLAKES, "debug"); for (i in 0..2) main.postDelayed({ tap(g, Names.sand(6), 0.05f + i * 0.04f, -0.02f) }, 3700L + i * 400) }
            Kind.JELLY -> { t.setTide(Tide.H, "debug"); t.setFood(Food.SHRIMP, "debug"); main.postDelayed({ tap(g, Names.sand(4), 0.0f, 0.04f) }, 3700) }
        }
    }

    private fun run(context: Context, g: Graph, cmd: String, arg: String) {
        val t = g.tank
        val a = arg.split(" ").filter { it.isNotBlank() }
        Log.i(TAG, "DEBUG $cmd $arg")
        when (cmd) {
            "tide" -> Tide.parse(a.getOrNull(0))?.let { t.setTide(it, "debug") }
            "food" -> Food.parse(a.getOrNull(0))?.let { t.setFood(it, "debug") }
            "tapjar" -> Active.listener?.route(Names.JAR, null)
            "tapsign" -> Active.listener?.route(Names.SIGN, null)
            // tap <x> <z>: a tap on the tank bottom at volume metres (same route as a real tap)
            "tap" -> { val x = f(a.getOrNull(0), 0f); val z = f(a.getOrNull(1), 0f); tap(g, if (x < Geo.ROCK_TOE_X) Names.rock(1) else Names.sand(5), x, z) }
            // carry <food> <x> <z> [hold]: grab the jar, move it over (x, z) through jarMove, keep pouring; release after 3 s unless hold
            "carry" -> {
                Food.parse(a.getOrNull(0))?.let { t.setFood(it, "debug") }
                val x = f(a.getOrNull(1), 0.1f); val z = f(a.getOrNull(2), 0.05f)
                t.jarGrab(); main.postDelayed({ t.jarMove(x, z) }, 350)
                if (a.getOrNull(3) == "hold") main.postDelayed({ t.frozen = true }, 1500)
                else main.postDelayed({ t.jarRelease() }, 3500)
            }
            // sweep <food> <x0> <x1> <z>: a real sweep of the jar along x (the pour path the hand would draw)
            "sweep" -> {
                Food.parse(a.getOrNull(0))?.let { t.setFood(it, "debug") }
                val x0 = f(a.getOrNull(1), -0.2f); val x1 = f(a.getOrNull(2), 0.2f); val z = f(a.getOrNull(3), 0f)
                t.jarGrab(); for (i in 0..30) main.postDelayed({ t.jarMove(x0 + (x1 - x0) * i / 30f, z) }, 400L + i * 100)
                main.postDelayed({ t.jarRelease() }, 3700)
            }
            "release" -> t.jarRelease()
            // handle <dy...>: a drag of the brass handle through handleGrab / handleMove / handleRelease (metres of travel)
            "handle" -> { t.handleGrab(); a.forEach { t.handleMove(it.toFloatOrNull() ?: 0f) }; t.handleRelease() }
            "find" -> Kind.parse(a.getOrNull(0))?.let { recipe(g, it) }
            "findall" -> Kind.entries.forEachIndexed { i, k -> main.postDelayed({ recipe(g, k) }, i * 9000L) }
            "hint" -> t.hintNow()
            "hold" -> t.frozen = a.getOrNull(0) != "off"
            "goto" -> goto(g, a.getOrNull(0).orEmpty())
            "page" -> { Ui.howTo = false; Ui.result = false; Ui.page = runCatching { Page.valueOf(a[0].uppercase()) }.getOrDefault(Page.TANK) }
            "detail" -> { Ui.page = Page.GUIDE; Ui.detail = Kind.parse(a.getOrNull(0)) }
            "howto" -> { Ui.page = Page.TANK; Ui.howTo = a.getOrNull(0) != "off" }
            "result" -> { Ui.page = Page.TANK; Ui.result = a.getOrNull(0) != "off" }
            "confirm" -> { Ui.page = Page.SETTINGS; Ui.confirm = when (a.getOrNull(0)) { "quit", "exit" -> Confirm.QUIT; "reset" -> Confirm.RESET; else -> null } }
            "yes" -> when (Ui.confirm) { Confirm.RESET -> { g.resetTank(); Ui.confirm = null; Ui.page = Page.TANK }; Confirm.QUIT -> io.embodit.tidetank.ui.control.exitApp(context, g); null -> Unit }
            "reset" -> g.resetTank()
            "lang" -> g.settings.update { it.copy(language = when (a.getOrNull(0)) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "sound" -> g.settings.update { it.copy(sound = a.getOrNull(0) != "off") }
            "pump" -> g.settings.update { it.copy(pump = a.getOrNull(0) != "off") }
            "volume" -> g.settings.update { it.copy(volume = (a.getOrNull(0)?.toIntOrNull() ?: 1).coerceIn(0, 2)) }
            "reduced" -> g.settings.update { it.copy(reducedMotion = a.getOrNull(0) != "off") }
            "firstrun" -> g.settings.update { it.copy(firstRunDone = a.getOrNull(0) == "off") }
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", a.getOrNull(0) != "off").apply()
            "closepanel" -> g.window.closeControlOnly()
            "openpanel" -> g.window.open("debug")
            "testf" -> SceneHolder.scene?.let { it.testF = a.getOrNull(0) != "off"; it.refreshText() }
            "dump" -> synchronized(t.lock) {
                Log.i(TAG, "DUMP state=${t.state} tide=${t.tide} level=${t.level} food=${t.food} found=${t.discovered.keys} jar=${t.jar} tilt=${t.jarTilt} handle=${t.handleY} htf=${t.highTideFlakeCount} fps=${SceneHolder.scene?.fps} page=${Ui.page}")
            }
            else -> Log.w(TAG, "DEBUG unknown $cmd")
        }
    }

    private fun goto(g: Graph, s: String) {
        val t = g.tank
        t.frozen = false
        when (s.uppercase()) {
            "S01" -> { Ui.page = Page.TANK; Ui.result = false; Ui.howTo = true }
            "S02" -> { Ui.page = Page.TANK; Ui.howTo = false; Ui.result = false; t.jarRelease() }
            "S03" -> { Ui.howTo = false; run(g, "carry", "${t.food.key} 0.1 0.05 hold") }
            "S04" -> { Ui.howTo = false; tap(g, Names.sand(5), 0.1f, 0.0f) }
            "S05" -> { Ui.howTo = false; t.setTide(if (t.tide == Tide.M) Tide.L else Tide.M, "debug"); main.postDelayed({ t.frozen = true }, 1250) }
            "S08" -> { Ui.howTo = false; Ui.page = Page.GUIDE; Ui.detail = null }
            "S08B" -> { Ui.page = Page.GUIDE; Ui.detail = t.discovered.keys.lastOrNull() ?: Kind.CLOWN }
            "S09" -> { Ui.howTo = false; Ui.page = Page.TANK; Ui.result = true }
            "S10" -> { Ui.howTo = false; Ui.page = Page.SETTINGS; Ui.confirm = null }
            "S11" -> { Ui.page = Page.SETTINGS; Ui.confirm = Confirm.QUIT }
        }
    }

    private fun run(g: Graph, cmd: String, arg: String) { run(appContext ?: return, g, cmd, arg) }


    companion object {
        private val main = Handler(Looper.getMainLooper())
        @Volatile var appContext: Context? = null
    }
}
