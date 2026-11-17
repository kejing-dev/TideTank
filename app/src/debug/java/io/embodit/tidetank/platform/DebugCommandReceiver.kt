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
import io.embodit.tidetank.domain.V3
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
            "tapjar" -> Active.listener?.route(Names.JAR, null, "debug")
            // ---- v0.1.3 jar toggle (D53 补): injected controller / hand go through the same per-frame StageRig -> JarInput path
            // ctrl <x> <y> <z> [roll] [0|1]: right controller tip at tank point (x, y, z), pointing into the tank, grip/trigger
            "ctrl" -> Active.rig?.injectCtrl(V3(f(a.getOrNull(0), Geo.JAR_HOME.x), f(a.getOrNull(1), Geo.JAR_HOME.y), f(a.getOrNull(2), Geo.JAR_HOME.z)), f(a.getOrNull(3), 0f), a.getOrNull(4)?.let { it == "1" })
            // ctrlaim <x> <y> <z> [0|1]: right controller at the user's hip, its ray on tank point (x, y, z)
            "ctrlaim" -> Active.rig?.injectAim(V3(f(a.getOrNull(0), Geo.JAR_HOME.x), f(a.getOrNull(1), Geo.JAR_HOME.y), f(a.getOrNull(2), Geo.JAR_HOME.z)), a.getOrNull(3)?.let { it == "1" })
            // ctrlmove <dx> <dy> <dz> [steps]: move the injected controller (Stage metres), in [steps] frames-apart steps
            "ctrlmove" -> { val n = (a.getOrNull(3)?.toIntOrNull() ?: 1).coerceAtLeast(1); val d = V3(f(a.getOrNull(0), 0f), f(a.getOrNull(1), 0f), f(a.getOrNull(2), 0f)) * (1f / n)
                for (i in 0 until n) main.postDelayed({ Active.rig?.moveCtrl(d) }, i * 33L) }
            "ctrlroll" -> Active.rig?.rollCtrl(f(a.getOrNull(0), 70f))
            // press [0|1] / click: the injected right controller's grip / trigger (click = down, 120 ms, up)
            "press" -> Active.rig?.pressCtrl(a.getOrNull(0) != "0")
            "click" -> { Active.rig?.pressCtrl(true); main.postDelayed({ Active.rig?.pressCtrl(false) }, 120) }
            "ctrloff" -> Active.rig?.ctrlOff()
            // hand <x> <y> <z> [roll] [0|1]: right hand pinch point at tank point, wrist roll, pinched
            "hand" -> Active.rig?.injectHand(V3(f(a.getOrNull(0), Geo.JAR_HOME.x), f(a.getOrNull(1), Geo.JAR_HOME.y), f(a.getOrNull(2), Geo.JAR_HOME.z)), f(a.getOrNull(3), 0f), a.getOrNull(4) == "1")
            "handoff" -> Active.rig?.handOff()
            // head <x> <y> <z> <yawDeg> <pitchDeg> / headoff: an injected head pose (placement / recall / level gate tests)
            "head" -> Active.rig?.injectHead(V3(f(a.getOrNull(0), 0f), f(a.getOrNull(1), 1.6f), f(a.getOrNull(2), 0f)), f(a.getOrNull(3), 0f), f(a.getOrNull(4), 0f))
            "headoff" -> Active.rig?.headOff()
            "stick" -> { Active.rig?.controllers?.injectStick(f(a.getOrNull(0), 1f)); main.postDelayed({ Active.rig?.controllers?.injectStick(0f) }, (f(a.getOrNull(1), 0.5f) * 1000).toLong()) }
            "recall" -> g.window.recall("debug")
            "tapsign" -> Active.listener?.route(Names.SIGN, null)
            // q1-1: a tap on the brass handle (next stop) / on a gauge plate (that stop), same route as a real tap
            "taphandle" -> Active.listener?.route(Names.HANDLE, null)
            "tapgauge" -> Tide.parse(a.getOrNull(0))?.let { Active.listener?.route(Names.gauge(it), null) }
            // tap <x> <z>: a tap on the tank bottom at volume metres (same route as a real tap)
            "tap" -> { val x = f(a.getOrNull(0), 0f); val z = f(a.getOrNull(1), 0f); tap(g, if (x < Geo.ROCK_TOE_X) Names.rock(1) else Names.sand(5), x, z) }
            // carry <food> <x> <z> [hold]: the injected controller touches the jar + click (direct pick), moves over (x, z),
            // rolls 90 deg (pours); clicks again after 3 s (put back) unless hold
            "carry" -> {
                Food.parse(a.getOrNull(0))?.let { t.setFood(it, "debug") }
                val x = f(a.getOrNull(1), 0.1f); val z = f(a.getOrNull(2), 0.05f)
                carry(g, x, z, a.getOrNull(3) == "hold")
            }
            // sweep <food> <x0> <x1> <z>: the same, then a sweep along x while tipped (the pour path the hand would draw)
            "sweep" -> {
                Food.parse(a.getOrNull(0))?.let { t.setFood(it, "debug") }
                val x0 = f(a.getOrNull(1), -0.2f); val x1 = f(a.getOrNull(2), 0.2f); val z = f(a.getOrNull(3), 0f)
                carry(g, x0, z, true)
                for (i in 1..30) main.postDelayed({ Active.rig?.moveCtrl(V3((x1 - x0) / 30f * Geo.SCALE, 0f, 0f)) }, 1400L + i * 100)
                main.postDelayed({ run(g, "click", "") }, 5000)
            }
            "release" -> { if (t.holder != null) run(g, "click", "") }
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
                Log.i(TAG, "DUMP state=${t.state} tide=${t.tide} level=${t.level} food=${t.food} found=${t.discovered.keys} jar=${t.jar} holder=${t.holder} mode=${t.holdMode} roll=${t.jarRoll} tilt=${t.jarTilt} tip=${t.tipShown} poured=${t.pouredThisHold} handle=${t.handleY} htf=${t.highTideFlakeCount} fps=${SceneHolder.scene?.fps} page=${Ui.page} pose=${SceneHolder.scene?.pose} panel=${g.window.panelState}")
            }
            else -> Log.w(TAG, "DEBUG unknown $cmd")
        }
    }

    private fun goto(g: Graph, s: String) {
        val t = g.tank
        t.frozen = false
        when (s.uppercase()) {
            "S01" -> { Ui.page = Page.TANK; Ui.result = false; Ui.howTo = true }
            "S02" -> { Ui.page = Page.TANK; Ui.howTo = false; Ui.result = false; if (t.holder != null) run(g, "click", "") }
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

    /** Direct pick by the injected right controller, carried over (x, z) on the carry plane, rolled 90 deg. */
    private fun carry(g: Graph, x: Float, z: Float, hold: Boolean) {
        val j = Geo.JAR_HOME
        run(g, "ctrl", "${j.x} ${j.y} ${j.z} 0 0")
        main.postDelayed({ run(g, "click", "") }, 150)
        // after the pick the jar follows the tip: move the tip up over the rim and then to (x, z)
        main.postDelayed({ Active.rig?.moveCtrl(V3(0f, (Geo.CARRY_Y - j.y) * Geo.SCALE, 0f)) }, 450)
        main.postDelayed({ val p = Active.rig?.scene?.pose ?: return@postDelayed; Active.rig?.moveCtrl(io.embodit.tidetank.domain.RootPose.rotY(V3((x - j.x) * Geo.SCALE, 0f, (z - j.z) * Geo.SCALE), p.yaw)) }, 750)
        main.postDelayed({ Active.rig?.rollCtrl(90f) }, 1100)
        if (hold) main.postDelayed({ g.tank.frozen = true }, 2600)
        else main.postDelayed({ run(g, "click", "") }, 3600)
    }


    companion object {
        private val main = Handler(Looper.getMainLooper())
        @Volatile var appContext: Context? = null
    }
}
