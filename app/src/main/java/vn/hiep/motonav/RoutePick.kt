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

    /** Vị trí xe gắn lên con đường OSM đang đi (đúng chiều xe chạy). */
    class OnWay(val way: Way, val idx: Int, val t: Double, val fwd: Boolean, val lat: Double, val lon: Double)

    private val NON_DRIVE = setOf("footway", "path", "steps", "cycleway", "pedestrian", "track")

    fun locate(snap: OsmSnapshot?, lat: Double, lon: Double, heading: Double): OnWay? {
        if (snap == null) return null
        val proj = LocalProjection(lat, lon)
        var bestW: Way? = null
        var bestI = 0
        var bestT = 0.0
        var bestFwd = true
        var bestScore = Double.MAX_VALUE
        for (w in snap.ways) {
            if (w.highway in NON_DRIVE) continue
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
        val w = bestW ?: return null
        if (bestScore >= 40) return null
        val la = w.lats[bestI] + bestT * (w.lats[bestI + 1] - w.lats[bestI])
        val lo = w.lons[bestI] + bestT * (w.lons[bestI + 1] - w.lons[bestI])
        return OnWay(w, bestI, bestT, bestFwd, la, lo)
    }

    /** Đi tiếp [dist] mét dọc con đường [o] theo chiều xe chạy (dừng ở đầu mút con đường). */
    fun walk(o: OnWay, dist: Double): Pair<Double, Double> {
        val w = o.way
        var la = o.lat
        var lo = o.lon
        var left = dist
        var k = if (o.fwd) o.idx + 1 else o.idx
        while (left > 0 && k in w.lats.indices) {
            val d = Geo.distance(la, lo, w.lats[k], w.lons[k])
            if (d >= left) {
                val f = left / d
                la += (w.lats[k] - la) * f; lo += (w.lons[k] - lo) * f
                left = 0.0
            } else {
                la = w.lats[k]; lo = w.lons[k]; left -= d
                k += if (o.fwd) 1 else -1
            }
        }
        return la to lo
    }

    /**
     * Điểm phía trước xe ~[dist] m dọc theo con đường đang đi (theo OSM), để ép tuyến mới đi tiếp
     * thay vì quay đầu. Không có dữ liệu đường thì lấy điểm thẳng phía trước.
     */
    fun aheadPoint(snap: OsmSnapshot?, lat: Double, lon: Double, heading: Double, dist: Double = 120.0): Pair<Double, Double> {
        val o = locate(snap, lat, lon, heading)
        if (o != null) {
            val p = walk(o, dist)
            if (Geo.distance(lat, lon, p.first, p.second) > dist * 0.3) return p
        }
        val r = Math.toRadians(heading)
        val straight = dist.coerceAtMost(150.0)
        return (lat + cos(r) * straight / 110540.0) to (lon + sin(r) * straight / (111320.0 * cos(Math.toRadians(lat))))
    }

    /** Điểm trên tuyến tại quãng đường [c] (m) tính từ đầu tuyến. */
    private fun pointAt(r: Route, c: Double): Pair<Double, Double> {
        val n = r.lats.size
        if (c <= 0) return r.lats[0] to r.lons[0]
        if (c >= r.total) return r.lats[n - 1] to r.lons[n - 1]
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (r.cum[mid] <= c) lo = mid else hi = mid
        }
        val seg = r.cum[hi] - r.cum[lo]
        val f = if (seg < 0.01) 0.0 else (c - r.cum[lo]) / seg
        return (r.lats[lo] + f * (r.lats[hi] - r.lats[lo])) to (r.lons[lo] + f * (r.lons[hi] - r.lons[lo]))
    }

    private fun bearingAt(r: Route, from: Double, to: Double): Double {
        val a = pointAt(r, from); val b = pointAt(r, to)
        return Geo.bearing(a.first, a.second, b.first, b.second)
    }

    /**
     * Quãng đường (m, tính từ đầu tuyến) tới chỗ QUAY ĐẦU đầu tiên trong [withinM] mét đầu, -1 nếu
     * không có. Quay đầu = Goong báo bước "uturn", hoặc hình học tuyến bẻ ngược >150° trong một
     * quãng ngắn (≤ 60 m) — kiểu quay đầu qua dải phân cách sang làn bên kia.
     */
    fun uturnAt(r: Route, withinM: Double = 3000.0): Double {
        for (st in r.steps) {
            if (st.sign == -8 && st.from in r.cum.indices && r.cum[st.from] <= withinM) return r.cum[st.from]
        }
        val end = kotlin.math.min(withinM, r.total - 30.0)
        var c = 30.0
        while (c <= end) {
            val bIn = bearingAt(r, c - 30.0, c)
            for (gap in doubleArrayOf(0.0, 15.0, 30.0)) {
                if (c + gap + 30.0 > r.total) break
                val bOut = bearingAt(r, c + gap, c + gap + 30.0)
                if (Geo.angleDiff(bIn, bOut) > 150) return c
            }
            c += 10.0
        }
        return -1.0
    }

    /**
     * Tuyến có kéo NGƯỢC lại đoạn đường xe vừa đi qua không (log 27/10: tuyến mới cứ bắt quay về
     * đường cũ dù xe đã đi tiếp 300–500 m). [trail] = các vị trí gần đây (cũ → mới) kèm hướng đi.
     * Đếm tổng quãng tuyến (trong 1 km đầu) nằm sát vết xe vừa đi (≤ 25 m) mà đi NGƯỢC chiều vết đó.
     */
    fun backtrackM(r: Route, trail: List<DoubleArray>): Double {
        if (trail.size < 2) return 0.0
        var back = 0.0
        var c = 20.0
        val end = kotlin.math.min(1000.0, r.total - 10.0)
        while (c <= end) {
            val p = pointAt(r, c)
            val b = bearingAt(r, c - 10.0, c + 10.0)
            for (tp in trail) {
                if (tp[2] < 0) continue
                if (Geo.distance(p.first, p.second, tp[0], tp[1]) <= 25.0 && Geo.angleDiff(b, tp[2]) > 120) {
                    back += 10.0
                    break
                }
            }
            c += 10.0
        }
        return back
    }

    /** Lý do loại tuyến theo ràng buộc "không quay đầu", null = hợp lệ. */
    fun rejectReason(r: Route, heading: Double?, trail: List<DoubleArray>): String? {
        if (isBackward(r, heading)) return "startBackward"
        val u = uturnAt(r)
        if (u >= 0) return "uturn@" + u.toInt()
        if (backtrackM(r, trail) >= 40.0) return "backtrack"
        return null
    }

    /** Ghép thêm điểm xe hiện tại vào đầu tuyến (khi tuyến được tìm từ một điểm phía trước xe). */
    fun prepend(r: Route, lat: Double, lon: Double): Route {
        if (Geo.distance(lat, lon, r.lats[0], r.lons[0]) < 2.0) return r
        val la = DoubleArray(r.lats.size + 1)
        val lo = DoubleArray(r.lons.size + 1)
        la[0] = lat; lo[0] = lon
        System.arraycopy(r.lats, 0, la, 1, r.lats.size)
        System.arraycopy(r.lons, 0, lo, 1, r.lons.size)
        val steps = r.steps.mapIndexed { i, st ->
            Step(st.sign, st.text, st.street, st.distance, if (i == 0) 0 else st.from + 1, st.to + 1, st.exitNumber)
        }
        val d0 = Geo.distance(lat, lon, r.lats[0], r.lons[0])
        return Route(la, lo, steps, r.distance + d0, r.timeMs, r.congestion)
    }
}
