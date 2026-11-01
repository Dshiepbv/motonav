package vn.hiep.motonav

/**
 * build-305: chỗ đường tách tầng (cầu vượt / đường dưới cầu chạy song song) — Sếp yêu cầu ghi rõ "Lên cầu" hay
 * "Đi dưới cầu" thay vì "Đi thẳng", vì đi nhầm tầng thì không quay đầu được.
 *
 * Mỗi điểm mẫu (10 m) trên tuyến được xếp loại theo OSM:
 *  ON    = tuyến nằm trên một đường gắn bridge (≤ 7 m, cùng hướng ≤ 30°) VÀ bên cạnh có đường mặt đất cùng hướng (≤ 40 m)
 *  UNDER = tuyến không trên cầu nhưng có cầu cùng hướng chạy song song (≤ 40 m)
 *  GROUND = còn lại (cầu nhỏ qua kênh không có đường song song thì không báo).
 */
object BridgeHint {
    const val GROUND = 0
    const val ON = 1
    const val UNDER = -1

    class Hint(val kind: Int, val distM: Double, val lat: Double, val lon: Double, val name: String?)

    private var snapRef: Any? = null
    private var bridges: List<Way> = emptyList()
    private var grounds: List<Way> = emptyList()
    private var routeRef: Route? = null
    private val memo = HashMap<Int, Pair<Int, String?>>()

    private fun isRoad(w: Way): Boolean = when (w.highway) {
        "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link",
        "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "road" -> true
        else -> false
    }

    private fun isBridge(w: Way): Boolean = w.tags["bridge"].let { it != null && it != "no" }

    /** Chỗ chuyển tầng gần nhất phía trước (từ [progress] tới [progress]+[maxAhead]), null nếu không có. */
    fun ahead(r: Route, progress: Double, maxAhead: Double = 500.0): Hint? {
        val snap = Live.osm ?: return null
        synchronized(this) {
            if (snap !== snapRef) {
                snapRef = snap
                bridges = snap.ways.filter { isRoad(it) && isBridge(it) }
                grounds = snap.ways.filter { isRoad(it) && !isBridge(it) && it.tags["tunnel"].let { t -> t == null || t == "no" } }
                memo.clear()
            }
            if (r !== routeRef) { routeRef = r; memo.clear() }
            if (bridges.isEmpty()) return null
            val k0 = Math.floor(progress / 10.0).toInt()
            val k1 = Math.floor(Math.min(r.total, progress + maxAhead) / 10.0).toInt()
            var prev = state(r, k0).first
            for (k in (k0 + 1)..k1) {
                val (s, nm) = state(r, k)
                if (s != prev && s != GROUND) {
                    val p = pointAt(r, k * 10.0)
                    return Hint(s, k * 10.0 - progress, p.first, p.second, nm)
                }
                prev = s
            }
            return null
        }
    }

    private fun state(r: Route, k: Int): Pair<Int, String?> {
        memo[k]?.let { return it }
        val c = k * 10.0
        val p = pointAt(r, c)
        val q = pointAt(r, Math.min(r.total, c + 10.0))
        val brg = if (p == q) Geo.bearing(pointAt(r, Math.max(0.0, c - 10.0)).first, pointAt(r, Math.max(0.0, c - 10.0)).second, p.first, p.second)
            else Geo.bearing(p.first, p.second, q.first, q.second)
        val proj = LocalProjection(p.first, p.second)
        fun near(list: List<Way>, maxD: Double, lateral: Boolean = false): Pair<Double, Way?> {
            var best = Double.MAX_VALUE; var bw: Way? = null
            val pad = maxD / 100000.0 * 1.2
            for (w in list) {
                if (p.first < w.minLat - pad || p.first > w.maxLat + pad || p.second < w.minLon - pad || p.second > w.maxLon + pad) continue
                for (i in 0 until w.lats.size - 1) {
                    val ax = proj.x(w.lons[i]); val ay = proj.y(w.lats[i])
                    val dx = proj.x(w.lons[i + 1]) - ax; val dy = proj.y(w.lats[i + 1]) - ay
                    val len2 = dx * dx + dy * dy
                    if (len2 < 1.0) continue
                    val tr = -(ax * dx + ay * dy) / len2
                    // đường mặt đất phải chạy SONG SONG bên cạnh (chân đường vuông góc nằm trong đoạn), không tính phần
                    // nối thẳng đầu cầu (cầu nhỏ qua kênh) — tránh báo "Lên cầu" ở mọi cây cầu.
                    if (lateral && (tr < 0.02 || tr > 0.98)) continue
                    val t = tr.coerceIn(0.0, 1.0)
                    val d = Math.hypot(ax + t * dx, ay + t * dy)
                    if (d > maxD || d >= best) continue
                    val sb = Geo.bearing(w.lats[i], w.lons[i], w.lats[i + 1], w.lons[i + 1])
                    val diff = Geo.angleDiff(brg, sb)
                    if (diff <= 30.0 || diff >= 150.0) { best = d; bw = w }
                }
            }
            return best to bw
        }
        val (dB, wB) = near(bridges, 40.0)
        val res: Pair<Int, String?> = if (wB == null) GROUND to null else {
            val (dG, wG) = near(grounds, 40.0, lateral = true)
            when {
                dB <= 9.0 && wG != null && dG > 9.0 -> ON to wB.name
                dB >= 12.0 && (wG == null || dG <= 9.0) -> UNDER to wB.name
                else -> GROUND to null
            }
        }
        memo[k] = res
        return res
    }

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
}
