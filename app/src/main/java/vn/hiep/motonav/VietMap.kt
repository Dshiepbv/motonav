package vn.hiep.motonav

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class Suggestion(val refId: String, val name: String, val address: String)

class Place(val lat: Double, val lon: Double, val name: String)

/** Gọi các API của VietMap: tìm đường xe máy, gợi ý địa điểm, chi tiết địa điểm. */
object VietMap {
    private const val BASE = "https://maps.vietmap.vn/api"

    fun styleUrl(key: String, night: Boolean): String {
        val s = if (night) "dm" else "tm"
        return "https://maps.vietmap.vn/maps/styles/$s/style.json?apikey=" + enc(key)
    }

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
            if (code == 401 || code == 403) throw RuntimeException("Mã API VietMap không hợp lệ hoặc chưa bật dịch vụ (HTTP $code)")
            if (code !in 200..299) throw RuntimeException("VietMap lỗi HTTP $code")
            return body
        } finally {
            c.disconnect()
        }
    }

    fun route(key: String, fromLat: Double, fromLon: Double, heading: Double?, toLat: Double, toLon: Double): Route {
        val sb = StringBuilder("$BASE/route/v4?apikey=").append(enc(key))
            .append("&point=").append(ll(fromLat, fromLon))
            .append("&point=").append(ll(toLat, toLon))
            .append("&vehicle=motorcycle&points_encoded=true&avoid=ferry")
        if (heading != null && heading >= 0) sb.append("&heading=").append(heading.toInt())
        val root = JSONObject(get(sb.toString()))
        val code = root.optString("code", "OK")
        if (code != "OK") {
            val msg = when (code) {
                "ZERO_RESULTS" -> "Không tìm được đường đi tới điểm này"
                "OVER_DAILY_LIMIT" -> "Đã hết lượt tìm đường VietMap trong ngày"
                else -> "VietMap: $code ${root.optString("messages")}"
            }
            throw RuntimeException(msg)
        }
        val paths = root.optJSONArray("paths") ?: throw RuntimeException("VietMap không trả về tuyến")
        val p = paths.getJSONObject(0)
        val (la, lo) = if (p.optBoolean("points_encoded", true)) {
            Route.decodePolyline(p.getString("points"))
        } else {
            parseCoords(p.get("points"))
        }
        val steps = ArrayList<Step>()
        val ins = p.optJSONArray("instructions") ?: JSONArray()
        for (i in 0 until ins.length()) {
            val o = ins.getJSONObject(i)
            val iv = o.optJSONArray("interval")
            steps.add(
                Step(
                    sign = o.optInt("sign", 0),
                    text = o.optString("text", ""),
                    street = o.optString("street_name", "").let { if (it == "null") "" else it },
                    distance = o.optDouble("distance", 0.0),
                    from = iv?.optInt(0, 0) ?: 0,
                    to = iv?.optInt(1, 0) ?: 0,
                    exitNumber = o.optInt("exit_number", 0),
                ),
            )
        }
        if (la.size < 2) throw RuntimeException("Tuyến đường rỗng")
        return Route(la, lo, steps, p.optDouble("distance", 0.0), p.optLong("time", 0L))
    }

    /** Dự phòng khi points không mã hoá: [[lon,lat],...] hoặc {"coordinates":[...]} */
    private fun parseCoords(o: Any): Pair<DoubleArray, DoubleArray> {
        val arr = when (o) {
            is JSONArray -> o
            is JSONObject -> o.optJSONArray("coordinates") ?: JSONArray()
            else -> JSONArray()
        }
        val la = DoubleArray(arr.length())
        val lo = DoubleArray(arr.length())
        for (i in 0 until arr.length()) {
            val pt = arr.getJSONArray(i)
            val a = pt.getDouble(0)
            val b = pt.getDouble(1)
            // Việt Nam: vĩ độ 7–24, kinh độ 101–118 → tự nhận thứ tự
            if (a > 90) { lo[i] = a; la[i] = b } else { la[i] = a; lo[i] = b }
        }
        return Pair(la, lo)
    }

    fun autocomplete(key: String, text: String, focusLat: Double?, focusLon: Double?): List<Suggestion> {
        var url = "$BASE/autocomplete/v4?apikey=${enc(key)}&text=${enc(text)}&display_type=1"
        if (focusLat != null && focusLon != null) url += "&focus=" + ll(focusLat, focusLon)
        val body = get(url).trim()
        val arr = if (body.startsWith("[")) JSONArray(body) else JSONObject(body).optJSONArray("data") ?: JSONArray()
        val out = ArrayList<Suggestion>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ref = o.optString("ref_id")
            if (ref.isBlank()) continue
            val name = o.optString("name").ifBlank { o.optString("display") }
            out.add(Suggestion(ref, name, o.optString("address").ifBlank { o.optString("display") }))
        }
        return out
    }

    fun place(key: String, refId: String): Place {
        val o = JSONObject(get("$BASE/place/v4?apikey=${enc(key)}&refid=${enc(refId)}"))
        val lat = o.optDouble("lat", Double.NaN)
        val lon = o.optDouble("lng", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) throw RuntimeException("Không lấy được toạ độ địa điểm")
        return Place(lat, lon, o.optString("name").ifBlank { o.optString("display") })
    }
}
