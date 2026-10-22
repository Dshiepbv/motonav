package vn.hiep.motonav

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Hộp nhập 2 mã VietMap: mã API (tìm đường, tìm kiếm) và mã Tilemap (hiện bản đồ). */
object KeyDialog {
    fun show(a: Activity, prefs: Prefs, after: (() -> Unit)? = null) {
        val pad = (16 * a.resources.displayMetrics.density).toInt()
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        box.addView(TextView(a).apply { text = "Mã API (tìm đường, tìm địa điểm):" })
        val api = EditText(a).apply {
            setText(prefs.vietmapKey)
            hint = "Dán mã API"
            setSingleLine()
        }
        box.addView(api)
        box.addView(TextView(a).apply { text = "\nMã Tilemap (hiện bản đồ) – trong mục \"Tilemap consumer\":" })
        val tile = EditText(a).apply {
            setText(prefs.vietmapTileKey)
            hint = "Dán mã Tilemap"
            setSingleLine()
        }
        box.addView(tile)
        AlertDialog.Builder(a)
            .setTitle("Mã VietMap")
            .setMessage("Đăng ký miễn phí tại maps.vietmap.vn → Console. VietMap tách 2 mã: mã API và mã Tilemap.")
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
