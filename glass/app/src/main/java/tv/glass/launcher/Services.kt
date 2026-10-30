package tv.glass.launcher

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Accessibility service, used for three things only:
 *  - the remote's settings button opens Control Center over any app
 *  - phone pop-ups are drawn over other apps (TYPE_ACCESSIBILITY_OVERLAY)
 *  - text typed on the phone is put into the focused text field (ACTION_SET_TEXT)
 * It reads no screen content beyond the focused input field.
 */
class KeyService : AccessibilityService() {
    companion object {
        @Volatile var instance: KeyService? = null
        /** KEYCODE_SETTINGS, plus 83 (KEYCODE_NOTIFICATION): TCL G10 remotes send the gear as 83. */
        val settingsKeys = setOf(KeyEvent.KEYCODE_SETTINGS, 83)
        /** Phone → Apps → "Teach the settings button": the next unusual key pressed becomes a settings key. */
        @Volatile var learnUntil = 0L
        fun isSettingsKey(ctx: Context, code: Int) = code in settingsKeys ||
            Store.of(ctx).p.getString("extra_settings_keys", "")!!.split(',').any { it == code.toString() }
    }
    private var overlay: View? = null

    /**
     * Home override, the approach Projectivy Launcher and CleanYourTV's stock_home_redirect use. Android picks Home
     * by intent priority, and a sideloaded app is capped below the TV's own launcher (and Google TV's setup
     * wizard), so Android's Home stays the TV's launcher. This service makes Glass the Home people see: a short
     * Home press opens Glass, and if the stock home comes up anyway (boot, Back out of an app) it hands over to
     * Glass. A long Home press shows the stock home on purpose: the escape hatch. Works without ADB.
     */
    private var stockAllowed = false
    private val stock by lazy { StockHome.pkg(this) }

    private fun openGlass() {
        stockAllowed = false
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    override fun onServiceConnected() {
        instance = this; Log.i(TAG, "key service connected")
        // At boot the stock home is usually on screen before Android binds this service.
        if (rootInActiveWindow?.packageName == stock) main.postDelayed({ openGlass() }, 300)
    }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == stock && !stockAllowed && StockHome.isHomeScreen(e.className?.toString())) openGlass()
        else if (pkg != stock && pkg != "com.android.systemui") stockAllowed = false
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        // Remote-button log (keycode + scancode) for anything that isn't navigation, so a new remote's
        // settings button can be identified from logcat: adb logcat -s GlassKeys
        if (e.action == KeyEvent.ACTION_UP && e.keyCode !in 19..23 && e.keyCode != KeyEvent.KEYCODE_BACK)
            Log.i("GlassKeys", "key ${e.keyCode} (${KeyEvent.keyCodeToString(e.keyCode)}) scan ${e.scanCode} held ${e.eventTime - e.downTime}ms")
        if (e.keyCode == KeyEvent.KEYCODE_HOME) {
            if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) {
                if (e.eventTime - e.downTime > 700) { stockAllowed = true; performGlobalAction(GLOBAL_ACTION_HOME) }   // long: stock home
                else openGlass()
            }
            return true
        }
        if (System.currentTimeMillis() < learnUntil && e.action == KeyEvent.ACTION_UP &&
            e.keyCode !in setOf(3, 4, 19, 20, 21, 22, 23, 24, 25, 164, 66)) {
            learnUntil = 0
            val p = Store.of(this).p; val cur = p.getString("extra_settings_keys", "")!!
            p.edit().putString("extra_settings_keys", (cur.split(',').filter { it.isNotEmpty() } + e.keyCode.toString()).distinct().joinToString(",")).apply()
            popup("Settings button learned (key ${e.keyCode}). It now opens Control Center.", 6)
            return true
        }
        if (!isSettingsKey(this, e.keyCode)) return false
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) {
            if (e.eventTime - e.downTime > 600) {   // long press: the TV's own settings
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else if (ControlCenterActivity.visible) ControlCenterActivity.close() else ControlCenterActivity.open(this)
        }
        return true
    }

    /**
     * Phone arrows/OK without ADB and without the TV's Remote v2 port (which refuses connections from
     * the TV itself). Moves accessibility input focus through the app on screen, like the D-pad would.
     * Works in apps built from standard focusable views; apps that draw their own UI may ignore it.
     */
    fun dpad(dir: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (dir == "ok") {
            var n = focused
            while (n != null && !n.isClickable) n = n.parent
            return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        }
        val d = when (dir) { "up" -> View.FOCUS_UP; "down" -> View.FOCUS_DOWN; "left" -> View.FOCUS_LEFT; else -> View.FOCUS_RIGHT }
        val next = focused?.focusSearch(d) ?: firstFocusable(root)
        return next?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ?: false
    }

    private fun firstFocusable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isFocusable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> firstFocusable(c)?.let { return it } }
        return null
    }

    fun setText(t: String): Boolean {
        val n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!n.isEditable) return false
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) })
    }

    /** Glass message card at the top right, over whatever app is on screen. */
    fun popup(text: String, seconds: Int, warning: Boolean = false) {
        if (HomeActivity.visible) return   // Home draws its own banner (warnings too)
        Ui.init(this)
        val wm = getSystemService(WindowManager::class.java)
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        val v = MessageCard.build(this, if (warning) "Glass · security" else "From your phone · now", text, warning)
        val lp = WindowManager.LayoutParams(Ui.px(560), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END; x = Ui.px(80); y = Ui.px(102)
        }
        wm.addView(v, lp); overlay = v
        v.alpha = 0f; v.translationY = -Ui.px(20).toFloat(); v.animate().alpha(1f).translationY(0f).setDuration(260).start()
        main.postDelayed({ if (overlay === v) { v.animate().alpha(0f).setDuration(300).withEndAction { try { wm.removeView(v) } catch (_: Exception) {} }.start(); overlay = null } }, seconds * 1000L)
    }
}

