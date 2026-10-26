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
}
