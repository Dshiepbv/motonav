package vn.hiep.motonav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Địa điểm đã lưu: Nhà, Công ty, Yêu thích. */
class SavedPlace(val name: String, val lat: Double, val lon: Double)

object Places {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("motonav", Context.MODE_PRIVATE)

    private fun read(ctx: Context, key: String): SavedPlace? {
        val s = sp(ctx).getString(key, null) ?: return null
        return try {
            val o = JSONObject(s)
            SavedPlace(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
        } catch (_: Exception) {
            null
        }
    }

    private fun write(ctx: Context, key: String, p: SavedPlace?) {
        val e = sp(ctx).edit()
        if (p == null) e.remove(key)
        else e.putString(key, JSONObject().put("name", p.name).put("lat", p.lat).put("lon", p.lon).toString())
        e.apply()
    }

    fun home(ctx: Context) = read(ctx, "place_home")
    fun work(ctx: Context) = read(ctx, "place_work")
    fun setHome(ctx: Context, p: SavedPlace?) = write(ctx, "place_home", p)
    fun setWork(ctx: Context, p: SavedPlace?) = write(ctx, "place_work", p)

    fun favorites(ctx: Context): List<SavedPlace> {
        val out = ArrayList<SavedPlace>()
        try {
            val a = JSONArray(sp(ctx).getString("place_favs", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(SavedPlace(o.getString("name"), o.getDouble("lat"), o.getDouble("lon")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveFavs(ctx: Context, list: List<SavedPlace>) {
        val a = JSONArray()
        for (p in list) a.put(JSONObject().put("name", p.name).put("lat", p.lat).put("lon", p.lon))
        sp(ctx).edit().putString("place_favs", a.toString()).apply()
    }

    fun addFavorite(ctx: Context, p: SavedPlace) = saveFavs(ctx, favorites(ctx) + p)

    fun removeFavorite(ctx: Context, index: Int) {
        val l = favorites(ctx).toMutableList()
        if (index in l.indices) l.removeAt(index)
        saveFavs(ctx, l)
    }
}
