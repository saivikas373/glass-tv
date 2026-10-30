package tv.glass.launcher

import android.app.PendingIntent
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.concurrent.Executors

/**
 * The phone controller's web server. Hardened like a management plane on a LAN segment:
 *  1. bound to the Wi-Fi address only (not 0.0.0.0)
 *  2. drops any source outside the TV's own subnet, and logs it
 *  3. no UPnP, no port mapping, no relay; nothing here dials out except the user-pressed actions
 *     noted below (weather lookups, speed test, APK download)
 *  4. phones pair once with the PIN, then send X-Glass-Token on every API call
 *  5. Host must be exactly <tv ip>:<port> (DNS rebinding); a foreign Origin or a cross-site
 *     Sec-Fetch-Site is refused; no CORS headers are ever sent
 */
class GlassServer(private val ctx: Context) {
    companion object { const val PORT = 8642; const val TV_NAME = "Glass TV"; @Volatile var running: GlassServer? = null }

    private val pool = Executors.newFixedThreadPool(4)
    @Volatile private var ss: ServerSocket? = null
    @Volatile var addr: Inet4Address? = null
    @Volatile var prefix = 24
    @Volatile private var stop = false

    fun start() {
        running = this
        Thread({
            while (!stop) {
                val w = Net.wifi()
                if (w == null) { Thread.sleep(10_000); continue }
                addr = w.first; prefix = w.second
                try {
                    ServerSocket().use { s ->
                        ss = s; s.reuseAddress = true
                        s.bind(InetSocketAddress(w.first, PORT), 16)          // rule 1: Wi-Fi interface only
                        Log.i(TAG, "server on ${w.first.hostAddress}:$PORT /${w.second}")
                        watchAddress(w)
                        while (!stop) { val c = s.accept(); pool.execute { serve(c) } }
                    }
                } catch (e: IOException) { if (!stop) { Log.i(TAG, "server rebinding: $e"); Thread.sleep(3000) } }
            }
        }, "glass-server").apply { isDaemon = true }.start()
    }

    /** Re-bind when the Wi-Fi address changes (DHCP renew, network switch). */
    private fun watchAddress(w: Pair<Inet4Address, Int>) {
        Thread({
            while (!stop && ss?.isClosed == false) {
                Thread.sleep(15_000)
                if (Net.wifi() != w) { try { ss?.close() } catch (_: Exception) {} ; return@Thread }
            }
        }, "glass-addr").apply { isDaemon = true }.start()
    }

    fun stop() { stop = true; try { ss?.close() } catch (_: Exception) {} }

