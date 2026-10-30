package tv.glass.launcher

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.dreams.DreamService
import android.util.Xml
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/** Glass Ambient as the system screensaver (when Glass-Setup or the user picked it in Settings → Screensaver). */
class AmbientDream : DreamService() {
    private var screen: AmbientScreen? = null
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false; isFullscreen = true; isScreenBright = true
        Store.of(this).p.edit().putLong("dream_seen", System.currentTimeMillis()).apply()
        screen = AmbientScreen(this).also { setContentView(it.root); it.start() }
    }
    override fun onDetachedFromWindow() { screen?.stop(); super.onDetachedFromWindow() }
}

/**
 * Ambient fallback: on Google TV a third-party screensaver can only be chosen with ADB, so after an Easy install
 * Glass shows the same screen itself when Home has been idle (see HomeActivity). Any button closes it.
 */
class AmbientActivity : Activity() {
    private lateinit var screen: AmbientScreen
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        screen = AmbientScreen(this); setContentView(screen.root); screen.start()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onDestroy() { screen.stop(); super.onDestroy() }
    override fun onKeyDown(k: Int, e: KeyEvent?): Boolean { finish(); return true }
    override fun onPause() { super.onPause(); finish() }   // an app or Home came to the front

    companion object {
        /** Is Glass the system screensaver? The setting is hidden on some TVs, so a dream that ran counts too. */
        fun isSystemScreensaver(ctx: Context): Boolean {
            val v = try { android.provider.Settings.Secure.getString(ctx.contentResolver, "screensaver_components") } catch (e: Exception) { null }
            val on = try { android.provider.Settings.Secure.getInt(ctx.contentResolver, "screensaver_enabled", 1) != 0 } catch (e: Exception) { true }
            if (v != null) return on && v.contains(ctx.packageName)   // screensaver switched off: Glass's own fallback runs
            return System.currentTimeMillis() - Store.of(ctx).p.getLong("dream_seen", 0) < 7 * 24 * 3600_000L
        }
    }
}

/**
 * Ambient mode (design/ambient.png): phone photos or the themed sky, a big clock, weather, the sticky note, the
 * next event and headlines, all on Liquid Glass cards. When Ambient is switched off it is a plain dark screen.
 */
class AmbientScreen(private val ctx: Context) {
    val root = FrameLayout(ctx)
    private lateinit var sky: SkyView
    private lateinit var photo: ImageView
    private lateinit var clock: android.widget.TextView
    private lateinit var date: android.widget.TextView
    private lateinit var cards: LinearLayout
    private lateinit var dots: LinearLayout
    private val backdrops = ArrayList<Backdrop>()
    private var photoIx = 0
    private var running = false

    init {
        Ui.init(ctx)
        val pal = Palette.current(ctx)
        root.setBackgroundColor(if (Store.of(ctx).ambient) pal.bg else Color.BLACK)
        if (Store.of(ctx).ambient) build(pal)
    }

