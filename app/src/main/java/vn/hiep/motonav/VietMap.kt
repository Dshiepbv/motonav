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

    fun route(key: String, fromLat: Double, fromLon: Double, heading: Double?, toLat: Double, toLon: Double, traffic: Boolean = true): Route =
        routes(key, fromLat, fromLon, heading, toLat, toLon, traffic, false).first()

    /** Tìm đường; alternative = true trả về thêm các tuyến thay thế (tối đa 3). */
    fun routes(
        key: String, fromLat: Double, fromLon: Double, heading: Double?, toLat: Double, toLon: Double,
        traffic: Boolean = true, alternative: Boolean = false,
    ): List<Route> {
        if (traffic) {
            try {
                return routeOnce(key, fromLat, fromLon, heading, toLat, toLon, true, alternative)
            } catch (e: Exception) {
                // Thử lại không kèm dữ liệu kẹt xe
            }
        }
        return routeOnce(key, fromLat, fromLon, heading, toLat, toLon, false, alternative)
    }

    /** Đọc mảng mức kẹt xe, chấp nhận nhiều dạng trả về. */
    private fun parseCongestion(p: JSONObject): List<String> {
        val out = ArrayList<String>()
        fun addArr(a: JSONArray?) {
            if (a == null) return
            for (i in 0 until a.length()) {
                val v = a.opt(i)
                when (v) {
                    is String -> out.add(v.lowercase())
                    is JSONObject -> out.add(v.optString("congestion", v.optString("level", "unknown")).lowercase())
                    else -> out.add("unknown")
                }
            }
        }
        when (val a = p.opt("annotations")) {
            is JSONObject -> addArr(a.optJSONArray("congestion"))
            is JSONArray -> {
                if (a.length() > 0 && a.opt(0) is JSONObject && (a.getJSONObject(0).has("congestion"))) {
                    val first = a.getJSONObject(0).opt("congestion")
                    if (first is JSONArray) {
                        for (i in 0 until a.length()) addArr(a.getJSONObject(i).optJSONArray("congestion"))
                    } else {
                        addArr(a)
                    }
                } else {
                    addArr(a)
                }
            }
        }
        if (out.isEmpty()) addArr(p.optJSONArray("congestion"))
        return out
    }

    private fun routeOnce(
        key: String, fromLat: Double, fromLon: Double, heading: Double?, toLat: Double, toLon: Double,
        traffic: Boolean, alternative: Boolean,
    ): List<Route> {
        val sb = StringBuilder("$BASE/route/v4?apikey=").append(enc(key))
            .append("&point=").append(ll(fromLat, fromLon))
            .append("&point=").append(ll(toLat, toLon))
            .append("&vehicle=motorcycle&points_encoded=true&avoid=ferry")
        if (traffic) sb.append("&annotations=congestion")
        if (alternative) sb.append("&alternative=true")
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
        val out = ArrayList<Route>()
        for (pi in 0 until minOf(paths.length(), 3)) {
            try {
                out.add(parsePath(paths.getJSONObject(pi)))
            } catch (_: Exception) {
            }
        }
        if (out.isEmpty()) throw RuntimeException("Tuyến đường rỗng")
        return out
    }

    private fun parsePath(p: JSONObject): Route {
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
        val cong = try { parseCongestion(p) } catch (_: Exception) { emptyList() }
        return Route(la, lo, steps, p.optDouble("distance", 0.0), p.optLong("time", 0L), cong)
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

    /** Các link kiểu bản đồ VietMap đã biết (mỗi loại tài khoản dùng một link khác nhau). */
    fun styleCandidates(key: String, night: Boolean, custom: String): List<String> {
        val k = enc(key)
        val list = ArrayList<String>()
        if (custom.isNotBlank()) {
            list.add(if (custom.contains("apikey=")) custom else custom + (if (custom.contains('?')) "&" else "?") + "apikey=" + k)
        }
        if (night) {
            list.add("https://maps.vietmap.vn/mt/dm/style.json?apikey=$k")
            list.add("https://maps.vietmap.vn/api/maps/dark/styles.json?apikey=$k")
        }
        list.add("https://maps.vietmap.vn/mt/tm/style.json?apikey=$k")
        list.add("https://maps.vietmap.vn/api/maps/light/styles.json?apikey=$k")
        list.add("https://maps.vietmap.vn/maps/styles/${if (night) "dm" else "tm"}/style.json?apikey=$k")
        return list.distinct()
    }

    private fun download(url: String): Pair<Int, String?> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12000
            c.readTimeout = 20000
            val code = c.responseCode
            if (code != 200) return Pair(code, null)
            return Pair(code, c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    /** Tải file kiểu bản đồ và điền mã vào mọi link còn trống "apikey=". */
    fun patchStyle(body: String, key: String): String {
        val k = enc(key)
        val root = JSONObject(body)
        fun withKey(u: String): String {
            if (!u.contains("vietmap.vn")) return u
            if (Regex("""apikey=[^&]+""").containsMatchIn(u)) return u // đã có mã → giữ nguyên
            val base = u.replace(Regex("""([?&])apikey=&?"""), "$1").trimEnd('?', '&')
            return base + (if (base.contains('?')) "&" else "?") + "apikey=" + k
        }
        val sources = root.optJSONObject("sources")
        if (sources != null) {
            val it = sources.keys()
            while (it.hasNext()) {
                val src = sources.getJSONObject(it.next())
                src.optJSONArray("tiles")?.let { arr ->
                    for (i in 0 until arr.length()) arr.put(i, withKey(arr.getString(i)))
                }
                if (src.has("url")) src.put("url", withKey(src.getString("url")))
            }
        }
        if (root.has("sprite") && root.get("sprite") is String) root.put("sprite", withKey(root.getString("sprite")))
        if (root.has("glyphs")) root.put("glyphs", withKey(root.getString("glyphs")))
        return root.toString()
    }

    class StyleResult(val json: String, val url: String, val log: String)

    /** Thử lần lượt các link, chọn link nào tải được mảnh bản đồ thật. */
    fun findWorkingStyle(key: String, night: Boolean, custom: String, lat: Double, lon: Double): StyleResult {
        val log = StringBuilder()
        var fallback: StyleResult? = null
        for (u in styleCandidates(key, night, custom)) {
            val shortName = u.substringAfter("vietmap.vn").substringBefore("?")
            try {
                val (code, body) = download(u)
                if (body == null) {
                    log.append("$shortName: HTTP $code; ")
                    continue
                }
                val json = patchStyle(body, key)
                val (tcode, _) = testTile(json, lat, lon)
                log.append("$shortName: mảnh $tcode; ")
                if (tcode in 200..299) return StyleResult(json, u, log.toString())
                if (fallback == null) fallback = StyleResult(json, u, "")
            } catch (e: Exception) {
                log.append("$shortName: ${e.javaClass.simpleName}; ")
            }
        }
        fallback?.let { return StyleResult(it.json, it.url, log.toString()) }
        throw RuntimeException("Không link kiểu bản đồ VietMap nào dùng được: $log")
    }

    /** Thử tải 1 mảnh bản đồ tại vị trí; trả về mã HTTP và link đã dùng. */
    fun testTile(styleJson: String, lat: Double, lon: Double): Pair<Int, String> {
        val root = JSONObject(styleJson)
        val sources = root.optJSONObject("sources") ?: return Pair(-1, "không có nguồn")
        var tpl: String? = null
        val it = sources.keys()
        while (it.hasNext() && tpl == null) {
            tpl = sources.getJSONObject(it.next()).optJSONArray("tiles")?.optString(0)
        }
        if (tpl == null) return Pair(-1, "không có link mảnh bản đồ")
        val z = 14
        val n = 1 shl z
        val x = ((lon + 180) / 360 * n).toInt()
        val latR = Math.toRadians(lat)
        val y = ((1 - Math.log(Math.tan(latR) + 1 / Math.cos(latR)) / Math.PI) / 2 * n).toInt()
        val u = tpl.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
        val c = URL(u).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 10000
            c.readTimeout = 15000
            Pair(c.responseCode, u.substringBefore("apikey="))
        } finally {
            c.disconnect()
        }
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
