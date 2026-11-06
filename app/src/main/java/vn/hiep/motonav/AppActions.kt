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

    /** Nút "!" – đánh dấu camera / ổ gà / CSGT tại vị trí hiện tại (xe đang ở đâu). */
    fun markAlert(a: Activity) {
        if (!Live.gpsOk) {
            Toast.makeText(a, "Chưa có GPS, chờ một chút rồi thử lại.", Toast.LENGTH_SHORT).show()
            return
        }
        markAlertAt(a, Live.lat, Live.lon, Live.heading)
    }

    /** Đánh dấu camera / ổ gà / CSGT tại một điểm bất kỳ (vd: chạm giữ trên bản đồ) — không
     * nhất thiết là vị trí hiện tại, nên mặc định không có hướng cụ thể (báo cả hai chiều). */
    fun markAlertAt(a: Activity, lat: Double, lon: Double, heading: Double = -1.0) {
        val kinds = arrayOf("📷  Camera phạt nguội", "🚨  Camera bắn tốc độ", "🚦  Camera đèn đỏ", "⚠️  Ổ gà / đường xấu", "👮  Trạm CSGT cố định")
        val codes = intArrayOf(CamKind.FINE, CamKind.SPEED, CamKind.RED_LIGHT, CamKind.HAZARD, CamKind.POLICE)
        AlertDialog.Builder(a)
            .setTitle("Báo cảnh báo tại vị trí này")
            .setItems(kinds) { _, which ->
                CameraStore.add(a, lat, lon, codes[which], heading)
                val note = if (heading >= 0) "chỉ báo theo hướng anh đang đi" else "báo cả hai chiều"
                Toast.makeText(a, "Đã lưu: ${kinds[which].substring(3).trim().lowercase()} ($note).", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    /** Mở Google Maps (MotoNav hiện ô tốc độ + cảnh báo nổi trên đó). */
    fun openGoogleMaps(a: Activity, prefs: Prefs) {
        if (!prefs.overlay) prefs.overlay = true
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

    /**
     * Xuất tối đa 3 chuyến gần nhất thành một file TXT UTF-8 thuần.
     * Nội dung giữ nguyên JSON Lines để máy và người có thể đọc/phân tích KPI;
     * mỗi chuyến vẫn có event start/stop làm ranh giới, không làm mất event hay timestamp.
     */
    fun sendTripLogs(a: Activity) {
        try { TftLink.dumpRecentFrames() } catch (_: Exception) {}
        val files = TripLog.files(a).take(3)
        if (files.isEmpty()) {
            Toast.makeText(a, "Chưa có nhật ký nào", Toast.LENGTH_SHORT).show()
            return
        }

        val export = try {
            val dir = files[0].parentFile ?: a.filesDir
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val out = java.io.File(dir, "MotoNav-log-$stamp.txt")
            val temp = java.io.File(dir, "MotoNav-log-$stamp.txt.tmp")
            try {
                temp.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                    // files mới trước; xuất cũ trước mới sau để timeline liên tục.
                    for (source in files.reversed()) {
                        val active = TripLog.isActive(source)
                        try {
                            java.util.zip.GZIPInputStream(source.inputStream().buffered()).bufferedReader(Charsets.UTF_8).use { reader ->
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    writer.append(line)
                                    writer.newLine()
                                }
                            }
                        } catch (e: java.io.EOFException) {
                            // File đang ghi chưa có GZIP trailer; phần dữ liệu đã flush vẫn hợp lệ.
                            if (!active) throw e
                        } catch (e: java.util.zip.ZipException) {
                            // Một số Android báo thiếu trailer dưới dạng ZipException.
                            if (!active) throw e
                        }
                    }
                }
                if (!temp.renameTo(out)) {
                    temp.copyTo(out, overwrite = true)
                    temp.delete()
                }
            } catch (e: Exception) {
                temp.delete()
                out.delete()
                throw e
            }
            out
        } catch (e: Exception) {
            android.util.Log.e("MotoNavTripLog", "TXT export failed", e)
            Toast.makeText(a, "Không thể tạo TXT: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, TripLogProvider.uri(a, export))
                putExtra(Intent.EXTRA_SUBJECT, "Nhật ký MotoNav (TXT)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            a.startActivity(Intent.createChooser(send, "Gửi nhật ký TXT"))
        } catch (e: Exception) {
            android.util.Log.e("MotoNavTripLog", "TXT share failed", e)
            Toast.makeText(a, "Đã tạo TXT nhưng không mở được chia sẻ", Toast.LENGTH_LONG).show()
        }
    }}
