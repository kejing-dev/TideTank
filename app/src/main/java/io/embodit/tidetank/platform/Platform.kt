package io.embodit.tidetank.platform

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.tidetank.data.SaveStore
import io.embodit.tidetank.data.SettingsStore
import io.embodit.tidetank.domain.Discovery
import io.embodit.tidetank.domain.Food
import io.embodit.tidetank.domain.Kind
import io.embodit.tidetank.domain.Saved
import io.embodit.tidetank.domain.Say
import io.embodit.tidetank.domain.Settings
import io.embodit.tidetank.domain.Sfx
import io.embodit.tidetank.domain.Tank
import io.embodit.tidetank.domain.TankState
import io.embodit.tidetank.domain.Tide
import io.embodit.tidetank.domain.V3
import io.embodit.tidetank.mainApp
import io.embodit.tidetank.ui.Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

const val TAG = "TideTank"

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        io.embodit.tidetank.ui.tank.input.BoardInputLog.attach(this)
        graph = Graph(this)
        launch(::mainApp)
    }
}

/** What the control window shows (one snapshot of the tank, published on the main thread). */
data class TankUi(
    val loaded: Boolean = false,
    val tide: Tide = Tide.M,
    val food: Food = Food.FLAKES,
    val discovered: Map<Kind, Discovery> = emptyMap(),
    val say: Say = Say.Start,
    val state: TankState = TankState.IDLE,
    val latest: Kind? = null,
    val present: Set<Kind> = emptySet(),
)

