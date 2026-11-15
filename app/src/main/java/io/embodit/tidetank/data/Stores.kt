package io.embodit.tidetank.data

import android.content.Context
import android.util.Log
import io.embodit.tidetank.domain.Discovery
import io.embodit.tidetank.domain.Food
import io.embodit.tidetank.domain.Kind
import io.embodit.tidetank.domain.Language
import io.embodit.tidetank.domain.Saved
import io.embodit.tidetank.domain.Settings
import io.embodit.tidetank.domain.Spot
import io.embodit.tidetank.domain.Tide
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** files/tank.json (design dataModel.storage): temp file -> read back -> atomic rename; previous copy kept as .bak. */
class SaveStore(context: Context) {
    private val dir = context.filesDir
    private val file = File(dir, "tank.json")
    private val backup = File(dir, "tank.bak")

    fun load(): Saved? = listOf(file, backup).firstNotNullOfOrNull { f ->
        runCatching { if (f.exists()) SaveCodec.decode(f.readText()) else null }
            .onFailure { Log.w("TideTank", "SAVE unreadable ${f.name}: $it") }.getOrNull()
    }

    @Synchronized
    fun save(v: Saved) {
        runCatching {
            val text = SaveCodec.encode(v)
            val tmp = File(dir, "tank.tmp")
            tmp.writeText(text)
            SaveCodec.decode(tmp.readText())
            if (file.exists()) file.copyTo(backup, overwrite = true)
            if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
        }.onFailure { Log.e("TideTank", "SAVE failed", it) }
    }

    fun clear() { file.delete(); backup.delete() }
}

object SaveCodec {
    fun encode(s: Saved): String = JSONObject().apply {
        put("v", 1)
        put("tide", s.tide.name)
        put("food", s.food.key)
        put("discovered", JSONArray().apply {
            s.discovered.forEach { d -> put(JSONObject().put("id", d.kind.key).put("at", d.at).put("tide", d.tide.name).put("food", d.food.key).put("spot", d.spot.name)) }
        })
    }.toString()

    fun decode(text: String): Saved {
        val o = JSONObject(text)
        val list = o.optJSONArray("discovered")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val d = a.getJSONObject(i)
                val k = Kind.parse(d.getString("id")) ?: return@mapNotNull null
                Discovery(k, d.optLong("at"), Tide.parse(d.optString("tide")) ?: Tide.M, Food.parse(d.optString("food")) ?: Food.FLAKES,
                    runCatching { Spot.valueOf(d.optString("spot")) }.getOrDefault(Spot.OPEN))
            }.distinctBy { it.kind }
        } ?: emptyList()
        return Saved(Tide.parse(o.optString("tide")) ?: Tide.M, Food.parse(o.optString("food")) ?: Food.FLAKES, list)
    }
}

/** App settings in SharedPreferences, exposed as a StateFlow so both windows recompose on change. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    fun update(change: (Settings) -> Settings) = set(change(_settings.value))

    @Synchronized
    fun set(s: Settings) {
        _settings.value = s
        prefs.edit()
            .putString("language", s.language.name)
            .putBoolean("sound", s.sound)
            .putInt("volume", s.volume)
            .putBoolean("pump", s.pump)
            .putBoolean("reducedMotion", s.reducedMotion)
            .putBoolean("firstRunDone", s.firstRunDone)
            .apply()
    }

    private fun read(): Settings = runCatching {
        Settings(
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            sound = prefs.getBoolean("sound", true),
            volume = prefs.getInt("volume", 1).coerceIn(0, 2),
            pump = prefs.getBoolean("pump", false),
            reducedMotion = prefs.getBoolean("reducedMotion", false),
            firstRunDone = prefs.getBoolean("firstRunDone", false),
        )
    }.getOrElse { Settings() }
}
