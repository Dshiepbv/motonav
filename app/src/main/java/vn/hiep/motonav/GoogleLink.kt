package vn.hiep.motonav

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Lấy toạ độ điểm đến từ nội dung chia sẻ của Google Maps
 * (link maps.app.goo.gl, link google.com/maps, hoặc geo:lat,lng).
 */
object GoogleLink {
    class Result(val lat: Double, val lon: Double, val name: String?)

    private val urlRe = Regex("""https?://\S+""")
    private val placeRe = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")
    private val atRe = Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""")
    private val qRe = Regex("""[?&](?:q|ll|daddr|destination|query|center)=(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)""")
    private val geoRe = Regex("""geo:(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")
    private val searchRe = Regex("""/maps/(?:place|search)/([^/@?]+)""")
    private val qTextRe = Regex("""[?&]q=([^&]+)""")

    /** Tên địa điểm (dòng đầu của nội dung chia sẻ, nếu không phải link). */
    fun nameFromText(text: String): String? =
        text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("http") }

    /** Phân tích toạ độ trực tiếp (không cần mạng). */
    fun parseCoords(s: String): Pair<Double, Double>? {
        val d = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
        // Link tìm kiếm / chỉ đường: "@lat,lng" chỉ là tâm bản đồ, không phải điểm đến
        val atIsCenter = d.contains("/maps/search/") || d.contains("/maps/dir/")
        val res = if (atIsCenter) listOf(placeRe, qRe, geoRe) else listOf(placeRe, qRe, geoRe, atRe)
        for (re in res) {
            val m = re.find(d) ?: continue
            val a = m.groupValues[1].toDoubleOrNull() ?: continue
            val b = m.groupValues[2].toDoubleOrNull() ?: continue
            if (a == 0.0 && b == 0.0) continue
            return Pair(a, b)
        }
        return null
    }

    /** Tên địa điểm nằm trong link (để tìm bằng VietMap khi link không có toạ độ). */
    fun queryFromUrl(url: String): String? {
        val d = try { URLDecoder.decode(url, "UTF-8") } catch (_: Exception) { url }
        searchRe.find(d)?.let { return it.groupValues[1].replace('+', ' ') }
        qTextRe.find(d)?.let { return it.groupValues[1].replace('+', ' ') }
        return null
    }

    /** Mở link rút gọn để lấy link đầy đủ (chạy nền, cần mạng). */
    fun expand(url: String): String {
        var cur = url
        repeat(6) {
            val c = URL(cur).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                val code = c.responseCode
                val loc = c.getHeaderField("Location")
                if (code in 300..399 && loc != null) {
                    cur = URL(URL(cur), loc).toString()
                } else {
                    return cur
                }
            } finally {
                c.disconnect()
            }
            if (parseCoords(cur) != null) return cur
        }
        return cur
    }

    /** Toàn bộ quy trình. Trả về toạ độ hoặc null + tên để tìm tiếp. */
    fun resolve(shared: String): Pair<Result?, String?> {
        val name = nameFromText(shared)
        parseCoords(shared)?.let { return Pair(Result(it.first, it.second, name), null) }
        val url = urlRe.find(shared)?.value ?: return Pair(null, name)
        val full = try { expand(url) } catch (_: Exception) { url }
        parseCoords(full)?.let { return Pair(Result(it.first, it.second, name ?: queryFromUrl(full)), null) }
        return Pair(null, queryFromUrl(full) ?: name)
    }
}
