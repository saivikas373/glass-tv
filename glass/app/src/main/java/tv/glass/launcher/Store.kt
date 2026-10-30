package tv.glass.launcher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** All saved state, in one SharedPreferences file. Listeners get told when something changes. */
class Store private constructor(ctx: Context) {
    val p: SharedPreferences = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)

    var theme: String get() = p.getString("theme", "midnight")!!; set(v) = p.edit().putString("theme", v).apply()
    var columns: Int get() = p.getInt("columns", 5).coerceIn(4, 7); set(v) = p.edit().putInt("columns", v.coerceIn(4, 7)).apply()
    var motion: Boolean get() = p.getBoolean("motion", true); set(v) = p.edit().putBoolean("motion", v).apply()
    var autoRepair: Boolean get() = p.getBoolean("autorepair", true); set(v) = p.edit().putBoolean("autorepair", v).apply()
    var ambient: Boolean get() = p.getBoolean("ambient", true); set(v) = p.edit().putBoolean("ambient", v).apply()

    // Weather: no default place. Nothing is fetched until a city is set (from the phone).
    var weatherPlace: String get() = p.getString("wplace", "")!!; set(v) = p.edit().putString("wplace", v).apply()
    var weatherLat: Double get() = p.getFloat("wlat", 0f).toDouble(); set(v) = p.edit().putFloat("wlat", v.toFloat()).apply()
    var weatherLon: Double get() = p.getFloat("wlon", 0f).toDouble(); set(v) = p.edit().putFloat("wlon", v.toFloat()).apply()
    val hasWeatherPlace get() = weatherPlace.isNotEmpty()
    var newsUrl: String get() = p.getString("news", "")!!; set(v) = p.edit().putString("news", v).apply()

    /** First-run setup screen finished. "full" = set up by Glass-Setup over ADB, "easy" = Downloader + setup screen. */
    var setupDone: Boolean get() = p.getBoolean("setup_done", false); set(v) = p.edit().putBoolean("setup_done", v).apply()
    var installKind: String get() = p.getString("install_kind", "easy")!!; set(v) = p.edit().putString("install_kind", v).apply()
    /** The TV's own Home app, remembered the first time Glass runs (Google TV, Android TV or a brand launcher). */
    var stockHome: String get() = p.getString("stock_home", "")!!; set(v) = p.edit().putString("stock_home", v).apply()

    /** Input names the user chose on the phone, e.g. hdmi1 -> "PlayStation". Empty = "HDMI 1". */
    fun inputName(key: String): String = p.getString("in_$key", "")!!
    fun setInputName(key: String, v: String) = p.edit().putString("in_$key", v.take(24)).apply()

    /** Ad/tracking packages Glass has seen switched off on this TV (so it only warns about ones that came back). */
    fun seenDisabled(): Set<String> = p.getStringSet("seen_disabled", emptySet())!!
    fun addSeenDisabled(s: Set<String>) { if (!seenDisabled().containsAll(s)) p.edit().putStringSet("seen_disabled", seenDisabled() + s).apply() }

    /** Sticky note from the phone; empty when cleared. */
    var note: String get() = p.getString("note", "")!!; set(v) = p.edit().putString("note", v).apply()

    // How often each app was opened, so the most used move to the front.
    fun launches(): Map<String, Int> {
        val o = JSONObject(p.getString("launches", "{}")!!)
        return o.keys().asSequence().associateWith { o.getInt(it) }
    }
    fun countLaunch(pkg: String) {
        val o = JSONObject(p.getString("launches", "{}")!!)
        o.put(pkg, o.optInt(pkg) + 1)
        p.edit().putString("launches", o.toString()).apply()
    }

    // Paired phones: we only keep a SHA-256 of each token, never the token itself.
    fun phones(): JSONArray = JSONArray(p.getString("phones", "[]")!!)
    fun setPhones(a: JSONArray) = p.edit().putString("phones", a.toString()).commit()

    var lastFingerprint: String get() = p.getString("fp", "")!!; set(v) = p.edit().putString("fp", v).apply()

    companion object {
        @Volatile private var inst: Store? = null
        fun of(ctx: Context): Store = inst ?: synchronized(this) { inst ?: Store(ctx.applicationContext).also { inst = it } }
    }
}
