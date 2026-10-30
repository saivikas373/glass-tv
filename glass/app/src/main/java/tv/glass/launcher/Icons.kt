package tv.glass.launcher

import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.*

/** Line icons (Lucide style, 24x24, 2px stroke), drawn from SVG path strings. */
object Icons {
    val paths = mapOf(
        "youtube" to "M6 5h12a4 4 0 0 1 4 4v6a4 4 0 0 1-4 4H6a4 4 0 0 1-4-4V9a4 4 0 0 1 4-4z M10 9l5 3-5 3z",
        "film" to "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M7 3v18 M17 3v18 M3 7.5h4 M3 12h18 M3 16.5h4 M17 7.5h4 M17 16.5h4",
        "playcircle" to "M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0z M10 8l6 4-6 4z",
        "tvplay" to "M4 7h16a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z M17 2l-5 5-5-5 M10 11l4 2.5-4 2.5z",
        "tv" to "M4 7h16a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z M17 2l-5 5-5-5",
        "music" to "M9 18V5l12-2v13 M9 18a3 3 0 1 1-6 0a3 3 0 1 1 6 0z M21 16a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
        "image" to "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M11 9a2 2 0 1 1-4 0a2 2 0 1 1 4 0z M21 15l-3.1-3.1a2 2 0 0 0-2.8 0L6 21",
        "star" to "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01z",
        "library" to "M16 6l4 14 M12 6v14 M8 8v12 M4 4v16",
        "settings" to "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
        "wifi" to "M12 20h.01 M2 8.82a15 15 0 0 1 20 0 M5 12.86a10 10 0 0 1 14 0 M8.5 16.43a5 5 0 0 1 7 0",
        "bluetooth" to "M7 7l10 10-5 5V2l5 5L7 17",
        "monitor" to "M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M8 21h8 M12 17v4",
        "volume" to "M11 4.7a.7.7 0 0 0-1.2-.5L6.4 7.6A1.4 1.4 0 0 1 5.4 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.4a1.4 1.4 0 0 1 1 .4l3.4 3.4a.7.7 0 0 0 1.2-.5z M16 9a5 5 0 0 1 0 6 M19.4 18.4a9 9 0 0 0 0-12.7",
        "calendar" to "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z M16 2v4 M8 2v4 M3 10h18",
        "prev" to "M18 19L8 12l10-7z M6 5v14",
        "next" to "M6 5l10 7-10 7z M18 5v14",
        "pause" to "M8 5v14 M16 5v14",
        "play" to "M7 4l13 8-13 8z",
        "shield" to "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z M9 12l2 2 4-4",
        "phone" to "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z M12 18h.01",
        "chat" to "M7.9 20A9 9 0 1 0 4 16.1L2 22z",
        "cloudsun" to "M12 2v2 M4.93 4.93l1.41 1.41 M20 12h2 M19.07 4.93l-1.41 1.41 M15.95 12.65a4 4 0 0 0-5.93-4.13 M13 22H7a5 5 0 1 1 4.9-6H13a3 3 0 0 1 0 6z",
        "sun" to "M16 12a4 4 0 1 1-8 0a4 4 0 1 1 8 0z M12 2v2 M12 20v2 M4.93 4.93l1.41 1.41 M17.66 17.66l1.41 1.41 M2 12h2 M20 12h2 M6.34 17.66l-1.41 1.41 M19.07 4.93l-1.41 1.41",
        "cloud" to "M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9z",
        "rain" to "M4 14.9A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.24 M16 14v6 M8 14v6 M12 16v6",
        "snow" to "M4 14.9A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.24 M8 15h.01 M8 19h.01 M12 17h.01 M12 21h.01 M16 15h.01 M16 19h.01",
        "gauge" to "M12 14l4-4 M3.34 19a10 10 0 1 1 17.32 0",
        "note" to "M16 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V8z M15 3v4a2 2 0 0 0 2 2h4",
        "news" to "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z M7 8h10 M7 12h10 M7 16h6",
        "apps" to "M5 3h4a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M15 3h4a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M5 13h4a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z M15 13h4a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z",
        "alert" to "M12 9v4 M12 17h.01 M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z",
    )

    private val cache = HashMap<String, Path>()
    fun path(name: String): Path = cache.getOrPut(name) { SvgPath.parse(paths[name] ?: paths["apps"]!!) }

    fun of(name: String, color: Int, sizePx: Int, stroke: Float = 2f, fill: Boolean = false) =
        IconDrawable(path(name), color, sizePx, stroke, fill || name == "play")
}

class IconDrawable(private val path: Path, var color: Int, private val size: Int, private val stroke: Float, private val fill: Boolean) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val m = Matrix(); private val tmp = Path()
    override fun draw(c: Canvas) {
        val b = bounds; val s = min(b.width(), b.height()) / 24f
        m.setScale(s, s); m.postTranslate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2)
        path.transform(m, tmp)
        p.color = color
        p.style = if (fill) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        p.strokeWidth = stroke * s
        c.drawPath(tmp, p)
    }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Minimal SVG path parser: M L H V C S Q Z A, absolute and relative. */
