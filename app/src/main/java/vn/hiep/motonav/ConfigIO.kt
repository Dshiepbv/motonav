package vn.hiep.motonav

import android.content.Context
import org.json.JSONObject

/** Bản 236 + nhập cấu hình: chỉ đọc file JSON xuất từ bản mới (Cài đặt → Cấu hình → Xuất). */
object ConfigIO {
    fun load(ctx: Context, text: String): Int {
        val root = JSONObject(text)
        val vals = root.optJSONObject("settings") ?: throw IllegalArgumentException("File không phải cấu hình MotoNav")
        val ed = ctx.getSharedPreferences("motonav", Context.MODE_PRIVATE).edit()
        var n = 0
        for (k in vals.keys()) {
            val o = vals.optJSONObject(k) ?: continue
            when (o.optString("t")) {
                "b" -> ed.putBoolean(k, o.getBoolean("v"))
                "i" -> ed.putInt(k, o.getInt("v"))
                "l" -> ed.putLong(k, o.getLong("v"))
                "f" -> ed.putFloat(k, o.getDouble("v").toFloat())
                "s" -> ed.putString(k, o.getString("v"))
                "set" -> {
                    val a = o.getJSONArray("v")
                    ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                }
                else -> continue
            }
            n++
        }
        ed.commit()
        return n
    }
}