/** Process-wide objects shared by the volume and the control window (native-window-move.md §3.4). */
class Graph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(context)
    val sounds = SoundPlayer(context)
    val store = SaveStore(context)
    val window = WindowCtl()
    private val main = Handler(Looper.getMainLooper())
    val tank = Tank(seed = SystemClock.elapsedRealtime().toInt(), onSfx = { s, p -> sounds.play(s, p) }, log = { Log.i(TAG, it) })
    private val _ui = MutableStateFlow(TankUi())
    val ui: StateFlow<TankUi> = _ui.asStateFlow()
    @Volatile var loaded = false
    /** The volume's models are in (S00 ends when both the save and the scene are ready). */
    var sceneReady by androidx.compose.runtime.mutableStateOf(false)
    private var saveJob: Job? = null
    private var publishPending = false
    private var lastSaved: Saved? = null

    init {
        tank.onChanged = { publishSoon() }
        tank.onFirstCarry = { main.post { if (Ui.howTo) { Ui.howTo = false; finishFirstRun() } } }
        tank.onAllFound = { main.post { Ui.result = true; Ui.page = io.embodit.tidetank.ui.Page.TANK } }
        scope.launch {
            settings.settings.collect { s ->
                sounds.muted = s.muted
                sounds.volumeLevel = s.volume
                sounds.pumpOn = s.pump
                tank.reducedMotion = s.reducedMotion
            }
        }
        scope.launch(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            if (context.getSharedPreferences("debug", Context.MODE_PRIVATE).getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 12 s"); delay(12_000) }
            val saved = store.load()
            tank.restore(saved ?: Saved())
            lastSaved = saved
            val first = !settings.value.firstRunDone
            tank.firstRun = first
            main.post {
                if (first) Ui.howTo = true
                loaded = true
                publishSoon()
            }
            Log.i(TAG, "LOAD ${if (saved != null) "restored S12" else if (first) "first run S01" else "fresh S02"} in ${SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    /** S01 closed ([知道了] or the jar picked up): the card does not come back on its own. */
    fun finishFirstRun() { tank.firstRun = false; settings.update { it.copy(firstRunDone = true) } }

    private fun publishSoon() {
        synchronized(this) { if (publishPending) return; publishPending = true }
        main.post {
            synchronized(this) { publishPending = false }
            publish()
        }
    }

    fun publish() {
        val u = synchronized(tank.lock) {
            TankUi(loaded, tank.tide, tank.food, LinkedHashMap(tank.discovered), tank.say, tank.state,
                tank.discovered.values.maxByOrNull { it.at }?.kind, Kind.entries.filter { tank.present(it) }.toSet())
        }
        _ui.value = u
        if (loaded) {
            val snap = tank.snapshot()
            if (snap != lastSaved) { lastSaved = snap; saveSoon() }
        }
    }

    private fun saveSoon() {
        saveJob?.cancel()
        saveJob = scope.launch { delay(500); saveNow("event") }
    }

    fun saveNow(why: String) {
        if (!loaded) return
        val snap = tank.snapshot()
        scope.launch(Dispatchers.IO) { store.save(snap); Log.i(TAG, "SAVE $why tide=${snap.tide} found=${snap.discovered.size}") }
    }

    fun onForeground() { sounds.foreground = true }

    fun onBackground() {
        sounds.foreground = false
        tank.jarRelease()          // design lifecycle: the jar goes back first
        saveNow("background")
    }

    /** S11 [重置]: the guide empties, the water goes to mid tide. */
    fun resetTank() {
        tank.reset()
        store.clear()
        lastSaved = null
        publish()
    }

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}

/**
 * Event sounds (design sound.list): CC0 recordings (tools/make_sounds.py), variant rotation and +-5 % pitch jitter (D51),
 * one instance per event (a new one stops the previous), panned to where it happens in the tank. Optional pump bed, off by
 * default (B24).
 */
class SoundPlayer(private val context: Context) {
    @Volatile var muted = false
        set(v) { field = v; updatePump() }
    @Volatile var volumeLevel = 1
        set(v) { field = v; updatePump() }
    @Volatile var pumpOn = false
        set(v) { field = v; updatePump() }
    @Volatile var foreground = true
        set(v) { field = v; updatePump() }

    private val pool = SoundPool.Builder().setMaxStreams(8)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<Sfx, List<Int>>()
    private val loaded = HashSet<Int>()
    private val random = Random(11)
    private val playing = HashMap<Sfx, Int>()
    private val lastAt = HashMap<Sfx, Long>()
    private val lastVariant = HashMap<Sfx, Int>()
    private var bed: android.media.MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (s in Sfx.entries) {
            val found = ArrayList<Int>()
            for (name in FILES.getValue(s)) runCatching { found += context.assets.openFd("sounds/$name.wav").use { fd -> pool.load(fd, 1) } }
            if (found.isNotEmpty()) ids[s] = found else Log.w(TAG, "no sound $s")
        }
    }

    fun gain(): Float = Settings.VOLUME[volumeLevel.coerceIn(0, 2)]

    fun play(s: Sfx, at: V3 = V3.ZERO) {
        if (muted) return
        val t = SystemClock.uptimeMillis()
        // rate limit per event (a pour of 3 particles = one plop; bites in a burst)
        if (t - (lastAt[s] ?: 0L) < MIN_GAP.getValue(s)) return
        lastAt[s] = t
        val variants = ids[s] ?: return
        var v = random.nextInt(variants.size)
        if (v == lastVariant[s] && variants.size > 1) v = (v + 1) % variants.size
        lastVariant[s] = v
        val id = variants[v]
        if (synchronized(loaded) { id !in loaded }) return
        val j = if (s == Sfx.DISCOVER || s == Sfx.ALLFOUND || s == Sfx.TIDE) 0f else 0.05f
        val rate = 1f - j + random.nextFloat() * 2 * j
        // simple stereo placement by x (the volume is 0.9 m wide)
        val pan = (at.x / 0.45f).coerceIn(-1f, 1f) * 0.5f
        val g = gain() * LEVEL.getValue(s)
        synchronized(playing) {
            if (s in ONE_AT_A_TIME) playing.remove(s)?.let { pool.stop(it) }
            val stream = pool.play(id, g * (1f - pan.coerceAtLeast(0f)), g * (1f + pan.coerceAtMost(0f)), 1, 0, rate)
            if (stream != 0) playing[s] = stream
        }
    }

    private fun updatePump() {
        main.post {
            val want = pumpOn && !muted && foreground
            val g = gain() * PUMP_LEVEL
            if (want && bed == null) {
                bed = runCatching {
                    android.media.MediaPlayer().apply {
                        context.assets.openFd("sounds/pump.ogg").use { fd -> setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
                        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        isLooping = true
                        setVolume(g, g)
                        prepare(); start()
                    }
                }.onFailure { Log.w(TAG, "PUMP failed: $it") }.getOrNull()
                Log.i(TAG, "PUMP on ok=${bed != null}")
            } else if (!want && bed != null) {
                runCatching { bed?.stop(); bed?.release() }; bed = null
                Log.i(TAG, "PUMP off")
            } else bed?.setVolume(g, g)
        }
    }

    fun release() = runCatching { pumpOn = false; main.post { runCatching { bed?.release() }; bed = null }; pool.release() }

    companion object {
        val FILES = mapOf(
            Sfx.PICK to listOf("pick", "pick_2"), Sfx.SHAKE_FLAKES to listOf("shake_flakes", "shake_flakes_2"),
            Sfx.SHAKE_PELLETS to listOf("shake_pellets", "shake_pellets_2"), Sfx.SHAKE_SHRIMP to listOf("shake_shrimp"),
            Sfx.PLOP to listOf("plop", "plop_2"), Sfx.BITE to listOf("bite", "bite_2"), Sfx.TIDE to listOf("tide", "tide_2"),
            Sfx.CLICK to listOf("click"), Sfx.GATE to listOf("gate"), Sfx.DISCOVER to listOf("discover"), Sfx.ALLFOUND to listOf("allfound"),
        )
        /** Level on top of the file (files peak -1..-4 dBFS); event sounds stay under the find cue. */
        val LEVEL = mapOf(
            Sfx.PICK to 0.8f, Sfx.SHAKE_FLAKES to 0.75f, Sfx.SHAKE_PELLETS to 0.75f, Sfx.SHAKE_SHRIMP to 0.75f, Sfx.PLOP to 0.7f,
            Sfx.BITE to 0.75f, Sfx.TIDE to 0.8f, Sfx.CLICK to 0.85f, Sfx.GATE to 0.8f, Sfx.DISCOVER to 0.9f, Sfx.ALLFOUND to 0.9f,
        )
        val MIN_GAP = mapOf(
            Sfx.PICK to 200L, Sfx.SHAKE_FLAKES to 120L, Sfx.SHAKE_PELLETS to 120L, Sfx.SHAKE_SHRIMP to 120L, Sfx.PLOP to 90L,
            Sfx.BITE to 70L, Sfx.TIDE to 600L, Sfx.CLICK to 100L, Sfx.GATE to 300L, Sfx.DISCOVER to 300L, Sfx.ALLFOUND to 1000L,
        )
        /** D51: the same event never stacks (a new one stops the previous). */
        val ONE_AT_A_TIME = setOf(Sfx.PICK, Sfx.SHAKE_FLAKES, Sfx.SHAKE_PELLETS, Sfx.SHAKE_SHRIMP, Sfx.TIDE, Sfx.CLICK, Sfx.GATE, Sfx.DISCOVER, Sfx.ALLFOUND, Sfx.BITE, Sfx.PLOP)
        const val PUMP_LEVEL = 0.25f
    }
}
