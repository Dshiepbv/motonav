package vn.hiep.motonav

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Nhãn tên đường tại một ngã rẽ phía trước. */
class JLabel(val lat: Double, val lon: Double, val name: String, val isTurn: Boolean, val ahead: Double)

/**
 * Tìm tất cả các đường cắt ngang tuyến đang đi trong khoảng [AHEAD] mét phía trước
 * (kể cả các lối ra của vòng xoay), để cắm nhãn tên đường dựng đứng trên bản đồ.
 */
object Junctions {
    private const val AHEAD = 700.0
    private const val TOUCH = 14.0     // đường cách tuyến ≤ 14 m coi là cắt/chạm tuyến
    private const val LABEL_OUT = 45.0 // đặt nhãn cách ngã rẽ ~45 m dọc theo đường nhánh

    fun compute(
        r: Route,
        seg: Int,
        progress: Double,
        snap: OsmSnapshot,
        here: Double,
        hereLon: Double,
        currentStreet: String?,
        turnStreet: String?,
    ): List<JLabel> {
        val n = r.lats.size
        if (n < 2) return emptyList()
        // Đoạn tuyến phía trước
        var end = seg + 1
        while (end < n - 1 && r.cum[end] - progress < AHEAD) end++
        val proj = LocalProjection(here, hereLon)
        val cnt = end - seg + 1
        val rx = DoubleArray(cnt)
        val ry = DoubleArray(cnt)
        var minLa = 90.0; var maxLa = -90.0; var minLo = 180.0; var maxLo = -180.0
        for (i in 0 until cnt) {
            val la = r.lats[seg + i]; val lo = r.lons[seg + i]
            rx[i] = proj.x(lo); ry[i] = proj.y(la)
            minLa = min(minLa, la); maxLa = max(maxLa, la); minLo = min(minLo, lo); maxLo = max(maxLo, lo)
        }
        val pad = 0.0008

        /** Khoảng cách điểm (x,y) tới tuyến phía trước và quãng đường tới điểm đó. */
        fun near(x: Double, y: Double): Pair<Double, Double> {
            var best = Double.MAX_VALUE
            var along = 0.0
            for (i in 0 until cnt - 1) {
                val ax = rx[i]; val ay = ry[i]
                val dx = rx[i + 1] - ax; val dy = ry[i + 1] - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 < 0.01) 0.0 else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0.0, 1.0)
                val px = ax + t * dx - x; val py = ay + t * dy - y
                val d = sqrt(px * px + py * py)
                if (d < best) {
                    best = d
                    along = r.cum[seg + i] + t * (r.cum[seg + i + 1] - r.cum[seg + i]) - progress
                }
            }
            return Pair(best, along)
        }

        val out = ArrayList<JLabel>()
        for (w in snap.ways) {
            val name = w.name ?: w.tags["ref"] ?: continue
            if (w.maxLat < minLa - pad || w.minLat > maxLa + pad || w.maxLon < minLo - pad || w.minLon > maxLo + pad) continue
            if (name == currentStreet && name != turnStreet) continue
            val m = w.lats.size
            val dist = DoubleArray(m)
            var touchIdx = -1
            var touchD = Double.MAX_VALUE
            var touchAlong = 0.0
            var onRoute = 0
            for (k in 0 until m) {
                val (d, a) = near(proj.x(w.lons[k]), proj.y(w.lats[k]))
                dist[k] = d
                if (d < TOUCH) onRoute++
                if (d < touchD) { touchD = d; touchIdx = k; touchAlong = a }
            }
            if (touchIdx < 0 || touchD > TOUCH || touchAlong < -20) continue
            val isTurn = turnStreet != null && name == turnStreet
            // Đường chạy trùng tuyến (không phải ngã rẽ) thì bỏ, trừ đường sắp rẽ vào
            if (!isTurn && onRoute >= 3 && onRoute.toDouble() / m > 0.6) continue

            // Đi dọc đường nhánh ra xa tuyến ~45 m để đặt nhãn
            fun walk(dir: Int): Int {
                var k = touchIdx
                var acc = 0.0
                while (k + dir in 0 until m) {
                    acc += Geo.distance(w.lats[k], w.lons[k], w.lats[k + dir], w.lons[k + dir])
                    k += dir
                    if (acc >= LABEL_OUT) break
                }
                return k
            }
            val a = walk(1)
            val b = walk(-1)
            val pick = when {
                a == touchIdx -> b
                b == touchIdx -> a
                isTurn -> if (r.cum.isNotEmpty()) (if (dist[a] >= dist[b]) a else b) else a
                else -> if (dist[a] >= dist[b]) a else b
            }
            var la = w.lats[pick]; var lo = w.lons[pick]
            if (pick != touchIdx) {
                // Nếu điểm cuối quá xa (đoạn dài) thì lùi về đúng 45 m
                val dTot = Geo.distance(w.lats[touchIdx], w.lons[touchIdx], la, lo)
                if (dTot > LABEL_OUT * 1.5) {
                    val f = LABEL_OUT / dTot
                    la = w.lats[touchIdx] + (la - w.lats[touchIdx]) * f
                    lo = w.lons[touchIdx] + (lo - w.lons[touchIdx]) * f
                }
            }
            out.add(JLabel(la, lo, name, isTurn, touchAlong))
        }

        // Bỏ nhãn trùng tên đặt gần nhau (một con đường bị chia nhiều đoạn)
        out.sortBy { it.ahead }
        val kept = ArrayList<JLabel>()
        for (l in out) {
            val dup = kept.indexOfFirst { it.name == l.name && Geo.distance(it.lat, it.lon, l.lat, l.lon) < 120 }
            if (dup >= 0) {
                if (l.isTurn && !kept[dup].isTurn) kept[dup] = l
                continue
            }
            kept.add(l)
        }
        return kept.take(14)
    }

    fun toGeoJson(list: List<JLabel>): String {
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        list.forEachIndexed { i, l ->
            if (i > 0) sb.append(',')
            sb.append("""{"type":"Feature","properties":{"name":""")
                .append(org.json.JSONObject.quote(l.name))
                .append(""","turn":""").append(if (l.isTurn) "true" else "false")
                .append("""},"geometry":{"type":"Point","coordinates":[""")
                .append(l.lon).append(',').append(l.lat).append("]}}")
        }
        sb.append("]}")
        return sb.toString()
    }
}
