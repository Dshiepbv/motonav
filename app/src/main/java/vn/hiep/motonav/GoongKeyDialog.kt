package vn.hiep.motonav

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.widget.LinearLayout

/** Hộp nhập mã API Goong (thử nghiệm thay VietMap cho định tuyến + bản đồ nền). */
object GoongKeyDialog {
    fun show(a: Activity, prefs: Prefs, after: (() -> Unit)? = null) {
        val pad = (16 * a.resources.displayMetrics.density).toInt()
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val rest = EditText(a).apply {
            setText(prefs.goongKey)
            hint = "REST API Key (tìm đường, tìm địa điểm)"
            setSingleLine()
        }
        val tile = EditText(a).apply {
            setText(prefs.goongTileKey)
            hint = "Map Tiles Key (hiện bản đồ nền)"
            setSingleLine()
        }
        box.addView(rest)
        box.addView(tile)
        AlertDialog.Builder(a)
            .setTitle("Mã API Goong (thử nghiệm)")
            .setMessage(
                "Cần CẢ 2 mã riêng từ account.goong.io → Account Page:\n" +
                    "• \"REST API Key\": tìm đường, tìm địa điểm — có mã này là app TỰ CHUYỂN sang Goong để định tuyến, VietMap chỉ còn làm dự phòng khi Goong lỗi.\n" +
                    "• \"Map Tiles Key\": hiện bản đồ nền — còn cần bật thêm ở mục \"Bản đồ nền\" bên dưới mới dùng.\n\n" +
                    "Lưu ý: chưa rõ \"Loại xe\" nào đúng cho xe máy (xem mục riêng), và Goong chưa có né cao tốc/kẹt xe như VietMap.",
            )
            .setView(box)
            .setPositiveButton("Lưu") { _, _ ->
                prefs.goongKey = rest.text.toString()
                prefs.goongTileKey = tile.text.toString()
                after?.invoke()
            }
            .setNeutralButton("Mở Goong Account") { _, _ ->
                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://account.goong.io/")))
            }
            .setNegativeButton("Đóng", null)
            .show()
    }
}
