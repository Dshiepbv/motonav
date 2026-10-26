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
            hint = "Mã API \"default\" (tìm đường, tìm kiếm, kẹt xe)"
            setSingleLine()
        }
        val tile = EditText(a).apply {
            setText(prefs.vietmapTileKey)
            hint = "Mã \"public tile\" (để hiện bản đồ nền)"
            setSingleLine()
        }
        box.addView(api)
        box.addView(tile)
        AlertDialog.Builder(a)
            .setTitle("Mã API VietMap")
            .setMessage(
                "Cần CẢ 2 mã riêng từ maps.vietmap.vn → Console → API Keys:\n" +
                    "• \"default\": tìm đường, tìm địa điểm, kẹt xe\n" +
                    "• \"public tile\": hiện bản đồ nền — thiếu mã này bản đồ sẽ trắng dù mã default vẫn đúng, vì VietMap không cho khoá \"default\" hiện bản đồ.",
            )
            .setView(box)
            .setPositiveButton("Lưu") { _, _ ->
                prefs.vietmapKey = api.text.toString()
                prefs.vietmapTileKey = tile.text.toString()
                after?.invoke()
            }
            .setNeutralButton("Mở VietMap Console") { _, _ ->
                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.vietmap.vn/console-v2/")))
            }
            .setNegativeButton("Đóng", null)
            .show()
    }
}
