package vn.hiep.motonav

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Gọi API Goong (rsapi.goong.io / tiles.goong.io) — PHƯƠNG ÁN THỬ NGHIỆM thay VietMap cho định
 * tuyến + (tuỳ chọn) bản đồ nền. Lý do chuyển: Goong có khách hàng thật dùng cho xe máy (Be, Xanh
 * SM, Viettel Post), và mảnh bản đồ nền Goong đọc được bằng MapLibre CHUẨN (có hướng dẫn chính
 * thức tích hợp MapLibre) — không như VietMap đang lỗi "invalid tag" vì mảnh vector định dạng
 * riêng (xem NavActivity.loadStyle() / VietMap.rasterStyleJson).
 *
 * CHƯA XÁC NHẬN với tài khoản thật (viết lúc đang chờ Goong kích hoạt tài khoản mới, 20261026):
 *  - Giá trị "vehicle" đúng cho XE MÁY: tài liệu Goong liệt kê car/bike/taxi/truck/hd nhưng không
 *    ghi rõ cái nào ứng với "xe máy" — khác VietMap có hẳn "vehicle=motorcycle" rõ ràng (xem
 *    VietMap.routeOnce). "bike" nhiều khả năng là XE ĐẠP (không phải xe máy, luật đường khác hẳn).
 *    Mặc định tạm "car" (Prefs.goongVehicle) — CẦN Hiệp thử lần lượt car/bike/taxi/truck/hd trên 1-2
 *    tuyến quen thuộc, so với tuyến VietMap/thực tế, rồi chọn giá trị đúng nhất trong Cài đặt.
 *  - Không xác nhận được tham số né cao tốc hay dữ liệu kẹt xe/traffic — "Tránh đường cao tốc" và
 *    "Tô màu kẹt xe trên tuyến" trong Cài đặt KHÔNG có tác dụng khi đang dùng Goong để định tuyến
 *    (route trả về luôn coi congestion = "unknown", tuyến vẽ một màu).
 *  - Endpoint/tham số suy ra từ tài liệu Goong (help.goong.io), chưa gọi thử thật — có thể cần
 *    chỉnh lại tên tham số khi thấy lỗi thật từ API (thông báo lỗi sẽ hiện ra màn hình, không câm
 *    lặng, để dễ debug).
 */
object GoongMap {
    private const val REST_BASE = "https://rsapi.goong.io"
    private const val TILE_BASE = "https://tiles.goong.io/assets"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun ll(lat: Double, lon: Double) = String.format(Locale.US, "%.6f,%.6f", lat, lon)

