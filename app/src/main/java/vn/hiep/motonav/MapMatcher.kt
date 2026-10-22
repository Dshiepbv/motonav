package vn.hiep.motonav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Khớp đường theo chuỗi vị trí (Hidden Markov Model, thuật toán Viterbi trực tuyến) – kiểu Google Maps.
 *
 * Mỗi điểm GPS có vài "ứng viên" (đoạn đường gần đó). Xác suất một ứng viên đúng phụ thuộc:
 *  - Phát xạ: khoảng cách tới đường (so với sai số GPS) + hướng đi so với hướng đường / chiều một chiều.
 *  - Chuyển tiếp: quãng đường đi theo mạng đường giữa hai ứng viên phải gần bằng quãng đường GPS thực đi.
 * Nhờ vậy không nhảy sang đường song song / cầu vượt / đường gom chỉ vì một điểm GPS lệch.
 */
class MapMatcher {

    class Result(val way: Way, val forward: Boolean, val bearing: Double, val dist: Double, val lat: Double, val lon: Double)

    private class Cand(
        val way: Way,
        val seg: Int,
        val t: Double,          // vị trí trên đoạn [0..1]
        val along: Double,      // mét từ đầu đường
        val dist: Double,       // khoảng cách GPS → đường
        val forward: Boolean,   // đi theo chiều vẽ đường
        val bearing: Double,    // hướng chạy theo chiều xe
        val lat: Double,
        val lon: Double,
        var logp: Double = 0.0,
    )

    private var snapRef: OsmSnapshot? = null
    private var alongCache = HashMap<Long, DoubleArray>()
    /** Đỉnh dùng chung giữa các đường (nút giao): khoá toạ độ → danh sách (đường, chỉ số đỉnh). */
    private var nodes = HashMap<Long, MutableList<Pair<Way, Int>>>()
    private var prev: List<Cand> = emptyList()
    private var prevLat = 0.0
    private var prevLon = 0.0

    private fun key(lat: Double, lon: Double): Long =
        (Math.round(lat * 1e6) shl 32) xor (Math.round(lon * 1e6) and 0xffffffffL)

    private fun prepare(snap: OsmSnapshot) {
        if (snap === snapRef) return
        snapRef = snap
        alongCache = HashMap()
        nodes = HashMap()
        for (w in snap.ways) {
            val a = DoubleArray(w.lats.size)
            for (i in 1 until w.lats.size) a[i] = a[i - 1] + Geo.distance(w.lats[i - 1], w.lons[i - 1], w.lats[i], w.lons[i])
            alongCache[w.id] = a
            for (i in w.lats.indices) nodes.getOrPut(key(w.lats[i], w.lons[i])) { ArrayList(2) }.add(w to i)
        }
        prev = emptyList()
    }

    fun reset() { prev = emptyList() }

    /**
     * @param hdg hướng GPS khi đang chạy (null khi đứng yên)
     * @param acc sai số GPS (m)
     */
    fun update(snap: OsmSnapshot, lat: Double, lon: Double, hdg: Double?, acc: Double): Result? {
        prepare(snap)
        val sigma = max(5.0, min(acc, 40.0))
        val radius = min(70.0, max(30.0, sigma * 2.5))
        val cands = candidates(snap, lat, lon, hdg, radius)
        if (cands.isEmpty()) {
            prev = emptyList()
            return null
        }
        // Phát xạ
        for (c in cands) {
            var lp = -0.5 * (c.dist / sigma) * (c.dist / sigma)
            if (hdg != null) {
                val d = Geo.angleDiff(hdg, c.bearing)
                lp -= (d / 30.0) * (d / 30.0) * 0.5
            }
            c.logp = lp
        }
        // Chuyển tiếp từ bước trước
        if (prev.isNotEmpty()) {
            val gps = Geo.distance(prevLat, prevLon, lat, lon)
            val beta = max(4.0, gps * 0.3)
            for (c in cands) {
                var best = Double.NEGATIVE_INFINITY
                for (p in prev) {
                    val rd = routeDist(p, c)
                    val tr = if (rd < 0) -12.0 else -abs(rd - gps) / beta
                    val v = p.logp + tr
                    if (v > best) best = v
                }
                c.logp += best
            }
            // Chuẩn hoá để không trôi số
            val mx = cands.maxOf { it.logp }
            for (c in cands) c.logp -= mx
        }
        prev = cands
        prevLat = lat
        prevLon = lon
        val b = cands.maxByOrNull { it.logp }!!
        return Result(b.way, b.forward, b.bearing, b.dist, b.lat, b.lon)
    }

    private fun candidates(snap: OsmSnapshot, lat: Double, lon: Double, hdg: Double?, radius: Double): List<Cand> {
        val proj = LocalProjection(lat, lon)
        val pad = radius / 100000.0 + 0.0002
        val out = ArrayList<Cand>()
        for (w in snap.ways) {
            if (lat < w.minLat - pad || lat > w.maxLat + pad || lon < w.minLon - pad || lon > w.maxLon + pad) continue
            var best: Cand? = null
            val al = alongCache[w.id] ?: continue
            for (i in 0 until w.lats.size - 1) {
                val ax = proj.x(w.lons[i]); val ay = proj.y(w.lats[i])
                val bx = proj.x(w.lons[i + 1]); val by = proj.y(w.lats[i + 1])
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                if (len2 < 0.01) continue
                val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val px = ax + t * dx; val py = ay + t * dy
                val d = sqrt(px * px + py * py)
                if (d > radius) continue
                val segB = Geo.norm(Math.toDegrees(atan2(dx, dy)))
                var fwd = hdg == null || Geo.angleDiff(hdg, segB) <= 90
                if (w.oneway == 1) fwd = true
                if (w.oneway == -1) fwd = false
                val brg = if (fwd) segB else Geo.norm(segB + 180)
                if (best == null || d < best.dist) {
                    val la = w.lats[i] + t * (w.lats[i + 1] - w.lats[i])
                    val lo = w.lons[i] + t * (w.lons[i + 1] - w.lons[i])
                    best = Cand(w, i, t, al[i] + t * (al[i + 1] - al[i]), d, fwd, brg, la, lo)
                }
            }
            if (best != null) out.add(best)
        }
        // Giữ tối đa 8 ứng viên gần nhất
        return out.sortedBy { it.dist }.take(8)
    }

    /** Quãng đường theo mạng đường từ ứng viên p tới c (m); -1 nếu không nối được (trong 1 nút giao). */
    private fun routeDist(p: Cand, c: Cand): Double {
        if (p.way.id == c.way.id) {
            val d = c.along - p.along
            // Đường một chiều: đi lùi là không hợp lệ (cho phép sai số GPS 8 m)
            if (p.way.oneway == 1 && d < -8) return -1.0
            if (p.way.oneway == -1 && d > 8) return -1.0
            return abs(d)
        }
        val pa = alongCache[p.way.id] ?: return -1.0
        val ca = alongCache[c.way.id] ?: return -1.0
        var best = -1.0
        // Hai đường có chung một đỉnh (nút giao)
        for (i in p.way.lats.indices) {
            val list = nodes[key(p.way.lats[i], p.way.lons[i])] ?: continue
            if (list.size < 2) continue
            for ((w, j) in list) {
                if (w.id != c.way.id) continue
                val d = abs(pa[i] - p.along) + abs(ca[j] - c.along)
                if (best < 0 || d < best) best = d
            }
        }
        return best
    }
}
