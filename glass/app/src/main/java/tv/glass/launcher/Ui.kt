package tv.glass.launcher

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** Layout is designed at 1920x1080; px() scales to whatever the UI really runs at. */
object Ui {
    var scale = 1f
    fun init(ctx: Context) { scale = ctx.resources.displayMetrics.widthPixels / 1920f }
    fun px(v: Float): Int = Math.round(v * scale)
    fun px(v: Int): Int = px(v.toFloat())

    const val WHITE = Color.WHITE

    /**
     * Android 12+ draws glass with RenderEffect blur (see Backdrop). Older TVs (Android 9-11) have no blur,
     * so glass becomes a solid tinted fill: the translucent white is mixed onto the theme's dark base.
     */
    val blur get() = android.os.Build.VERSION.SDK_INT >= 31
    @Volatile var base = Color.parseColor("#0A1222")
    fun solid(c: Int): Int {
        val a = Color.alpha(c) / 255f
        if (blur || a >= 0.85f) return c
        val m = mix(base, Color.rgb(Color.red(c), Color.green(c), Color.blue(c)), (a * 1.25f).coerceAtMost(1f))
        return Color.argb(242, Color.red(m), Color.green(m), Color.blue(m))
    }
    val SECONDARY = Color.argb(199, 255, 255, 255)   // rgba(255,255,255,0.78)
    val TERTIARY = Color.argb(140, 255, 255, 255)

    private val faces = HashMap<Int, Typeface>()
    fun font(ctx: Context, weight: Int): Typeface = faces.getOrPut(weight) {
        Typeface.create(ctx.resources.getFont(R.font.inter), weight, false)
    }