    // ---------------- HTTP ----------------
    private class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray, val ip: String) {
        fun json() = try { JSONObject(String(body)) } catch (e: Exception) { JSONObject() }
    }

    private fun serve(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 15000
            val ip = s.inetAddress.hostAddress ?: "?"
            val net = addr ?: return
            if (!Net.inSubnet(s.inetAddress, net, prefix)) {                    // rule 2
                SecLog.sec("refused $ip: outside ${net.hostAddress}/$prefix"); return
            }
            val inp = BufferedInputStream(s.getInputStream()); val out = s.getOutputStream()
            val req = try { parse(inp, ip) } catch (e: Exception) { send(out, 400, "text/plain", "bad request".toByteArray()); return }
            val host = "${net.hostAddress}:$PORT"
            if (req.headers["host"] != host) {                                   // rule 5: DNS rebinding
                SecLog.sec("refused $ip: Host '${req.headers["host"]}' is not $host ${req.method} ${req.path}")
                send(out, 421, "text/plain", "wrong host".toByteArray()); return
            }
            val origin = req.headers["origin"]
            if (origin != null && origin != "http://$host") {                     // rule 5: cross-origin
                SecLog.sec("refused $ip: Origin '$origin' ${req.method} ${req.path}")
                send(out, 403, "text/plain", "cross-origin refused".toByteArray()); return
            }
            val site = req.headers["sec-fetch-site"]
            if (site != null && site != "same-origin" && site != "none") {
                SecLog.sec("refused $ip: Sec-Fetch-Site '$site' ${req.method} ${req.path}")
                send(out, 403, "text/plain", "cross-site refused".toByteArray()); return
            }
            if (req.method == "OPTIONS") { send(out, 405, "text/plain", "no".toByteArray()); return }   // no CORS, ever
            try { route(req, out) } catch (e: Exception) {
                Log.w(TAG, "api error ${req.path}", e); json(out, 500, JSONObject().put("ok", false).put("error", e.message ?: "error"))
            }
        }
    }

    private fun parse(inp: BufferedInputStream, ip: String): Req {
        val head = ByteArrayOutputStream(); var last4 = 0
        while (true) {
            val b = inp.read(); if (b < 0) throw EOFException(); head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0d0a0d0a) break
            if (head.size() > 16384) throw IOException("headers too big")
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val (method, target) = lines[0].split(" ").let { it[0] to it[1] }
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) { val k = l.indexOf(':'); if (k > 0) headers[l.substring(0, k).trim().lowercase()] = l.substring(k + 1).trim() }
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val max = if (path == "/api/photo") 20 shl 20 else 64 shl 10
        if (len < 0 || len > max) throw IOException("body too big")
        val body = ByteArray(len); var n = 0
        while (n < len) { val r = inp.read(body, n, len - n); if (r < 0) throw EOFException(); n += r }
        return Req(method, path, query, headers, body, ip)
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = mapOf(200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
            405 to "Method Not Allowed", 421 to "Misdirected Request", 423 to "Locked", 429 to "Too Many Requests", 500 to "Error")[code] ?: "OK"
        val h = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n" +
                "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\n" +
                "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; connect-src 'self'; frame-ancestors 'none'; form-action 'none'\r\n\r\n"
        out.write(h.toByteArray()); out.write(body); out.flush()
    }
    private fun json(out: OutputStream, code: Int, o: JSONObject) = send(out, code, "application/json; charset=utf-8", o.toString().toByteArray())
    private fun ok(out: OutputStream, extra: JSONObject = JSONObject()) = json(out, 200, extra.put("ok", true))

    // ---------------- routes ----------------
    private fun route(r: Req, out: OutputStream) {
        if (r.method == "GET" && (r.path == "/" || r.path == "/index.html")) {
            send(out, 200, "text/html; charset=utf-8", ctx.assets.open("web/index.html").readBytes()); return
        }
        if (r.method == "GET" && r.path == "/manifest.webmanifest") {
            send(out, 200, "application/manifest+json", """{"name":"Glass TV","short_name":"Glass","display":"standalone","background_color":"#0A1222","theme_color":"#0A1222","start_url":"/"}""".toByteArray()); return
        }
        if (r.method == "POST" && r.path == "/api/pair") {
            val b = r.json()
            when (val res = Pairing.pair(ctx, b.optString("pin"), b.optString("name"), r.ip)) {
                is Pairing.Result.Ok -> ok(out, JSONObject().put("token", res.token).put("name", TV_NAME))
                is Pairing.Result.Wrong -> json(out, 401, JSONObject().put("ok", false).put("error", "Wrong PIN"))
                is Pairing.Result.Locked -> json(out, 423, JSONObject().put("ok", false).put("error", "Too many wrong PINs. Try again in ${(res.secondsLeft + 59) / 60} min."))
            }
            return
        }
        if (!r.path.startsWith("/api/")) { send(out, 404, "text/plain", "not found".toByteArray()); return }
        if (!Pairing.valid(ctx, r.headers["x-glass-token"])) {                   // rule 4
            SecLog.sec("refused ${r.ip}: no valid token for ${r.method} ${r.path}")
            json(out, 401, JSONObject().put("ok", false).put("error", "Not paired")); return
        }
        if (r.method == "GET") when (r.path) {
            "/api/status" -> { ok(out, status()); return }
            "/api/icon" -> { send(out, 200, "image/png", icon(r.query["pkg"] ?: "")); return }
        }
        if (r.method != "POST") { send(out, 405, "text/plain", "no".toByteArray()); return }
        val b = r.json()
        when (r.path) {
            "/api/key" -> ok(out, JSONObject().put("sent", key(b.optString("key"))))
            "/api/text" -> ok(out, JSONObject().put("where", text(b.optString("text"), b.optBoolean("submit"))))
            "/api/message" -> { Hub.message(ctx, b.optString("text").ifBlank { return json(out, 400, JSONObject().put("ok", false)) },
                b.optString("mode", "popup"), b.optInt("seconds", 10).coerceIn(3, 120)); ok(out) }
            "/api/note/clear" -> { Hub.clearNote(ctx); ok(out) }
            "/api/photos/clear" -> { Hub.photos(ctx).forEach { it.delete() }; Hub.clearNote(ctx); ok(out) }
            "/api/photo" -> {
                val type = r.headers["content-type"] ?: ""
                val ext = when { type.contains("png") -> "png"; type.contains("webp") -> "webp"; type.contains("jpeg") || type.contains("jpg") -> "jpg"; else -> null }
                if (ext == null || r.body.isEmpty()) { json(out, 400, JSONObject().put("ok", false).put("error", "send a JPEG, PNG or WebP")); return }
                val f = Hub.addPhoto(ctx, r.body, ext)
                ctx.startActivity(Intent(ctx, MessageActivity::class.java).putExtra("photo", f.absolutePath).putExtra("seconds", 30)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)); ok(out)
            }
            "/api/open" -> { val u = b.optString("url"); if (!openLink(u)) json(out, 400, JSONObject().put("ok", false).put("error", "http(s) links only")) else ok(out) }
            "/api/launch" -> ok(out, JSONObject().put("opened", Apps.launch(ctx, b.optString("pkg"))))
            "/api/input" -> ok(out, JSONObject().put("opened", Inputs.open(ctx, b.optString("key"))))
            "/api/input/rename" -> { if (Inputs.rename(ctx, b.optString("key"), b.optString("label"))) { main.post { Hub.listeners.toList().forEach { it() } }; ok(out) }
                else json(out, 400, JSONObject().put("ok", false).put("error", "No such input")) }
            "/api/speedtest" -> ok(out, JSONObject().put("mbps", SpeedTest.run()))
            "/api/toggle" -> { val s = Store.of(ctx); val on = b.optBoolean("on")
                when (b.optString("name")) { "autorepair" -> s.autoRepair = on; "ambient" -> s.ambient = on; "motion" -> s.motion = on }
                Hub.listeners.toList().forEach { main.post(it) }; ok(out) }
            "/api/theme" -> { val n = b.optString("name"); if (n in Palette.names(ctx)) { Store.of(ctx).theme = n; main.post { Hub.listeners.toList().forEach { it() } } }; ok(out) }
            "/api/columns" -> { Store.of(ctx).columns = b.optInt("n", 5); main.post { Hub.listeners.toList().forEach { it() } }; ok(out) }
            "/api/install" -> { val u = b.optString("url")
                if (!u.startsWith("https://")) json(out, 400, JSONObject().put("ok", false).put("error", "https:// links only"))   // rule 7
                else { Installer.start(ctx, u); ok(out, JSONObject().put("note", "Confirm the install on the TV with the remote")) } }
            "/api/weather" -> { val place = Weather.setPlace(ctx, b.optString("place")); ok(out, JSONObject().put("place", place ?: JSONObject.NULL)) }
            "/api/news" -> { val u = b.optString("url"); if (u.isNotEmpty() && !u.startsWith("https://")) json(out, 400, JSONObject().put("ok", false)) else { Store.of(ctx).newsUrl = u; ok(out) } }
            "/api/controlcenter" -> { ControlCenterActivity.open(ctx); ok(out) }
            "/api/learn-settings-key" -> { KeyService.learnUntil = System.currentTimeMillis() + 20_000
                main.post { Hub.message(ctx, "Press the remote's settings button now", "popup", 15) }; ok(out) }
            "/api/repair" -> { val ok = fix(b.optString("action"), b.optString("pkg")); json(out, if (ok) 200 else 400, JSONObject().put("ok", ok)) }
            "/api/unpair" -> { Pairing.forget(ctx, r.headers["x-glass-token"]!!); ok(out) }
            else -> send(out, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun status(): JSONObject {
        val s = Store.of(ctx); val w = Net.info(ctx)
        val apps = JSONArray(); Apps.load(ctx).forEach { apps.put(JSONObject().put("pkg", it.pkg).put("label", it.label)) }
        val inputs = JSONArray(); Inputs.list(ctx).forEach { inputs.put(JSONObject().put("key", it.key).put("label", it.label).put("name", it.name)) }
        val rep = Repair.check(ctx)
        return JSONObject().put("name", TV_NAME)
            .put("wifi", JSONObject().put("quality", w.quality).put("band", w.band).put("ip", w.ip))
            .put("nowPlaying", JSONObject().put("title", MediaListener.title() ?: JSONObject.NULL)
                .put("app", MediaListener.controller?.packageName?.let { WatchNext.label(ctx, it) } ?: JSONObject.NULL).put("playing", MediaListener.playing()))
            .put("apps", apps).put("inputs", inputs)
            .put("toggles", JSONObject().put("autorepair", s.autoRepair).put("ambient", s.ambient).put("motion", s.motion))
            .put("remote", JSONObject().put("ready", KeyService.instance != null))
            .put("theme", s.theme).put("themes", JSONArray(Palette.names(ctx))).put("columns", s.columns)
            .put("weather", JSONObject().put("place", s.weatherPlace.ifEmpty { null } ?: JSONObject.NULL).put("temp", Weather.temp ?: JSONObject.NULL).put("summary", Weather.summary()))
            .put("note", s.note).put("news", s.newsUrl)
            .put("repair", JSONObject().put("ok", rep.ok).put("problems", JSONArray(rep.problems)).put("fixes", fixes(rep)))
            .put("adb", android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 1)
    }

    /**
     * Phone-side repair, no Mac and no ADB: Glass can't switch packages off itself (that needs the shell),
     * so each fix opens the right TV settings page and you finish it with the remote. "ack" just clears the banner.
     */
    private fun fixes(rep: Repair.Status): JSONArray {
        val a = JSONArray()
        for (p in rep.problems) when {
            p.endsWith(" is back on") -> { val pkg = p.removeSuffix(" is back on")
                a.put(JSONObject().put("action", "app").put("pkg", pkg).put("label", "Turn off $pkg").put("hint", "On the TV choose Disable, then OK")) }
            p == "Glass key service is off" -> a.put(JSONObject().put("action", "a11y").put("label", "Turn the Glass key service back on").put("hint", "Pick Glass and switch it on"))
            p == "firmware changed" -> a.put(JSONObject().put("action", "ack").put("label", "TV updated: mark as checked").put("hint", "Do this after the other fixes"))
            p.startsWith("Home is ") -> a.put(JSONObject().put("action", "mac").put("label", p).put("hint", "Run Glass-Setup on your computer again"))
            p == "Home button override stopped" -> a.put(JSONObject().put("action", "a11y").put("label", "Turn Glass off and on in Accessibility").put("hint", "A TV update can do this; switch Glass off, then on"))
        }
        return a
    }

    private fun fix(action: String, pkg: String): Boolean {
        val i = when (action) {
            "app" -> { if (pkg !in Repair.expected(ctx)) return false     // only packages we switched off
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg")) }
            "a11y" -> Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "adb" -> Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            "ack" -> { Repair.acknowledge(ctx); main.post { Hub.listeners.toList().forEach { it() } }; return true }
            else -> return false
        }
        return try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
    }

    private fun icon(pkg: String): ByteArray {
        val app = Apps.cache.firstOrNull { it.pkg == pkg } ?: Apps.load(ctx).firstOrNull { it.pkg == pkg } ?: return ByteArray(0)
        val bm = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888); app.icon.setBounds(0, 0, 96, 96); app.icon.draw(Canvas(bm))
        return ByteArrayOutputStream().also { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /**
     * Phone keys, all handled on the TV with no network port and no ADB:
     *  arrows/OK -> accessibility focus moves; Back -> accessibility; Home -> Glass;
     *  volume/mute -> AudioManager; play/pause -> media key; power -> sleep (accessibility lock screen).
     * (Remote v2 was the first choice, but this TV's remote service refuses connections from the TV itself.)
     */
    private fun key(name: String): String {
        val ks = KeyService.instance
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        return when (name) {
            "settings" -> { ControlCenterActivity.open(ctx); "controlcenter" }
            "home" -> { ctx.startActivity(Intent(ctx, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "glass" }
            "back" -> if (runOnMain { Hub.phoneKey("back") }) "glass" else if (ks?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) == true) "accessibility" else "not-ready"
            "up", "down", "left", "right", "ok" -> if (runOnMain { Hub.phoneKey(name) }) "glass" else if (ks == null) "not-ready" else { var r = false; val l = Object()
                main.post { r = ks.dpad(name); synchronized(l) { l.notify() } }; synchronized(l) { l.wait(1500) }; if (r) "accessibility" else "no-focus" }
            "volup" -> { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI); "audio" }
            "voldown" -> { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI); "audio" }
            "mute" -> { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_TOGGLE_MUTE, android.media.AudioManager.FLAG_SHOW_UI); "audio" }
            "playpause" -> { listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP).forEach {
                am.dispatchMediaKeyEvent(android.view.KeyEvent(it, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) }; "media" }
            "power" -> if (ks?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true) "sleep" else "not-ready"
            else -> "unknown"
        }
    }

    private fun runOnMain(f: () -> Boolean): Boolean {
        val l = java.util.concurrent.CountDownLatch(1); var r = false
        main.post { r = try { f() } catch (e: Exception) { false }; l.countDown() }
        l.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS); return r
    }

    private fun text(t: String, submit: Boolean): String {
        val ks = KeyService.instance
        if (ks != null && ks.setText(t)) return "field"
        if (submit && t.isNotBlank()) {   // nothing to type into: run a search instead (voice search lands here)
            try {
                ctx.startActivity(Intent("android.search.action.GLOBAL_SEARCH").putExtra(SearchManager.QUERY, t).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return "search"
            } catch (e: Exception) {
                ctx.startActivity(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube.tv").putExtra(SearchManager.QUERY, t).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return "youtube"
            }
        }
        return "no-field"
    }

    private fun openLink(u: String): Boolean {
        val uri = Uri.parse(u); if (uri.scheme != "https" && uri.scheme != "http") return false
        val host = uri.host ?: return false
        val yt = Regex("(^|\\.)(youtube\\.com|youtu\\.be)$").containsMatchIn(host)
        val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (yt) i.setPackage("com.google.android.youtube.tv")
        return try { ctx.startActivity(i); true } catch (e: Exception) {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e2: Exception) { false }
        }
    }
}

/** User-pressed speed test: downloads up to 25 MB from Cloudflare for at most 8 s (HTTPS GET). */
object SpeedTest {
    @Volatile var last: Int? = null
    fun run(): Int {
        val c = URL("https://speed.cloudflare.com/__down?bytes=25000000").openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000
        val buf = ByteArray(64 shl 10); var total = 0L; val t0 = System.nanoTime()
        c.inputStream.use { s -> while (true) { val n = s.read(buf); if (n < 0) break; total += n; if (System.nanoTime() - t0 > 8_000_000_000L) break } }
        val secs = (System.nanoTime() - t0) / 1e9
        return (total * 8 / secs / 1e6).toInt().also { last = it }
    }
}

/**
 * "Install from link": https only, downloaded by Glass, then handed to Android's own installer.
 * Android shows its install prompt on the TV, which must be confirmed with the remote.
 */
object Installer {
    fun start(ctx: Context, url: String) = io.execute {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true; c.connectTimeout = 10000; c.readTimeout = 30000
            if (c.url.protocol != "https") throw IOException("redirected off https")
            val pi = ctx.packageManager.packageInstaller
            val sid = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
            pi.openSession(sid).use { s ->
                c.inputStream.use { inp -> s.openWrite("app.apk", 0, -1).use { o ->
                    val buf = ByteArray(64 shl 10); var total = 0L
                    while (true) { val n = inp.read(buf); if (n < 0) break; total += n; if (total > 300L shl 20) throw IOException("APK over 300 MB"); o.write(buf, 0, n) }
                    s.fsync(o) } }
                if (c.url.protocol != "https") throw IOException("redirected off https")
                val cb = PendingIntent.getBroadcast(ctx, sid, Intent(ctx, InstallReceiver::class.java), (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT)
                s.commit(cb.intentSender)
            }
            SecLog.sec("install requested from $url; waiting for confirmation on the TV")
        } catch (e: Exception) { Log.w(TAG, "install failed", e); main.post { Hub.message(ctx, "Install failed: ${e.message}", "popup", 8) } }
    }
}

class InstallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))   // Android's own prompt, on the TV
            }
            PackageInstaller.STATUS_SUCCESS -> Hub.message(ctx, "App installed", "popup", 6)
            else -> Hub.message(ctx, "Install cancelled or failed", "popup", 6)
        }
    }
}
