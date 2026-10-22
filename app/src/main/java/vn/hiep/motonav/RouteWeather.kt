package vn.hiep.motonav

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Một biểu tượng thời tiết đặt trên tuyến. */
class WxMark(
    val lat: Double,
    val lon: Double,
    val cat: Int,          // nhóm thời tiết (WxCat)
    val label: String,     // vd "28°" hoặc "70%"
    val km: Double,        // cách vị trí hiện tại bao xa (km)
    val minutes: Int,      // bao lâu nữa tới (phút)
)

object WxCat {
    const val CLEAR = 0
    const val PARTLY = 1
    const val CLOUDY = 2
    const val CHANCE = 3   // có thể mưa
    const val RAIN = 4
    const val STORM = 5

    fun emoji(cat: Int, night: Boolean): String = when (cat) {
        CLEAR -> if (night) "🌙" else "☀️"
        PARTLY -> if (night) "☁️" else "⛅"
        CLOUDY -> "☁️"
        CHANCE -> "🌦"
        RAIN -> "🌧"
        else -> "⛈"
    }

    fun word(cat: Int): String = when (cat) {
        CLEAR -> "nắng"
        PARTLY -> "ít mây"
        CLOUDY -> "nhiều mây"
        CHANCE -> "có thể mưa"
        RAIN -> "mưa"
        else -> "mưa giông"
    }

    /** Nhóm "ướt" để tóm tắt. */
    fun wet(cat: Int) = cat >= CHANCE
}

/**
 * Thời tiết dọc tuyến (dữ liệu Open-Meteo, CC BY 4.0).
 * Lấy mẫu các điểm trên tuyến, gọi 1 lần cho tất cả, chọn dự báo theo giờ xe tới từng điểm.
 */
object RouteWeather {

    private fun classify(code: Int, precip: Double, prob: Int): Int = when {
        code >= 95 -> WxCat.STORM
        precip >= 2.5 || code == 65 || code == 67 || code == 82 -> WxCat.RAIN
        precip >= 0.3 || code in 51..67 || code in 80..82 || prob >= 60 -> WxCat.RAIN
        prob >= 30 -> WxCat.CHANCE
        code == 3 || code == 45 || code == 48 -> WxCat.CLOUDY
        code == 2 -> WxCat.PARTLY
        else -> WxCat.CLEAR
    }

    /** Tính và trả về các biểu tượng + câu tóm tắt. */
    fun fetch(r: Route, progress: Double): Pair<List<WxMark>, String?> {
        val n = r.lats.size
        val total = r.total
        val remain = total - progress
        if (n < 2 || remain < 800) return Pair(emptyList(), null)

        // Khoảng cách lấy mẫu: ~12 điểm cho cả quãng, tối thiểu 1,5 km, tối đa 8 km
        val step = (remain / 12).coerceIn(1500.0, 8000.0)
        val sLat = ArrayList<Double>()
        val sLon = ArrayList<Double>()
        val sDist = ArrayList<Double>()
        var target = progress
        var i = 0
        while (target <= total + 1) {
            while (i < n - 2 && r.cum[i + 1] < target) i++
            val segLen = r.cum[i + 1] - r.cum[i]
            val t = if (segLen < 0.01) 0.0 else ((target - r.cum[i]) / segLen).coerceIn(0.0, 1.0)
            sLat.add(r.lats[i] + t * (r.lats[i + 1] - r.lats[i]))
            sLon.add(r.lons[i] + t * (r.lons[i + 1] - r.lons[i]))
            sDist.add(target - progress)
            if (target >= total) break
            target = min(total, target + step)
        }
        if (sLat.size > 20) return Pair(emptyList(), null)

        val lats = sLat.joinToString(",") { String.format(Locale.US, "%.4f", it) }
        val lons = sLon.joinToString(",") { String.format(Locale.US, "%.4f", it) }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
            "&hourly=weather_code,precipitation,precipitation_probability,temperature_2m" +
            "&forecast_hours=24&timeformat=unixtime&timezone=GMT"
        val body = get(url)
        val arr: JSONArray = if (body.trim().startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))

