package vn.hiep.motonav

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * build-454 (Sếp 15/11): nhớ chỗ đậu xe. Mỗi gói tốc độ 0x41 của xe = xe còn nổ máy và ở gần điện thoại → lưu vị trí GPS
 * (xe đứng yên: mỗi 2 s; đang chạy: mỗi 15 s). Tắt máy thì xe im lặng → điểm lưu cuối cùng chính là chỗ đậu.
 */
object ParkSpot {
    private var lastSave = 0L

    fun onBikeAlive(ctx: Context?, kmh: Int) {
        ctx ?: return
        if (!Live.gpsOk || (Live.lat == 0.0 && Live.lon == 0.0)) return
        val now = System.currentTimeMillis()
        if (now - lastSave < if (kmh <= 3) 2_000L else 15_000L) return
        lastSave = now
        ctx.getSharedPreferences("motonav", Context.MODE_PRIVATE).edit()
            .putString("parkLat", Live.lat.toString()).putString("parkLon", Live.lon.toString())
            .putLong("parkAt", now).apply()
    }

    fun open(a: Activity) {
        val sp = a.getSharedPreferences("motonav", Context.MODE_PRIVATE)
        val lat = sp.getString("parkLat", null)?.toDoubleOrNull()
        val lon = sp.getString("parkLon", null)?.toDoubleOrNull()
        val at = sp.getLong("parkAt", 0L)
        if (lat == null || lon == null || at == 0L) {
            Toast.makeText(a, "Chưa có chỗ đậu xe (cần kết nối màn xe ít nhất một lần)", Toast.LENGTH_LONG).show(); return
        }
        val dist = if (Live.gpsOk) Geo.distance(Live.lat, Live.lon, lat, lon).toInt() else -1
        val msg = "Tắt máy lúc ${SimpleDateFormat("HH:mm dd/MM", Locale.US).format(Date(at))}" +
            (if (dist >= 0) "\nCách đây khoảng ${if (dist >= 1000) String.format(Locale.US, "%.1f km", dist / 1000.0) else "$dist m"}" else "")
        AlertDialog.Builder(a).setTitle("Chỗ đậu xe").setMessage(msg)
            .setPositiveButton("Dẫn đường đi bộ") { _, _ ->
                try {
                    a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon&mode=w")).setPackage("com.google.android.apps.maps"))
                } catch (_: Exception) {
                    try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(Xe)"))) } catch (_: Exception) {}
                }
            }
            .setNegativeButton("Đóng", null).show()
    }
}
