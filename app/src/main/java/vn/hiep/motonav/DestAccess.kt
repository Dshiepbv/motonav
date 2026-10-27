package vn.hiep.motonav

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * Chốt điểm đến thực tế cho xe máy: theo yêu cầu "tìm địa chỉ luôn là cổng, lối ra vào, điểm gửi xe
 * của toà nhà" — toạ độ địa chỉ (Goong/tìm kiếm) thường là tâm toà nhà, tuyến đường tới đó hay dẫn
 * vào mặt đường sai phía hoặc hẻm sau lưng. Hỏi OSM quanh điểm đến rồi chọn theo thứ tự ưu tiên:
 *   bãi gửi xe máy → lối vào bãi xe / lối vào chính → lối vào khác / bãi xe chung → cổng
 *   → (không có gì) điểm gần nhất trên con đường xe vào được, ưu tiên đường trùng tên trong địa chỉ.
 */
object DestAccess {
    class Result(val lat: Double, val lon: Double, val kind: String, val label: String?)

    private const val R = 150

    private class Cand(val lat: Double, val lon: Double, val prio: Int, val kind: String, val label: String?)

    fun resolve(lat: Double, lon: Double, destName: String?): Result? {
        val p = String.format(Locale.US, "%.6f,%.6f", lat, lon)
        val q = buildString {
            append("[out:json][timeout:15];\n")
            append("way(around:60,$p)[\"building\"];\n")
            append("out tags geom;\n")
            append("(\n")
            append("node(around:$R,$p)[\"entrance\"];\n")
            append("node(around:$R,$p)[\"barrier\"~\"^(gate|lift_gate|swing_gate|sliding_gate|entrance)\$\"];\n")
            append("nwr(around:$R,$p)[\"amenity\"~\"^(motorcycle_parking|parking|parking_entrance)\$\"];\n")
            append(");\n")
            append("out center tags;\n")
            append("way(around:$R,$p)[\"highway\"~\"^(trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|trunk_link|primary_link|secondary_link|tertiary_link)\$\"];\n")
            append("out tags geom;\n")
        }
        val root = Overpass.raw(q)
        val els = root.optJSONArray("elements") ?: return null

        val buildings = ArrayList<Pair<DoubleArray, DoubleArray>>()
        val roads = ArrayList<Triple<String?, DoubleArray, DoubleArray>>()
        val cands = ArrayList<Cand>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val type = e.optString("type")
            val geom = e.optJSONArray("geometry")
            if (type == "way" && geom != null && geom.length() >= 2 && (tags.has("building") || tags.has("highway"))) {
                val la = DoubleArray(geom.length()) { geom.getJSONObject(it).optDouble("lat") }
                val lo = DoubleArray(geom.length()) { geom.getJSONObject(it).optDouble("lon") }
                if (tags.has("building")) buildings.add(la to lo)
                else if (tags.optString("access") !in setOf("private", "no")) roads.add(Triple(tags.optString("name").ifBlank { null }, la, lo))
                continue
            }
            val cLat: Double
            val cLon: Double
            if (e.has("lat")) {
                cLat = e.optDouble("lat"); cLon = e.optDouble("lon")
            } else {
                val c = e.optJSONObject("center") ?: continue
                cLat = c.optDouble("lat"); cLon = c.optDouble("lon")
            }
            val amenity = tags.optString("amenity")
            val entrance = tags.optString("entrance")
            val barrier = tags.optString("barrier")
            val access = tags.optString("access")
            val moto = tags.optString("motorcycle")
            if (access == "no" || moto == "no") continue
            val name = tags.optString("name").ifBlank { null }
            val prio = when {
                amenity == "motorcycle_parking" -> 0
                amenity == "parking" && moto in setOf("yes", "designated") -> 0
                amenity == "parking_entrance" -> 1
                entrance == "main" -> 1
                entrance in setOf("exit", "emergency") -> continue
                entrance.isNotEmpty() -> 2
                amenity == "parking" -> 2
                barrier.isNotEmpty() -> if (access == "private") 4 else 3
                else -> continue
            }
            val kind = when {
                amenity.isNotEmpty() -> amenity
                entrance.isNotEmpty() -> "entrance=$entrance"
                else -> "barrier=$barrier"
            }
            cands.add(Cand(cLat, cLon, prio, kind, name))
        }

