package vn.hiep.motonav

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Tìm đường bằng Google Maps Directions API — thay thế VietMap khi tuyến VietMap hay dẫn vào
 * đường có chắn/không thông minh bằng Google. Cần mã API riêng của người dùng (Google Cloud
 * Console, bật Directions API + thanh toán — có hạn mức miễn phí hàng tháng).
 *
 * LƯU Ý: chưa test với API key thật (chưa có mã để gọi thử) — cần kiểm tra lại các trường hợp
 * biên (vòng xuyến, quay đầu, chỉ dẫn tiếng Việt) trên xe thật.
 */
object GoogleDirections {
    private const val BASE = "https://maps.googleapis.com/maps/api/directions/json"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun ll(lat: Double, lon: Double) = String.format(Locale.US, "%.6f,%.6f", lat, lon)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "MotoNav/1.0 (Android)")
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) throw RuntimeException("Google Directions lỗi HTTP $code")
            return body
        } finally {
            c.disconnect()
        }
    }

    fun routes(
        key: String, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        alternative: Boolean = false, via: List<Pair<Double, Double>> = emptyList(),
        avoidHighway: Boolean = false,
    ): List<Route> {
        val sb = StringBuilder(BASE).append("?key=").append(enc(key))
            .append("&origin=").append(ll(fromLat, fromLon))
            .append("&destination=").append(ll(toLat, toLon))
            .append("&mode=driving&language=vi&units=metric")
        if (via.isNotEmpty()) {
            sb.append("&waypoints=").append(via.joinToString("|") { ll(it.first, it.second) })
        }
        if (alternative && via.isEmpty()) sb.append("&alternatives=true")
        // Google không có "tránh phà" riêng cho avoid=ferries kết hợp tránh cao tốc — ghép bằng |.
        val avoid = buildList {
            add("ferries")
            if (avoidHighway) add("highways")
        }
        sb.append("&avoid=").append(avoid.joinToString("|"))
        val root = JSONObject(get(sb.toString()))
        val status = root.optString("status", "UNKNOWN_ERROR")
        if (status != "OK") {
            val msg = when (status) {
                "ZERO_RESULTS" -> "Không tìm được đường đi tới điểm này (Google)"
                "OVER_QUERY_LIMIT" -> "Đã hết hạn mức Google Directions trong ngày/giây"
                "REQUEST_DENIED" -> "Mã API Google không hợp lệ hoặc chưa bật Directions API (${root.optString("error_message")})"
                else -> "Google Directions: $status ${root.optString("error_message")}"
            }
            throw RuntimeException(msg)
        }
        val routesArr = root.optJSONArray("routes") ?: throw RuntimeException("Google không trả về tuyến")
        val out = ArrayList<Route>()
        for (ri in 0 until minOf(routesArr.length(), 3)) {
            try {
                out.add(parseRoute(routesArr.getJSONObject(ri)))
            } catch (_: Exception) {
            }
        }
        if (out.isEmpty()) throw RuntimeException("Tuyến đường rỗng (Google)")
        return out
    }

    private val tagRe = Regex("<[^>]*>")
    private val boldRe = Regex("<b>(.*?)</b>", RegexOption.DOT_MATCHES_ALL)
    private val exitNumRe = Regex("""(\d+)""")

    /** Tên đường/điểm đáng chú ý nhất trong câu chỉ dẫn HTML — thường là cụm cuối cùng in đậm. */
    private fun streetFromHtml(html: String): String {
        val bolds = boldRe.findAll(html).map { it.groupValues[1] }.toList()
        if (bolds.isNotEmpty()) return bolds.last().trim()
        return html.replace(tagRe, " ").replace(Regex("\\s+"), " ").trim()
    }

    /** Mã rẽ kiểu GraphHopper (dùng chung toàn app) từ chuỗi "maneuver" của Google. */
    private fun signFromManeuver(m: String?, plainInstruction: String): Int = when (m) {
        "turn-slight-left" -> -1
        "turn-left" -> -2
        "turn-sharp-left" -> -3
        "uturn-left", "uturn-right" -> -8
        "turn-slight-right" -> 1
        "turn-right" -> 2
        "turn-sharp-right" -> 3
        "roundabout-left", "roundabout-right" -> 6
        "merge" -> 0
        "fork-left", "ramp-left" -> -1
        "fork-right", "ramp-right" -> 1
        "ferry", "ferry-train" -> 0
        "straight", null -> 0
        else -> 0
    }

    private fun parseRoute(r: JSONObject): Route {
        val legs = r.getJSONArray("legs")
        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        val steps = ArrayList<Step>()
        var totalDist = 0.0
        var totalMs = 0L
        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            totalDist += leg.optJSONObject("distance")?.optDouble("value", 0.0) ?: 0.0
            totalMs += ((leg.optJSONObject("duration")?.optInt("value", 0) ?: 0) * 1000L)
            val stepsArr = leg.getJSONArray("steps")
            for (si in 0 until stepsArr.length()) {
                val s = stepsArr.getJSONObject(si)
                val from = lats.size
                val (pla, plo) = Route.decodePolyline(s.getJSONObject("polyline").getString("points"))
                // Điểm đầu của bước sau trùng điểm cuối bước trước — bỏ điểm trùng để tuyến liền mạch.
                val startAt = if (from > 0 && pla.isNotEmpty() &&
                    kotlin.math.abs(pla[0] - lats.last()) < 1e-7 && kotlin.math.abs(plo[0] - lons.last()) < 1e-7
                ) 1 else 0
                for (i in startAt until pla.size) { lats.add(pla[i]); lons.add(plo[i]) }
                val html = s.optString("html_instructions", "")
                val maneuver = if (s.has("maneuver")) s.optString("maneuver") else null
                val plain = html.replace(tagRe, " ").replace(Regex("\\s+"), " ").trim()
                val sign = signFromManeuver(maneuver, plain)
                val street = if (sign == 6) plain else streetFromHtml(html) // vòng xuyến: giữ cả câu để còn số lối ra
                val exitNum = if (sign == 6) exitNumRe.find(plain)?.groupValues?.get(1)?.toIntOrNull() ?: 0 else 0
                steps.add(
                    Step(
                        sign = sign, text = plain, street = if (sign == 6) "" else street,
                        distance = s.optJSONObject("distance")?.optDouble("value", 0.0) ?: 0.0,
                        from = from, to = lats.size - 1, exitNumber = exitNum,
                    ),
                )
            }
        }
        // Bước cuối: "đến nơi" — route/v4 (VietMap) luôn có bước này, Google thì ngầm hiểu nên thêm
        // vào để phần còn lại của app (báo "đến nơi", icon mũi tên...) hoạt động giống nhau.
        if (lats.isNotEmpty()) {
            steps.add(Step(sign = 4, text = "Đến nơi", street = "", distance = 0.0, from = lats.size - 1, to = lats.size - 1, exitNumber = 0))
        }
        return Route(lats.toDoubleArray(), lons.toDoubleArray(), steps, totalDist, totalMs)
        // congestion: Google Directions chuẩn không trả mức kẹt xe theo từng đoạn, để rỗng.
    }
}
