package vn.hiep.motonav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * build-473 (Sếp 16/11, ý 3 + 4): nhật ký chuyến và "đường quen thuộc".
 * - Mỗi chuyến (tới điểm đến) ghi: giờ đi, thời gian thật, quãng đường, tốc độ TB, thời gian đứng. Giữ 300 chuyến gần nhất.
 * - Ô lưới ~55 m đã đi qua (≥5 km/h), đếm mỗi chuyến 1 lần. Tuyến nào đi qua nhiều ô quen (đếm ≥2) thì được ưu tiên khi xếp hạng.
 */
object TripStats {
    private const val CELL = 0.0005 // ≈ 55 m
    private var dir: File? = null
    private val cells = HashMap<Long, Int>()
    private val tripCells = HashSet<Long>()
    private var loaded = false
    private var t0 = 0L
    private var destKey = ""
    private var destName: String? = null
    private var distM = 0.0
    private var stoppedMs = 0L
    private var lastLat = 0.0; private var lastLon = 0.0; private var lastAt = 0L
    private val trips = ArrayList<JSONObject>()

    private fun key(lat: Double, lon: Double): Long = (Math.floor(lat / CELL).toLong() shl 32) xor (Math.floor(lon / CELL).toLong() and 0xFFFFFFFFL)
    private fun dKey(lat: Double, lon: Double) = "%.3f,%.3f".format(java.util.Locale.US, lat, lon)

    @Synchronized private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        dir = ctx.applicationContext.filesDir
        try {
            val f = File(dir, "trip_stats.json")
            if (f.exists()) {
                val o = JSONObject(f.readText())
                o.optJSONArray("trips")?.let { a -> for (i in 0 until a.length()) trips.add(a.getJSONObject(i)) }
                o.optJSONObject("cells")?.let { c -> c.keys().forEach { k -> cells[k.toLong()] = c.optInt(k) } }
            }
        } catch (_: Exception) {}
    }

    @Synchronized private fun save() {
        try {
            val o = JSONObject()
            o.put("trips", JSONArray(trips.takeLast(300)))
            val c = JSONObject()
            // giữ tối đa 60 000 ô
            cells.entries.sortedByDescending { it.value }.take(60_000).forEach { c.put(it.key.toString(), it.value) }
            o.put("cells", c)
            File(dir, "trip_stats.json").writeText(o.toString())
        } catch (_: Exception) {}
    }

    /** Bắt đầu chuyến mới (người dùng chọn điểm đến), KHÔNG gọi khi app tự làm mới chuyến. */
    @Synchronized fun begin(ctx: Context, lat: Double, lon: Double, name: String?) {
        load(ctx)
        // chuyến cũ chưa kết thúc (dừng giữa đường) → bỏ, không ghi
        t0 = System.currentTimeMillis(); destKey = dKey(lat, lon); destName = name
        distM = 0.0; stoppedMs = 0L; lastAt = 0L; tripCells.clear()
    }

    @Synchronized fun onLocation(ctx: Context, lat: Double, lon: Double, kmh: Double) {
        load(ctx)
        if (t0 == 0L) return
        val now = System.currentTimeMillis()
        if (lastAt != 0L) {
            val dt = now - lastAt
            if (dt in 1..5000) {
                if (kmh < 2.0) stoppedMs += dt
                distM += Geo.distance(lastLat, lastLon, lat, lon).coerceAtMost(200.0)
            }
        }
        lastLat = lat; lastLon = lon; lastAt = now
        if (kmh >= 5.0) tripCells.add(key(lat, lon))
    }

    /** Tới nơi: ghi chuyến, cộng ô quen. */
    @Synchronized fun finish(ctx: Context) {
        load(ctx)
        if (t0 == 0L) return
        val dur = (System.currentTimeMillis() - t0) / 1000
        if (dur >= 120 && distM >= 500) {
            val o = JSONObject()
            o.put("t0", t0); o.put("dur", dur); o.put("dist", distM.toInt()); o.put("stop", stoppedMs / 1000)
            o.put("dest", destKey); o.put("name", destName ?: "")
            o.put("avg", Math.round(distM / dur * 3.6 * 10) / 10.0)
            trips.add(o)
            for (k in tripCells) cells[k] = (cells[k] ?: 0) + 1
            try { TripLog.ev("tripStat", "dur" to dur, "dist" to distM.toInt(), "stop" to stoppedMs / 1000, "cells" to tripCells.size) } catch (_: Exception) {}
            save()
        }
        t0 = 0L
    }

    /** Tỉ lệ (0..1) điểm của tuyến nằm trong ô đã đi ≥2 chuyến — đo "đường quen" của người dùng. */
    fun familiarity(ctx: Context, r: Route): Double {
        load(ctx)
        if (cells.isEmpty() || r.lats.size < 2) return 0.0
        var n = 0; var hit = 0
        var next = 0.0
        for (i in r.lats.indices) {
            if (r.cum[i] < next) continue
            next = r.cum[i] + 60.0
            n++
            val k = key(r.lats[i], r.lons[i])
            if ((cells[k] ?: 0) >= 2) hit++
        }
        return if (n == 0) 0.0 else hit.toDouble() / n
    }

    /** Dòng gợi ý cho hub: các chuyến trước tới cùng điểm đến. */
    fun hint(ctx: Context, lat: Double, lon: Double): String? {
        load(ctx)
        val k = dKey(lat, lon)
        val same = trips.filter { it.optString("dest") == k }
        if (same.isEmpty()) return null
        val avgMin = same.map { it.optLong("dur") }.average() / 60.0
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val near = same.filter { Math.abs(Calendar.getInstance().apply { timeInMillis = it.optLong("t0") }.get(Calendar.HOUR_OF_DAY) - h) <= 1 }
        val s = "Đã đi ${same.size} lần, TB ${Math.round(avgMin)} phút"
        return if (near.size >= 2) s + " · giờ này TB ${Math.round(near.map { it.optLong("dur") }.average() / 60.0)} phút (${near.size} lần)" else s
    }
}