        val now = System.currentTimeMillis()
        val speedMs = if (r.timeMs > 0) r.total / (r.timeMs / 1000.0) else 8.0 // m/s
        val cats = IntArray(sLat.size)
        val temps = IntArray(sLat.size)
        val probs = IntArray(sLat.size)
        val etas = IntArray(sLat.size)
        for (k in sLat.indices) {
            val o = arr.optJSONObject(k) ?: arr.optJSONObject(0) ?: continue
            val h = o.optJSONObject("hourly") ?: continue
            val times = h.optJSONArray("time") ?: continue
            val etaSec = now / 1000 + (sDist[k] / max(speedMs, 2.0)).toLong()
            etas[k] = ((etaSec - now / 1000) / 60).toInt()
            // Giờ dự báo gần nhất với lúc tới
            var best = 0
            var bestD = Long.MAX_VALUE
            for (j in 0 until times.length()) {
                val d = abs(times.getLong(j) + 1800 - etaSec)
                if (d < bestD) { bestD = d; best = j }
            }
            val code = h.optJSONArray("weather_code")?.optInt(best, 0) ?: 0
            val pr = h.optJSONArray("precipitation")?.optDouble(best, 0.0) ?: 0.0
            val pp = h.optJSONArray("precipitation_probability")?.optInt(best, 0) ?: 0
            cats[k] = classify(code, if (pr.isNaN()) 0.0 else pr, pp)
            temps[k] = (h.optJSONArray("temperature_2m")?.optDouble(best, 0.0) ?: 0.0).roundToInt()
            probs[k] = pp
        }

        // Đặt biểu tượng: điểm đầu, mỗi khi đổi thời tiết, và cách nhau tối đa ~12 km
        val marks = ArrayList<WxMark>()
        var lastCat = -1
        var lastKm = -100.0
        for (k in sLat.indices) {
            val km = sDist[k] / 1000
            val changed = cats[k] != lastCat
            if (changed || km - lastKm >= 12 || k == sLat.size - 1 && cats[k] != lastCat) {
                val label = if (WxCat.wet(cats[k])) "${probs[k]}%" else "${temps[k]}°"
                marks.add(WxMark(sLat[k], sLon[k], cats[k], label, km, etas[k]))
                lastCat = cats[k]
                lastKm = km
            }
        }

        // Tóm tắt: đoạn mưa đầu tiên phía trước
        var summary: String? = null
        val firstWet = cats.indexOfFirst { WxCat.wet(it) }
        if (firstWet >= 0) {
            var lastWet = firstWet
            while (lastWet + 1 < cats.size && WxCat.wet(cats[lastWet + 1])) lastWet++
            val a = (sDist[firstWet] / 1000).roundToInt()
            val b = (sDist[lastWet] / 1000).roundToInt()
            val word = WxCat.word(cats.slice(firstWet..lastWet).maxOrNull() ?: WxCat.RAIN)
            summary = if (a == 0) "Đang ${word} khu vực này" + (if (b > 0) ", tới km $b" else "")
            else "Phía trước $word từ km $a" + (if (b > a) " đến km $b" else "") + " (khoảng ${etas[firstWet]} phút nữa)"
        } else {
            summary = "Dọc tuyến: không mưa"
        }
        return Pair(marks, summary)
    }

    fun isNightAt(minutesAhead: Int): Boolean {
        val c = Calendar.getInstance()
        c.add(Calendar.MINUTE, minutesAhead)
        val h = c.get(Calendar.HOUR_OF_DAY)
        return h >= 18 || h < 6
    }

    fun toGeoJson(marks: List<WxMark>): String {
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        marks.forEachIndexed { i, m ->
            if (i > 0) sb.append(',')
            val night = isNightAt(m.minutes)
            val id = "wx-${m.cat}-${if (night) 1 else 0}-${m.label}"
            sb.append("""{"type":"Feature","properties":{"img":${JSONObject.quote(id)},"c":${m.cat},"n":${if (night) 1 else 0},"l":${JSONObject.quote(m.label)}},"geometry":{"type":"Point","coordinates":[${m.lon},${m.lat}]}}""")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "MotoNav/1.0 (personal Android app)")
            if (c.responseCode != 200) throw RuntimeException("Thời tiết HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