    /** Style bản đồ nền Goong (vector, MapLibre chuẩn) — ngày/đêm. */
    fun tileStyleUrl(key: String, night: Boolean): String {
        val name = if (night) "goong_map_dark" else "goong_map_web"
        return "$TILE_BASE/$name.json?api_key=" + enc(key)
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "MotoNav/1.0 (Android)")
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code == 401 || code == 403) throw RuntimeException("Mã API Goong không hợp lệ hoặc tài khoản chưa kích hoạt (HTTP $code)")
            if (code !in 200..299) throw RuntimeException("Goong lỗi HTTP $code")
            return body
        } finally {
            c.disconnect()
        }
    }

    /** Thao tác rẽ kiểu Goong/Google ("turn-left"...) -> mã sign nội bộ (chuẩn GraphHopper, app đang dùng chung cho VietMap). */
    private fun signFor(maneuver: String): Int = when (maneuver) {
        "turn-sharp-left" -> -3
        "turn-left" -> -2
        "turn-slight-left", "ramp-left", "fork-left", "keep-left" -> -1
        "straight", "merge" -> 0
        "turn-slight-right", "ramp-right", "fork-right", "keep-right" -> 1
        "turn-right" -> 2
        "turn-sharp-right" -> 3
        "roundabout-left", "roundabout-right" -> 6
        "uturn-left", "uturn-right" -> -8
        else -> 0
    }

    /** Bỏ thẻ HTML trong câu chỉ dẫn Goong trả về (vd "Rẽ phải vào <b>Lê Văn Việt</b>"). */
    private fun stripHtml(s: String): String =
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()

    private val boldRe = Regex("<b>(.*?)</b>")

    /** Tên đường trong thẻ <b>...</b> của câu chỉ dẫn, nếu có (Goong hay in đậm tên đường sắp vào). */
    private fun streetFrom(html: String): String = boldRe.find(html)?.groupValues?.get(1)?.let { stripHtml(it) } ?: ""

    /** Tìm đường bằng Goong Directions v2. [vehicle] xem Prefs.goongVehicle (chưa xác nhận giá trị đúng cho xe máy). */
    fun routes(
        key: String, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        vehicle: String, alternative: Boolean = false,
    ): List<Route> {
        val url = "$REST_BASE/v2/direction?origin=" + enc(ll(fromLat, fromLon)) +
            "&destination=" + enc(ll(toLat, toLon)) +
            "&vehicle=" + enc(vehicle) +
            "&alternatives=" + (if (alternative) "true" else "false") +
            "&api_key=" + enc(key)
        val root = JSONObject(get(url))
        val status = root.optString("status", "OK")
        if (status != "OK") {
            val msg = when (status) {
                "ZERO_RESULTS", "NOT_FOUND" -> "Goong: không tìm được đường đi tới điểm này"
                "OVER_QUERY_LIMIT" -> "Goong: đã hết lượt gọi trong gói hiện tại"
                "REQUEST_DENIED" -> "Goong: mã API bị từ chối (kiểm tra lại mã/kích hoạt tài khoản)"
                else -> "Goong: $status"
            }
            throw RuntimeException(msg)
        }
        val routesArr = root.optJSONArray("routes") ?: throw RuntimeException("Goong không trả về tuyến")
        val out = ArrayList<Route>()
        for (ri in 0 until minOf(routesArr.length(), 3)) {
            try {
                out.add(parseRoute(routesArr.getJSONObject(ri)))
            } catch (_: Exception) {
            }
        }
        if (out.isEmpty()) throw RuntimeException("Tuyến đường rỗng")
        return out
    }

    private fun parseRoute(r: JSONObject): Route {
        val legs = r.optJSONArray("legs") ?: JSONArray()
        val la = ArrayList<Double>()
        val lo = ArrayList<Double>()
        val steps = ArrayList<Step>()
        var totalDist = 0.0
        var totalTimeS = 0.0
        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            totalDist += leg.optJSONObject("distance")?.optDouble("value", 0.0) ?: 0.0
            totalTimeS += leg.optJSONObject("duration")?.optDouble("value", 0.0) ?: 0.0
            val stepsArr = leg.optJSONArray("steps") ?: JSONArray()
            for (si in 0 until stepsArr.length()) {
                val st = stepsArr.getJSONObject(si)
                val poly = st.optJSONObject("polyline")?.optString("points", "") ?: ""
                if (poly.isBlank()) continue
                val (sla, slo) = Route.decodePolyline(poly)
                if (sla.isEmpty()) continue
                val from = la.size
                // Bỏ điểm đầu nếu trùng điểm cuối bước trước (các bước nối liền nhau, tránh trùng điểm)
                val skipFirst = la.isNotEmpty() && sla[0] == la.last() && slo[0] == lo.last()
                val startAt = if (skipFirst) 1 else 0
                for (k in startAt until sla.size) {
                    la.add(sla[k]); lo.add(slo[k])
                }
                val to = (la.size - 1).coerceAtLeast(from)
                val html = st.optString("html_instructions", "")
                steps.add(
                    Step(
                        sign = signFor(st.optString("maneuver", "")),
                        text = stripHtml(html),
                        street = streetFrom(html),
                        distance = st.optJSONObject("distance")?.optDouble("value", 0.0) ?: 0.0,
                        from = from,
                        to = to,
                        exitNumber = 0,
                    ),
                )
            }
        }
        if (la.size < 2) throw RuntimeException("Tuyến đường rỗng")
        return Route(la.toDoubleArray(), lo.toDoubleArray(), steps, totalDist, (totalTimeS * 1000).toLong())
    }
}
