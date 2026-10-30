package tv.glass.launcher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.CalendarContract
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.Calendar
import java.util.concurrent.Executors

const val TAG = "Glass"
val io = Executors.newFixedThreadPool(3)

// ---------- Apps ----------
class App(val pkg: String, val label: String, val component: ComponentName, val banner: Drawable?, val icon: Drawable, val tint: Int)

object Apps {
    // Default order before usage takes over (matches the design's first row).
    private val preferred = listOf("com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
        "com.spotify.tv.android", "com.disney.disneyplus", "com.hulu.livingroomplus", "com.google.android.videos")
    /** Glass itself, the stock home screens it replaces, and setup wizards. */
    private val hidden = setOf("tv.glass.launcher", "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher", "com.google.android.tungsten.setupwraith")
    var cache: List<App> = emptyList()

    fun load(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, App>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val ai = ri.activityInfo; val pkg = ai.packageName
                if (pkg in hidden || pkg in seen) continue
                val banner = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
                val icon = ai.loadIcon(pm)
                seen[pkg] = App(pkg, ri.loadLabel(pm).toString(), ComponentName(pkg, ai.name), banner, icon, tintOf(banner ?: icon))
            }
        }
        val uses = Store.of(ctx).launches()
        cache = seen.values.sortedWith(compareByDescending<App> { uses[it.pkg] ?: 0 }
            .thenBy { preferred.indexOf(it.pkg).let { i -> if (i < 0) 99 else i } }
            .thenBy { if (it.pkg == "com.android.tv.settings") 1 else 0 }   // Settings stays last
            .thenBy { it.label.lowercase() })
        return cache
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        Store.of(ctx).countLaunch(pkg)
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
    }

    /** Average colour of the artwork, deepened, for tiles without a banner. */
    fun tintOf(d: Drawable): Int {
        val bm = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 24, 24); d.draw(Canvas(bm))
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (x in 0 until 24) for (y in 0 until 24) {
            val c = bm.getPixel(x, y); if (Color.alpha(c) < 100) continue
            val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
            val wgt = if (hsv[1] > 0.25f && hsv[2] > 0.2f) 4 else 1   // favour saturated pixels
            r += Color.red(c) * wgt; g += Color.green(c) * wgt; b += Color.blue(c) * wgt; n += wgt
        }
        bm.recycle()
        if (n == 0L) return Color.parseColor("#3A4256")
        val hsv = FloatArray(3); Color.colorToHSV(Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt()), hsv)
        hsv[1] = (hsv[1] * 1.1f).coerceAtMost(0.75f); hsv[2] = hsv[2].coerceIn(0.32f, 0.62f)
        return Color.HSVToColor(hsv)
    }
}

// ---------- Continue watching (Watch Next row, READ_EPG_DATA is a normal permission) ----------
class WatchItem(val title: String, val pkg: String, val appLabel: String, val progress: Float, val intent: Intent?, val poster: String?)

object WatchNext {
    @SuppressLint("Range")
    fun load(ctx: Context, max: Int = 2): List<WatchItem> {
        val out = ArrayList<WatchItem>()
        try {
            ctx.contentResolver.query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")?.use { c ->
                while (c.moveToNext() && out.size < max) {
                    val title = c.getString(c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_TITLE)) ?: continue
                    val pkg = c.getString(c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME)) ?: ""
                    val pos = c.getLong(c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS))
                    val dur = c.getLong(c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS))
                    val uri = c.getString(c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_INTENT_URI))
                    val poster = c.getString(c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI))
                    val intent = try { uri?.let { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) } } catch (e: Exception) { null }
                    out += WatchItem(title, pkg, label(ctx, pkg), if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0.35f, intent, poster)
                }
            }
        } catch (e: Exception) { Log.w(TAG, "watch next unavailable: $e") }
        return out
    }
    fun label(ctx: Context, pkg: String) = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }
}

// ---------- Today's calendar ----------
class Event(val start: Long, val title: String)

