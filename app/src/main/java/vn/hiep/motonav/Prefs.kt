package vn.hiep.motonav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("motonav", Context.MODE_PRIVATE)

    var moped: Boolean
        get() = sp.getBoolean("moped", false)
        set(v) = sp.edit().putBoolean("moped", v).apply()

    var areaMode: Int
        get() = sp.getInt("area", AREA_AUTO)
        set(v) = sp.edit().putInt("area", v).apply()

    var estimate: Boolean
        get() = sp.getBoolean("estimate", true)
        set(v) = sp.edit().putBoolean("estimate", v).apply()

    var tolerance: Int
        get() = sp.getInt("tol", 3)
        set(v) = sp.edit().putInt("tol", v).apply()

    var voice: Boolean
        get() = sp.getBoolean("voice", true)
        set(v) = sp.edit().putBoolean("voice", v).apply()

    var announceLimit: Boolean
        get() = sp.getBoolean("announceLimit", true)
        set(v) = sp.edit().putBoolean("announceLimit", v).apply()

    var overlay: Boolean
        get() = sp.getBoolean("overlay", true)
        set(v) = sp.edit().putBoolean("overlay", v).apply()

    var overlayScale: Float
        get() = sp.getFloat("overlayScale", 1f)
        set(v) = sp.edit().putFloat("overlayScale", v).apply()

    var overlayX: Int
        get() = sp.getInt("overlayX", -1)
        set(v) = sp.edit().putInt("overlayX", v).apply()

    var overlayY: Int
        get() = sp.getInt("overlayY", -1)
        set(v) = sp.edit().putInt("overlayY", v).apply()

    var vietmapKey: String
        get() = sp.getString("vmkey", "") ?: ""
        set(v) = sp.edit().putString("vmkey", v.trim()).apply()

    /** Mã của "Tilemap consumer" trên VietMap (để hiện bản đồ); trống thì dùng mã API. */
    var vietmapTileKey: String
        get() = sp.getString("vmtile", "") ?: ""
        set(v) = sp.edit().putString("vmtile", v.trim()).apply()

    var rotateMap: Boolean
        get() = sp.getBoolean("rotateMap", true)
        set(v) = sp.edit().putBoolean("rotateMap", v).apply()

    /** Hướng xoay bản đồ: 0 = theo la bàn/con quay của điện thoại, 1 = theo hướng đường đi (GPS) */
    var mapHeading: Int
        get() = sp.getInt("mapHeading", 0)
        set(v) = sp.edit().putInt("mapHeading", v).apply()

    var autoZoom: Boolean
        get() = sp.getBoolean("autoZoom", true)
        set(v) = sp.edit().putBoolean("autoZoom", v).apply()

    /** Nền bản đồ: 0 = Tự động (tối từ 18h–6h), 1 = Sáng, 2 = Tối */
    var mapTheme: Int
        get() = sp.getInt("mapTheme", if (sp.getBoolean("nightMap", true)) 0 else 1)
        set(v) = sp.edit().putInt("mapTheme", v).apply()

    var text3d: Boolean
        get() = sp.getBoolean("text3d", true)
        set(v) = sp.edit().putBoolean("text3d", v).apply()

    var weather: Boolean
        get() = sp.getBoolean("weather", true)
        set(v) = sp.edit().putBoolean("weather", v).apply()

    var traffic: Boolean
        get() = sp.getBoolean("traffic", true)
        set(v) = sp.edit().putBoolean("traffic", v).apply()

    /** 0 = OpenFreeMap (miễn phí, không cần mã), 1 = VietMap */
    var mapSource: Int
        get() = sp.getInt("mapSource", 0)
        set(v) = sp.edit().putInt("mapSource", v).apply()

    /** Link kiểu bản đồ VietMap do người dùng dán (tuỳ chọn). */
    var vietmapStyleUrl: String
        get() = sp.getString("vmstyle", "") ?: ""
        set(v) = sp.edit().putString("vmstyle", v.trim()).apply()

    /** Chuyến đang đi (để dẫn tiếp nếu app bị tắt giữa chừng). */
    fun saveTrip(lat: Double, lon: Double, name: String?) {
        sp.edit().putString("tripLat", lat.toString()).putString("tripLon", lon.toString())
            .putString("tripName", name ?: "").putLong("tripAt", System.currentTimeMillis()).apply()
    }

    fun clearTrip() = sp.edit().remove("tripLat").remove("tripLon").remove("tripName").remove("tripAt").apply()

    /** Trả về (lat, lon, tên) nếu có chuyến dở trong vòng 3 giờ. */
    fun pendingTrip(): Triple<Double, Double, String?>? {
        val at = sp.getLong("tripAt", 0L)
        if (at == 0L || System.currentTimeMillis() - at > 3 * 3600_000L) return null
        val lat = sp.getString("tripLat", null)?.toDoubleOrNull() ?: return null
        val lon = sp.getString("tripLon", null)?.toDoubleOrNull() ?: return null
        return Triple(lat, lon, sp.getString("tripName", "")?.ifBlank { null })
    }

    companion object {
        const val AREA_AUTO = 0
        const val AREA_URBAN = 1
        const val AREA_RURAL = 2
    }
}

