package tv.glass.launcher

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.random.Random

/**
 * The night-sky backdrop from design/home.png and ambient.png: four-stop sky gradient, stars,
 * the moon (with a halo in ambient mode) and three mountain ridges that fade into the ground.
 * Everything is measured in 1920x1080 design units. Drawn once into a bitmap, so idle cost is zero.
 */
class SkyView(ctx: Context, var pal: Palette, private val ambient: Boolean = false) : View(ctx) {
    private var cache: Bitmap? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setPalette(pp: Palette) { pal = pp; cache = null; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { cache = null }

    override fun onDraw(c: Canvas) {
        if (width == 0 || height == 0) return
        val b = cache ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { render(Canvas(it)); cache = it }
        c.drawBitmap(b, 0f, 0f, null)
    }

    private fun y(v: Float) = v * height / 1080f
    private fun x(v: Float) = v * width / 1920f

    private fun render(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // Sky: top -> upper -> lower -> horizon, the horizon sitting at the far ridge.
        val horizon = if (ambient) 0.66f else 0.47f
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(pal.skyTop, pal.skyUpper, pal.skyLower, pal.skyHorizon, pal.ground),
            floatArrayOf(0f, horizon * 0.45f, horizon * 0.82f, horizon * 1.04f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        // Home keeps the sky calm: darken the upper part so text reads (design: deep navy at the top).
        if (!ambient) {
            p.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, Ui.alpha(pal.skyTop, 0.75f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h * 0.55f, p); p.shader = null
        }
        // Stars: fixed seed so they never jump between frames.
        val r = Random(7)
        repeat(if (ambient) 14 else 12) {
            val sx = r.nextFloat() * w; val sy = r.nextFloat() * h * (if (ambient) 0.42f else 0.40f)
            p.color = Color.argb(70 + r.nextInt(120), 255, 255, 255)
            c.drawCircle(sx, sy, x(1.4f + r.nextFloat() * 1.2f), p)
        }
        // Moon
        val mx = if (ambient) x(672f) else x(1115f); val my = if (ambient) y(540f) else y(276f)
        val mr = if (ambient) x(67f) else x(53f)
        if (ambient) {
            p.color = Ui.alpha(pal.moon, 0.13f); c.drawCircle(mx, my, x(192f), p)
            p.color = Ui.alpha(Ui.mix(pal.moon, pal.skyHorizon, 0.25f), 1f)
        } else {
            p.shader = RadialGradient(mx, my, mr * 2.6f, Ui.alpha(pal.moon, 0.14f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(mx, my, mr * 2.6f, p); p.shader = null
            p.color = Ui.mix(pal.moon, Color.parseColor("#F2E7D4"), 0.35f)
        }
        c.drawCircle(mx, my, mr, p)

        // Ridges (x in 0..1920, y in 0..1080), far -> near.
        val far = if (ambient) floatArrayOf(0f,660f, 180f,585f, 330f,650f, 470f,525f, 640f,612f, 790f,560f, 980f,640f, 1100f,540f, 1270f,620f, 1430f,575f, 1600f,640f, 1760f,590f, 1920f,620f)
                  else floatArrayOf(0f,560f, 170f,520f, 330f,555f, 510f,445f, 690f,520f, 830f,465f, 1010f,530f, 1180f,470f, 1390f,410f, 1560f,500f, 1720f,455f, 1920f,515f)
        val mid = if (ambient) floatArrayOf(0f,780f, 160f,710f, 330f,760f, 500f,685f, 700f,775f, 900f,705f, 1080f,770f, 1250f,712f, 1430f,780f, 1620f,720f, 1800f,770f, 1920f,745f)
                  else floatArrayOf(0f,610f, 210f,575f, 420f,620f, 520f,565f, 700f,640f, 880f,585f, 1040f,660f, 1210f,570f, 1400f,630f, 1600f,595f, 1780f,640f, 1920f,605f)
        val near = if (ambient) floatArrayOf(0f,880f, 230f,830f, 430f,870f, 620f,840f, 820f,900f, 1010f,850f, 1220f,880f, 1420f,845f, 1650f,905f, 1920f,860f)
                   else floatArrayOf(0f,680f, 240f,650f, 470f,690f, 700f,655f, 960f,705f, 1180f,662f, 1420f,700f, 1680f,668f, 1920f,690f)
        ridge(c, far, pal.far, 0.85f); ridge(c, mid, pal.mid, 0.95f); ridge(c, near, pal.near, 1f)
        // Fade into the ground so tiles sit on a calm, dark base.
        val g0 = y(if (ambient) 900f else 640f)
        p.shader = LinearGradient(0f, g0, 0f, h, Color.TRANSPARENT, pal.ground, Shader.TileMode.CLAMP)
        c.drawRect(0f, g0, w, h, p); p.shader = null
        if (!ambient) { p.color = pal.ground; c.drawRect(0f, y(760f).coerceAtLeast(g0), w, h, p) }
    }

    private fun ridge(c: Canvas, pts: FloatArray, color: Int, a: Float) {
        val path = Path(); path.moveTo(0f, height.toFloat()); var i = 0
        while (i < pts.size) { path.lineTo(x(pts[i]), y(pts[i + 1])); i += 2 }
        path.lineTo(width.toFloat(), height.toFloat()); path.close()
        p.shader = LinearGradient(0f, y(pts.filterIndexed { k, _ -> k % 2 == 1 }.minOrNull() ?: 0f), 0f, height.toFloat(),
            Ui.alpha(color, a), Ui.alpha(Ui.mix(color, pal.ground, 0.6f), a), Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
    }
}
