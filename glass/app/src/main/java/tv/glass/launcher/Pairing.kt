package tv.glass.launcher

import android.content.Context
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Phone pairing, like a one-time enable password:
 * - a random 6-digit PIN, new on every reboot (tied to Settings.Global.BOOT_COUNT)
 * - a correct PIN earns a 256-bit random token; the TV keeps only its SHA-256
 * - 5 wrong PINs lock pairing for 5 minutes and put a warning on the TV
 */
object Pairing {
    private val rng = SecureRandom()
    private const val MAX_FAILS = 5
    private const val LOCK_MS = 5 * 60_000L
    @Volatile private var fails = 0
    /** Kept in storage too, so restarting Glass doesn't lift a lock. */
    var lockedUntil = 0L

    fun pin(ctx: Context): String {
        val s = Store.of(ctx).p
        val boot = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (s.getInt("pin_boot", -2) != boot || s.getString("pin", "").isNullOrEmpty()) {
            val pin = "%06d".format(rng.nextInt(1_000_000))
            s.edit().putString("pin", pin).putInt("pin_boot", boot).apply()
            return pin
        }
        return s.getString("pin", "")!!
    }

    sealed class Result { class Ok(val token: String) : Result(); object Wrong : Result(); class Locked(val secondsLeft: Long) : Result() }

    @Synchronized
    fun pair(ctx: Context, given: String, name: String, ip: String): Result {
        val now = System.currentTimeMillis()
        lockedUntil = maxOf(lockedUntil, Store.of(ctx).p.getLong("lock_until", 0))
        if (now < lockedUntil) return Result.Locked((lockedUntil - now) / 1000 + 1)
        if (!MessageDigest.isEqual(given.trim().toByteArray(), pin(ctx).toByteArray())) {
            fails++
            SecLog.sec("wrong PIN from $ip ($fails/$MAX_FAILS)")
            if (fails >= MAX_FAILS) {
                fails = 0; lockedUntil = now + LOCK_MS; Store.of(ctx).p.edit().putLong("lock_until", lockedUntil).apply()
                SecLog.sec("pairing locked for 5 minutes after $MAX_FAILS wrong PINs (last from $ip)")
                Hub.warn(ctx, "Phone pairing locked for 5 minutes: 5 wrong PINs from $ip")
                return Result.Locked(LOCK_MS / 1000)
            }
            return Result.Wrong
        }
        fails = 0
        val raw = ByteArray(32).also { rng.nextBytes(it) }
        val token = raw.joinToString("") { "%02x".format(it) }
        // The same phone pairing again (e.g. after clearing its browser) replaces its old entry.
        val old = Store.of(ctx).phones(); val phones = org.json.JSONArray()
        for (i in 0 until old.length()) { val o = old.getJSONObject(i); if (!(o.optString("name") == name.take(40).ifBlank { "Phone" } && o.optString("ip") == ip)) phones.put(o) }
        phones.put(JSONObject().put("hash", sha(token)).put("name", name.take(40).ifBlank { "Phone" }).put("ip", ip).put("at", now))
        Store.of(ctx).setPhones(phones)
        SecLog.sec("paired ${name.take(40)} from $ip")
        return Result.Ok(token)
    }

    /** Constant-time check of the X-Glass-Token header against every stored hash. */
    fun valid(ctx: Context, token: String?): Boolean {
        if (token == null || token.length != 64) return false
        val h = sha(token).toByteArray(); val a = Store.of(ctx).phones(); var ok = false
        for (i in 0 until a.length()) if (MessageDigest.isEqual(h, a.getJSONObject(i).getString("hash").toByteArray())) ok = true
        return ok
    }

    fun forget(ctx: Context, token: String) {
        val h = sha(token); val a = Store.of(ctx).phones(); val keep = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).getString("hash") != h) keep.put(a.getJSONObject(i))
        Store.of(ctx).setPhones(keep); SecLog.sec("a phone unpaired itself")
    }

    fun forgetAll(ctx: Context) { Store.of(ctx).setPhones(JSONArray()); SecLog.sec("all phones forgotten") }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Security log: logcat tag GlassSec plus the last 200 lines in a file (shown nowhere public). */
object SecLog {
    private var file: java.io.File? = null
    fun init(ctx: Context) { file = java.io.File(ctx.filesDir, "security.log") }
    @Synchronized fun sec(msg: String) {
        android.util.Log.w("GlassSec", msg)
        val f = file ?: return
        val lines = (if (f.exists()) f.readLines() else emptyList()).takeLast(199) + "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}  $msg"
        f.writeText(lines.joinToString("\n") + "\n")
    }
}