object SvgPath {
    fun parse(d: String): Path {
        val p = Path()
        val t = Regex("[MmLlHhVvCcSsQqZzAa]|-?(?:\\d+\\.?\\d*|\\.\\d+)(?:e-?\\d+)?").findAll(d).map { it.value }.toList()
        var i = 0; var cmd = 'M'; var x = 0f; var y = 0f; var sx = 0f; var sy = 0f; var cx = 0f; var cy = 0f
        fun num() = t[i++].toFloat()
        fun isNum() = i < t.size && !t[i][0].isLetter()
        while (i < t.size) {
            if (t[i][0].isLetter()) cmd = t[i++][0]
            val rel = cmd.isLowerCase(); val ox = if (rel) x else 0f; val oy = if (rel) y else 0f
            when (cmd.uppercaseChar()) {
                'M' -> { x = ox + num(); y = oy + num(); p.moveTo(x, y); sx = x; sy = y; cmd = if (rel) 'l' else 'L'; cx = x; cy = y }
                'L' -> { x = ox + num(); y = oy + num(); p.lineTo(x, y); cx = x; cy = y }
                'H' -> { x = ox + num(); p.lineTo(x, y); cx = x; cy = y }
                'V' -> { y = oy + num(); p.lineTo(x, y); cx = x; cy = y }
                'C' -> { val x1 = ox + num(); val y1 = oy + num(); val x2 = ox + num(); val y2 = oy + num(); x = ox + num(); y = oy + num()
                    p.cubicTo(x1, y1, x2, y2, x, y); cx = x2; cy = y2 }
                'S' -> { val x1 = 2 * x - cx; val y1 = 2 * y - cy; val x2 = ox + num(); val y2 = oy + num(); x = ox + num(); y = oy + num()
                    p.cubicTo(x1, y1, x2, y2, x, y); cx = x2; cy = y2 }
                'Q' -> { val x1 = ox + num(); val y1 = oy + num(); x = ox + num(); y = oy + num(); p.quadTo(x1, y1, x, y); cx = x1; cy = y1 }
                'A' -> { val rx = num(); val ry = num(); val rot = num(); val large = num() != 0f; val sweep = num() != 0f
                    val ex = ox + num(); val ey = oy + num(); arc(p, x, y, ex, ey, rx, ry, rot, large, sweep); x = ex; y = ey; cx = x; cy = y }
                'Z' -> { p.close(); x = sx; y = sy; cx = x; cy = y; if (isNum()) cmd = 'L' }
            }
        }
        return p
    }

    /** SVG elliptical arc -> cubic Béziers (standard endpoint-to-center conversion). */
    private fun arc(p: Path, x0: Float, y0: Float, x: Float, y: Float, rxIn: Float, ryIn: Float, angle: Float, large: Boolean, sweep: Boolean) {
        if (x0 == x && y0 == y) return
        var rx = abs(rxIn.toDouble()); var ry = abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0) { p.lineTo(x, y); return }
        val phi = Math.toRadians(angle.toDouble()); val cosP = cos(phi); val sinP = sin(phi)
        val dx = (x0 - x) / 2.0; val dy = (y0 - y) / 2.0
        val x1 = cosP * dx + sinP * dy; val y1 = -sinP * dx + cosP * dy
        val lam = x1 * x1 / (rx * rx) + y1 * y1 / (ry * ry)
        if (lam > 1) { rx *= sqrt(lam); ry *= sqrt(lam) }
        val sign = if (large == sweep) -1 else 1
        val num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
        val coef = sign * sqrt(max(0.0, num / (rx * rx * y1 * y1 + ry * ry * x1 * x1)))
        val cxp = coef * rx * y1 / ry; val cyp = -coef * ry * x1 / rx
        val cxx = cosP * cxp - sinP * cyp + (x0 + x) / 2.0; val cyy = sinP * cxp + cosP * cyp + (y0 + y) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = atan2(ux * vy - uy * vx, ux * vx + uy * vy); return a
        }
        val th1 = ang(1.0, 0.0, (x1 - cxp) / rx, (y1 - cyp) / ry)
        var dth = ang((x1 - cxp) / rx, (y1 - cyp) / ry, (-x1 - cxp) / rx, (-y1 - cyp) / ry)
        if (!sweep && dth > 0) dth -= 2 * PI else if (sweep && dth < 0) dth += 2 * PI
        val segs = ceil(abs(dth) / (PI / 2)).toInt().coerceAtLeast(1)
        val delta = dth / segs; val k = 4.0 / 3.0 * tan(delta / 4)
        var t = th1
        for (s in 0 until segs) {
            val c1 = cos(t); val s1 = sin(t); val c2 = cos(t + delta); val s2 = sin(t + delta)
            fun px(cx_: Double, sy_: Double) = (cosP * rx * cx_ - sinP * ry * sy_ + cxx).toFloat()
            fun py(cx_: Double, sy_: Double) = (sinP * rx * cx_ + cosP * ry * sy_ + cyy).toFloat()
            p.cubicTo(px(c1 - k * s1, s1 + k * c1), py(c1 - k * s1, s1 + k * c1),
                px(c2 + k * s2, s2 - k * c2), py(c2 + k * s2, s2 - k * c2), px(c2, s2), py(c2, s2))
            t += delta
        }
    }
}