        // Toà nhà chứa điểm đến (hoặc sát nhất, ≤ 30 m) — đo khoảng cách ứng viên tới VIỀN toà nhà
        // thay vì tới tâm, để cổng/bãi xe ở mặt tiền toà nhà lớn không bị coi là "xa".
        val bld = buildings.firstOrNull { contains(it.first, it.second, lat, lon) }
            ?: buildings.minByOrNull { edgeDist(it.first, it.second, lat, lon) }?.takeIf { edgeDist(it.first, it.second, lat, lon) <= 30.0 }
        fun refDist(cLat: Double, cLon: Double): Double =
            if (bld != null) edgeDist(bld.first, bld.second, cLat, cLon) else Geo.distance(lat, lon, cLat, cLon)
        val maxD = if (bld != null) 60.0 else 120.0
        val best = cands.filter { refDist(it.lat, it.lon) <= maxD }
            .minByOrNull { it.prio * 50.0 + refDist(it.lat, it.lon) }
        if (best != null) return Result(best.lat, best.lon, best.kind, best.label)

        // Không có cổng/bãi xe trên OSM → điểm gần nhất trên đường xe vào được (ưu tiên đường trùng
        // tên trong địa chỉ, ví dụ "12 Nguyễn Du" → đường Nguyễn Du).
        val want = fold(destName ?: "")
        var bestRoad: Pair<Double, Double>? = null
        var bestScore = Double.MAX_VALUE
        for ((name, la, lo) in roads) {
            val bonus = if (name != null && want.isNotEmpty() && want.contains(fold(name))) -40.0 else 0.0
            for (k in 0 until la.size - 1) {
                val pt = nearestOnSeg(la[k], lo[k], la[k + 1], lo[k + 1], lat, lon)
                val d = refDist(pt.first, pt.second)
                if (d > 80.0) continue
                val score = d + bonus
                if (score < bestScore) { bestScore = score; bestRoad = pt }
            }
        }
        val r = bestRoad ?: return null
        return Result(r.first, r.second, "road", null)
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace('đ', 'd')

    private fun contains(la: DoubleArray, lo: DoubleArray, lat: Double, lon: Double): Boolean {
        var inside = false
        var j = la.size - 1
        for (i in la.indices) {
            if ((la[i] > lat) != (la[j] > lat) && lon < (lo[j] - lo[i]) * (lat - la[i]) / (la[j] - la[i]) + lo[i]) inside = !inside
            j = i
        }
        return inside
    }

    private fun nearestOnSeg(aLat: Double, aLon: Double, bLat: Double, bLon: Double, lat: Double, lon: Double): Pair<Double, Double> {
        val proj = LocalProjection(lat, lon)
        val ax = proj.x(aLon); val ay = proj.y(aLat)
        val bx = proj.x(bLon); val by = proj.y(bLat)
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 0.01) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        return (aLat + t * (bLat - aLat)) to (aLon + t * (bLon - aLon))
    }

    private fun edgeDist(la: DoubleArray, lo: DoubleArray, lat: Double, lon: Double): Double {
        if (contains(la, lo, lat, lon)) return 0.0
        var best = Double.MAX_VALUE
        for (k in 0 until la.size - 1) {
            val pt = nearestOnSeg(la[k], lo[k], la[k + 1], lo[k + 1], lat, lon)
            val d = Geo.distance(lat, lon, pt.first, pt.second)
            if (d < best) best = d
        }
        return best
    }
}
