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
        // Bấm "Mở Google Maps" nghĩa là muốn có ô nổi → luôn bật
        if (!prefs.overlay) prefs.overlay = true

        // 1. Quyền hiện trên ứng dụng khác (bắt buộc)
        if (!Settings.canDrawOverlays(a)) {
            AlertDialog.Builder(a)
                .setTitle("Cần quyền hiện ô nổi")
                .setMessage(
                    "Để ô tốc độ nổi trên Google Maps, bấm \"Cấp quyền\", tìm MotoNav và bật " +
                        "\"Cho phép hiển thị trên ứng dụng khác\".\n\nXong quay lại MotoNav bấm Mở Google Maps lần nữa.",
                )
                .setPositiveButton("Cấp quyền") { _, _ ->
                    a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}")))
                }
                .setNegativeButton("Huỷ", null)
                .show()
            return
        }

        // 2. Quyền biết app nào đang mở (để tự bật/tắt ô nổi) – hỏi cho tới khi cấp hoặc chọn "Không cần"
        val sp = a.getSharedPreferences("motonav", Context.MODE_PRIVATE)
        if (!ForegroundApp.hasPermission(a) && !sp.getBoolean("skipUsage", false)) {
            AlertDialog.Builder(a)
                .setTitle("Tự bật/tắt ô nổi theo Google Maps")
                .setMessage(
                    "Cấp quyền \"Truy cập dữ liệu sử dụng\" để ô tốc độ chỉ hiện khi Google Maps đang mở và tự tắt khi thoát " +
                        "(chỉ để biết app nào đang mở, không đọc nội dung).\n\n" +
                        "Bấm \"Cấp quyền\" → tìm MotoNav → bật. Xong quay lại bấm Mở Google Maps lần nữa.",
                )
                .setPositiveButton("Cấp quyền") { _, _ ->
                    try {
                        a.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(a, "Máy không mở được trang này, vào Cài đặt → Ứng dụng → Quyền đặc biệt → Truy cập dữ liệu sử dụng", Toast.LENGTH_LONG).show()
                    }
                }
                .setNeutralButton("Không cần") { _, _ ->
                    sp.edit().putBoolean("skipUsage", true).apply()
                    startAndLaunch(a)
                }
                .setNegativeButton("Để sau") { _, _ -> startAndLaunch(a) }
                .show()
            return
        }
        startAndLaunch(a)
    }

    private fun startAndLaunch(a: Activity) {
        Live.userStopped = false
        try { DriveService.start(a) } catch (_: Exception) {}
        Toast.makeText(a, "Ô tốc độ sẽ nổi trên Google Maps", Toast.LENGTH_SHORT).show()
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
