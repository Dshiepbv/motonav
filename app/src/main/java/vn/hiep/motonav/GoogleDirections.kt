package vn.hiep.motonav

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tìm đường bằng Google Maps Routes API (computeRoutes) — thay thế VietMap khi tuyến VietMap hay
 * dẫn vào đường có chắn/không thông minh bằng Google. Cần mã API riêng của người dùng (Google
 * Cloud Console, bật "Routes API" + thanh toán — có hạn mức miễn phí hàng tháng).
 *
 * LƯU Ý: Google đã khai tử "Directions API" (cũ) từ 1/3/2025 — project mới KHÔNG bật được API đó
 * nữa, chỉ dùng được "Routes API" (API kế thừa). Code này gọi Routes API v2, không phải Directions
 * API cũ (khác hẳn format request/response).
 */
object GoogleDirections {
    private const val BASE = "https://routes.googleapis.com/directions/v2:computeRoutes"
    private const val FIELD_MASK =
        "routes.duration,routes.distanceMeters,routes.legs.steps.distanceMeters," +
            "routes.legs.steps.polyline.encodedPolyline,routes.legs.steps.navigationInstruction"

    private fun post(key: String, body: JSONObject): String {
        val c = URL(BASE).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 12000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "MotoNav/1.0 (Android)")
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("X-Goog-Api-Key", key)
            c.setRequestProperty("X-Goog-FieldMask", FIELD_MASK)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            val respBody = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                val err = try { JSONObject(respBody).optJSONObject("error") } catch (_: Exception) { null }
                val status = err?.optString("status") ?: "HTTP_$code"
                val msg = err?.optString("message") ?: respBody.take(200)
                throw RuntimeException(
                    when (status) {
                        "PERMISSION_DENIED" -> "Mã API Google không hợp lệ hoặc chưa bật Routes API ($msg)"
                        "RESOURCE_EXHAUSTED" -> "Đã hết hạn mức Google Routes trong ngày/giây"
                        "INVALID_ARGUMENT" -> "Google Routes: tham số không hợp lệ ($msg)"
                        else -> "Google Routes: $status $msg"
                    },
                )
            }
            return respBody
        } finally {
            c.disconnect()
        }
    }

    private fun latLng(lat: Double, lon: Double) = JSONObject().put(
        "location",
        JSONObject().put("latLng", JSONObject().put("latitude", lat).put("longitude", lon)),
    )

    fun routes(
        key: String, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        alternative: Boolean = false, via: List<Pair<Double, Double>> = emptyList(),
        avoidHighway: Boolean = false,
    ): List<Route> {
        val body = JSONObject()
            .put("origin", latLng(fromLat, fromLon))
            .put("destination", latLng(toLat, toLon))
            .put("travelMode", "DRIVE")
            .put("routingPreference", "TRAFFIC_AWARE")
            .put("languageCode", "vi")
            .put("units", "METRIC")
            .put(
                "routeModifiers",
                JSONObject().put("avoidTolls", false).put("avoidFerries", true).put("avoidHighways", avoidHighway),
            )
        if (via.isNotEmpty()) {
            body.put("intermediates", JSONArray(via.map { latLng(it.first, it.second) }))
        } else if (alternative) {
            body.put("computeAlternativeRoutes", true)
        }
        val root = JSONObject(post(key, body))
        val routesArr = root.optJSONArray("routes")
        if (routesArr == null || routesArr.length() == 0) throw RuntimeException("Không tìm được đường đi tới điểm này (Google)")
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

    private val exitNumRe = Regex("""(\d+)""")
    // "Rẽ phải vào Nguyễn Huệ", "Đi theo Xa Lộ Hà Nội" — lấy cụm tên đường sau "vào "/"theo " cuối câu.
    private val streetRe = Regex("""(?:vào|theo)\s+(.+)$""")

    private fun streetFromInstruction(plain: String): String {
        val m = streetRe.find(plain)
        return (m?.groupValues?.get(1) ?: plain).trim().trimEnd('.')
    }

    /** Mã rẽ kiểu GraphHopper (dùng chung toàn app) từ "maneuver" của Routes API. */
    private fun signFromManeuver(m: String?): Int = when (m) {
        "TURN_SLIGHT_LEFT" -> -1
        "TURN_LEFT" -> -2
        "TURN_SHARP_LEFT" -> -3
        "UTURN_LEFT", "UTURN_RIGHT" -> -8
        "TURN_SLIGHT_RIGHT" -> 1
        "TURN_RIGHT" -> 2
        "TURN_SHARP_RIGHT" -> 3
        "ROUNDABOUT_LEFT", "ROUNDABOUT_RIGHT" -> 6
        "MERGE" -> 0
        "FORK_LEFT", "RAMP_LEFT" -> -1
        "FORK_RIGHT", "RAMP_RIGHT" -> 1
        "FERRY", "FERRY_TRAIN" -> 0
        "STRAIGHT", "DEPART", "NAME_CHANGE", null -> 0
        else -> 0
    }

    private fun parseDurationS(d: String?): Long {
        if (d.isNullOrBlank()) return 0L
        return d.trimEnd('s').toDoubleOrNull()?.toLong() ?: 0L
    }

    private fun parseRoute(r: JSONObject): Route {
        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        val steps = ArrayList<Step>()
        val totalDist = r.optDouble("distanceMeters", 0.0)
        val totalMs = parseDurationS(r.optString("duration")) * 1000L
        val legs = r.optJSONArray("legs") ?: JSONArray()
        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            val stepsArr = leg.optJSONArray("steps") ?: JSONArray()
            for (si in 0 until stepsArr.length()) {
                val s = stepsArr.getJSONObject(si)
                val from = lats.size
                val enc = s.optJSONObject("polyline")?.optString("encodedPolyline", "") ?: ""
                if (enc.isNotEmpty()) {
                    val (pla, plo) = Route.decodePolyline(enc)
                    val startAt = if (from > 0 && pla.isNotEmpty() &&
                        kotlin.math.abs(pla[0] - lats.last()) < 1e-7 && kotlin.math.abs(plo[0] - lons.last()) < 1e-7
                    ) 1 else 0
                    for (i in startAt until pla.size) { lats.add(pla[i]); lons.add(plo[i]) }
                }
                val nav = s.optJSONObject("navigationInstruction")
                val maneuver = nav?.optString("maneuver")
                val plain = nav?.optString("instructions", "")?.trim() ?: ""
                val sign = signFromManeuver(maneuver)
                val street = if (sign == 6) plain else streetFromInstruction(plain)
                val exitNum = if (sign == 6) exitNumRe.find(plain)?.groupValues?.get(1)?.toIntOrNull() ?: 0 else 0
                steps.add(
                    Step(
                        sign = sign, text = plain, street = if (sign == 6) "" else street,
                        distance = s.optDouble("distanceMeters", 0.0),
                        from = from, to = maxOf(from, lats.size - 1), exitNumber = exitNum,
                    ),
                )
            }
        }
        // Bước cuối: "đến nơi" — route/v4 (VietMap) luôn có bước này, Routes API thì ngầm hiểu nên
        // thêm vào để phần còn lại của app (báo "đến nơi", icon mũi tên...) hoạt động giống nhau.
        if (lats.isNotEmpty()) {
            steps.add(Step(sign = 4, text = "Đến nơi", street = "", distance = 0.0, from = lats.size - 1, to = lats.size - 1, exitNumber = 0))
        }
        return Route(lats.toDoubleArray(), lons.toDoubleArray(), steps, totalDist, totalMs)
        // congestion: Routes API không trả mức kẹt xe theo từng đoạn ở field mask tối giản này, để rỗng.
    }
}
