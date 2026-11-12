package vn.hiep.motonav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.cos
import kotlin.math.floor

/**
 * Gói dữ liệu OSM ngoại tuyến (đường, tốc độ, camera, biển báo) cho TP.HCM mở rộng.
 * Tạo bởi GitHub Actions hằng tuần (tools/osmpack.py), tải về bộ nhớ riêng của app.
 * Có gói → không cần Overpass (nhanh hơn, mất mạng vẫn chạy).
 */
object OfflinePack {
    private var zip: ZipFile? = null
    private var zipFile: File? = null
    private var tile = 0.02
    private var bbox = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    @Volatile var date: String? = null
        private set
    private val areaCache = HashMap<Long, Pair<Boolean?, String?>>()

    fun file(ctx: Context) = File(File(ctx.filesDir, "osm").apply { mkdirs() }, "pack.zip")

    @Synchronized
    fun open(ctx: Context): Boolean {
        val f = file(ctx)
        if (!f.exists()) { close(); return false }
        if (zip != null && zipFile == f && f.lastModified() == lastMod) return true
        close()
        return try {
            val z = ZipFile(f)
            val meta = JSONObject(z.getInputStream(z.getEntry("meta.json")).bufferedReader().readText())
            tile = meta.optDouble("tile", 0.02)
            val b = meta.getJSONArray("bbox")
            bbox = doubleArrayOf(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
            date = meta.optString("date")
            zip = z
            zipFile = f
            lastMod = f.lastModified()
            true
        } catch (_: Exception) {
            close()
            false
        }
    }

    private var lastMod = 0L

    @Synchronized
    fun close() {
        try { zip?.close() } catch (_: Exception) {}
        zip = null
        date = null
    }

    fun covers(lat: Double, lon: Double) = zip != null && lat in bbox[0]..bbox[2] && lon in bbox[1]..bbox[3]

    @Synchronized
    private fun read(name: String): JSONArray? {
        val z = zip ?: return null
        val e = z.getEntry(name) ?: return null
        return JSONObject(z.getInputStream(e).bufferedReader(Charsets.UTF_8).readText()).optJSONArray("elements")
    }

    private fun tiles(prefix: String, lat: Double, lon: Double, radius: Double): List<JSONArray> {
        val dLat = radius / 110540.0
        val dLon = radius / (111320.0 * cos(Math.toRadians(lat)))
        val out = ArrayList<JSONArray>()
        val x0 = floor((lat - dLat) / tile).toInt(); val x1 = floor((lat + dLat) / tile).toInt()
        val y0 = floor((lon - dLon) / tile).toInt(); val y1 = floor((lon + dLon) / tile).toInt()
        for (x in x0..x1) for (y in y0..y1) read("$prefix/${x}_$y.json")?.let { out.add(it) }
        return out
    }

    /** Mảng màu nền TFT (nước/xanh/sông) từ gói; null nếu gói cũ chưa có thư mục "a/". */
    fun areas(lat: Double, lon: Double, radius: Double): List<Area>? {
        val z = zip ?: return null
        val dLat = radius / 110540.0
        val dLon = radius / (111320.0 * cos(Math.toRadians(lat)))
        val x0 = floor((lat - dLat) / tile).toInt(); val x1 = floor((lat + dLat) / tile).toInt()
        val y0 = floor((lon - dLon) / tile).toInt(); val y1 = floor((lon + dLon) / tile).toInt()
        var any = false
        val seen = HashSet<Long>()
        val out = ArrayList<Area>()
        for (x in x0..x1) for (y in y0..y1) {
            val els = read("a/${x}_$y.json") ?: continue
            any = true
            for (i in 0 until els.length()) {
                val e = els.getJSONObject(i)
                if (!seen.add(e.optLong("i"))) continue
                val g = e.optJSONArray("g") ?: continue
                val n = g.length() / 2
                if (n < 2) continue
                val kind = e.optInt("k")
                out.add(Area(kind, DoubleArray(n) { g.optDouble(it * 2) }, DoubleArray(n) { g.optDouble(it * 2 + 1) }, kind == Areas.RIVER, e.optDouble("w", 0.0)))
            }
        }
        if (!any && z.getEntry("a/") == null && z.stream().noneMatch { it.name.startsWith("a/") }) return null
        return out
    }

    /** Dựng snapshot giống Overpass từ gói. Phường/xã hỏi Overpass riêng (truy vấn rất nhẹ, có bộ nhớ đệm). */
    private val fuelCache = HashMap<Long, List<DoubleArray>>()

    fun snapshot(lat: Double, lon: Double, wayRadius: Int, camRadius: Int): OsmSnapshot {
        val arrays = tiles("w", lat, lon, wayRadius.toDouble()) + tiles("n", lat, lon, camRadius.toDouble())
        val base = Overpass.parseElements(arrays, lat, lon, wayRadius.toDouble())
        val cell = (Math.round(lat / 0.01) shl 20) xor Math.round(lon / 0.01)
        val area = synchronized(areaCache) { areaCache[cell] } ?: try {
            Overpass.areaOf(lat, lon).also { synchronized(areaCache) { areaCache[cell] = it } }
        } catch (_: Exception) {
            null to null
        }
        // build-436 (Sếp: "không thấy cây xăng"): gói không chứa trạm xăng → hỏi Overpass riêng, nhớ theo ô ~2 km.
        val fcell = (Math.round(lat / 0.02) shl 20) xor Math.round(lon / 0.02)
        val fuel = synchronized(fuelCache) { fuelCache[fcell] } ?: try {
            Overpass.fuelNear(lat, lon, maxOf(wayRadius, 2500)).also { synchronized(fuelCache) { fuelCache[fcell] = it } }
        } catch (_: Exception) { base.fuel }
        return OsmSnapshot(lat, lon, base.ways, base.cameras, area.first, area.second, base.signs, fuel)
    }
}