    fun text(ctx: Context, s: CharSequence, size: Float, weight: Int = 400, color: Int = WHITE, spacing: Float = 0f): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size).toFloat())
            typeface = font(ctx, weight)
            setTextColor(color)
            letterSpacing = spacing
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    fun frame(w: Int, h: Int, x: Int = 0, y: Int = 0) =
        FrameLayout.LayoutParams(if (w > 0) px(w) else w, if (h > 0) px(h) else h).apply { leftMargin = px(x); topMargin = px(y) }

    fun lin(w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(if (w > 0) px(w) else w, if (h > 0) px(h) else h, weight).apply { setMargins(px(l), px(t), px(r), px(b)) }

    fun row(ctx: Context, gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = gravity }
    fun col(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun pill(fill: Int, radius: Float = 999f, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = px(radius).toFloat(); if (stroke != 0) setStroke(1, stroke)
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        val u = 1 - t
        return Color.argb((Color.alpha(a) * u + Color.alpha(b) * t).toInt(), (Color.red(a) * u + Color.red(b) * t).toInt(),
            (Color.green(a) * u + Color.green(b) * t).toInt(), (Color.blue(a) * u + Color.blue(b) * t).toInt())
    }
    fun alpha(c: Int, a: Float) = Color.argb((255 * a).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    /** Gentle focus: scale up, lift. Used for buttons, chips and Control Center tiles. */
    fun focusScale(v: View, s: Float = 1.06f) {
        v.isFocusable = true
        v.setOnFocusChangeListener { x, f ->
            val anim = Store.of(x.context).motion
            val t = if (f) s else 1f
            if (anim) x.animate().scaleX(t).scaleY(t).setDuration(160).start() else { x.scaleX = t; x.scaleY = t }
        }
    }
}

/**
 * The Liquid Glass surface: rgba(255,255,255,.12) fill, a 1px rgba(255,255,255,.2) border and a
 * soft highlight along the inside of the top edge.
 */
class GlassDrawable(
    private val radius: Float,
    var fill: Int = Color.argb(31, 255, 255, 255),
    var border: Int = Color.argb(51, 255, 255, 255),
    var highlight: Float = 0.25f,
    var tintTop: Int = 0, var tintBottom: Int = 0,
) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun draw(c: Canvas) {
        val b = bounds; r.set(b); val rad = Ui.px(radius).toFloat().coerceAtMost(r.height() / 2)
        p.style = Paint.Style.FILL; p.shader = null
        if (tintTop != 0) p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, tintTop, tintBottom, Shader.TileMode.CLAMP)
        else p.color = Ui.solid(fill)
        c.drawRoundRect(r, rad, rad, p); p.shader = null
        // inset top highlight
        if (highlight > 0) {
            p.style = Paint.Style.STROKE; p.strokeWidth = Ui.px(2f).toFloat().coerceAtLeast(1.5f)
            p.shader = LinearGradient(0f, r.top, 0f, r.top + rad * 1.4f,
                Color.argb((255 * highlight).toInt(), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            val ri = RectF(r.left + 1.5f, r.top + 1.5f, r.right - 1.5f, r.bottom - 1.5f)
            c.drawRoundRect(ri, rad - 1, rad - 1, p); p.shader = null
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f; p.color = border
        r.inset(0.6f, 0.6f); c.drawRoundRect(r, rad, rad, p)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Liquid Glass blur for a card that sits over Glass's own sky or photo (Android 12+): this view redraws the
 * views behind it, offset to its own position, through a RenderEffect blur. Put it as the card's first child.
 * On Android 9-11 it draws nothing and the card's solid tinted fill does the work.
 */
class Backdrop(ctx: Context, private val sources: () -> List<View>, private val radiusDp: Float = 44f, cornerDesign: Float = 32f) : View(ctx) {
    private val loc = IntArray(2); private val src = IntArray(2)
    init {
        if (Ui.blur) {
            setRenderEffect(android.graphics.RenderEffect.createBlurEffect(Ui.px(radiusDp).toFloat(), Ui.px(radiusDp).toFloat(), Shader.TileMode.CLAMP))
            clipToOutline = true
            val r = Ui.px(cornerDesign).toFloat()
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, r) }
        }
    }
    /** Takes no room of its own: the card sets the size, then FrameLayout stretches this to match it. */
    override fun onMeasure(w: Int, h: Int) {
        fun size(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(size(w), size(h))
    }
    override fun onDraw(c: Canvas) {
        if (!Ui.blur) return
        getLocationInWindow(loc)
        for (v in sources()) {
            if (v.visibility != View.VISIBLE || v.alpha <= 0f || v.width == 0) continue
            v.getLocationInWindow(src)
            val save = c.save(); c.translate((src[0] - loc[0]).toFloat(), (src[1] - loc[1]).toFloat())
            v.draw(c); c.restoreToCount(save)
        }
    }
    /** Called by the screen when what is behind changes (photo fade, theme). Cheap enough once a second. */
    fun refresh() { if (Ui.blur) invalidate() }
}

/** One theme from assets/themes.json (design/themes.json, copied at build). */
class Palette(private val o: JSONObject, val name: String) {
    private fun c(k: String) = Color.parseColor(o.getString(k))
    val bg = c("background"); val skyTop = c("sky_top"); val skyUpper = c("sky_upper"); val skyLower = c("sky_lower")
    val skyHorizon = c("sky_horizon"); val moon = c("moon"); val far = c("mountains_far"); val mid = c("mountains_mid")
    val near = c("mountains_near"); val ground = c("ground"); val accent = c("accent")

    companion object {
        private var all: JSONObject? = null
        fun names(ctx: Context): List<String> = load(ctx).keys().asSequence().toList()
        private fun load(ctx: Context): JSONObject = all ?: JSONObject(ctx.assets.open("themes.json").bufferedReader().readText())
            .getJSONObject("themes").also { all = it }
        fun of(ctx: Context, n: String): Palette = Palette(load(ctx).getJSONObject(n), n)
        fun current(ctx: Context): Palette {
            val t = load(ctx); val n = Store.of(ctx).theme
            return (if (t.has(n)) Palette(t.getJSONObject(n), n) else Palette(t.getJSONObject("midnight"), "midnight")).also { Ui.base = it.bg }
        }
    }
}