object Cal {
    var available = true
    fun today(ctx: Context): List<Event> {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) { available = false; return emptyList() }
        val c0 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        val start = c0.timeInMillis; val end = start + 24 * 3600_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, start); android.content.ContentUris.appendId(it, end) }.build()
        val out = ArrayList<Event>()
        try {
            ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.TITLE, CalendarContract.Instances.ALL_DAY),
                null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext()) if (c.getInt(2) == 0) out += Event(c.getLong(0), c.getString(1) ?: "Event")
            }
            available = true
        } catch (e: Exception) { available = false; Log.w(TAG, "calendar provider unavailable: $e") }
        return out
    }
}

// ---------- Weather (the one allowed outbound call: a one-way HTTPS GET to Open-Meteo) ----------
object Weather {
    @Volatile var temp: Int? = null; @Volatile var code = -1; @Volatile var at = 0L
    val listeners = ArrayList<() -> Unit>()

    fun summary(): String = when (code) {
        0 -> "Clear"; 1, 2 -> "Partly cloudy"; 3 -> "Cloudy"; 45, 48 -> "Fog"
        in 51..67 -> "Rain"; in 71..77 -> "Snow"; in 80..82 -> "Showers"; in 95..99 -> "Storms"; else -> ""
    }
    fun icon(): String = when (code) { 0 -> "sun"; 1, 2 -> "cloudsun"; 3, 45, 48 -> "cloud"; in 51..67, in 80..82, in 95..99 -> "rain"; in 71..77 -> "snow"; else -> "cloudsun" }
    fun short() = temp?.let { "$it°" } ?: "--°"

    /** Fahrenheit only where people use it (US and a few others); Celsius everywhere else. */
    private fun unit(): String = if (java.util.Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")) "fahrenheit" else "celsius"

    fun refresh(ctx: Context, force: Boolean = false) {
        val s = Store.of(ctx)
        if (!s.hasWeatherPlace) return   // no city yet: no internet call at all
        if (!force && System.currentTimeMillis() - at < 30 * 60_000L) return
        at = System.currentTimeMillis()
        io.execute {
            try {
                val o = getJson("https://api.open-meteo.com/v1/forecast?latitude=${s.weatherLat}&longitude=${s.weatherLon}" +
                        "&current=temperature_2m,weather_code&temperature_unit=${unit()}")
                val cur = o.getJSONObject("current")
                temp = Math.round(cur.getDouble("temperature_2m")).toInt(); code = cur.getInt("weather_code")
                android.os.Handler(android.os.Looper.getMainLooper()).post { listeners.toList().forEach { it() } }
            } catch (e: Exception) { at = 0; Log.w(TAG, "weather: $e") }
        }
    }

    /** Set the place by name (from the phone). Same Open-Meteo service, HTTPS GET only. */
    fun setPlace(ctx: Context, q: String): String? {
        val o = getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + Uri.encode(q))
        val r = o.optJSONArray("results")?.optJSONObject(0) ?: return null
        val s = Store.of(ctx); s.weatherLat = r.getDouble("latitude"); s.weatherLon = r.getDouble("longitude")
        s.weatherPlace = r.optString("name", q); refresh(ctx, true); return s.weatherPlace
    }

    fun getJson(u: String): JSONObject {
        require(u.startsWith("https://"))
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000; c.setRequestProperty("User-Agent", "GlassTV")
        return c.inputStream.use { JSONObject(it.bufferedReader().readText()) }
    }
}

// ---------- Network ----------
object Net {
    /** The Wi-Fi IPv4 address and prefix length, read at runtime (e.g. 192.168.1.20 /24). Nothing is hard-coded. */
    fun wifi(): Pair<Inet4Address, Int>? {
        for (name in listOf("wlan0", "wlan1")) {
            val nif = try { NetworkInterface.getByName(name) } catch (e: Exception) { null } ?: continue
            if (!nif.isUp) continue
            for (ia in nif.interfaceAddresses) { val a = ia.address; if (a is Inet4Address && !a.isLoopbackAddress) return a to ia.networkPrefixLength.toInt() }
        }
        return null
    }

