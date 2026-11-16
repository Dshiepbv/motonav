package vn.hiep.motonav

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * build-456 (Sếp 15/11): cảnh báo mưa thông minh — báo TRƯỚC khi chạy vào vùng mưa.
 *
 * Nguồn: Open-Meteo (miễn phí, CC BY 4.0), dự báo lượng mưa theo từng 15 phút.
 * Cách làm:
 *  - Lấy các điểm phía trước: đang dẫn đường → dọc tuyến còn lại mỗi ~1,5 km tới 25 km;
 *    đi tự do → vị trí hiện tại + 2, 5, 9 km theo hướng đang chạy.
 *  - Mỗi điểm tính GIỜ XE TỚI (quãng đường / tốc độ) rồi lấy lượng mưa của ô 15 phút đúng lúc đó
 *    → biết được mưa sẽ gặp trên đường chứ không chỉ "có mưa ở đâu đó".
 *  - Tìm vùng mưa đầu tiên phía trước: bắt đầu ở km nào, sau bao nhiêu phút, mạnh cỡ nào, dài bao nhiêu km.
 * Báo:
 *  - Sớm (≤ 15 phút nữa tới): đọc 1 lần "Khoảng X phút nữa có mưa vừa, sau Y ki lô mét, dài khoảng Z ki lô mét".
 *  - Sát (≤ 3 phút / ≤ 1,5 km): "Sắp vào vùng mưa".
 *  - Đang mưa mà phía trước khô: "Hết mưa sau khoảng Y ki lô mét".
 *  - Màn xe: ô xanh dương "Mưa vừa · 6 km · 12 ph" suốt lúc vùng mưa còn ở phía trước (≤ 25 phút).
 * Gọi mạng mỗi 3 phút khi đang chạy (Open-Meteo cho 10.000 lượt/ngày).
 */
object RainAlert {
    // Trạng thái cho màn xe
    @Volatile var boxText: String? = null
    @Volatile var boxLevel = 0 // 1 nhỏ, 2 vừa, 3 to
    /** build-457: biểu tượng thời tiết dưới biển tốc độ (WxCat) + điểm bắt đầu vùng mưa phía trước để đếm ngược km. */
    @Volatile var iconCat: Int? = null
    @Volatile private var rainStartAbs = -1.0          // dẫn đường: vị trí trên tuyến (m)
    @Volatile private var rainLat = 0.0; @Volatile private var rainLon = 0.0 // đi tự do
    @Volatile private var rainNav = false

    fun distToRainM(): Int? {
        if (rainStartAbs < 0) return null
        val d = if (rainNav) rainStartAbs - Live.routeProgress else Geo.distance(Live.lat, Live.lon, rainLat, rainLon)
        return d.toInt().takeIf { it > 0 }
    }

    private fun codeCat(code: Int): Int = when {
        code >= 95 -> WxCat.STORM
        code in 51..67 || code in 80..82 -> WxCat.RAIN
        code == 3 || code == 45 || code == 48 -> WxCat.CLOUDY
        code == 1 || code == 2 -> WxCat.PARTLY
        else -> WxCat.CLEAR
    }

    private var lastFetch = 0L
    private var busy = false
    private var zoneKey: String? = null   // vùng mưa đã báo sớm
    private var nearSaid = false
    private var exitSaid = false
    private var lastRouteVer = -1

    private class Sample(val lat: Double, val lon: Double, val distM: Double)

    private fun level(mm15: Double): Int = when {
        mm15 >= 2.0 -> 3
        mm15 >= 0.5 -> 2
        mm15 >= 0.1 -> 1
        else -> 0
    }

    private fun word(l: Int) = when (l) { 3 -> "mưa to"; 2 -> "mưa vừa"; else -> "mưa nhỏ" }
    private fun kmTxt(m: Double) = if (m >= 1000) String.format(Locale.US, "%.0f km", m / 1000) else "${(m / 100).roundToInt() * 100} m"
    private fun kmSpeech(m: Double) = if (m >= 1000) "${(m / 1000).roundToInt()} ki lô mét" else "${(m / 100).roundToInt() * 100} mét"

