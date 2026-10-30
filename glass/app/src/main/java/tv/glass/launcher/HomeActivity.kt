package tv.glass.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** Glass Home: design/home.png. Everything is positioned in 1920x1080 design units. */
class HomeActivity : Activity() {
    companion object { @Volatile var visible = false }

    private lateinit var pal: Palette
    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var content: FrameLayout
    private lateinit var sky: SkyView
    private lateinit var photoBg: ImageView
    private lateinit var grid: FrameLayout
    private lateinit var resume: TextView
    private lateinit var more: TextView
    private lateinit var chip: TextView
    private lateinit var eyebrow: TextView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var bar: View
    private lateinit var barFill: View
    private lateinit var dots: LinearLayout
    private lateinit var shelfHint: TextView
    private lateinit var shelfText: LinearLayout
    private lateinit var clock: TextView
    private lateinit var weather: TextView
    private lateinit var weatherIcon: ImageView
    private lateinit var bannerBox: FrameLayout
    private lateinit var repairBox: FrameLayout
    private lateinit var topRight: View
    private val tiles = ArrayList<Tile>()
    private var pages: List<Page> = emptyList()
    private var page = 0
    private var cols = 5

    private val hubListener: () -> Unit = { applyTheme(); showBanner(); if (cols != Store.of(this).columns) buildGrid() }
    private val weatherListener: () -> Unit = { updateWeather() }
    private val mediaListener: () -> Unit = { if (pages.isEmpty() || pages[page].kind == "media") buildPages() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.init(this); SecLog.init(this)
        pal = Palette.current(this)
        root = FrameLayout(this).apply { setBackgroundColor(pal.bg); clipChildren = false }
        scroll = object : ScrollView(this) {
            override fun computeScrollDeltaToGetChildRectOnScreen(r: Rect?) = 0   // we scroll by rows ourselves
        }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipChildren = false; isFocusable = false }
        content = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        sky = SkyView(this, pal)
        content.addView(sky, FrameLayout.LayoutParams(-1, Ui.px(1080)))
        photoBg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        content.addView(photoBg, FrameLayout.LayoutParams(-1, Ui.px(760)))
        content.addView(View(this).apply {   // scrim so text reads over photos
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.argb(170, 0, 0, 0), Color.TRANSPARENT))
            alpha = 0f; tag = "scrim" }, FrameLayout.LayoutParams(-1, Ui.px(760)))
        buildShelf()
        grid = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        content.addView(grid, FrameLayout.LayoutParams(-1, -2).apply { topMargin = Ui.px(711) })
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        // Time/weather and the repair chip belong to the top of Home; fade them once the rows scroll up.
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> val a = (1f - y / Ui.px(200f).toFloat()).coerceIn(0f, 1f); topRight.alpha = a; repairBox.alpha = a }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        buildTopRight()
        repairBox = FrameLayout(this)
        root.addView(repairBox, Ui.frame(-2, -2, 96, 40))
        setContentView(root)
        buildGrid()
        handleIntent(intent)
        Hub.listeners += hubListener   // kept while Control Center covers Home, so theme picks repaint at once
    }

    override fun onDestroy() { Hub.listeners -= hubListener; super.onDestroy() }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i); handleIntent(i)
        // Pressing Home while already on Home: back to the first tile and the top.
        scroll.smoothScrollTo(0, 0); tiles.firstOrNull()?.requestFocus()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra("ack_repair", false) == true) Repair.acknowledge(this)
        // Glass-Setup (computer, over ADB) already switched everything on: skip the first-run screen.
        if (i?.getBooleanExtra("from_setup", false) == true) { val s = Store.of(this); s.setupDone = true; s.installKind = "full"; Repair.acknowledge(this) }
    }

    // ---------------- ambient fallback ----------------
    /**
     * On Google TV a third-party screensaver can only be chosen over ADB. If Glass isn't the system screensaver,
     * Glass shows its Ambient screen itself after Home has been idle. If it is, the TV's own timer does it.
     */
    private val idle = Runnable {
        if (visible && Store.of(this).ambient && !AmbientActivity.isSystemScreensaver(this))
            startActivity(Intent(this, AmbientActivity::class.java))
    }
    private fun resetIdle() { root.removeCallbacks(idle); root.postDelayed(idle, Store.of(this).p.getInt("ambient_idle_min", 5) * 60_000L) }
    override fun dispatchKeyEvent(e: KeyEvent): Boolean { resetIdle(); return super.dispatchKeyEvent(e) }

    override fun onResume() {
        super.onResume(); visible = true; Hub.front = java.lang.ref.WeakReference(this)
        StockHome.pkg(this)   // remember the TV's own Home the first time
        val st = Store.of(this)
        if (!st.setupDone && st.p.getBoolean("setup_home_test", false) && SetupActivity.keyServiceOn(this)) {
            st.setupDone = true; Hub.message(this, "Home works: Glass is your home screen now", "popup", 6)   // pressed Home on setup step 4
        }
        if (!st.setupDone) SetupActivity.open(this, "setup")
        resetIdle()
        Weather.listeners += weatherListener; MediaListener.listeners += mediaListener
        ServerService.start(this)
        applyTheme()
        if (Apps.load(this).map { it.pkg } != tiles.map { it.app.pkg }) buildGrid()
        buildPages(); updateWeather(); Weather.refresh(this); showBanner(); checkRepair()
        if (currentFocus == null) tiles.firstOrNull()?.requestFocus()
        root.removeCallbacks(rotate); root.postDelayed(rotate, 10_000)
    }

    override fun onPause() {
        super.onPause(); visible = false; if (Hub.front?.get() === this) Hub.front = null
        Weather.listeners -= weatherListener; MediaListener.listeners -= mediaListener
        root.removeCallbacks(rotate); root.removeCallbacks(idle)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { scroll.smoothScrollTo(0, 0); tiles.firstOrNull()?.requestFocus() }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        // Without the key service (or before it binds) Menu/Settings still open Control Center from Home.
        if (k == KeyEvent.KEYCODE_MENU || KeyService.isSettingsKey(this, k)) { ControlCenterActivity.open(this); return true }
        if (shelfKey(k)) return true
        return super.onKeyDown(k, e)
    }

    /** Left/Right on the Top Shelf buttons flips pages (Continue watching, Today, Photos), like the dots show. */
    fun shelfKey(k: Int): Boolean {
        if (pages.size < 2) return false
        val lastBtn = if (more.visibility == View.VISIBLE) more else resume
        val step = when {
            k == KeyEvent.KEYCODE_DPAD_LEFT && resume.isFocused -> -1
            k == KeyEvent.KEYCODE_DPAD_RIGHT && lastBtn.isFocused -> 1
            else -> return false
        }
        page = (page + step + pages.size) % pages.size
        showPage(true)
        resume.requestFocus()
        root.removeCallbacks(rotate); root.postDelayed(rotate, 10_000)
        return true
    }

    // ---------------- theme ----------------
    private fun applyTheme() {
        val p = Palette.current(this)
        if (p.name == pal.name) return
        pal = p; root.setBackgroundColor(p.bg); sky.setPalette(p)
        tiles.forEach { it.invalidate() }; showBanner()
    }

    // ---------------- top shelf ----------------
    class Page(val kind: String, val chip: String, val chipIcon: String, val eyebrow: String, val title: String, val subtitle: String,
               val progress: Float?, val primary: String, val primaryAct: () -> Unit, val secondary: String?, val secondaryAct: (() -> Unit)?, val photo: File? = null)

    private fun buildShelf() {
        val chipRow = Ui.row(this)
        chip = Ui.text(this, "YouTube", 17f, 600).apply {
            background = Ui.pill(Color.argb(46, 255, 255, 255), stroke = Color.argb(60, 255, 255, 255))
            setPadding(Ui.px(16), 0, Ui.px(18), 0); gravity = Gravity.CENTER_VERTICAL; compoundDrawablePadding = Ui.px(8)
        }
        chipRow.addView(chip, Ui.lin(-2, 38))
        eyebrow = Ui.text(this, "CONTINUE WATCHING", 18f, 600, Color.argb(230, 255, 255, 255), 0.2f)
        chipRow.addView(eyebrow, Ui.lin(-2, -2, l = 16))
        shelfText = Ui.col(this).apply { clipChildren = false; clipToPadding = false }
        shelfText.addView(chipRow)
        title = Ui.text(this, "", 80f, 700).apply { letterSpacing = -0.02f }
        shelfText.addView(title, Ui.lin(1300, -2, t = 22))
        val progRow = Ui.row(this)
        bar = FrameLayout(this).apply { background = Ui.pill(Color.argb(70, 255, 255, 255)) }
        barFill = View(this).apply { background = Ui.pill(Color.WHITE) }
        (bar as FrameLayout).addView(barFill, FrameLayout.LayoutParams(Ui.px(95), -1))
        progRow.addView(bar, Ui.lin(216, 5, r = 18))
        subtitle = Ui.text(this, "Pick up where you left off", 23f, 400, Ui.SECONDARY)
        progRow.addView(subtitle)
        shelfText.addView(progRow, Ui.lin(-2, -2, t = 26))
        val btns = Ui.row(this).apply { clipChildren = false; clipToPadding = false }
        resume = Ui.text(this, "Resume", 23f, 600, Color.parseColor("#0A1222")).apply {
            id = View.generateViewId(); gravity = Gravity.CENTER; compoundDrawablePadding = Ui.px(14)
            setPadding(Ui.px(36), 0, Ui.px(40), 0); background = Ui.pill(Color.WHITE)
        }
        more = Ui.text(this, "More Info", 23f, 600).apply {
            id = View.generateViewId(); gravity = Gravity.CENTER; setPadding(Ui.px(34), 0, Ui.px(34), 0)
            background = GlassDrawable(30f, fill = Color.argb(46, 255, 255, 255))
        }
        for (v in listOf(resume, more)) {
            Ui.focusScale(v, 1.07f)
            val base = v.onFocusChangeListener
            v.setOnFocusChangeListener { x, f -> base.onFocusChange(x, f); if (f) { scroll.smoothScrollTo(0, 0); styleButtons() } else styleButtons()
                shelfHint.animate().alpha(if (resume.isFocused || more.isFocused) 1f else 0f).setDuration(200).start() }
        }
        resume.setOnClickListener { pages.getOrNull(page)?.primaryAct?.invoke() }
        more.setOnClickListener { pages.getOrNull(page)?.secondaryAct?.invoke() }
        btns.addView(resume, Ui.lin(-2, 62)); btns.addView(more, Ui.lin(-2, 62, l = 18))
        shelfText.addView(btns, Ui.lin(-2, -2, t = 30))
        content.addView(shelfText, Ui.frame(1500, -2, 96, 297))
        shelfHint = Ui.text(this, "◀  ▶  Today · Photos", 17f, 500, Ui.TERTIARY).apply { alpha = 0f }
        content.addView(shelfHint, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { topMargin = Ui.px(632); rightMargin = Ui.px(95) })
        dots = Ui.row(this)
        content.addView(dots, FrameLayout.LayoutParams(-2, Ui.px(10), Gravity.END or Gravity.TOP).apply { topMargin = Ui.px(606); rightMargin = Ui.px(95) })
    }

    /** Resume is white; when More Info has focus it turns white instead (tvOS: the focused button lifts). */
    private fun styleButtons() {
        val moreFocused = more.isFocused
        resume.background = if (moreFocused) GlassDrawable(30f, fill = Color.argb(46, 255, 255, 255)) else Ui.pill(Color.WHITE)
        resume.setTextColor(if (moreFocused) Color.WHITE else Color.parseColor("#0A1222"))
        resume.setCompoundDrawablesRelativeWithIntrinsicBounds(Icons.of("play", resume.currentTextColor, Ui.px(16)), null, null, null)
        more.background = if (moreFocused) Ui.pill(Color.WHITE) else GlassDrawable(30f, fill = Color.argb(46, 255, 255, 255))
        more.setTextColor(if (moreFocused) Color.parseColor("#0A1222") else Color.WHITE)
    }

    private fun buildPages() {
        val list = ArrayList<Page>()
        val watch = WatchNext.load(this)
        for (w in watch) list += Page("watch", w.appLabel, iconFor(w.pkg), "CONTINUE WATCHING", w.title, "Pick up where you left off", w.progress,
            "Resume", { if (w.intent != null) startSafe(w.intent) else Apps.launch(this, w.pkg) }, "More Info", { Apps.launch(this, w.pkg) })
        val mt = MediaListener.title()
        val mpkg = MediaListener.controller?.packageName
        if (watch.isEmpty() && mt != null && mpkg != null) list += Page("media", WatchNext.label(this, mpkg), iconFor(mpkg), "CONTINUE WATCHING", mt,
            "Pick up where you left off", MediaListener.progress(), "Resume", { Apps.launch(this, mpkg) }, "More Info", { ControlCenterActivity.open(this) })
        if (list.isEmpty()) {
            val top = Store.of(this).launches().filterKeys { it != "com.android.tv.settings" }.maxByOrNull { it.value }?.key ?: "com.google.android.youtube.tv"
            val app = Apps.cache.firstOrNull { it.pkg == top } ?: Apps.cache.firstOrNull()
            if (app != null) list += Page("watch", app.label, iconFor(app.pkg), "CONTINUE WATCHING", "Back to ${app.label}", "Pick up where you left off",
                0.42f, "Resume", { Apps.launch(this, app.pkg) }, "More Info", { Apps.launch(this, app.pkg) })
        }
        // Today
        val ev = Cal.today(this); val now = System.currentTimeMillis(); val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        val next = ev.firstOrNull { it.start >= now - 30 * 60_000 }
        list += when {
            !Cal.available -> Page("today", "Calendar", "calendar", "TODAY", "Today's calendar", "No calendar is connected to this TV yet", null,
                "Control Center", { ControlCenterActivity.open(this) }, null, null)
            next == null -> Page("today", "Calendar", "calendar", "TODAY", "Nothing else today", SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date()), null,
                "Control Center", { ControlCenterActivity.open(this) }, null, null)
            else -> Page("today", "Calendar", "calendar", "TODAY · ${fmt.format(Date(next.start))}", next.title,
                (ev.size - 1).let { if (it > 0) "and $it more today" else "Your only event today" }, null, "Control Center", { ControlCenterActivity.open(this) }, null, null)
        }
        // Photos from the phone
        val ph = Hub.photos(this)
        list += if (ph.isNotEmpty()) Page("photos", "Photos", "image", "FROM YOUR PHONE", "Your photos", "${ph.size} photo${if (ph.size == 1) "" else "s"} sent from your phone",
            null, "View", { startActivity(Intent(this, MessageActivity::class.java).putExtra("photo", ph.first().absolutePath).putExtra("seconds", 60)) }, null, null, ph.first())
        else Page("photos", "Photos", "image", "PHOTOS", "Send photos from your phone", "Open http://${Net.wifi()?.first?.hostAddress ?: "<tv ip>"}:${GlassServer.PORT} on your phone",
            null, "Show PIN", { ControlCenterActivity.open(this, showPin = true) }, null, null)
        pages = list
        if (page >= pages.size) page = 0
        showPage(false)
    }

    private fun iconFor(pkg: String) = when {
        pkg.contains("youtube") -> "youtube"; pkg.contains("netflix") -> "film"; pkg.contains("spotify") || pkg.contains("music") -> "music"
        pkg.contains("amazon") -> "tvplay"; else -> "playcircle"
    }

    private fun showPage(animate: Boolean) {
        val p = pages.getOrNull(page) ?: return
        val apply = {
            chip.text = p.chip
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(Icons.of(p.chipIcon, Color.WHITE, Ui.px(17)), null, null, null)
            eyebrow.text = p.eyebrow; title.text = p.title; subtitle.text = p.subtitle
            bar.visibility = if (p.progress != null) View.VISIBLE else View.GONE
            barFill.layoutParams = (barFill.layoutParams as FrameLayout.LayoutParams).apply { width = (Ui.px(216) * (p.progress ?: 0f)).toInt().coerceAtLeast(Ui.px(8)) }
            resume.text = p.primary; more.text = p.secondary ?: ""; more.visibility = if (p.secondary != null) View.VISIBLE else View.GONE
            if (more.visibility != View.VISIBLE && more.isFocused) resume.requestFocus()
            styleButtons()
            val scrim = content.findViewWithTag<View>("scrim")
            if (p.photo != null) {
                val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                photoBg.setImageBitmap(BitmapFactory.decodeFile(p.photo.absolutePath, o)); photoBg.animate().alpha(1f).setDuration(500).start(); scrim.animate().alpha(1f).start()
            } else { photoBg.animate().alpha(0f).setDuration(400).start(); scrim.animate().alpha(0f).start() }
            dots.removeAllViews()
            pages.indices.forEach { i ->
                dots.addView(View(this).apply { background = Ui.pill(if (i == page) Color.WHITE else Color.argb(110, 255, 255, 255)) },
                    Ui.lin(if (i == page) 28 else 8, 8, l = 10))
            }
        }
        if (animate && Store.of(this).motion) shelfText.animate().alpha(0f).setDuration(220).withEndAction { apply(); shelfText.animate().alpha(1f).setDuration(320).start() }.start()
        else apply()
    }

    private val rotate = object : Runnable {
        override fun run() {
            if (pages.size > 1 && !resume.isFocused && !more.isFocused && Store.of(this@HomeActivity).motion) { page = (page + 1) % pages.size; showPage(true) }
            root.postDelayed(this, 10_000)
        }
    }

    private fun startSafe(i: Intent) = try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { i.`package`?.let { Apps.launch(this, it) } }

    // ---------------- top right: time, weather, phone banner ----------------
    private fun buildTopRight() {
        val r = Ui.row(this); topRight = r
        weatherIcon = ImageView(this)
        r.addView(weatherIcon, Ui.lin(28, 28, r = 10))
        weather = Ui.text(this, "--°", 26f, 600)
        r.addView(weather)
        clock = Ui.text(this, "", 26f, 600)
        r.addView(clock, Ui.lin(-2, -2, l = 28))
        root.addView(r, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { topMargin = Ui.px(44); rightMargin = Ui.px(80) })
        bannerBox = FrameLayout(this)
        root.addView(bannerBox, FrameLayout.LayoutParams(Ui.px(454), -2, Gravity.END or Gravity.TOP).apply { topMargin = Ui.px(102); rightMargin = Ui.px(79) })
        val tick = object : Runnable { override fun run() {
            clock.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            root.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000) } }
        tick.run()
    }

    private fun updateWeather() {
        val set = Store.of(this).hasWeatherPlace   // no city yet: no weather shown (set it from the phone)
        weather.visibility = if (set) View.VISIBLE else View.GONE; weatherIcon.visibility = weather.visibility
        weather.text = Weather.short()
        weatherIcon.setImageDrawable(Icons.of(Weather.icon(), Color.WHITE, Ui.px(28)))
    }

    private fun showBanner() {
        bannerBox.removeAllViews()
        val m = Hub.current; val note = Store.of(this).note
        val v = when {
            m != null && m.mode == "warn" -> MessageCard.build(this, "Glass · security", m.text, true)
            m != null -> MessageCard.build(this, "From your phone · now", m.text)
            note.isNotEmpty() -> MessageCard.build(this, "Note from your phone", note)
            else -> return
        }
        bannerBox.addView(v, FrameLayout.LayoutParams(-1, -2))
        if (Store.of(this).motion) { v.alpha = 0f; v.translationY = -Ui.px(16).toFloat(); v.animate().alpha(1f).translationY(0f).setDuration(280).start() }
    }

    private fun checkRepair() {
        if (!Store.of(this).autoRepair) { repairBox.removeAllViews(); return }
        io.execute {
            val st = Repair.check(this)
            runOnUiThread {
                repairBox.removeAllViews(); resume.nextFocusUpId = View.NO_ID; more.nextFocusUpId = View.NO_ID
                if (!st.ok) {
                    val t = Ui.text(this, "TV updated: run repair", 20f, 600).apply {
                        id = View.generateViewId()
                        background = GlassDrawable(22f, fill = Color.argb(120, 120, 60, 20), border = Color.argb(120, 255, 190, 120))
                        setPadding(Ui.px(18), Ui.px(10), Ui.px(22), Ui.px(10)); compoundDrawablePadding = Ui.px(10)
                        setCompoundDrawablesRelativeWithIntrinsicBounds(Icons.of("alert", Color.parseColor("#FFC27A"), Ui.px(22)), null, null, null)
                        Ui.focusScale(this, 1.06f); isClickable = true
                        setOnClickListener { SetupActivity.open(this@HomeActivity, "repair") }   // explains both fixes
                    }
                    repairBox.addView(t)
                    resume.nextFocusUpId = t.id; more.nextFocusUpId = t.id; t.nextFocusDownId = resume.id
                }
            }
        }
    }

    // ---------------- app grid ----------------
    private fun buildGrid() {
        grid.removeAllViews(); tiles.clear()
        cols = Store.of(this).columns
        val apps = Apps.cache.ifEmpty { Apps.load(this) }
        val gap = 48f; val w = (1728f - (cols - 1) * gap) / cols; val h = w * 0.6f
        val pitch = h + 73f
        apps.forEachIndexed { i, app ->
            val r = i / cols; val c = i % cols
            val t = Tile(this, app, w, h)
            t.id = View.generateViewId()
            val x = 96f + c * (w + gap); val y = r * pitch
            grid.addView(t, FrameLayout.LayoutParams(Ui.px(w), Ui.px(h)).apply { leftMargin = Ui.px(x); topMargin = Ui.px(y) })
            grid.addView(t.label, FrameLayout.LayoutParams(Ui.px(w + 40), -2).apply { leftMargin = Ui.px(x - 20); topMargin = Ui.px(y + h + 25) })
            t.setOnClickListener { Apps.launch(this, app.pkg) }
            t.onFocused = { if (r == 0) scroll.smoothScrollTo(0, 0) else scroll.smoothScrollTo(0, Ui.px(711 + r * pitch - 240)) }
            tiles += t
        }
        val rows = (apps.size + cols - 1) / cols
        grid.minimumHeight = Ui.px(rows * pitch + 300)
        tiles.forEachIndexed { i, t -> if (i < cols) t.nextFocusUpId = resume.id }
        resume.nextFocusDownId = tiles.firstOrNull()?.id ?: View.NO_ID
        more.nextFocusDownId = tiles.getOrNull(1)?.id ?: resume.nextFocusDownId
        tiles.firstOrNull()?.requestFocus()
    }
}

