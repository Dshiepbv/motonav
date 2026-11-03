package vn.hiep.motonav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Xuất / nhập toàn bộ cài đặt (SharedPreferences "motonav") ra 1 file JSON. */
object ConfigIO {
    private const val PREFS = "motonav"

    /** Thông số viết cứng trong code của build này — chỉ để xem, nhập file không đổi được. */
    private fun fixed(): JSONObject = JSONObject().apply {
        put("tftPaceMsPerKb", TftLink.PACE_STEPS.joinToString(","))
        put("tftPaceChunkBytes", TftLink.PACE_CHUNK)
        put("tftConnect", "insecure RFCOMM trước, secure sau")
        put("tftAckWaitMs", TftLink.ACK_WAIT_MS)
        put("tftStallWaitMs", TftLink.STALL_WAIT_MS)
        put("tftResend", if (TftLink.RESEND_AFTER_MS >= 3_600_000L) "tắt" else "${TftLink.RESEND_AFTER_MS} ms")
        put("tftAutoReset", TftLink.AUTO_RESET)
        put("tftPredictS", TftLink.PRED_S)
        put("destSnap", false)
        put("carOnlyFilter", RoutePick.CAR_ONLY_FILTER)
        put("uturnRule", "chỉ bỏ tuyến tìm lại quay đầu về đường vừa đi; quay đầu nằm trong tuyến thì giữ")
        put("speedSign", "biển sát làn phải nhất (làn xe máy), bỏ biển ghép cặp khác giá trị ≤5 m")
    }

    fun export(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val vals = JSONObject()
        for ((k, v) in sp.all.toSortedMap()) {
            val o = JSONObject()
            when (v) {
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is String -> o.put("t", "s").put("v", v)
                is Set<*> -> o.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            vals.put(k, o)
        }
        return JSONObject().apply {
            put("app", "MotoNav")
            put("build", Updater.currentVersion(ctx))
            put("exportedAt", System.currentTimeMillis())
            put("settings", vals)
            put("fixedInCode", fixed())
        }.toString(2)
    }

    /** Trả về số mục đã nạp. */
    fun load(ctx: Context, text: String): Int {
        val root = JSONObject(text)
        val vals = root.optJSONObject("settings") ?: throw IllegalArgumentException("File không phải cấu hình MotoNav")
        val ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
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
