package tv.glass.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import java.io.File

val main = Handler(Looper.getMainLooper())

/** Messages, sticky notes and photos sent from the phone, plus whatever is listening for them. */
object Hub {
    class Msg(val text: String, val mode: String, val seconds: Int, val at: Long = System.currentTimeMillis())

    @Volatile var current: Msg? = null
    /** The Glass screen in front (Home or Control Center), so phone keys can drive it directly. */
    @Volatile var front: java.lang.ref.WeakReference<android.app.Activity>? = null

    /** Phone arrows/OK/Back inside Glass: move focus and click like the real remote. Main thread. */
    fun phoneKey(name: String): Boolean {
        val a = front?.get() ?: return false
        if (a.isFinishing) return false
        if (name == "back") { @Suppress("DEPRECATION") a.onBackPressed(); return true }
        if (name == "ok") { val f = a.currentFocus ?: return false; f.performClick(); return true }
        val code = mapOf("up" to 19, "down" to 20, "left" to 21, "right" to 22)[name] ?: return false
        if (a is HomeActivity && a.shelfKey(code)) return true
        val dir = mapOf(19 to android.view.View.FOCUS_UP, 20 to android.view.View.FOCUS_DOWN, 21 to android.view.View.FOCUS_LEFT, 22 to android.view.View.FOCUS_RIGHT)[code]!!
        val f = a.currentFocus
        val next = f?.focusSearch(dir) ?: a.window.decorView.focusSearch(dir)
        next?.requestFocus(dir)
        return true
    }
    val listeners = ArrayList<() -> Unit>()
    private fun changed() = main.post { listeners.toList().forEach { it() } }

    fun message(ctx: Context, text: String, mode: String, seconds: Int) {
        val clean = text.take(280)
        when (mode) {
            "note" -> { Store.of(ctx).note = clean; current = Msg(clean, "popup", seconds) }
            "fullscreen" -> {
                ctx.startActivity(Intent(ctx, MessageActivity::class.java).putExtra("text", clean).putExtra("seconds", seconds)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)); return
            }
            else -> current = Msg(clean, "popup", seconds)
        }
        changed()
        // Over other apps the accessibility overlay shows it; Home draws its own banner.
        main.post { KeyService.instance?.popup(clean, seconds) }
        val shown = current
        main.postDelayed({ if (current === shown) { current = null; changed() } }, seconds * 1000L)
    }

    fun clearNote(ctx: Context) { Store.of(ctx).note = ""; changed() }

    fun photosDir(ctx: Context) = File(ctx.filesDir, "photos").apply { mkdirs() }
    fun photos(ctx: Context): List<File> = photosDir(ctx).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    fun addPhoto(ctx: Context, bytes: ByteArray, ext: String): File {
        val f = File(photosDir(ctx), "p${System.currentTimeMillis()}.$ext"); f.writeBytes(bytes)
        photos(ctx).drop(30).forEach { it.delete() }   // keep the 30 newest
        changed(); return f
    }

    /** Pairing lockout warning (5 wrong PINs). */
    fun warn(ctx: Context, text: String) { main.post { KeyService.instance?.popup(text, 12, warning = true) }; current = Msg(text, "warn", 12); changed() }
}

/**
 * Notification listener: Android only hands active MediaSessions to an app that holds one.
 * We read the sessions for Now Playing; notifications themselves are ignored.
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = true
        val msm = getSystemService(MediaSessionManager::class.java)
        val me = ComponentName(this, MediaListener::class.java)
        try {
            update(msm.getActiveSessions(me))
            msm.addOnActiveSessionsChangedListener({ update(it ?: emptyList()) }, me)
        } catch (e: SecurityException) { Log.w(TAG, "media sessions: $e") }
    }
    override fun onListenerDisconnected() { connected = false }

    private fun update(list: List<MediaController>) {
        controller?.unregisterCallback(cb)
        controller = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        controller?.registerCallback(cb, main)
        notifyChange()
    }

    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { notifyChange() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { notifyChange() }
    }

    companion object {
        @Volatile var controller: MediaController? = null
        @Volatile var connected = false
        val listeners = ArrayList<() -> Unit>()
        private fun notifyChange() { main.post { listeners.toList().forEach { it() } } }

        fun title(): String? = controller?.metadata?.let { it.getString(MediaMetadata.METADATA_KEY_TITLE) ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) }
        fun playing() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        fun progress(): Float {
            val c = controller ?: return 0f; val d = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
            val st = c.playbackState ?: return 0f
            var pos = st.position
            if (st.state == PlaybackState.STATE_PLAYING) pos += ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
            return if (d > 0) (pos.toFloat() / d).coerceIn(0f, 1f) else 0f
        }
        fun toggle() { val c = controller ?: return; if (playing()) c.transportControls.pause() else c.transportControls.play() }
    }
}

/**
 * Auto-repair check. A TV update can switch ads back on, give Home to another app, or switch Glass's
 * accessibility service off. Glass can only notice it (ADB is closed day to day). Fixes: Easy installs turn
 * Glass off and on in Accessibility; Full installs run Glass-Setup on the computer again.
 */
object Repair {
    class Status(val ok: Boolean, val problems: List<String>)

    /** The cross-brand ad/tracking list Glass-Setup uses (same file, bundled). Lines: "pkg  # why", "[brand]" headers. */
    private fun lines(ctx: Context) = ctx.assets.open("debloat-list.txt").bufferedReader().readLines()
    fun expected(ctx: Context) = lines(ctx).map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() && !it.startsWith("[") }

    private fun enabled(pm: PackageManager, p: String): Boolean? = try {
        val st = pm.getApplicationEnabledSetting(p)
        st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || (st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && pm.getApplicationInfo(p, 0).enabled)
    } catch (e: Exception) { null }   // not installed

    fun check(ctx: Context): Status {
        val pm = ctx.packageManager; val problems = ArrayList<String>(); val s = Store.of(ctx)
        val quiet = lines(ctx).filter { it.contains("# quiet") }.map { it.substringBefore('#').trim() }.toSet()
        // Only packages Glass has seen switched off count: after an Easy install nothing was disabled, so no banner.
        val list = expected(ctx); val now = list.associateWith { enabled(pm, it) }
        s.addSeenDisabled(now.filterValues { it == false }.keys)
        for (p in s.seenDisabled() - quiet) if (now[p] == true) problems += "$p is back on"   // "quiet": the TV itself keeps re-enabling it
        // Android's Home stays the TV's own launcher (see KeyService); it must not move to some other app.
        val home = pm.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        val homePkg = home?.activityInfo?.packageName
        if (homePkg != null && homePkg != "android" && homePkg != ctx.packageName && homePkg != StockHome.pkg(ctx)) problems += "Home is $homePkg"
        val a11y = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        if (!a11y.contains("${ctx.packageName}/")) problems += "Glass key service is off"
        else if (KeyService.instance == null && android.os.SystemClock.elapsedRealtime() > 3 * 60_000L) problems += "Home button override stopped"
        if (s.lastFingerprint.isEmpty()) s.lastFingerprint = Build.FINGERPRINT
        else if (s.lastFingerprint != Build.FINGERPRINT) problems += "firmware changed"
        return Status(problems.isEmpty(), problems)
    }

    /** Call after Glass-Setup has run again (it opens Glass with from_setup), or "Mark as checked". */
    fun acknowledge(ctx: Context) { Store.of(ctx).lastFingerprint = Build.FINGERPRINT }
}
