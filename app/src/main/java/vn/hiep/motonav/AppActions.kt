package vn.hiep.motonav

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.Context
import android.widget.Toast

/** Các thao tác dùng chung cho màn hình bản đồ và màn hình tốc độ. */
object AppActions {

    /** Nút "!" – đánh dấu camera / ổ gà / CSGT tại vị trí hiện tại. */
    fun markAlert(a: Activity) {
        if (!Live.gpsOk) {
            Toast.makeText(a, "Chưa có GPS, chờ một chút rồi thử lại.", Toast.LENGTH_SHORT).show()
            return
        }
        val lat = Live.lat
        val lon = Live.lon
        val hdg = Live.heading
        val kinds = arrayOf("📷  Camera phạt nguội", "🚨  Camera bắn tốc độ", "🚦  Camera đèn đỏ", "⚠️  Ổ gà / đường xấu", "👮  Trạm CSGT cố định")
        val codes = intArrayOf(CamKind.FINE, CamKind.SPEED, CamKind.RED_LIGHT, CamKind.HAZARD, CamKind.POLICE)
        AlertDialog.Builder(a)
            .setTitle("Báo cảnh báo tại vị trí này")
            .setItems(kinds) { _, which ->
                CameraStore.add(a, lat, lon, codes[which], hdg)
                val note = if (hdg >= 0) "chỉ báo theo hướng anh đang đi" else "báo cả hai chiều"
                Toast.makeText(a, "Đã lưu: ${kinds[which].substring(3).trim().lowercase()} ($note).", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    /** Mở Google Maps (MotoNav hiện ô nổi tốc độ + cảnh báo trên đó). */
    fun openGoogleMaps(a: Activity, prefs: Prefs) {
        if (prefs.overlay && !Settings.canDrawOverlays(a)) {
            AlertDialog.Builder(a)
                .setTitle("Hiện tốc độ nổi trên Google Maps")
                .setMessage(
                    "MotoNav sẽ hiện một ô nhỏ (biển giới hạn, tốc độ, cảnh báo) đè lên Google Maps.\n\n" +
                        "Bấm \"Cho phép\", tìm MotoNav và bật \"Cho phép hiển thị trên ứng dụng khác\".",
                )
                .setPositiveButton("Cho phép") { _, _ ->
                    a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}")))
                }
                .setNeutralButton("Mở luôn") { _, _ -> launchMaps(a) }
                .setNegativeButton("Huỷ", null)
                .show()
            return
        }
        val sp = a.getSharedPreferences("motonav", android.content.Context.MODE_PRIVATE)
        if (prefs.overlay && !ForegroundApp.hasPermission(a) && !sp.getBoolean("askedUsage", false)) {
            sp.edit().putBoolean("askedUsage", true).apply()
            AlertDialog.Builder(a)
                .setTitle("Tự bật/tắt ô nổi")
                .setMessage(
                    "Để ô tốc độ chỉ hiện khi đang mở Google Maps và tự tắt khi thoát, MotoNav cần quyền " +
                        "\"Truy cập dữ liệu sử dụng\" (chỉ để biết app nào đang mở, không đọc nội dung).\n\n" +
                        "Bấm \"Cấp quyền\", tìm MotoNav và bật lên.",
                )
                .setPositiveButton("Cấp quyền") { _, _ ->
                    a.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .setNegativeButton("Bỏ qua") { _, _ -> openGoogleMaps(a, prefs) }
                .show()
            return
        }
        if (!Live.serviceRunning) {
            Live.userStopped = false
            try { DriveService.start(a) } catch (_: Exception) {}
        }
        launchMaps(a)
    }

    private fun launchMaps(a: Activity) {
        val i = a.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")).setPackage("com.google.android.apps.maps")
        try {
            a.startActivity(i)
        } catch (_: Exception) {
            try {
                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com")))
            } catch (_: Exception) {
                Toast.makeText(a, "Không mở được Google Maps", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
