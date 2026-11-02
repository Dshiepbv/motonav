package vn.hiep.motonav

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.floor

/** Một biển giới hạn tốc độ thật (OSM hoặc Mapillary). end = biển hết hạn chế. dir < 0 = không rõ hướng. */
class SpeedSign(val id: String, val lat: Double, val lon: Double, val kmh: Int, val end: Boolean, val dir: Double, val src: String)

/**
 * CSDL biển tốc độ từ Mapillary (biển nhận dạng tự động trên ảnh đường phố).
 * Tải theo ô lưới 0,05° (~5,5 km) quanh xe, lưu file 30 ngày, lỗi thì thử lại sau 10 phút.
 * Cần Client Token (MLY|…) của người dùng nhập trong Cài đặt — không có thì không làm gì.
 */
object SpeedSigns {
    private const val CELL = 0.05
    private const val KEEP_MS = 30L * 24 * 3600 * 1000
    private const val RETRY_MS = 10L * 60 * 1000

    @Volatile var token: String = ""
    @Volatile var loadedSigns = 0
    private var dir: File? = null
    private val cells = HashMap<Long, List<SpeedSign>>()
    private val loading = HashSet<Long>()
    private val failAt = HashMap<Long, Long>()
    private val exec = Executors.newSingleThreadExecutor()

    private val valueRe = Regex("""(end-of-)?maximum-speed-limit-(\d{2,3})""")

    private val objectValues: String by lazy {
        val v = ArrayList<String>()
        for (k in listOf(20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120))
            for (g in 1..3) v.add("regulatory--maximum-speed-limit-$k--g$g")
        for (k in listOf(30, 40, 50, 60, 70, 80))
            for (g in 1..2) v.add("regulatory--end-of-maximum-speed-limit-$k--g$g")
        v.joinToString(",")
    }

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "mly").apply { mkdirs() }
    }

    private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

    /** Gọi mỗi lần có vị trí: bảo đảm các ô quanh xe đã tải (chạy nền). */
    fun ensure(lat: Double, lon: Double) {
        val tk = token
        if (tk.isBlank()) return
        val cx0 = floor(lon / CELL).toInt()
        val cy0 = floor(lat / CELL).toInt()
        val now = System.currentTimeMillis()
        for (dx in -1..1) for (dy in -1..1) {
            val cx = cx0 + dx
            val cy = cy0 + dy
            // Ô bên cạnh chỉ tải khi xe cách mép ô < 1,5 km
            if (dx != 0 && Math.abs(lon - (if (dx < 0) cx0 * CELL else (cx0 + 1) * CELL)) > 0.014) continue
            if (dy != 0 && Math.abs(lat - (if (dy < 0) cy0 * CELL else (cy0 + 1) * CELL)) > 0.014) continue
            val k = key(cx, cy)
            synchronized(this) {
                if (cells.containsKey(k) || k in loading || now - (failAt[k] ?: 0L) < RETRY_MS) return@synchronized
                loading.add(k)
                exec.execute { load(cx, cy, k, tk) }
            }
        }
    }

    private fun load(cx: Int, cy: Int, k: Long, tk: String) {
        var list: List<SpeedSign>? = null
        val f = dir?.let { File(it, "${cx}_${cy}.json") }
        try {
            if (f != null && f.exists() && System.currentTimeMillis() - f.lastModified() < KEEP_MS) {
                list = parse(f.readText())
            }
            if (list == null) {
                val body = fetch(cx, cy, tk)
                list = parse(body)
                f?.writeText(body)
                TripLog.ev("mlyCell", "cell" to "${cx}_$cy", "n" to list.size)
            }
        } catch (e: Exception) {
            TripLog.ev("mlyErr", "cell" to "${cx}_$cy", "err" to (e.message ?: e.javaClass.simpleName).take(200))
        }
        synchronized(this) {
            loading.remove(k)
            if (list != null) {
                cells[k] = list
                loadedSigns = cells.values.sumOf { it.size }
            } else failAt[k] = System.currentTimeMillis()
        }
    }

    private fun fetch(cx: Int, cy: Int, tk: String): String {
        val bbox = "%.4f,%.4f,%.4f,%.4f".format(java.util.Locale.US, cx * CELL, cy * CELL, (cx + 1) * CELL, (cy + 1) * CELL)
        val url = "https://graph.mapillary.com/map_features?fields=id,object_value,geometry,aligned_direction" +
            "&limit=2000&bbox=$bbox&object_values=" + URLEncoder.encode(objectValues, "UTF-8")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("Authorization", "OAuth $tk")
        try {
            val code = c.responseCode
            if (code != 200) {
                val err = try { c.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                throw RuntimeException("HTTP $code ${err?.take(150) ?: ""}")
            }
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun parse(body: String): List<SpeedSign> {
        val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<SpeedSign>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val m = valueRe.find(o.optString("object_value")) ?: continue
            val kmh = m.groupValues[2].toIntOrNull() ?: continue
            if (kmh !in 5..130) continue
            val co = o.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val dir = if (o.has("aligned_direction") && !o.isNull("aligned_direction")) o.optDouble("aligned_direction", -1.0) else -1.0
            out.add(SpeedSign("m" + o.optString("id"), co.optDouble(1), co.optDouble(0), kmh, m.groupValues[1].isNotEmpty(), dir, "mly"))
        }
        return out
    }

    /** Biển Mapillary trong bán kính [r] mét. */
    fun near(lat: Double, lon: Double, r: Double): List<SpeedSign> {
        val out = ArrayList<SpeedSign>()
        val cx0 = floor(lon / CELL).toInt()
        val cy0 = floor(lat / CELL).toInt()
        synchronized(this) {
            for (dx in -1..1) for (dy in -1..1) {
                val l = cells[key(cx0 + dx, cy0 + dy)] ?: continue
                for (s in l) if (Math.abs(s.lat - lat) < 0.001 && Math.abs(s.lon - lon) < 0.001 &&
                    Geo.distance(lat, lon, s.lat, s.lon) <= r) out.add(s)
            }
        }
        return out
    }
}
