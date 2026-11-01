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
        return (density / 6.0).coerceIn(0.0, 2.5) * 0.08 + (if (uturnAt(r, 3000.0) >= 0) 0.08 else 0.0)
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
    /** build-305: quãng "vòng vô lý gần đích" (m): có điểm cách đích d < 1,5 km (chim bay) mà quãng tuyến còn lại > 3d + 800 m
     * (vd. Goong từ Xa lộ HN không đi thẳng D1 mà vòng Lê Văn Việt → D2B → Ngã Tư Công Nghệ). 0 = không vòng. */
    fun detourM(r: Route): Double {
        val n = r.lats.size
        if (n < 2) return 0.0
        val eLat = r.lats[n - 1]; val eLon = r.lons[n - 1]
        var worst = 0.0
        for (i in 0 until n) {
            val d = Geo.distance(r.lats[i], r.lons[i], eLat, eLon)
            if (d >= 1500.0) continue
            val rem = r.total - r.cum[i]
            val extra = rem - (3 * d + 800.0)
            if (extra > worst) worst = extra
        }
        return worst
    }

    fun rank(list: List<Route>, heading: Double?, forwardFirst: Boolean, mixed: Boolean = false): List<Route> {
        if (list.size <= 1) return list
        // Trộn Goong + VietMap: thời gian dự kiến hai bên không cùng thang (Goong "bike" chậm gấp ~2) → so bằng quãng đường.
        val traffic = !mixed && list.any { hasTraffic(it) }
        // Theo yêu cầu "ưu tiên đường thẳng, đường lớn, hạn chế zigzag": cộng thêm phạt nhẹ theo
        // mật độ rẽ vào chi phí trước khi so sánh, bên cạnh thời gian/quãng đường như cũ.
        fun cost(r: Route): Double = (if (traffic) trafficTime(r) else r.total) * (1.0 + zigzagPenalty(r) + (if (carOnlyM(r, 3000.0) >= 40.0) 1.0 else 0.0) + (if (detourM(r) > 0) 0.3 else 0.0))
        // Ưu tiên số 1: tuyến không có đoạn nào cấm xe máy (luật); sau đó mới so thời gian/quãng đường.
        val sorted = list.sortedWith(compareBy<Route>({ if (carOnlyM(it, 3000.0) >= 40.0) 1 else 0 }, { cost(it) })).toMutableList()
        // Thời gian gần bằng nhau (chênh < 5%) → chọn tuyến ngắn hơn
        if (traffic && sorted.size > 1) {
            val a = sorted[0]
            val a0 = carOnlyM(a, 3000.0) >= 40.0
            val b = sorted.drop(1).filter { (carOnlyM(it, 3000.0) >= 40.0) == a0 }.minByOrNull { it.total }
            if (b != null && b.total < a.total * 0.9 && trafficTime(b) <= trafficTime(a) * 1.05) {
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
    fun backtrackM(r: Route, trail: List<DoubleArray>, radius: Double = 25.0): Double {
        if (trail.size < 2) return 0.0
        var back = 0.0
        var c = 20.0
        val end = kotlin.math.min(1000.0, r.total - 10.0)
        while (c <= end) {
            val p = pointAt(r, c)
            val b = bearingAt(r, c - 10.0, c + 10.0)
            for (tp in trail) {
                if (tp[2] < 0) continue
                if (Geo.distance(p.first, p.second, tp[0], tp[1]) <= radius && Geo.angleDiff(b, tp[2]) > 120) {
                    back += 10.0
                    break
                }
            }
            c += 10.0
        }
        return back
    }

    /**
     * Lý do loại tuyến khi TÌM LẠI tuyến (đi lệch), null = hợp lệ. Theo Hiệp: "không quay đầu" nghĩa
     * là không bắt quay lại tuyến cũ/đường vừa đi — còn quay đầu nằm sẵn trong lộ trình đi tiếp
     * (vòng xuống chân cầu, quay đầu đúng điểm để sang bên kia đường) thì vẫn phải giữ.
     */
    fun rejectReason(r: Route, heading: Double?, trail: List<DoubleArray>): String? {
        if (isBackward(r, heading)) return "startBackward"
        if (carOnlyM(r, 1500.0) >= 40.0) return "carOnly"
        if (backtrackM(r, trail) >= 40.0) return "backtrack"
        // Quay đầu trong 1 km đầu rồi chạy ngược lại sát vết vừa đi (làn bên kia dải phân cách, ≤60 m)
        // = kéo về tuyến cũ → loại. Quay đầu xong đi hướng khác → giữ.
        val u = uturnAt(r, 1000.0)
        if (u >= 0 && backtrackM(r, trail, 60.0) >= 40.0) return "uturnBack@" + u.toInt()
        return null
    }

    /** Đường cấm xe máy (làn riêng ô tô, cao tốc, cấm xe cơ giới) theo thẻ OSM. */
    fun carOnly(w: Way): Boolean {
        val t = w.tags
        fun no(v: String?) = v == "no" || v == "private"
        val mc = t["motorcycle"]
        if (mc == "yes" || mc == "designated" || mc == "permissive") return false
        if (no(mc)) return true
        if (no(t["motor_vehicle"])) return true
        if (t["access"] == "no" || t["access"] == "private") return true
        if (w.highway == "motorway" || w.highway == "motorway_link") return true
        return mainCarriageway(w)
    }

    // build-304: lòng chính (trunk) Xa lộ Hà Nội / Phạm Văn Đồng cấm xe máy từ lâu nhưng OSM chưa gắn nhãn (log 1/11:
    // Goong dẫn lên lòng chính, rider đi song hành cách ~95 m, reroute liên tục). Coi là cấm xe máy KHI có đường
    // song hành (tên chứa "Song Hành", hoặc cùng tên nhưng không phải trunk) chạy gần (khung bao cách ≤ ~150 m).
    private val MAIN_NAMES = listOf("xa lộ hà nội", "phạm văn đồng")
    private var mcSnap: Any? = null
    private var mcSet: Set<Long> = emptySet()
    private fun mainCarriageway(w: Way): Boolean {
        if (w.highway != "trunk") return false
        val n = w.name?.lowercase() ?: return false
        if (MAIN_NAMES.none { n.contains(it) } || n.contains("song hành")) return false
        val snap = Live.osm ?: return false
        synchronized(this) {
            if (mcSnap !== snap) {
                val side = snap.ways.filter { o ->
                    val on = o.name?.lowercase() ?: ""
                    o.highway != "trunk" && o.highway != "service" && o.highway != "residential" &&
                        (on.contains("song hành") || MAIN_NAMES.any { on.contains(it) })
                }
                val set = HashSet<Long>()
                val pad = 0.0014
                for (t in snap.ways) {
                    if (t.highway != "trunk") continue
                    val tn = t.name?.lowercase() ?: continue
                    if (MAIN_NAMES.none { tn.contains(it) } || tn.contains("song hành")) continue
                    if (side.any { o -> o.maxLat >= t.minLat - pad && o.minLat <= t.maxLat + pad && o.maxLon >= t.minLon - pad && o.minLon <= t.maxLon + pad }) set.add(t.id)
                }
                mcSet = set; mcSnap = snap
                try { TripLog.ev("mainCarriageway", "n" to set.size) } catch (_: Exception) {}
            }
            return w.id in mcSet
        }
    }

    /** Quãng tuyến (m, trong [withinM] đầu) chạy sát (≤ 8 m) đường cấm xe máy. 0 nếu không có dữ liệu. */
    fun carOnlyM(r: Route, withinM: Double = 3000.0): Double {
        val snap = Live.osm ?: return 0.0
        val bad = snap.ways.filter { carOnly(it) }
        if (bad.isEmpty()) return 0.0
        var m = 0.0
        var c = 0.0
        val end = kotlin.math.min(withinM, r.total)
        // build-279: khi xe ĐANG chạy trên đoạn bị coi là cấm xe máy (log 31/10, trục Xa lộ Hà Nội: carOnly 640→130 m
        // giảm dần đúng bằng quãng xe đã đi) thì phần liền mạch từ vị trí xe không tránh được → không tính, tránh
        // từ chối mọi tuyến và reroute dồn 4–8 lần.
        var leading = skipLeading.get() == true
        while (c <= end) {
            val p = pointAt(r, c)
            val proj = LocalProjection(p.first, p.second)
            var hit = false
            loop@ for (w in bad) {
                if (p.first < w.minLat - 0.0001 || p.first > w.maxLat + 0.0001 || p.second < w.minLon - 0.0001 || p.second > w.maxLon + 0.0001) continue
                for (i in 0 until w.lats.size - 1) {
                    val ax = proj.x(w.lons[i]); val ay = proj.y(w.lats[i])
                    val dx = proj.x(w.lons[i + 1]) - ax; val dy = proj.y(w.lats[i + 1]) - ay
                    val len2 = dx * dx + dy * dy
                    val t = if (len2 < 0.01) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                    if (Math.hypot(ax + t * dx, ay + t * dy) <= 8.0) { hit = true; break@loop }
                }
            }
            if (hit) { if (!leading) m += 10.0 } else leading = false
            c += 10.0
        }
        return m
    }

    /** NavEngine đặt true trong luồng định tuyến lại khi xe đang chạy (xem carOnlyM). */
    val skipLeading = ThreadLocal<Boolean>()

    /** Các đỉnh dọc con đường [o] từ xe tới [dist] mét phía trước (điểm đầu là vị trí xe, điểm cuối là điểm đích đoạn). */
    fun walkPoints(o: OnWay, dist: Double): List<Pair<Double, Double>> {
        val w = o.way
        val out = ArrayList<Pair<Double, Double>>()
        var la = o.lat; var lo = o.lon
        out.add(la to lo)
        var left = dist
        var k = if (o.fwd) o.idx + 1 else o.idx
        while (left > 0 && k in w.lats.indices) {
            val d = Geo.distance(la, lo, w.lats[k], w.lons[k])
            if (d >= left) {
                val f = left / d
                la += (w.lats[k] - la) * f; lo += (w.lons[k] - lo) * f
                out.add(la to lo)
                left = 0.0
            } else {
                la = w.lats[k]; lo = w.lons[k]; left -= d
                out.add(la to lo)
                k += if (o.fwd) 1 else -1
            }
        }
        return out
    }

    /** Ghép đoạn đường đi [pts] (xe → điểm đầu tuyến) vào đầu tuyến, bám đúng đường cong thay vì nối thẳng chéo. */
    fun prependPath(r: Route, pts: List<Pair<Double, Double>>): Route {
        if (pts.size < 2) return r
        val use = if (Geo.distance(pts.last().first, pts.last().second, r.lats[0], r.lons[0]) < 2.0) pts.dropLast(1) else pts
        val k = use.size
        val la = DoubleArray(r.lats.size + k)
        val lo = DoubleArray(r.lons.size + k)
        for (i in 0 until k) { la[i] = use[i].first; lo[i] = use[i].second }
        System.arraycopy(r.lats, 0, la, k, r.lats.size)
        System.arraycopy(r.lons, 0, lo, k, r.lons.size)
        var d0 = 0.0
        for (i in 1 until k) d0 += Geo.distance(use[i - 1].first, use[i - 1].second, use[i].first, use[i].second)
        d0 += Geo.distance(use[k - 1].first, use[k - 1].second, r.lats[0], r.lons[0])
        val steps = r.steps.mapIndexed { i, st ->
            Step(st.sign, st.text, st.street, st.distance, if (i == 0) 0 else st.from + k, st.to + k, st.exitNumber)
        }
        return Route(la, lo, steps, r.distance + d0, r.timeMs, r.congestion)
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
