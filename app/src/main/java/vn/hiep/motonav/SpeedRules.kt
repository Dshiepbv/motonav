package vn.hiep.motonav

import kotlin.math.min
import kotlin.math.roundToInt

class LimitResult(val kmh: Int, val estimated: Boolean)

/**
 * Tốc độ giới hạn cho xe máy.
 *
 * Ưu tiên biển thật trên OpenStreetMap (maxspeed:motorcycle, maxspeed theo chiều, maxspeed).
 * Khi đường chưa có biển, ước tính theo Thông tư 38/2024/TT-BGTVT:
 *  - Xe mô tô trong khu đông dân cư: 60 km/h (đường đôi / một chiều từ 2 làn), 50 km/h (hai chiều / một chiều 1 làn)
 *  - Xe mô tô ngoài khu đông dân cư: 70 km/h và 60 km/h tương ứng
 *  - Xe gắn máy (≤ 50cc): tối đa 40 km/h
 */
object SpeedRules {
    private val numberRe = Regex("""(\d+(?:\.\d+)?)\s*(mph|knots)?""")

    fun resolve(way: Way, forward: Boolean, prefs: Prefs, urbanArea: Boolean?): LimitResult? {
        val vehicleCap = if (prefs.moped) 40 else 70
        val keys = listOf(
            "maxspeed:motorcycle",
            if (forward) "maxspeed:motorcycle:forward" else "maxspeed:motorcycle:backward",
            if (forward) "maxspeed:forward" else "maxspeed:backward",
            "maxspeed",
        )
        for (k in keys) {
            val raw = way.tags[k]?.trim() ?: continue
            val n = parseNumber(raw)
            if (n != null) return LimitResult(min(n, vehicleCap), false)
            val lower = raw.lowercase()
            if (lower.startsWith("vn:")) {
                val urban = lower.contains("urban")
                return LimitResult(min(estimateValue(way, urban), vehicleCap), false)
            }
        }
        if (!prefs.estimate) return null
        // Theo yêu cầu: có dữ liệu biển -> dùng biển (đã xử lý ở trên); có dữ liệu trong/ngoài khu
        // dân cư (ranh giới hành chính Phường/Thị trấn/Xã trong OsmData, hoặc người dùng tự chọn ở
        // Cài đặt) -> áp công thức Thông tư 38/2024; HOÀN TOÀN không có dữ liệu gì (urbanArea null,
        // areaMode tự động) -> để thẳng 50, không đoán mò theo loại đường (trước đây đoán sai lệch
        // khi gặp đường ngoài KDC chưa xác định được ranh giới).
        val urban = when (prefs.areaMode) {
            Prefs.AREA_URBAN -> true
            Prefs.AREA_RURAL -> false
            else -> urbanArea
        }
        if (urban == null) return LimitResult(min(50, vehicleCap), true)
        return LimitResult(min(estimateValue(way, urban), vehicleCap), true)
    }

    /** Đoạn đường đang đi có trong khu đông dân cư không. Trả về (trong KDC?, ước tính?). */
    fun urbanOf(way: Way, prefs: Prefs, urbanArea: Boolean?): Pair<Boolean, Boolean> {
        for (k in listOf("maxspeed:motorcycle", "maxspeed")) {
            val raw = way.tags[k]?.lowercase() ?: continue
            if (raw.startsWith("vn:")) return Pair(raw.contains("urban"), false)
        }
        return when (prefs.areaMode) {
            Prefs.AREA_URBAN -> Pair(true, false)
            Prefs.AREA_RURAL -> Pair(false, false)
            else -> Pair(urbanArea ?: !(way.highway.startsWith("motorway") || way.highway.startsWith("trunk")), true)
        }
    }

    private fun parseNumber(raw: String): Int? {
        val first = raw.split(';', '|').first().trim()
        val m = numberRe.matchEntire(first) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2]
        val kmh = when (unit) {
            "mph" -> v * 1.609
            "knots" -> v * 1.852
            else -> v
        }
        val r = kmh.roundToInt()
        return if (r in 5..150) r else null
    }

    private fun isDivided(way: Way): Boolean {
        if (way.tags["dual_carriageway"] == "yes") return true
        if (way.oneway == 0) return false
        val lanes = way.lanes
        if (lanes != null) return lanes >= 2
        return way.highway in setOf("motorway", "trunk", "primary", "secondary")
    }

    private fun estimateValue(way: Way, urban: Boolean): Int {
        val divided = isDivided(way)
        return if (urban) {
            if (divided) 60 else 50
        } else {
            if (divided) 70 else 60
        }
    }
}
