package vn.hiep.motonav

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Dựng tên đường thành chữ khối 3D đứng thẳng, chạy dọc theo hướng con đường
 * (giống chữ nổi bảng hiệu). Mỗi chữ được chia thành các khối nhỏ (fill-extrusion có
 * độ cao bắt đầu/kết thúc), nên hiện được hình chữ đứng trên bản đồ.
 */
object Text3D {
    private const val LIFT = 1.5        // chữ cách mặt đất 1,5 m
    private const val MAX_HEIGHT = 8.0  // chiều cao chữ tối đa (m)
    private const val MIN_CELL = 0.28   // kích thước ô nhỏ nhất (m)
    private const val MAX_LEN = 55.0    // chiều dài tối đa cả tên (m)
    private const val START = 10.0      // bắt đầu cách ngã rẽ 10 m
    private const val THICK = 1.4       // độ dày chữ (m)
    private const val SMALL = 0.72      // tỉ lệ cỡ chữ các đường khác (không phải đường rẽ)

    private class Grid(val w: Int, val h: Int, val on: BooleanArray)

    private val gridCache = HashMap<String, Grid>()

    /** Vẽ tên đường ra lưới điểm (pixel sáng = có khối). */
    private fun raster(text: String): Grid {
        gridCache[text]?.let { return it }
        val p = Paint().apply {
            isAntiAlias = false
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            color = Color.WHITE
        }
        val fm = p.fontMetrics
        val h = ceil(fm.bottom - fm.top).toInt().coerceAtLeast(1)
        val w = ceil(p.measureText(text)).toInt().coerceAtLeast(1) + 1
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, 0f, -fm.top, p)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val on = BooleanArray(w * h) { (px[it] ushr 24) > 110 }
        // Cắt bỏ hàng trống trên/dưới
        var top = 0
        while (top < h - 1 && (0 until w).none { on[top * w + it] }) top++
        var bot = h - 1
        while (bot > top && (0 until w).none { on[bot * w + it] }) bot--
        val nh = bot - top + 1
        val trimmed = BooleanArray(w * nh) { on[(it / w + top) * w + it % w] }
        val g = Grid(w, nh, trimmed)
        gridCache[text] = g
        return g
    }

    /**
     * Tạo GeoJSON các khối chữ.
     * @param viewBearing hướng nhìn của bản đồ (để chữ đọc từ trái sang phải).
     */
    fun build(labels: List<JLabel>, viewBearing: Double): String {
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var first = true
        for (l in labels) {
            val d = l.outBearing
            if (d < 0) continue
            val g = raster(l.name)
            // Đường sắp rẽ: cỡ chữ đầy đủ; các đường khác ở ngã rẽ: nhỏ hơn
            val sc = if (l.isTurn) 1.0 else SMALL
            val cell = min(MAX_HEIGHT * sc / g.h, MAX_LEN * sc / g.w).coerceAtLeast(MIN_CELL)
            val thick = THICK * sc
            val len = g.w * cell
            // Chiều chạy của chữ: hướng có thành phần sang phải màn hình
            val right = Geo.norm(viewBearing + 90)
            val forward = Geo.angleDiff(d, right) <= 90
            val u = if (forward) d else Geo.norm(d + 180)
            val startAlong = if (forward) START else START + len // tính từ ngã rẽ theo hướng d
            val ur = Math.toRadians(u)
            val dr = Math.toRadians(d)
            val ux = sin(ur); val uy = cos(ur)              // (đông, bắc)
            val nx = sin(ur + Math.PI / 2); val ny = cos(ur + Math.PI / 2)
            val ox = sin(dr) * startAlong; val oy = cos(dr) * startAlong
            val kx = 111320.0 * cos(Math.toRadians(l.jLat))
            val ky = 110540.0
            fun pt(along: Double, side: Double): String {
                val e = ox + ux * along + nx * side
                val n = oy + uy * along + ny * side
                return "[" + (l.jLon + e / kx) + "," + (l.jLat + n / ky) + "]"
            }
            val turn = if (l.isTurn) 1 else 0
            // Gộp các đoạn sáng theo hàng, rồi gộp các hàng giống nhau theo chiều dọc
            data class Box(val x0: Int, val x1: Int, var rTop: Int, var rBot: Int)
            val open = HashMap<Long, Box>()
            val done = ArrayList<Box>()
            for (r in 0 until g.h) {
                val seen = HashSet<Long>()
                var x = 0
                while (x < g.w) {
                    if (!g.on[r * g.w + x]) { x++; continue }
                    var x1 = x
                    while (x1 + 1 < g.w && g.on[r * g.w + x1 + 1]) x1++
                    val key = (x.toLong() shl 20) or x1.toLong()
                    val b = open[key]
                    if (b != null && b.rBot == r - 1) b.rBot = r else {
                        b?.let { done.add(it) }
                        open[key] = Box(x, x1, r, r)
                    }
                    seen.add(key)
                    x = x1 + 1
                }
                val it = open.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    if (e.key !in seen) { done.add(e.value); it.remove() }
                }
            }
            done.addAll(open.values)
            for (b in done) {
                val s = b.x0 * cell
                val e = (b.x1 + 1) * cell
                val base = LIFT + (g.h - 1 - b.rBot) * cell
                val top = LIFT + (g.h - b.rTop) * cell
                if (!first) sb.append(',')
                first = false
                sb.append("""{"type":"Feature","properties":{"b":""").append(String.format(java.util.Locale.US, "%.2f", base))
                    .append(""","h":""").append(String.format(java.util.Locale.US, "%.2f", top))
                    .append(""","k":""").append(turn)
                    .append("""},"geometry":{"type":"Polygon","coordinates":[[""")
                    .append(pt(s, -thick / 2)).append(',').append(pt(e, -thick / 2)).append(',')
                    .append(pt(e, thick / 2)).append(',').append(pt(s, thick / 2)).append(',')
                    .append(pt(s, -thick / 2)).append("]]}}")
            }
        }
        sb.append("]}")
        return sb.toString()
    }
}