/** One app: its real banner, or its icon on a tinted glass tile. Focus = 1.12x, shadow, sheen, label. */
class Tile(ctx: android.content.Context, val app: App, wDesign: Float, hDesign: Float) : FrameLayout(ctx) {
    var onFocused: (() -> Unit)? = null
    val label: TextView = Ui.text(ctx, app.label, 22f, 500, Ui.SECONDARY).apply { gravity = Gravity.CENTER; alpha = 0f }
    private val sheen = View(ctx)
    private val radius = Ui.px(26).toFloat()

    init {
        isFocusable = true; clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, radius) }
        outlineAmbientShadowColor = Color.BLACK; outlineSpotShadowColor = Color.BLACK
        if (app.banner != null) {
            addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageDrawable(app.banner) }, LayoutParams(-1, -1))
            addView(View(ctx).apply { background = GlassDrawable(26f, fill = Color.TRANSPARENT, border = Color.argb(56, 255, 255, 255), highlight = 0.3f) }, LayoutParams(-1, -1))
        } else {
            val top = Ui.mix(app.tint, Color.WHITE, 0.16f); val bottom = Ui.mix(app.tint, Color.BLACK, 0.38f)
            background = GlassDrawable(26f, tintTop = top, tintBottom = bottom, border = Color.argb(60, 255, 255, 255), highlight = 0.32f)
            val col = Ui.col(ctx).apply { gravity = Gravity.CENTER }
            col.addView(ImageView(ctx).apply { setImageDrawable(app.icon) }, Ui.lin((hDesign * 0.42f).toInt(), (hDesign * 0.42f).toInt()))
            col.addView(Ui.text(ctx, app.label, 24f, 600).apply { gravity = Gravity.CENTER }, Ui.lin(-2, -2, t = 12))
            addView(col, LayoutParams(-1, -1))
        }
        sheen.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.TRANSPARENT, Color.argb(56, 255, 255, 255), Color.TRANSPARENT))
        sheen.alpha = 0f
        addView(sheen, LayoutParams(-1, -1))
    }

    override fun onFocusChanged(f: Boolean, dir: Int, prev: Rect?) {
        super.onFocusChanged(f, dir, prev)
        val motion = Store.of(context).motion
        val s = if (f) 1.12f else 1f
        val z = if (f) Ui.px(36).toFloat() else 0f
        if (motion) {
            animate().scaleX(s).scaleY(s).translationZ(z).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            label.animate().alpha(if (f) 1f else 0f).setDuration(200).start()
        } else { scaleX = s; scaleY = s; translationZ = z; label.alpha = if (f) 1f else 0f }
        if (f) {
            onFocused?.invoke()
            if (motion) {   // light sheen sweeping once across the tile
                sheen.alpha = 1f; sheen.translationX = -width.toFloat()
                sheen.animate().translationX(width.toFloat()).setDuration(700).withEndAction { sheen.alpha = 0f }.start()
            }
        }
        foreground = if (f) GlassDrawable(26f, fill = Color.argb(14, 255, 255, 255), border = Color.argb(150, 255, 255, 255), highlight = 0.4f) else null
    }
}