    fun inSubnet(a: java.net.InetAddress, net: Inet4Address, prefix: Int): Boolean {
        val b = (a as? Inet4Address ?: return false).address; val n = net.address
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        fun i(x: ByteArray) = (x[0].toInt() and 255 shl 24) or (x[1].toInt() and 255 shl 16) or (x[2].toInt() and 255 shl 8) or (x[3].toInt() and 255)
        return (i(b) and mask) == (i(n) and mask)
    }

    class WifiInfo(val connected: Boolean, val quality: String, val band: String, val ip: String)

    @Suppress("DEPRECATION")
    fun info(ctx: Context): WifiInfo {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val wi = wm.connectionInfo
        val lvl = WifiManager.calculateSignalLevel(wi.rssi, 5)
        val q = when { !onWifi -> "Off"; lvl >= 4 -> "Excellent"; lvl == 3 -> "Good"; lvl == 2 -> "Fair"; else -> "Weak" }
        val band = when (wi.frequency) { in 2400..2500 -> "2.4 GHz"; in 4900..5900 -> "5 GHz"; in 5925..7125 -> "6 GHz"; else -> "" }
        return WifiInfo(onWifi, q, band, wifi()?.first?.hostAddress ?: "")
    }
}

// ---------- TV inputs (HDMI 1-3, AV) ----------
/**
 * Inputs come from Android's TV Input Framework, which TCL, Hisense, Sony, Philips, Xiaomi and most TVs fill in.
 * Streaming boxes and some brands expose none: then the list is empty and the Input row is hidden.
 * Names are neutral ("HDMI 1", "AV"); the user can rename them from the phone.
 */
object Inputs {
    /** [name] is the neutral name, [label] what is shown (the user's name if they set one). */
    class In(val key: String, val name: String, val label: String, val id: String)

    /** The port number in the input id: HW5 (Realtek/TCL), HDMI3, /hdmi2, ...; unknown ones keep Android's order. */
    private fun port(id: String): Int = Regex("(?i)(?:hw|hdmi)[^0-9]{0,3}(\\d+)").findAll(id).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 99

    fun list(ctx: Context): List<In> {
        val tim = try { ctx.getSystemService(TvInputManager::class.java) } catch (e: Exception) { null } ?: return emptyList()
        val all = try { tim.tvInputList.filter { it.parentId == null && !it.isHidden(ctx) } } catch (e: Exception) { return emptyList() }
        val hdmi = all.withIndex().filter { it.value.type == TvInputInfo.TYPE_HDMI }.sortedWith(compareBy({ port(it.value.id) }, { it.index })).map { it.value }
        val av = all.filter { it.type == TvInputInfo.TYPE_COMPOSITE || it.type == TvInputInfo.TYPE_COMPONENT }
        val s = Store.of(ctx); val out = ArrayList<In>()
        fun add(key: String, name: String, id: String) { out += In(key, name, s.inputName(key).ifEmpty { name }, id) }
        hdmi.take(4).forEachIndexed { i, t -> add("hdmi${i + 1}", "HDMI ${i + 1}", t.id) }
        av.firstOrNull()?.let { add("av", "AV", it.id) }
        return out
    }

    fun rename(ctx: Context, key: String, label: String): Boolean {
        if (list(ctx).none { it.key == key }) return false
        Store.of(ctx).setInputName(key, label.replace(Regex("[\\p{Cntrl}]"), "").trim()); return true
    }

    fun open(ctx: Context, key: String): Boolean {
        if (key == "home") { ctx.startActivity(Intent(ctx, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true }
        val i = list(ctx).firstOrNull { it.key == key } ?: return false
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(i.id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: Exception) { Log.w(TAG, "input ${i.id}: $e"); false }   // no TV app handles passthrough on this brand
    }
}

fun Context.openSettings(action: String) = try {
    startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (e: Exception) { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); false }
