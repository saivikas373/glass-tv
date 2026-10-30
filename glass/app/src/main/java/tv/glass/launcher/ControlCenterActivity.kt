package tv.glass.launcher

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

/** Control Center: design/control-center.png. A glass panel on the right, over whatever is playing. */
class ControlCenterActivity : Activity() {
    companion object {
        @Volatile var visible = false
        private var inst: ControlCenterActivity? = null
        fun open(ctx: Context, showPin: Boolean = false) = ctx.startActivity(Intent(ctx, ControlCenterActivity::class.java)
            .putExtra("pin", showPin).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        fun close() { inst?.finish() }
    }

    private lateinit var pal: Palette
    private lateinit var panel: FrameLayout
    private lateinit var body: LinearLayout
    private lateinit var sheet: LinearLayout
    private val dark = Color.parseColor("#0A1222")
    private val refreshers = ArrayList<() -> Unit>()
    private val mediaL: () -> Unit = { refreshers.forEach { it() } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); Ui.init(this); inst = this
        pal = Palette.current(this)
        val root = FrameLayout(this)
        // Real blur of the app behind needs Android 12+ and a TV whose graphics support cross-window blur.
        // Everywhere else (Android 9-11, and many TVs on 12+) the panel is a deeper solid tint instead.
        val blurBehind = Ui.blur && try { windowManager.isCrossWindowBlurEnabled } catch (e: Throwable) { false }
        if (blurBehind) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = Ui.px(40) }
        }
        root.addView(View(this).apply {   // soften the app behind
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.argb(40, 0, 0, 0), Color.argb(if (blurBehind) 110 else 170, 0, 0, 0)))
        }, FrameLayout.LayoutParams(-1, -1))
        panel = FrameLayout(this).apply {
            background = GlassDrawable(44f, fill = Ui.alpha(Ui.mix(pal.bg, pal.skyUpper, 0.35f), if (blurBehind) 0.72f else 0.99f), border = Color.argb(56, 255, 255, 255), highlight = 0.22f)
            elevation = Ui.px(30).toFloat()
        }
        root.addView(panel, FrameLayout.LayoutParams(Ui.px(688), Ui.px(1010), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = Ui.px(36) })
        body = Ui.col(this).apply { setPadding(Ui.px(40), Ui.px(36), Ui.px(38), Ui.px(36)); clipChildren = false; clipToPadding = false }
        panel.addView(body, FrameLayout.LayoutParams(-1, -1))
        build()
        sheet = Ui.col(this).apply { visibility = View.GONE }
        panel.addView(sheet, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        if (Store.of(this).motion) { panel.translationX = Ui.px(120).toFloat(); panel.alpha = 0f; panel.animate().translationX(0f).alpha(1f).setDuration(260).start() }
        if (intent.getBooleanExtra("pin", false)) panel.post { showPhones() }
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); if (i.getBooleanExtra("pin", false)) showPhones() }
    override fun onResume() { super.onResume(); visible = true; Hub.front = java.lang.ref.WeakReference(this); MediaListener.listeners += mediaL; tick.run() }
    override fun onPause() { super.onPause(); visible = false; if (Hub.front?.get() === this) Hub.front = null; MediaListener.listeners -= mediaL; panel.removeCallbacks(tick) }
    override fun onDestroy() { if (inst === this) inst = null; super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (sheet.visibility == View.VISIBLE) hidePhones() else finish() }

    private val tick = object : Runnable { override fun run() { refreshers.forEach { it() }; panel.postDelayed(this, 1000) } }

    // ---------------- helpers ----------------
    private fun card(r: Float = 34f) = GlassDrawable(r, fill = Color.argb(28, 255, 255, 255), border = Color.argb(46, 255, 255, 255), highlight = 0.18f)
    private fun focusable(v: View, s: Float = 1.05f, onClick: () -> Unit) { Ui.focusScale(v, s); v.isClickable = true; v.setOnClickListener { onClick() } }
    private fun label(t: String) = Ui.text(this, t, 16f, 600, Ui.SECONDARY, 0.14f)

    private fun build() {
        // Header: time, date, weather pill
        val head = FrameLayout(this)
        val tc = Ui.col(this)
        val time = Ui.text(this, "", 52f, 600); tc.addView(time)
        val date = Ui.text(this, "", 21f, 400, Ui.SECONDARY); tc.addView(date, Ui.lin(-2, -2, t = 4))
        head.addView(tc)
        val wp = Ui.text(this, "", 20f, 500).apply {
            background = GlassDrawable(22f, fill = Color.argb(24, 255, 255, 255)); gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.px(18), 0, Ui.px(20), 0); compoundDrawablePadding = Ui.px(10)
        }
        // Theme button next to the weather pill: opens the 12-theme picker (remote-friendly).
        val themeBtn = Ui.text(this, "Theme", 20f, 600).apply {
            background = GlassDrawable(22f, fill = Color.argb(24, 255, 255, 255)); gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.px(18), 0, Ui.px(20), 0); compoundDrawablePadding = Ui.px(10)
            setCompoundDrawablesRelativeWithIntrinsicBounds(Icons.of("image", Color.WHITE, Ui.px(20)), null, null, null)
        }
        focusable(themeBtn, 1.06f) { showThemes() }
        val about = Ui.text(this, "About", 20f, 600).apply {
            background = GlassDrawable(22f, fill = Color.argb(24, 255, 255, 255)); gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.px(18), 0, Ui.px(18), 0)
        }
        focusable(about, 1.06f) { SetupActivity.open(this, "about") }
        val pills = Ui.row(this); pills.addView(about, Ui.lin(-2, 44, r = 12)); pills.addView(themeBtn, Ui.lin(-2, 44, r = 12)); pills.addView(wp, Ui.lin(-2, 44))
        head.addView(pills, FrameLayout.LayoutParams(-2, Ui.px(44), Gravity.END or Gravity.TOP).apply { topMargin = Ui.px(2) })
        body.addView(head, Ui.lin(-1, 98))
        refreshers += {
            time.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
            // After a TV update the Home override can stop: say how to fix it right here.
            if (overrideBroken) { date.text = "Turn Glass off and on in Accessibility"; date.setTextColor(Color.parseColor("#FFC27A")) }
            else { date.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date()); date.setTextColor(Ui.SECONDARY) }
            wp.visibility = if (Store.of(this).hasWeatherPlace) View.VISIBLE else View.GONE
            wp.text = "${Weather.short()}${Weather.summary().let { if (it.isEmpty()) "" else " · $it" }}"
            wp.setCompoundDrawablesRelativeWithIntrinsicBounds(Icons.of(Weather.icon(), Color.WHITE, Ui.px(22)), null, null, null)
        }

        // Four square tiles
        val tilesRow = Ui.row(this)
        val wifi = squareTile("wifi", "Wi-Fi", "") { openSettings(android.provider.Settings.ACTION_WIFI_SETTINGS) }
        val bt = squareTile("bluetooth", "Bluetooth", "") { openSettings(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS) }
        val pic = squareTile("monitor", "Picture", "Adjust") { picture() }
        val snd = squareTile("volume", "Sound", "TV speakers") { openSettings(android.provider.Settings.ACTION_SOUND_SETTINGS) }
        listOf(wifi, bt, pic, snd).forEachIndexed { i, t -> tilesRow.addView(t.view, Ui.lin(138, 138, l = if (i == 0) 0 else 20)) }
        body.addView(tilesRow, Ui.lin(-1, 138))
        refreshers += {
            val w = Net.info(this); wifi.set(w.connected, w.quality)
            val on = try { getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true } catch (e: SecurityException) { false }
            bt.set(on, if (on) "On" else "Off")
        }

        // Now playing + Today
        val mid = Ui.row(this, Gravity.TOP)
        mid.addView(nowPlaying(), Ui.lin(296, 296))
        mid.addView(today(), Ui.lin(296, 296, l = 20))
        body.addView(mid, Ui.lin(-1, 296, t = 20))

        // Input
        val inCard = Ui.col(this).apply { background = card(30f); setPadding(Ui.px(20), Ui.px(17), Ui.px(20), Ui.px(13)) }
        inCard.addView(label("INPUT"))
        val chips = Ui.row(this)
        val ins = listOf("home" to "Home") + Inputs.list(this).filter { it.key != "hdmi4" }.map { it.key to it.label }
        ins.forEachIndexed { i, (k, l) ->
            val c = Ui.text(this, l, 19f, 600, if (i == 0) dark else Color.WHITE).apply {
                gravity = Gravity.CENTER; background = if (i == 0) Ui.pill(Color.WHITE) else GlassDrawable(22f, fill = Color.argb(24, 255, 255, 255), highlight = 0.12f)
            }
            focusable(c, 1.08f) { Inputs.open(this, k); finish() }
            chips.addView(c, Ui.lin(0, 44, l = if (i == 0) 0 else 12, weight = 1f))
        }
        inCard.addView(chips, Ui.lin(-1, 44, t = 11))
        // Brands that don't expose TV inputs to apps: no dead buttons, the row is hidden.
        if (ins.size > 1) body.addView(inCard, Ui.lin(-1, 107, t = 19)) else body.addView(View(this), Ui.lin(-1, 107, t = 19))

        // Network + apps per row
        val low = Ui.row(this, Gravity.TOP)
        low.addView(network(), Ui.lin(296, 140))
        low.addView(columns(), Ui.lin(296, 140, l = 20))
        body.addView(low, Ui.lin(-1, 141, t = 20))

        // Auto-repair, Motion, Phone
        val bot = Ui.row(this)
        val rep = smallTile("shield", "Auto-repair", "") {
            if (!repairOk || overrideBroken) SetupActivity.open(this, "repair")
            else { Store.of(this).autoRepair = !Store.of(this).autoRepair; refreshers.forEach { it() } }
        }
        rep.iconTint = Color.parseColor("#5EE29A")
        val motion = toggleTile("Motion") { Store.of(this).motion = !Store.of(this).motion; Hub.listeners.toList().forEach { it() } }
        val phone = smallTile("phone", "Phone", "Show PIN") { showPhones() }
        bot.addView(rep.view, Ui.lin(0, 75, weight = 1f)); bot.addView(motion.first, Ui.lin(0, 75, l = 16, weight = 1f)); bot.addView(phone.view, Ui.lin(0, 75, l = 16, weight = 1f))
        body.addView(bot, Ui.lin(-1, 75, t = 19))
        refreshers += {
            val s = Store.of(this)
            rep.sub.text = if (!s.autoRepair) "Off" else if (repairOk) "On" else "Run repair"
            motion.second(s.motion)
        }
        io.execute { val st = Repair.check(this); repairOk = st.ok
            overrideBroken = st.problems.any { it == "Home button override stopped" || it == "Glass key service is off" }
            runOnUiThread { refreshers.forEach { it() } } }
        wifi.view.post { wifi.view.requestFocus() }
    }
    @Volatile private var repairOk = true
    @Volatile private var overrideBroken = false

    private class Sq(val view: LinearLayout, val icon: ImageView, val title: TextView, val sub: TextView, val iconName: String) {
        var active = false
        fun set(on: Boolean, s: String) {
            sub.text = s
            if (on == active && view.background != null) return
            active = on
            val dark = Color.parseColor("#0A1222")
            view.background = if (on) GlassDrawable(32f, fill = Color.parseColor("#7CC4FF"), border = Color.argb(90, 255, 255, 255), highlight = 0.35f)
                              else GlassDrawable(32f, fill = Color.argb(28, 255, 255, 255), border = Color.argb(46, 255, 255, 255), highlight = 0.18f)
            val c = if (on) dark else Color.WHITE
            title.setTextColor(c); sub.setTextColor(if (on) Ui.alpha(dark, 0.8f) else Ui.SECONDARY)
            icon.setImageDrawable(Icons.of(iconName, c, Ui.px(28)))
        }
    }

    private fun squareTile(icon: String, t: String, s: String, onClick: () -> Unit): Sq {
        val v = Ui.col(this).apply { setPadding(Ui.px(20), Ui.px(20), Ui.px(14), Ui.px(18)) }
        val ic = ImageView(this); v.addView(ic, Ui.lin(30, 30))
        v.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        val tt = Ui.text(this, t, 20f, 600); v.addView(tt)
        val ss = Ui.text(this, s, 17f, 400, Ui.SECONDARY); v.addView(ss, Ui.lin(-2, -2, t = 3))
        focusable(v, 1.07f, onClick)
        return Sq(v, ic, tt, ss, icon).also { it.set(false, s) }
    }

    private class Small(val view: LinearLayout, val sub: TextView, val icon: ImageView, val name: String) {
        var iconTint: Int = Color.WHITE; set(v) { field = v; icon.setImageDrawable(Icons.of(name, v, Ui.px(26))) }
    }

    private fun smallTile(icon: String, t: String, s: String, onClick: () -> Unit): Small {
        val v = Ui.row(this).apply { background = card(28f); setPadding(Ui.px(20), 0, Ui.px(12), 0) }
        val ic = ImageView(this).apply { setImageDrawable(Icons.of(icon, Color.WHITE, Ui.px(26))) }
        v.addView(ic, Ui.lin(26, 26, r = 14))
        val c = Ui.col(this); c.addView(Ui.text(this, t, 19f, 600)); val sub = Ui.text(this, s, 16f, 400, Ui.SECONDARY); c.addView(sub, Ui.lin(-2, -2, t = 2))
        v.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
        focusable(v, 1.05f, onClick)
        return Small(v, sub, ic, icon)
    }

    private fun toggleTile(t: String, onClick: () -> Unit): Pair<View, (Boolean) -> Unit> {
        val v = Ui.row(this).apply { background = card(28f); setPadding(Ui.px(20), 0, Ui.px(16), 0) }
        v.addView(Ui.text(this, t, 19f, 600), LinearLayout.LayoutParams(0, -2, 1f))
        val track = FrameLayout(this); val knob = View(this).apply { background = Ui.pill(Color.WHITE) }
        track.addView(knob, FrameLayout.LayoutParams(Ui.px(30), Ui.px(30), Gravity.CENTER_VERTICAL).apply { leftMargin = Ui.px(3) })
        v.addView(track, Ui.lin(58, 36))
        focusable(v, 1.05f, onClick)
        return v to { on: Boolean ->
            track.background = Ui.pill(if (on) Color.parseColor("#7CC4FF") else Color.argb(60, 255, 255, 255))
            knob.translationX = if (on) Ui.px(22).toFloat() else 0f
        }
    }

    private fun nowPlaying(): View {
        val v = Ui.col(this).apply { background = card(); setPadding(Ui.px(24), Ui.px(24), Ui.px(24), Ui.px(20)) }
        val top = Ui.row(this)
        val art = FrameLayout(this).apply { background = GlassDrawable(18f, fill = Color.argb(30, 255, 255, 255), border = Color.argb(70, 255, 255, 255)) }
        art.addView(ImageView(this).apply { setImageDrawable(Icons.of("youtube", Color.WHITE, Ui.px(30))) }, FrameLayout.LayoutParams(Ui.px(32), Ui.px(32), Gravity.CENTER))
        top.addView(art, Ui.lin(78, 78, r = 16))
        val tc = Ui.col(this)
        tc.addView(Ui.text(this, "NOW PLAYING", 16f, 600, pal.accent, 0.14f))
        val app = Ui.text(this, "", 19f, 400, Ui.SECONDARY); tc.addView(app, Ui.lin(-2, -2, t = 4))
        top.addView(tc)
        v.addView(top)
        val title = Ui.text(this, "", 22f, 600).apply { maxLines = 1 }
        v.addView(title, Ui.lin(-1, -2, t = 20))
        val bar = FrameLayout(this).apply { background = Ui.pill(Color.argb(60, 255, 255, 255)) }
        val fill = View(this).apply { background = Ui.pill(Color.WHITE) }
        bar.addView(fill, FrameLayout.LayoutParams(0, -1))
        v.addView(bar, Ui.lin(-1, 5, t = 18))
        val ctr = Ui.row(this, Gravity.CENTER)
        fun btn(icon: String, size: Int, filled: Boolean, act: () -> Unit) = FrameLayout(this).apply {
            background = if (filled) Ui.pill(Color.WHITE) else null
            addView(ImageView(this@ControlCenterActivity).apply { tag = "i" }, FrameLayout.LayoutParams(Ui.px(size), Ui.px(size), Gravity.CENTER))
            focusable(this, 1.12f, act)
            setOnFocusChangeListener { x, f -> x.animate().scaleX(if (f) 1.12f else 1f).scaleY(if (f) 1.12f else 1f).setDuration(140).start()
                if (!filled) x.background = if (f) Ui.pill(Color.argb(40, 255, 255, 255)) else null }
        }
        val prev = btn("prev", 22, false) { MediaListener.controller?.transportControls?.skipToPrevious() }
        val play = btn("pause", 26, true) { MediaListener.toggle() }
        val next = btn("next", 22, false) { MediaListener.controller?.transportControls?.skipToNext() }
        ctr.addView(prev, Ui.lin(52, 52)); ctr.addView(play, Ui.lin(64, 64, l = 26, r = 26)); ctr.addView(next, Ui.lin(52, 52))
        v.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        v.addView(ctr, Ui.lin(-1, 66))
        refreshers += {
            val c = MediaListener.controller
            app.text = c?.packageName?.let { WatchNext.label(this, it) } ?: if (MediaListener.connected) "Nothing playing" else "Allow media access"
            title.text = MediaListener.title() ?: "—"
            fill.layoutParams = (fill.layoutParams as FrameLayout.LayoutParams).apply { width = (bar.width * MediaListener.progress()).toInt() }
            fill.requestLayout()
            play.findViewWithTag<ImageView>("i").setImageDrawable(Icons.of(if (MediaListener.playing()) "pause" else "play", dark, Ui.px(26), stroke = 3f))
            prev.findViewWithTag<ImageView>("i").setImageDrawable(Icons.of("prev", Color.WHITE, Ui.px(22)))
            next.findViewWithTag<ImageView>("i").setImageDrawable(Icons.of("next", Color.WHITE, Ui.px(22)))
        }
        return v
    }

    private fun today(): View {
        val v = Ui.col(this).apply { background = card(); setPadding(Ui.px(26), Ui.px(26), Ui.px(22), Ui.px(20)) }
        v.addView(Ui.text(this, "TODAY", 16f, 600, pal.accent, 0.14f).apply {
            compoundDrawablePadding = Ui.px(10); setCompoundDrawablesRelativeWithIntrinsicBounds(Icons.of("calendar", pal.accent, Ui.px(20)), null, null, null) })
        val list = Ui.col(this); v.addView(list, Ui.lin(-1, -2, t = 14))
        val ev = Cal.today(this); val fmt = SimpleDateFormat("h:mm a", Locale.getDefault()); val now = System.currentTimeMillis()
        if (ev.isEmpty()) {
            list.addView(Ui.text(this, if (Cal.available) "Nothing scheduled" else "No calendar connected", 21f, 600))
            list.addView(Ui.text(this, if (Cal.available) "Enjoy your day" else "Add a Google account calendar to see events", 17f, 400, Ui.SECONDARY).apply { maxLines = 2 }, Ui.lin(-2, -2, t = 6))
        }
        ev.filter { it.start >= now - 3600_000 }.ifEmpty { ev }.take(3).forEachIndexed { i, e ->
            val row = Ui.row(this, Gravity.TOP)
            row.addView(View(this).apply { background = Ui.pill(if (i == 0) pal.accent else Color.argb(90, 255, 255, 255)) }, Ui.lin(3, 50, r = 14))
            val c = Ui.col(this); c.addView(Ui.text(this, fmt.format(Date(e.start)), 17f, 400, Ui.SECONDARY)); c.addView(Ui.text(this, e.title, 20f, 600), Ui.lin(-2, -2, t = 2))
            row.addView(c); list.addView(row, Ui.lin(-1, -2, t = if (i == 0) 0 else 14))
        }
        return v
    }

    private fun network(): View {
        val v = Ui.col(this).apply { background = card(30f); setPadding(Ui.px(22), Ui.px(20), Ui.px(22), Ui.px(18)) }
        val r = Ui.row(this)
        r.addView(ImageView(this).apply { setImageDrawable(Icons.of("wifi", Color.WHITE, Ui.px(28))) }, Ui.lin(28, 28, r = 14))
        val c = Ui.col(this); val l1 = Ui.text(this, "", 20f, 600); val l2 = Ui.text(this, "", 16f, 400, Ui.SECONDARY)
        c.addView(l1); c.addView(l2, Ui.lin(-2, -2, t = 2)); r.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
        v.addView(r)
        val btn = Ui.text(this, "Run speed test", 19f, 600, dark).apply {
            gravity = Gravity.CENTER; background = Ui.pill(Color.argb(235, 255, 255, 255)); compoundDrawablePadding = Ui.px(10)
            setCompoundDrawablesRelativeWithIntrinsicBounds(Icons.of("gauge", dark, Ui.px(20)), null, null, null)
            setPadding(Ui.px(18), 0, Ui.px(18), 0)
        }
        focusable(btn, 1.05f) {
            btn.text = "Testing…"
            io.execute { val m = try { SpeedTest.run() } catch (e: Exception) { -1 }
                runOnUiThread { btn.text = if (m < 0) "Test failed: try again" else "$m Mbps · test again" } }
        }
        v.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        v.addView(btn, Ui.lin(-1, 46))
        refreshers += { val w = Net.info(this); l1.text = listOf(w.band, w.quality).filter { it.isNotEmpty() }.joinToString(" · "); l2.text = w.ip }
        return v
    }

    private fun columns(): View {
        val v = Ui.col(this).apply { background = card(30f); setPadding(Ui.px(22), Ui.px(22), Ui.px(22), Ui.px(18)) }
        v.addView(label("APPS PER ROW"))
        val seg = Ui.row(this).apply { background = Ui.pill(Color.argb(60, 0, 0, 0)); setPadding(Ui.px(6), Ui.px(6), Ui.px(6), Ui.px(6)) }
        val opts = (4..7).map { n ->
            Ui.text(this, "$n", 21f, 600).apply { gravity = Gravity.CENTER; focusable(this, 1.1f) { Store.of(this@ControlCenterActivity).columns = n; Hub.listeners.toList().forEach { it() }; refreshers.forEach { it() } } }
        }
        opts.forEachIndexed { i, o -> seg.addView(o, Ui.lin(0, 44, l = if (i == 0) 0 else 4, weight = 1f)) }
        v.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        v.addView(seg, Ui.lin(-1, 56))
        refreshers += { val n = Store.of(this).columns; opts.forEachIndexed { i, o ->
            val on = i + 4 == n; o.background = if (on) Ui.pill(Color.WHITE) else null; o.setTextColor(if (on) dark else Color.WHITE) } }
        return v
    }

    private fun picture() {
        // TCL's own picture menu if present, otherwise Android's display settings.
        for (a in listOf("com.tcl.settings.PICTURE", "android.settings.DISPLAY_SETTINGS")) {
            try { startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
    }

    // ---------------- phone PIN and paired phones ----------------
    private fun showPhones() {
        sheet.removeAllViews(); sheet.setPadding(Ui.px(44), Ui.px(44), Ui.px(44), Ui.px(40))
        sheet.background = GlassDrawable(44f, fill = Ui.alpha(Ui.mix(pal.bg, pal.skyUpper, 0.3f), 0.985f), border = Color.argb(56, 255, 255, 255))
        sheet.addView(Ui.text(this, "PHONE CONTROLLER", 16f, 600, pal.accent, 0.16f))
        val ip = Net.wifi()?.first?.hostAddress ?: "<tv ip>"
        sheet.addView(Ui.text(this, "On your phone, open", 21f, 400, Ui.SECONDARY), Ui.lin(-2, -2, t = 22))
        sheet.addView(Ui.text(this, "http://$ip:${GlassServer.PORT}", 30f, 600), Ui.lin(-2, -2, t = 6))
        sheet.addView(Ui.text(this, "and enter this PIN", 21f, 400, Ui.SECONDARY), Ui.lin(-2, -2, t = 22))
        val locked = Pairing.lockedUntil > System.currentTimeMillis()
        sheet.addView(Ui.text(this, if (locked) "Locked" else Pairing.pin(this).chunked(3).joinToString(" "), 84f, 700, if (locked) Color.parseColor("#FFB25C") else Color.WHITE, 0.06f), Ui.lin(-2, -2, t = 6))
        sheet.addView(Ui.text(this, if (locked) "5 wrong PINs: pairing is paused for 5 minutes" else "Only phones on this Wi-Fi can connect. The PIN changes on every reboot.", 17f, 400, Ui.SECONDARY).apply { maxLines = 2 }, Ui.lin(-1, -2, t = 4))
        sheet.addView(Ui.text(this, "PAIRED PHONES", 16f, 600, Ui.SECONDARY, 0.14f), Ui.lin(-2, -2, t = 36))
        val phones = Store.of(this).phones(); val df = SimpleDateFormat("MMM d", Locale.getDefault())
        if (phones.length() == 0) sheet.addView(Ui.text(this, "None yet", 20f, 500), Ui.lin(-2, -2, t = 12))
        for (i in 0 until minOf(phones.length(), 5)) { val p = phones.getJSONObject(i)
            sheet.addView(Ui.text(this, "${p.optString("name")}  ·  ${p.optString("ip")}  ·  ${df.format(Date(p.optLong("at")))}", 20f, 500), Ui.lin(-2, -2, t = 12)) }
        if (phones.length() > 5) sheet.addView(Ui.text(this, "and ${phones.length() - 5} more", 18f, 400, Ui.SECONDARY), Ui.lin(-2, -2, t = 8))
        sheet.addView(Ui.text(this, "Remote keys: " + if (KeyService.instance != null) "ready (on the TV, no network port)" else "Glass key service is off",
            17f, 400, Ui.SECONDARY).apply { maxLines = 2 }, Ui.lin(-1, -2, t = 22))
        sheet.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        val btns = Ui.row(this).apply { clipChildren = false; setPadding(Ui.px(6), Ui.px(6), Ui.px(6), Ui.px(6)) }
        sheet.clipChildren = false; sheet.clipToPadding = false
        val forget = Ui.text(this, "Forget all phones", 21f, 600).apply { gravity = Gravity.CENTER
            background = GlassDrawable(28f, fill = Color.argb(70, 255, 90, 90), border = Color.argb(110, 255, 140, 140)) }
        focusable(forget, 1.05f) { Pairing.forgetAll(this); showPhones() }
        val done = Ui.text(this, "Done", 21f, 600, dark).apply { gravity = Gravity.CENTER; background = Ui.pill(Color.WHITE) }
        focusable(done, 1.05f) { hidePhones() }
        btns.addView(forget, Ui.lin(0, 58, weight = 1.4f)); btns.addView(done, Ui.lin(0, 58, l = 16, weight = 1f))
        sheet.addView(btns, Ui.lin(-1, 72))
        sheet.visibility = View.VISIBLE; body.visibility = View.INVISIBLE
        done.requestFocus()
    }

    // ---------------- theme picker ----------------
    private fun showThemes() {
        sheet.removeAllViews(); sheet.setPadding(Ui.px(40), Ui.px(40), Ui.px(40), Ui.px(36))
        sheet.background = GlassDrawable(44f, fill = Ui.alpha(Ui.mix(pal.bg, pal.skyUpper, 0.3f), 0.985f), border = Color.argb(56, 255, 255, 255))
        sheet.clipChildren = false; sheet.clipToPadding = false
        sheet.addView(Ui.text(this, "THEME", 16f, 600, pal.accent, 0.16f))
        sheet.addView(Ui.text(this, "Pick a look. Home changes right away.", 19f, 400, Ui.SECONDARY), Ui.lin(-2, -2, t = 10, b = 22))
        val names = Palette.names(this); val cur = Store.of(this).theme
        var first: View? = null; var selected: View? = null
        names.chunked(3).forEach { rowNames ->
            val row = Ui.row(this).apply { clipChildren = false }
            rowNames.forEachIndexed { i, n ->
                val p = Palette.of(this, n)
                val t = FrameLayout(this).apply {
                    background = GlassDrawable(24f, tintTop = p.skyUpper, tintBottom = p.skyHorizon,
                        border = if (n == cur) p.accent else Color.argb(60, 255, 255, 255), highlight = 0.3f)
                    if (n == cur) foreground = GlassDrawable(24f, fill = Color.TRANSPARENT, border = Color.WHITE, highlight = 0f)
                }
                t.addView(View(this).apply { background = Ui.pill(p.moon) }, FrameLayout.LayoutParams(Ui.px(22), Ui.px(22), Gravity.TOP or Gravity.END).apply { setMargins(0, Ui.px(14), Ui.px(16), 0) })
                t.addView(View(this).apply { background = Ui.pill(p.accent) }, FrameLayout.LayoutParams(Ui.px(28), Ui.px(6), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, Ui.px(16), Ui.px(18)) })
                t.addView(Ui.text(this, n.replaceFirstChar { it.uppercase() }, 20f, 600), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(Ui.px(16), 0, 0, Ui.px(12)) })
                focusable(t, 1.08f) {
                    Store.of(this).theme = n
                    Hub.listeners.toList().forEach { it() }   // Home behind repaints now
                    pal = Palette.current(this); showThemes()
                }
                row.addView(t, Ui.lin(0, 128, l = if (i == 0) 0 else 16, weight = 1f))
                if (first == null) first = t
                if (n == cur) selected = t
            }
            sheet.addView(row, Ui.lin(-1, 128, b = 16))
        }
        sheet.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        sheet.visibility = View.VISIBLE; body.visibility = View.INVISIBLE
        (selected ?: first)?.requestFocus()
    }

    private fun hidePhones() { sheet.visibility = View.GONE; body.visibility = View.VISIBLE; body.findFocus() ?: body.getChildAt(1)?.requestFocus() }
}
