package vn.hiep.motonav

import kotlin.math.cos
import kotlin.math.sin

/**
 * Chọn tuyến thông minh:
 *  - Có dữ liệu kẹt xe → tuyến thời gian ngắn nhất (đã cộng thời gian kẹt xe).
 *  - Không có → tuyến ngắn nhất.
 *  - Khi tìm lại đường (đi lệch/quẹo nhầm): ưu tiên tuyến đi tiếp về phía trước, bỏ tuyến bắt quay đầu.
 */
object RoutePick {

    /** Hướng xuất phát của tuyến (đo ở ~40 m đầu). */
    fun startBearing(r: Route): Double {
        val n = r.lats.size
        var j = 1
        while (j < n - 1 && r.cum[j] < 40) j++
        return Geo.bearing(r.lats[0], r.lons[0], r.lats[j], r.lons[j])
    }

    /** Tuyến bắt quay đầu: hướng xuất phát ngược hướng xe, hoặc vòng lại gần điểm xuất phát trong 400 m đầu. */
    fun isBackward(r: Route, heading: Double?): Boolean {
        if (heading != null && heading >= 0 && Geo.angleDiff(startBearing(r), heading) > 110) return true
        for (i in r.lats.indices) {
            val c = r.cum[i]
            if (c < 80) continue
            if (c > 400) break
            if (Geo.distance(r.lats[0], r.lons[0], r.lats[i], r.lons[i]) < 25) return true
        }
        return false
    }

    private fun hasTraffic(r: Route) = r.congestion.any { it != "unknown" && it.isNotEmpty() }

    /** Số lần rẽ "thật" trên tuyến (sign=0 là đi thẳng/giữ làn, không tính). */
    private fun turnCount(r: Route): Int = r.steps.count { it.sign != 0 }

    /** Ước lượng độ ngoằn ngoèo: số lần rẽ trên mỗi km. Tuyến theo đường lớn/thẳng thường rất ít
     * rẽ dù dài; tuyến len lỏi qua hẻm/đường nhỏ dồn rất nhiều rẽ trong quãng ngắn. Chuẩn hoá về hệ
     * số phạt NHẸ (tối đa +12% chi phí) — chỉ đủ nghiêng cán cân giữa các tuyến gần ngang nhau về
     * thời gian, không chọn tuyến chậm hẳn chỉ vì ít rẽ hơn. */
    private fun zigzagPenalty(r: Route): Double {
        val km = (r.total / 1000.0).coerceAtLeast(0.3)
        val density = turnCount(r) / km
        return (density / 6.0).coerceIn(0.0, 2.5) * 0.05
    }

    /** Thời gian dự kiến (giây) có tính kẹt xe. */
    fun trafficTime(r: Route): Double {
        val base = r.timeMs / 1000.0
        if (!hasTraffic(r) || r.total <= 0) return base
        var extra = 0.0
        for (i in 0 until r.lats.size - 1) {
            val f = when (r.congestionAt(i)) {
                "moderate" -> 0.6
                "heavy" -> 1.6
                "severe" -> 3.0
                else -> 0.0
            }
            if (f > 0) extra += base * ((r.cum[i + 1] - r.cum[i]) / r.total) * f
        }
        return base + extra
    }

    /** Sắp xếp: tuyến đi tiếp về phía trước trước, rồi theo thời gian (có kẹt xe) hoặc quãng đường. */
    fun rank(list: List<Route>, heading: Double?, forwardFirst: Boolean): List<Route> {
        if (list.size <= 1) return list
        val traffic = list.any { hasTraffic(it) }
        // Theo yêu cầu "ưu tiên đường thẳng, đường lớn, hạn chế zigzag": cộng thêm phạt nhẹ theo
        // mật độ rẽ vào chi phí trước khi so sánh, bên cạnh thời gian/quãng đường như cũ.
        fun cost(r: Route): Double = (if (traffic) trafficTime(r) else r.total) * (1.0 + zigzagPenalty(r))
        val sorted = list.sortedBy { cost(it) }.toMutableList()
        // Thời gian gần bằng nhau (chênh < 5%) → chọn tuyến ngắn hơn
        if (traffic && sorted.size > 1) {
            val a = sorted[0]
            val b = sorted.drop(1).minByOrNull { it.total }!!
            if (b.total < a.total * 0.9 && trafficTime(b) <= trafficTime(a) * 1.05) {
                sorted.remove(b); sorted.add(0, b)
            }
        }
        if (!forwardFirst) return sorted
        val fwd = sorted.filter { !isBackward(it, heading) }
        return fwd + sorted.filter { it !in fwd }
    }

    /**
     * Điểm phía trước xe ~120 m dọc theo con đường đang đi (theo OSM), để ép tuyến mới đi tiếp
     * thay vì quay đầu. Không có dữ liệu đường thì lấy điểm thẳng phía trước 80 m.
     */
    fun aheadPoint(snap: OsmSnapshot?, lat: Double, lon: Double, heading: Double): Pair<Double, Double> {
        if (snap != null) {
            val proj = LocalProjection(lat, lon)
            var bestW: Way? = null
            var bestI = 0
            var bestT = 0.0
            var bestFwd = true
            var bestScore = Double.MAX_VALUE
            for (w in snap.ways) {
                if (w.highway in setOf("footway", "path", "steps", "cycleway", "pedestrian", "track")) continue
                for (i in 0 until w.lats.size - 1) {
                    val ax = proj.x(w.lons[i]); val ay = proj.y(w.lats[i])
                    val bx = proj.x(w.lons[i + 1]); val by = proj.y(w.lats[i + 1])
                    val dx = bx - ax; val dy = by - ay
                    val len2 = dx * dx + dy * dy
                    if (len2 < 0.01) continue
                    val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                    val px = ax + t * dx; val py = ay + t * dy
                    val d = kotlin.math.sqrt(px * px + py * py)
                    if (d > 30) continue
                    val segB = Geo.norm(Math.toDegrees(kotlin.math.atan2(dx, dy)))
                    val diff = Geo.angleDiff(segB, heading)
                    val fwd = diff <= 90
                    if (w.oneway == 1 && !fwd || w.oneway == -1 && fwd) continue
                    val score = d + kotlin.math.min(diff, 180 - diff) * 0.5
                    if (score < bestScore) { bestScore = score; bestW = w; bestI = i; bestT = t; bestFwd = fwd }
                }
            }
            val w = bestW
            if (w != null && bestScore < 40) {
                var la = w.lats[bestI] + bestT * (w.lats[bestI + 1] - w.lats[bestI])
                var lo = w.lons[bestI] + bestT * (w.lons[bestI + 1] - w.lons[bestI])
                var left = 120.0
                var k = if (bestFwd) bestI + 1 else bestI
                while (left > 0 && k in w.lats.indices) {
                    val d = Geo.distance(la, lo, w.lats[k], w.lons[k])
                    if (d >= left) {
                        val f = left / d
                        la += (w.lats[k] - la) * f; lo += (w.lons[k] - lo) * f
                        left = 0.0
                    } else {
                        la = w.lats[k]; lo = w.lons[k]; left -= d
                        k += if (bestFwd) 1 else -1
                    }
                }
                if (Geo.distance(lat, lon, la, lo) > 35) return la to lo
            }
        }
        val r = Math.toRadians(heading)
        return (lat + cos(r) * 80 / 110540.0) to (lon + sin(r) * 80 / (111320.0 * cos(Math.toRadians(lat))))
    }
}