    /** Gọi mỗi lần có vị trí mới. [say] = hiện + đọc cảnh báo. */
    fun tick(enabled: Boolean, speedKmh: Int, say: (String, String) -> Unit) {
        if (!enabled) { boxText = null; iconCat = null; rainStartAbs = -1.0; return }
        if (busy || !Live.gpsOk) return
        val now = SystemClock.elapsedRealtime()
        val routeChanged = Live.ownNav && Live.routeVersion != lastRouteVer
        val period = if (speedKmh >= 5 || Live.ownNav) 3 * 60_000L else 10 * 60_000L
        if (!routeChanged && now - lastFetch < period) return
        lastFetch = now
        lastRouteVer = Live.routeVersion
        val samples = samples(speedKmh) ?: return
        busy = true
        Thread {
            try { evaluate(samples, speedKmh, say) }
            catch (e: Exception) {
                lastFetch = SystemClock.elapsedRealtime() - period + 60_000L // lỗi mạng: thử lại sau 1 phút
                try { TripLog.ev("rainErr", "msg" to (e.message ?: e.javaClass.simpleName)) } catch (_: Exception) {}
            } finally { busy = false }
        }.start()
    }

    private fun samples(speedKmh: Int): List<Sample>? {
        val out = ArrayList<Sample>()
        val r = Live.route
        if (Live.ownNav && r != null && r.lats.size >= 2) {
            val progress = Live.routeProgress
            val end = minOf(r.total, progress + 25_000.0)
            var target = progress
            var i = 0
            val n = r.lats.size
            while (target <= end + 1 && out.size < 18) {
                while (i < n - 2 && r.cum[i + 1] < target) i++
                val seg = r.cum[i + 1] - r.cum[i]
                val t = if (seg < 0.01) 0.0 else ((target - r.cum[i]) / seg).coerceIn(0.0, 1.0)
                out.add(Sample(r.lats[i] + t * (r.lats[i + 1] - r.lats[i]), r.lons[i] + t * (r.lons[i + 1] - r.lons[i]), target - progress))
                if (target >= end) break
                target = minOf(end, target + 1500.0)
            }
        } else {
            out.add(Sample(Live.lat, Live.lon, 0.0))
            val hdg = Live.heading
            if (hdg >= 0 && speedKmh >= 10) for (d in listOf(2000.0, 5000.0, 9000.0)) {
                val rad = Math.toRadians(hdg)
                val dLat = d * cos(rad) / 111_320.0
                val dLon = d * sin(rad) / (111_320.0 * cos(Math.toRadians(Live.lat)))
                out.add(Sample(Live.lat + dLat, Live.lon + dLon, d))
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun evaluate(s: List<Sample>, speedKmh: Int, say: (String, String) -> Unit) {
        val lats = s.joinToString(",") { String.format(Locale.US, "%.4f", it.lat) }
        val lons = s.joinToString(",") { String.format(Locale.US, "%.4f", it.lon) }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
            "&current=weather_code&minutely_15=precipitation&forecast_minutely_15=10&past_minutely_15=1&timeformat=unixtime&timezone=GMT"
        val body = get(url)
        val arr = if (body.trim().startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))
        // tốc độ dùng để tính giờ tới: tốc độ tuyến (nếu có) hoặc tốc độ hiện tại, tối thiểu 20 km/h
        val r = Live.route
        val routeMs = if (Live.ownNav && r != null && r.timeMs > 0) r.total / (r.timeMs / 1000.0) else 0.0
        val ms = max(max(routeMs, speedKmh / 3.6), 20 / 3.6)
        val nowS = System.currentTimeMillis() / 1000
        val lv = IntArray(s.size)
        val mm = DoubleArray(s.size)
        val codes = IntArray(s.size)
        for (k in s.indices) {
            val o = arr.optJSONObject(k) ?: continue
            codes[k] = o.optJSONObject("current")?.optInt("weather_code", 0) ?: 0
            val m = o.optJSONObject("minutely_15") ?: continue
            val times = m.optJSONArray("time") ?: continue
            val pr = m.optJSONArray("precipitation") ?: continue
            val eta = nowS + (s[k].distM / ms).toLong()
            var best = -1
            for (j in 0 until times.length()) {
                val t0 = times.getLong(j) // ô 15 phút kết thúc lúc t0 (lượng mưa của 15 phút trước đó)
                if (eta <= t0 && eta > t0 - 900) { best = j; break }
            }
            if (best < 0) continue
            val v = pr.optDouble(best, 0.0).let { if (it.isNaN()) 0.0 else it }
            mm[k] = v; lv[k] = level(v)
        }
        val minutes = { k: Int -> ((s[k].distM / ms) / 60).roundToInt() }
        try { TripLog.ev("rain", "n" to s.size, "lv" to lv.joinToString(""), "nav" to Live.ownNav) } catch (_: Exception) {}

        val hereWet = lv[0] > 0
        val first = lv.indexOfFirst { it > 0 }
        rainStartAbs = -1.0
        if (first < 0) {
            iconCat = codeCat(codes[0]).let { if (WxCat.wet(it)) WxCat.CLOUDY else it } // dự báo 15 ph không mưa → không vẽ mưa
            boxText = null; boxLevel = 0; zoneKey = null; nearSaid = false; exitSaid = false; return
        }
        var last = first
        while (last + 1 < lv.size && lv[last + 1] > 0) last++
        iconCat = if ((first..last).any { codes[it] >= 95 }) WxCat.STORM else WxCat.RAIN
        if (!hereWet && minutes(first) <= 25) {
            rainNav = Live.ownNav
            rainLat = s[first].lat; rainLon = s[first].lon
            rainStartAbs = if (rainNav) Live.routeProgress + s[first].distM else 0.0
        } else if (!hereWet) iconCat = codeCat(codes[0]).let { if (WxCat.wet(it)) WxCat.CLOUDY else it }
        val maxL = (first..last).maxOf { lv[it] }
        val startM = s[first].distM
        val lenM = s[last].distM - startM + 1500.0
        val inMin = minutes(first)

        if (hereWet) {
            // đang ở trong mưa: báo khi phía trước hết mưa
            val dryAt = (last + 1).takeIf { it < lv.size }
            boxLevel = maxL
            boxText = if (dryAt != null) "${word(maxL).replaceFirstChar { it.uppercase() }} · tạnh sau ${kmTxt(s[dryAt].distM)}" else "${word(maxL).replaceFirstChar { it.uppercase() }} dọc đường"
            if (dryAt != null && !exitSaid && s[dryAt].distM <= 10_000) {
                exitSaid = true
                say("☔ Hết mưa sau khoảng ${kmTxt(s[dryAt].distM)}", "Hết mưa sau khoảng ${kmSpeech(s[dryAt].distM)}")
            }
            zoneKey = "here"; nearSaid = true
            return
        }
        exitSaid = false
        if (inMin > 25) { boxText = null; boxLevel = 0; return }
        boxLevel = maxL
        boxText = "${word(maxL).replaceFirstChar { it.uppercase() }} · ${kmTxt(startM)} · $inMin ph"
        // mỗi vùng mưa chỉ báo sớm 1 lần (vùng coi là cùng nếu điểm bắt đầu dời < 3 km)
        val key = if (Live.ownNav) "r" + ((startM + Live.routeProgress) / 3000).toInt()
            else "f" + (s[first].lat / 0.03).roundToInt() + "_" + (s[first].lon / 0.03).roundToInt()
        if (inMin <= 15 && zoneKey != key) {
            zoneKey = key; nearSaid = false
            val len = if (lenM >= 2000) ", dài khoảng ${kmSpeech(lenM)}" else ""
            say("☔ ${word(maxL)} sau ${kmTxt(startM)} (~$inMin phút)",
                "Khoảng $inMin phút nữa có ${word(maxL)}, sau ${kmSpeech(startM)}$len. Chuẩn bị áo mưa.")
        } else if (!nearSaid && (inMin <= 3 || startM <= 1500)) {
            nearSaid = true
            say("☔ Sắp vào vùng mưa", "Sắp vào vùng ${word(maxL)}")
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000; c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "MotoNav/1.0 (personal Android app)")
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }
}
