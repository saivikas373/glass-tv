package tv.glass.launcher

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Three full-screen Glass pages that share one look:
 *  - mode "setup": the first-run steps the public guide describes (Turn on Glass, Show what's playing, Pick a look, Done)
 *  - mode "repair": what "TV updated: run repair" means and the two ways to fix it
 *  - mode "about": version and licenses (opened from Control Center)
 */
class SetupActivity : Activity() {
    companion object {
        fun open(ctx: Context, mode: String) = ctx.startActivity(Intent(ctx, SetupActivity::class.java).putExtra("mode", mode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))

        fun keyServiceOn(ctx: Context): Boolean =
            (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains("${ctx.packageName}/")

        fun mediaAccessOn(ctx: Context): Boolean = MediaListener.connected || try {
            ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(ctx, MediaListener::class.java))
        } catch (e: Exception) { false }
    }

    private lateinit var pal: Palette
    private lateinit var card: LinearLayout
    private var step = 0
    private var triedA11y = false
    private var mode = "setup"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); Ui.init(this)
        pal = Palette.current(this)
        mode = intent.getStringExtra("mode") ?: "setup"
        step = b?.getInt("step") ?: 0; triedA11y = b?.getBoolean("tried") ?: false   // kept across a theme pick
        val root = FrameLayout(this).apply { setBackgroundColor(pal.bg) }
        val sky = SkyView(this, pal, ambient = true)
        root.addView(sky, FrameLayout.LayoutParams(-1, -1))
        val wrap = FrameLayout(this)
        wrap.addView(Backdrop(this, { listOf(sky) }, cornerDesign = 44f), FrameLayout.LayoutParams(-1, -1))
        card = Ui.col(this).apply {
            background = GlassDrawable(44f, fill = Color.argb(46, 255, 255, 255), border = Color.argb(60, 255, 255, 255), highlight = 0.28f)
            setPadding(Ui.px(88), Ui.px(72), Ui.px(88), Ui.px(72)); clipChildren = false; clipToPadding = false
        }
        wrap.addView(card, FrameLayout.LayoutParams(-1, -2))
        root.addView(wrap, FrameLayout.LayoutParams(Ui.px(1280), -2, Gravity.CENTER))
        setContentView(root)
        show()
    }

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); o.putInt("step", step); o.putBoolean("tried", triedA11y) }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); mode = i.getStringExtra("mode") ?: mode; show() }

    /** Coming back from a TV settings page: re-check what the user just switched on. */
    override fun onResume() { super.onResume(); if (mode == "setup") show() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mode == "setup" && step > 0) { step--; show() } else if (mode != "setup") finish()
        // On step 1 Back does nothing: Glass needs the setup once (Home still works through the stock home).
    }

    // ---------------- building blocks ----------------
    private fun eyebrow(t: String) = Ui.text(this, t, 20f, 600, pal.accent, 0.18f)
    private fun title(t: String) = Ui.text(this, t, 64f, 700).apply { letterSpacing = -0.02f; maxLines = 2 }
    private fun body(t: String, color: Int = Ui.SECONDARY) = Ui.text(this, t, 27f, 400, color).apply { maxLines = 6; setLineSpacing(Ui.px(8).toFloat(), 1f) }

    private fun button(t: String, primary: Boolean, onClick: () -> Unit): TextView = Ui.text(this, t, 25f, 600, if (primary) Color.parseColor("#0A1222") else Color.WHITE).apply {
        gravity = Gravity.CENTER; setPadding(Ui.px(40), 0, Ui.px(40), 0)
        background = if (primary) Ui.pill(Color.WHITE) else GlassDrawable(34f, fill = Color.argb(46, 255, 255, 255))
        Ui.focusScale(this, 1.07f); isClickable = true; setOnClickListener { onClick() }
    }

    private fun buttons(vararg b: TextView): LinearLayout = Ui.row(this).apply {
        clipChildren = false; clipToPadding = false
        b.forEachIndexed { i, v -> addView(v, Ui.lin(-2, 72, l = if (i == 0) 0 else 20)) }
    }

    private fun dots(n: Int, at: Int) = Ui.row(this).apply {
        (0 until n).forEach { i -> addView(View(this@SetupActivity).apply { background = Ui.pill(if (i == at) Color.WHITE else Color.argb(90, 255, 255, 255)) },
            Ui.lin(if (i == at) 32 else 10, 10, r = 10)) }
    }

    private fun openSettings(action: String, fallback: String? = null): Boolean {
        for (a in listOfNotNull(action, fallback)) try { startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: Exception) {}
        return false
    }

    private fun show() {
        card.removeAllViews()
        when (mode) { "repair" -> repair(); "about" -> about(); else -> setupStep() }
    }

    // ---------------- first-run setup ----------------
    private fun setupStep() {
        card.addView(dots(4, step))
        val focus: View
        when (step) {
            0 -> {
                val on = keyServiceOn(this)
                card.addView(eyebrow("STEP 1 OF 4"), Ui.lin(-2, -2, t = 36))
                card.addView(title("Turn on Glass"), Ui.lin(-1, -2, t = 14))
                card.addView(body(when {
                    on -> "Glass is on. The Home button now opens Glass."
                    else -> "Find Glass and turn it on. Glass uses it to catch the Home button and the remote's settings button."
                }, if (on) Color.parseColor("#7CF0B0") else Ui.SECONDARY), Ui.lin(-1, -2, t = 22))
                if (!on && triedA11y) {
                    val more = if (Build.VERSION.SDK_INT >= 33) "\nOn Android 13 and newer this is usually \"restricted settings\" for apps installed with Downloader." else ""
                    card.addView(body("Your TV blocked this. Use the computer setup in the guide (Full path).$more", Color.parseColor("#FFC27A")), Ui.lin(-1, -2, t = 18))
                }
                val open = button(if (on) "Next" else "Turn on Glass", true) {
                    if (keyServiceOn(this)) { step = 1; show() }
                    else { triedA11y = true; if (!openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS)) show() }
                }
                val skip = button(if (triedA11y && !on) "Continue without it" else "Later", false) { step = 1; show() }
                card.addView(buttons(open, skip), Ui.lin(-2, -2, t = 44)); focus = open
            }
            1 -> {
                val on = mediaAccessOn(this)
                card.addView(eyebrow("STEP 2 OF 4 · OPTIONAL"), Ui.lin(-2, -2, t = 36))
                card.addView(title("Show what's playing"), Ui.lin(-1, -2, t = 14))
                card.addView(body(if (on) "Done. Glass shows what's playing on Home and in Control Center."
                    else "Find Glass and allow it. Glass only reads what's playing (the title and progress), never your notifications.",
                    if (on) Color.parseColor("#7CF0B0") else Ui.SECONDARY), Ui.lin(-1, -2, t = 22))
                val open = button(if (on) "Next" else "Allow Now Playing", true) {
                    if (mediaAccessOn(this)) { step = 2; show() }
                    else if (!openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) { Hub.message(this, "This TV has no Notification access page. Skip this step.", "popup", 6) }
                }
                val skip = button("Skip", false) { step = 2; show() }
                card.addView(buttons(open, skip), Ui.lin(-2, -2, t = 44)); focus = open
            }
            2 -> {
                card.addView(eyebrow("STEP 3 OF 4"), Ui.lin(-2, -2, t = 36))
                card.addView(title("Pick a look"), Ui.lin(-1, -2, t = 14))
                card.addView(body("You can change it any time in Control Center → Theme."), Ui.lin(-1, -2, t = 16))
                val grid = Ui.col(this).apply { clipChildren = false }
                val cur = Store.of(this).theme; var sel: View? = null; var first: View? = null
                Palette.names(this).chunked(6).forEach { names ->
                    val row = Ui.row(this).apply { clipChildren = false }
                    names.forEachIndexed { i, n ->
                        val p = Palette.of(this, n)
                        val t = FrameLayout(this).apply {
                            background = GlassDrawable(22f, tintTop = p.skyUpper, tintBottom = p.skyHorizon, border = if (n == cur) Color.WHITE else Color.argb(60, 255, 255, 255), highlight = 0.3f)
                        }
                        t.addView(View(this).apply { background = Ui.pill(p.moon) }, FrameLayout.LayoutParams(Ui.px(18), Ui.px(18), Gravity.TOP or Gravity.END).apply { setMargins(0, Ui.px(12), Ui.px(14), 0) })
                        t.addView(Ui.text(this, n.replaceFirstChar { it.uppercase() }, 19f, 600), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(Ui.px(14), 0, 0, Ui.px(10)) })
                        Ui.focusScale(t, 1.08f); t.isClickable = true
                        t.setOnClickListener { Store.of(this).theme = n; Hub.listeners.toList().forEach { it() }; pal = Palette.current(this); recreate() }
                        row.addView(t, Ui.lin(0, 104, l = if (i == 0) 0 else 16, weight = 1f))
                        if (first == null) first = t; if (n == cur) sel = t
                    }
                    grid.addView(row, Ui.lin(-1, 104, t = 16))
                }
                card.addView(grid, Ui.lin(-1, -2, t = 16))
                val next = button("Next", true) { step = 3; show() }
                card.addView(buttons(next), Ui.lin(-2, -2, t = 36)); focus = sel ?: first ?: next
            }
            else -> {
                val on = keyServiceOn(this)
                val ip = Net.wifi()?.first?.hostAddress
                card.addView(eyebrow("STEP 4 OF 4"), Ui.lin(-2, -2, t = 36))
                card.addView(title("Done: press Home"), Ui.lin(-1, -2, t = 14))
                card.addView(body(if (on) "Home now goes to Glass. Press Home on your remote to try it. Hold Home to reach the TV's own home screen."
                    else "Glass is not turned on yet, so Home still opens the TV's own home. Go back to step 1, or use the computer setup in the guide."), Ui.lin(-1, -2, t = 22))
                card.addView(body("Phone remote: open http://${ip ?: "<tv ip>"}:${GlassServer.PORT} on your phone (same Wi-Fi), enter the PIN from Control Center, then set your weather city."),
                    Ui.lin(-1, -2, t = 18))
                Store.of(this).p.edit().putBoolean("setup_home_test", true).apply()
                val done = button("Done", true) { finishSetup() }
                card.addView(buttons(done), Ui.lin(-2, -2, t = 44)); focus = done
            }
        }
        focus.post { focus.requestFocus() }
    }

    private fun finishSetup() {
        Store.of(this).setupDone = true
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish()
    }

    // ---------------- "TV updated: run repair" ----------------
    private fun repair() {
        val st = Repair.check(this)
        card.addView(eyebrow("AUTO-REPAIR"))
        card.addView(title("TV updated: run repair"), Ui.lin(-1, -2, t = 14))
        card.addView(body(if (st.ok) "Everything looks right now." else "Glass found: " + st.problems.joinToString("; ") + "."), Ui.lin(-1, -2, t = 22))
        card.addView(Ui.text(this, "Installed with Downloader (Easy)", 26f, 600), Ui.lin(-1, -2, t = 34))
        card.addView(body("Turn Glass off and on in Accessibility. That brings the Home button back."), Ui.lin(-1, -2, t = 8))
        card.addView(Ui.text(this, "Installed with the computer (Full)", 26f, 600), Ui.lin(-1, -2, t = 26))
        card.addView(body("Turn USB debugging on (Settings → System → Developer options) and double-click Glass-Setup again. It re-applies everything and closes ADB at the end."), Ui.lin(-1, -2, t = 8))
        val a11y = button("Open Accessibility", true) { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS) }
        val dev = button("Developer options", false) { openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_SETTINGS) }
        val done = button("Mark as checked", false) { Repair.acknowledge(this); Hub.listeners.toList().forEach { it() }; finish() }
        card.addView(buttons(a11y, dev, done), Ui.lin(-2, -2, t = 40))
        a11y.post { a11y.requestFocus() }
    }

    // ---------------- About ----------------
    private fun about() {
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        card.addView(eyebrow("ABOUT"))
        card.addView(title("Glass TV $v"), Ui.lin(-1, -2, t = 14))
        card.addView(body("A calm home screen and phone remote for Android TV and Google TV. No account, no cloud, no ads. The phone remote only answers devices on your Wi-Fi."), Ui.lin(-1, -2, t = 18))
        val text = listOf("LICENSE-Glass.txt", "LICENSE-CleanYourTV.txt", "OFL-Inter.txt").joinToString("\n\n──────────\n\n") {
            try { assets.open("licenses/$it").bufferedReader().readText().trim() } catch (e: Exception) { "" }
        }
        val scroll = object : ScrollView(this) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {   // D-pad scrolls the text; Back still closes
                if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { smoothScrollBy(0, Ui.px(160)); return true }
                if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_DPAD_UP) { if (scrollY == 0) return super.dispatchKeyEvent(e); smoothScrollBy(0, -Ui.px(160)); return true }
                return super.dispatchKeyEvent(e)
            }
        }.apply {
            isFocusable = true; isVerticalScrollBarEnabled = true
            background = GlassDrawable(26f, fill = Color.argb(40, 0, 0, 0), border = Color.argb(40, 255, 255, 255), highlight = 0f)
            setPadding(Ui.px(32), Ui.px(24), Ui.px(32), Ui.px(24))
        }
        scroll.addView(Ui.text(this, text, 19f, 400, Ui.SECONDARY).apply { maxLines = Int.MAX_VALUE; ellipsize = null; setLineSpacing(Ui.px(5).toFloat(), 1f) })
        card.addView(scroll, Ui.lin(-1, 470, t = 28))
        card.addView(body("Press Back to close. Use ▲ ▼ to read the licenses."), Ui.lin(-1, -2, t = 18))
        scroll.post { scroll.requestFocus() }
    }
}