/** Camera anh tự đánh dấu (phạt nguội, tốc độ...), lưu trong máy. */
object CameraStore {
    @Volatile
    private var cache: List<Camera>? = null

    fun all(ctx: Context): List<Camera> {
        cache?.let { return it }
        val sp = ctx.applicationContext.getSharedPreferences("motonav", Context.MODE_PRIVATE)
        val list = ArrayList<Camera>()
        try {
            val arr = JSONArray(sp.getString("user_cams", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Camera(
                        o.getString("id"), o.getDouble("lat"), o.getDouble("lon"),
                        o.optInt("kind", CamKind.FINE), o.optDouble("heading", -1.0), true,
                    ),
                )
            }
        } catch (_: Exception) {
        }
        cache = list
        return list
    }

    private fun save(ctx: Context, list: List<Camera>) {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject()
                    .put("id", c.id).put("lat", c.lat).put("lon", c.lon)
                    .put("kind", c.kind).put("heading", c.heading),
            )
        }
        ctx.applicationContext.getSharedPreferences("motonav", Context.MODE_PRIVATE)
            .edit().putString("user_cams", arr.toString()).apply()
        cache = list
    }

    fun add(ctx: Context, lat: Double, lon: Double, kind: Int, heading: Double) {
        val list = ArrayList(all(ctx))
        list.add(Camera("u" + System.currentTimeMillis(), lat, lon, kind, heading, true))
        save(ctx, list)
    }

    /** Thêm nhiều camera (bỏ qua điểm trùng trong 20 m). Trả về số điểm mới. */
    fun addAll(ctx: Context, items: List<CameraIO.Item>): Int {
        val list = ArrayList(all(ctx))
        var added = 0
        val base = System.currentTimeMillis()
        for (it in items) {
            val dup = list.any { c -> Geo.distance(c.lat, c.lon, it.lat, it.lon) < 20 && c.kind == it.kind }
            if (dup) continue
            list.add(Camera("u$base-$added", it.lat, it.lon, it.kind, it.heading, true))
            added++
        }
        if (added > 0) save(ctx, list)
        return added
    }

    /** Xoá camera tự thêm gần vị trí nhất (trong 300 m). Trả về true nếu có xoá. */
    fun removeNearest(ctx: Context, lat: Double, lon: Double): Boolean {
        val list = ArrayList(all(ctx))
        val best = list.minByOrNull { Geo.distance(lat, lon, it.lat, it.lon) } ?: return false
        if (Geo.distance(lat, lon, best.lat, best.lon) > 300) return false
        list.remove(best)
        save(ctx, list)
        return true
    }

    fun clear(ctx: Context) = save(ctx, emptyList())
}