    private fun build(pal: Palette) {
        sky = SkyView(ctx, pal, ambient = true)
        root.addView(sky, FrameLayout.LayoutParams(-1, -1))
        photo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(photo, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(ctx).apply {   // bottom band so the clock reads on any photo
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(200, 4, 7, 16), Color.TRANSPARENT)) },
            FrameLayout.LayoutParams(-1, Ui.px(420), Gravity.BOTTOM))

        clock = Ui.text(ctx, "", 220f, 300).apply { letterSpacing = -0.03f }
        root.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = Ui.px(88); bottomMargin = Ui.px(118) })
        date = Ui.text(ctx, "", 34f, 400).apply { compoundDrawablePadding = Ui.px(14) }
        root.addView(date, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = Ui.px(88); bottomMargin = Ui.px(60) })

        cards = Ui.col(ctx)
        root.addView(cards, FrameLayout.LayoutParams(Ui.px(528), -2, Gravity.END or Gravity.TOP).apply { rightMargin = Ui.px(86); topMargin = Ui.px(86) })
        buildCards(pal, emptyList())

        dots = Ui.row(ctx)
        root.addView(dots, FrameLayout.LayoutParams(-2, Ui.px(10), Gravity.END or Gravity.BOTTOM).apply { rightMargin = Ui.px(88); bottomMargin = Ui.px(124) })
        val hint = Ui.text(ctx, "Your photos · press any button to go home", 21f, 400, Ui.SECONDARY).apply {
            compoundDrawablePadding = Ui.px(12); setCompoundDrawablesRelativeWithIntrinsicBounds(Icons.of("image", Ui.SECONDARY, Ui.px(22)), null, null, null) }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { rightMargin = Ui.px(88); bottomMargin = Ui.px(66) })
    }

    fun start() {
        if (!Store.of(ctx).ambient || running) return
        running = true
        Weather.refresh(ctx)
        tick.run(); nextPhoto.run(); blurTick.run()
        val url = Store.of(ctx).newsUrl
        val pal = Palette.current(ctx)
        if (url.startsWith("https://")) io.execute { val h = headlines(url); main.post { if (running) buildCards(pal, h) } }
    }

    fun stop() { running = false; root.removeCallbacks(tick); root.removeCallbacks(nextPhoto); root.removeCallbacks(blurTick) }

    private val tick = object : Runnable { override fun run() {
        if (!running) return
        clock.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
        val w = Weather.temp?.let { "   •   ${Weather.short()} · ${Weather.summary()}" } ?: ""
        date.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date()) + w
        root.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000)
    } }

    /** The photo cross-fades under the cards, so their blur is redrawn while it moves. */
    private val blurTick = object : Runnable { override fun run() {
        if (!running || !Ui.blur) return
        backdrops.forEach { it.refresh() }; root.postDelayed(this, 1000)
    } }

    private val nextPhoto = object : Runnable { override fun run() {
        if (!running) return
        val ph = Hub.photos(ctx)
        dots.removeAllViews()
        if (ph.isNotEmpty()) {
            val f = ph[photoIx % ph.size]; photoIx++
            val o = BitmapFactory.Options().apply { inSampleSize = 2 }
            photo.animate().alpha(0f).setDuration(600).withEndAction {
                photo.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath, o)); photo.animate().alpha(1f).setDuration(1200).start() }.start()
            (0 until minOf(ph.size, 5)).forEach { i -> dots.addView(View(ctx).apply {
                background = Ui.pill(if (i == (photoIx - 1) % minOf(ph.size, 5)) Color.WHITE else Color.argb(110, 255, 255, 255)) },
                Ui.lin(if (i == (photoIx - 1) % minOf(ph.size, 5)) 32 else 9, 9, l = 11)) }
        }
        root.postDelayed(this, 20_000)
    } }

    private fun card(accentBorder: Boolean) = GlassDrawable(32f, fill = Color.argb(46, 255, 255, 255),
        border = if (accentBorder) Color.argb(200, 124, 196, 255) else Color.argb(52, 255, 255, 255), highlight = 0.25f)

    /** A glass card with the blurred sky/photo behind it (Android 12+). */
    private fun glass(content: LinearLayout): View {
        val f = FrameLayout(ctx)
        val b = Backdrop(ctx, { listOf(sky, photo) }); backdrops += b
        f.addView(b, FrameLayout.LayoutParams(-1, -1)); f.addView(content, FrameLayout.LayoutParams(-1, -2))
        return f
    }

    private fun header(pal: Palette, icon: String, t: String) = Ui.text(ctx, t, 17f, 600, pal.accent, 0.16f).apply {
        compoundDrawablePadding = Ui.px(14); setCompoundDrawablesRelativeWithIntrinsicBounds(Icons.of(icon, pal.accent, Ui.px(22)), null, null, null) }

    private fun buildCards(pal: Palette, news: List<Pair<String, String>>) {
        cards.removeAllViews(); backdrops.clear()
        val note = Store.of(ctx).note
        if (note.isNotEmpty()) {
            val c = Ui.col(ctx).apply { background = card(true); setPadding(Ui.px(30), Ui.px(30), Ui.px(30), Ui.px(28)) }
            c.addView(header(pal, "note", "NOTE FROM PHONE"))
            c.addView(Ui.text(ctx, note, 31f, 600).apply { maxLines = 3 }, Ui.lin(-1, -2, t = 16))
            c.addView(Ui.text(ctx, "Pinned until you clear it", 19f, 400, Ui.SECONDARY), Ui.lin(-2, -2, t = 16))
            cards.addView(glass(c), Ui.lin(-1, -2, b = 24))
        }
        val now = System.currentTimeMillis(); val next = Cal.today(ctx).firstOrNull { it.start >= now }
        val e = Ui.col(ctx).apply { background = card(false); setPadding(Ui.px(30), Ui.px(28), Ui.px(30), Ui.px(26)) }
        e.addView(header(pal, "calendar", "NEXT UP"))
        val er = Ui.row(ctx)
        if (next != null) {
            er.addView(Ui.text(ctx, SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(next.start)), 23f, 400, Ui.SECONDARY))
            er.addView(Ui.text(ctx, next.title, 27f, 600), Ui.lin(-2, -2, l = 18))
        } else er.addView(Ui.text(ctx, if (Cal.available) "Nothing else today" else "No calendar connected", 25f, 600))
        e.addView(er, Ui.lin(-1, -2, t = 14))
        cards.addView(glass(e), Ui.lin(-1, -2, b = 24))
        if (news.isNotEmpty()) {
            val n = Ui.col(ctx).apply { background = card(false); setPadding(Ui.px(30), Ui.px(28), Ui.px(30), Ui.px(14)) }
            n.addView(header(pal, "news", "HEADLINES"))
            news.take(3).forEachIndexed { i, (t, src) ->
                if (i > 0) n.addView(View(ctx).apply { setBackgroundColor(Color.argb(40, 255, 255, 255)) }, Ui.lin(-1, 1, t = 4))
                n.addView(Ui.text(ctx, t, 22f, 500).apply { maxLines = 2 }, Ui.lin(-1, -2, t = 16))
                n.addView(Ui.text(ctx, src, 17f, 400, Ui.SECONDARY), Ui.lin(-2, -2, t = 6, b = 12))
            }
            cards.addView(glass(n))
        } else if (Store.of(ctx).newsUrl.isEmpty()) {
            val n = Ui.col(ctx).apply { background = card(false); setPadding(Ui.px(30), Ui.px(28), Ui.px(30), Ui.px(26)) }
            n.addView(header(pal, "news", "HEADLINES"))
            n.addView(Ui.text(ctx, "Add a news feed from your phone", 22f, 500), Ui.lin(-1, -2, t = 16))
            n.addView(Ui.text(ctx, "Phone controller → Apps → Headlines", 17f, 400, Ui.SECONDARY), Ui.lin(-2, -2, t = 6))
            cards.addView(glass(n))
        }
    }

    /** Headlines only if a news feed was set from the phone (https RSS). Off by default. */
    private fun headlines(url: String): List<Pair<String, String>> = try {
        val c = URL(url).openConnection() as HttpURLConnection; c.connectTimeout = 8000; c.readTimeout = 8000
        val out = ArrayList<Pair<String, String>>(); var src = URL(url).host.removePrefix("www.")
        c.inputStream.use { s ->
            val p = Xml.newPullParser(); p.setInput(s, null); var inItem = false; var tag = ""
            while (p.next() != XmlPullParser.END_DOCUMENT && out.size < 3) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> { tag = p.name; if (tag == "item" || tag == "entry") inItem = true }
                    XmlPullParser.TEXT -> if (tag == "title") { val t = p.text.trim(); if (t.isNotEmpty()) { if (inItem) out += t to src else src = t } }
                    XmlPullParser.END_TAG -> { if (p.name == "item" || p.name == "entry") inItem = false; tag = "" }
                }
            }
        }
        out
    } catch (e: Exception) { android.util.Log.w(TAG, "news: $e"); emptyList() }
}
