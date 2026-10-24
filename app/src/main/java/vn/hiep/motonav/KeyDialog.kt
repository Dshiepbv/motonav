package vn.hiep.motonav

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.widget.LinearLayout

/** Hộp nhập mã API VietMap (dùng cho tìm đường, tìm địa điểm, kẹt xe). */
object KeyDialog {
    fun show(a: Activity, prefs: Prefs, after: (() -> Unit)? = null) {
        val pad = (16 * a.resources.displayMetrics.density).toInt()
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val api = EditText(a).apply {
            setText(prefs.vietmapKey)
            hint = "Dán mã API VietMap"
            setSingleLine()
        }
        box.addView(api)
        AlertDialog.Builder(a)
            .setTitle("Mã API VietMap")
            .setMessage("Dùng để tìm đường xe máy, tìm địa điểm và xem kẹt xe. Đăng ký miễn phí tại maps.vietmap.vn → Console → API Keys.")
            .setView(box)
            .setPositiveButton("Lưu") { _, _ ->
                prefs.vietmapKey = api.text.toString()
                after?.invoke()
            }
            .setNeutralButton("Mở VietMap Console") { _, _ ->
                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.vietmap.vn/console-v2/")))
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    /** Hộp nhập mã API Google Directions (tuỳ chọn, thay VietMap khi bật). */
    fun showGoogle(a: Activity, prefs: Prefs, after: (() -> Unit)? = null) {
        val pad = (16 * a.resources.displayMetrics.density).toInt()
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val api = EditText(a).apply {
            setText(prefs.googleDirKey)
            hint = "Dán mã API Google Directions"
            setSingleLine()
        }
        box.addView(api)
        AlertDialog.Builder(a)
            .setTitle("Mã API Google Directions")
            .setMessage("Tìm đường bằng Google thay VietMap (thường thông minh hơn, né đường có chắn tốt hơn). Cần tạo mã riêng tại Google Cloud Console → bật \"Directions API\" + bật thanh toán (có hạn mức miễn phí hàng tháng nhưng vẫn cần thẻ).")
            .setView(box)
            .setPositiveButton("Lưu") { _, _ ->
                prefs.googleDirKey = api.text.toString()
                after?.invoke()
            }
            .setNeutralButton("Mở Google Cloud Console") { _, _ ->
                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.cloud.google.com/apis/library/directions-backend.googleapis.com")))
            }
            .setNegativeButton("Đóng", null)
            .show()
    }
}
