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