/**
 * The TV's own Home app (Google TV launcherx, Android TV tvlauncher, or a brand launcher), found at runtime and
 * remembered the first time, so the override and the repair check work on any brand.
 */
object StockHome {
    private val known = listOf("com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.google.android.leanbacklauncher")
    private val ignore = setOf("android", "com.android.settings", "com.google.android.tungsten.setupwraith", "com.android.tv.settings")

    fun pkg(ctx: Context): String {
        val s = Store.of(ctx)
        if (s.stockHome.isNotEmpty()) return s.stockHome
        val pm = ctx.packageManager; val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val def = pm.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val found = def?.takeIf { it != ctx.packageName && it !in ignore }
            ?: pm.queryIntentActivities(home, 0).sortedByDescending { it.priority }.map { it.activityInfo.packageName }
                .firstOrNull { it != ctx.packageName && it !in ignore }
            ?: known.first()
        s.stockHome = found; return found
    }

    /** The stock launcher's main screen (not its settings or search screens). */
    fun isHomeScreen(cls: String?): Boolean = cls != null && (cls.contains("Home") || cls.contains("Main") || cls.endsWith("Launcher") || cls.contains("TvLauncher"))
}

/** The glass "From your phone" banner, shared by Home and the overlay. */
object MessageCard {
    fun build(ctx: Context, top: String, text: String, warning: Boolean = false): View {
        val pal = Palette.current(ctx)
        val card = Ui.row(ctx).apply {
            background = GlassDrawable(24f, fill = Color.argb(215, 24, 32, 58), border = Color.argb(60, 255, 255, 255))
            setPadding(Ui.px(22), Ui.px(16), Ui.px(28), Ui.px(16))
        }
        val badge = FrameLayout(ctx).apply { background = Ui.pill(if (warning) Color.parseColor("#FFB25C") else pal.accent, 14f) }
        badge.addView(ImageView(ctx).apply { setImageDrawable(Icons.of(if (warning) "alert" else "chat", Color.parseColor("#0A1222"), Ui.px(24))) },
            FrameLayout.LayoutParams(Ui.px(26), Ui.px(26), Gravity.CENTER))
        card.addView(badge, Ui.lin(48, 48, r = 20))
        val col = Ui.col(ctx)
        col.addView(Ui.text(ctx, top, 17f, 500, Ui.SECONDARY))
        col.addView(Ui.text(ctx, text, 22f, 600).apply { maxLines = 2 }, Ui.lin(-2, -2, t = 4))
        card.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return card
    }
}

/** Keeps the phone-controller server running (Android needs a foreground service for that). */
class ServerService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        SecLog.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("server", "Phone controller", NotificationManager.IMPORTANCE_MIN))
        startForeground(1, Notification.Builder(this, "server").setContentTitle("Glass phone controller")
            .setContentText("Local Wi-Fi only, PIN protected").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build())
        if (GlassServer.running == null) GlassServer(applicationContext).start()
        RemoteV2.retire(applicationContext)
        Pairing.pin(this)
    }
    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY

    companion object {
        fun start(ctx: Context) = try { ctx.startForegroundService(Intent(ctx, ServerService::class.java)) } catch (e: Exception) { Log.w(TAG, "server start: $e") }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Pairing.pin(ctx)       // new PIN after every reboot
        ServerService.start(ctx)
    }
}

/** Full-screen message or photo from the phone. Any button closes it. */
class MessageActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); Ui.init(this)
        val pal = Palette.current(this)
        val root = FrameLayout(this).apply { setBackgroundColor(pal.bg) }
        val photo = intent.getStringExtra("photo")
        if (photo != null) {
            root.setBackgroundColor(Color.BLACK)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(photo, bounds)
            var s = 1; while (bounds.outWidth / s > 2400 || bounds.outHeight / s > 2400) s *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = s }
            root.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(BitmapFactory.decodeFile(photo, opts)) }, FrameLayout.LayoutParams(-1, -1))
        } else {
            root.addView(SkyView(this, pal, ambient = true), FrameLayout.LayoutParams(-1, -1))
            val card = Ui.col(this).apply {
                background = GlassDrawable(44f, fill = Ui.alpha(Ui.mix(pal.bg, pal.skyUpper, 0.5f), 0.9f), border = Color.argb(70, 255, 255, 255)); gravity = Gravity.CENTER_HORIZONTAL
                setPadding(Ui.px(80), Ui.px(56), Ui.px(80), Ui.px(60))
            }
            card.addView(Ui.text(this, "FROM YOUR PHONE", 20f, 600, pal.accent, 0.18f), Ui.lin(-2, -2))
            card.addView(Ui.text(this, intent.getStringExtra("text") ?: "", 64f, 600).apply { maxLines = 4; gravity = Gravity.CENTER }, Ui.lin(-2, -2, t = 24))
            card.addView(Ui.text(this, "Press any button to close", 20f, 400, Ui.SECONDARY), Ui.lin(-2, -2, t = 32))
            root.addView(card, FrameLayout.LayoutParams(Ui.px(1300), -2, Gravity.CENTER))
        }
        setContentView(root)
        root.postDelayed({ finish() }, intent.getIntExtra("seconds", 10) * 1000L)
    }
    override fun onKeyDown(k: Int, e: KeyEvent?): Boolean { finish(); return true }
}
