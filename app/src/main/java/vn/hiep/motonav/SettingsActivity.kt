package vn.hiep.motonav

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Trang cài đặt: công tắc bật/tắt rõ ràng, nhóm theo mục. */
class SettingsActivity : Activity() {

    private val bg = Color.parseColor("#0B0D10")
    private val card = Color.parseColor("#161A20")
    private val muted = Color.parseColor("#9AA3AE")
    private val accent = Color.parseColor("#0B6E4F")

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(14f), dp(14f), dp(24f))
        }
        scroll.addView(list)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun rounded(color: Int, r: Float = 14f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun tv(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private var group: LinearLayout? = null

    private fun header(t: String) {
        list.addView(tv(14f, Color.parseColor("#5DD3A6"), true).apply {
            text = t.uppercase()
            setPadding(dp(4f), dp(18f), 0, dp(6f))
        })
        group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card)
            setPadding(dp(14f), dp(4f), dp(14f), dp(4f))
        }
        list.addView(group)
    }

    private fun divider() {
        val g = group ?: return
        if (g.childCount == 0) return
        g.addView(View(this).apply { setBackgroundColor(Color.parseColor("#262C34")) }, LinearLayout.LayoutParams(-1, 1))
    }

    private fun labelCol(title: String, sub: String?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(tv(16f).apply { text = title })
        if (sub != null) addView(tv(13f, muted).apply { text = sub })
    }

    /** Hàng có công tắc bật/tắt. */
    private fun toggle(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
        divider()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12f), 0, dp(12f))
        }
        row.addView(labelCol(title, sub), LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this).apply {
            isChecked = value
            setOnCheckedChangeListener { _, v ->
                onChange(v)
                Live.changed()
            }
        }
        row.setOnClickListener { sw.toggle() }
        row.addView(sw)
        group?.addView(row)
    }

    /** Hàng chọn một trong nhiều mức (nút bấm liền nhau). */
    private fun choice(title: String, sub: String?, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        divider()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12f), 0, dp(12f))
        }
        box.addView(labelCol(title, sub))
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.parseColor("#0F1216"), 10f)
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        }
        val views = ArrayList<TextView>()
        options.forEachIndexed { i, o ->
            val t = tv(14f, Color.WHITE, true).apply {
                text = o
                gravity = Gravity.CENTER
                setPadding(dp(4f), dp(9f), dp(4f), dp(9f))
            }
            views.add(t)
            seg.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }
        fun paint(sel: Int) {
            views.forEachIndexed { i, v ->
                v.background = if (i == sel) rounded(accent, 8f) else null
                v.setTextColor(if (i == sel) Color.WHITE else muted)
            }
        }
        paint(selected)
        views.forEachIndexed { i, v ->
            v.setOnClickListener {
                onPick(i)
                paint(i)
                Live.changed()
            }
        }
        box.addView(seg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        group?.addView(box)
    }

    /** Hàng bấm để mở một thao tác. */
    private fun action(title: String, sub: String?, color: Int = Color.WHITE, onClick: () -> Unit) {
        divider()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14f), 0, dp(14f))
            setOnClickListener { onClick() }
        }
        val col = labelCol(title, sub)
        (col.getChildAt(0) as TextView).setTextColor(color)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(tv(20f, muted).apply { text = "›" })
        group?.addView(row)
    }

    private fun status(ok: Boolean) = if (ok) "✅ Đã bật" else "⚠️ Chưa bật – chạm để cấp"

    private fun build() {
        list.removeAllViews()
        list.addView(tv(26f, Color.WHITE, true).apply { text = "Cài đặt" })

        header("Dẫn đường & bản đồ")
        toggle("Xoay bản đồ theo hướng đi", "Tắt thì bản đồ luôn hướng Bắc lên trên", prefs.rotateMap) { prefs.rotateMap = it }
        toggle("Tự thu phóng theo tốc độ", "Thu nhỏ dần khi chạy trên 30, 50, 65 km/h", prefs.autoZoom) { prefs.autoZoom = it }
        choice("Nguồn bản đồ nền", "OpenFreeMap: miễn phí, không cần mã · VietMap: cần mã Tilemap", listOf("OpenFreeMap", "VietMap"), prefs.mapSource) {
            prefs.mapSource = it
        }
        choice("Nền bản đồ", "Tự động: nền tối từ 18h đến 6h sáng", listOf("Tự động", "Sáng", "Tối"), prefs.mapTheme) {
            prefs.mapTheme = it
        }
        toggle("Tô màu kẹt xe trên tuyến", "Dữ liệu kẹt xe của VietMap: cam = chậm, đỏ = kẹt, đỏ sẫm = kẹt nặng", prefs.traffic) {
            prefs.traffic = it
        }
        val keysOk = prefs.vietmapKey.isNotBlank() && prefs.vietmapTileKey.isNotBlank()
        action("Mã VietMap", if (keysOk) "✅ Đã nhập mã API và mã Tilemap" else "⚠️ Chưa nhập đủ 2 mã") {
            KeyDialog.show(this, prefs) { build() }
        }

        header("Tốc độ giới hạn")
        choice("Loại xe", null, listOf("Mô tô (>50cc)", "Xe gắn máy (≤50cc)"), if (prefs.moped) 1 else 0) {
            prefs.moped = it == 1
        }
        choice("Khu vực", "Dùng khi đường chưa có biển trên bản đồ", listOf("Tự động", "Trong khu dân cư", "Ngoài khu dân cư"), prefs.areaMode) {
            prefs.areaMode = it
        }
        toggle("Ước tính khi thiếu biển", "Tính theo Thông tư 38/2024 cho xe máy", prefs.estimate) { prefs.estimate = it }
        val tols = listOf(0, 3, 5, 10)
        choice("Báo vượt tốc khi quá", null, tols.map { "+$it" }, tols.indexOf(prefs.tolerance).coerceAtLeast(0)) {
            prefs.tolerance = tols[it]
        }

        header("Âm thanh & cảnh báo")
        toggle("Giọng đọc", "Tiếng bíp và dải chữ cảnh báo luôn bật", prefs.voice) { prefs.voice = it }
        toggle("Đọc giới hạn khi đổi đoạn đường", null, prefs.announceLimit) { prefs.announceLimit = it }

        header("Ô nổi trên Google Maps")
        toggle("Hiện ô nổi", "Biển giới hạn, tốc độ, cảnh báo nổi trên Google Maps", prefs.overlay) { prefs.overlay = it }
        val scales = listOf(0.8f, 1f, 1.3f)
        choice("Cỡ ô nổi", null, listOf("Nhỏ", "Vừa", "Lớn"), scales.indexOf(prefs.overlayScale).let { if (it < 0) 1 else it }) {
            prefs.overlayScale = scales[it]
        }

        header("Camera & điểm cảnh báo")
        val n = CameraStore.all(this).size
        action("Nhập danh sách từ file", "KML (Google My Maps), CSV, JSON · đang có $n điểm") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT,
            )
        }
        action("Xuất danh sách ra file CSV", "Để chia sẻ cho người khác") {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/csv").putExtra(Intent.EXTRA_TITLE, "canh-bao-motonav.csv"),
                REQ_EXPORT,
            )
        }
        action("Xoá điểm gần vị trí hiện tại", "Trong bán kính 300 m") {
            val ok = Live.gpsOk && CameraStore.removeNearest(this, Live.lat, Live.lon)
            toast(if (ok) "Đã xoá điểm gần nhất." else "Không có điểm nào trong 300 m.")
            build()
        }
        action("Xoá tất cả điểm tự thêm", null, Color.parseColor("#FF6B60")) {
            AlertDialog.Builder(this)
                .setMessage("Xoá tất cả $n điểm cảnh báo đã thêm?")
                .setPositiveButton("Xoá") { _, _ -> CameraStore.clear(this); build() }
                .setNegativeButton("Huỷ", null)
                .show()
        }

        header("Hệ thống")
        toggle("Theo dõi tốc độ & camera", "Chạy nền khi dùng app", Live.serviceRunning) { on ->
            if (on) {
                Live.userStopped = false
                DriveService.start(this)
            } else {
                Live.userStopped = true
                DriveService.stop(this)
            }
        }
        action("Quyền hiện ô nổi", status(Settings.canDrawOverlays(this))) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val notif = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        action("Quyền đọc chỉ dẫn Google Maps", status(notif) + "\nAndroid 13+: Thông tin ứng dụng → ⋮ → Cho phép cài đặt bị hạn chế") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        action("Thông tin ứng dụng", "Pin: chọn \"Không hạn chế\" để không bị tắt khi chạy nền") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            when (requestCode) {
                REQ_IMPORT -> {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    val items = CameraIO.parse(text)
                    val added = CameraStore.addAll(this, items)
                    toast(
                        if (items.isEmpty()) "Không tìm thấy điểm nào trong file."
                        else "Đã nhập $added điểm mới (đọc được ${items.size}, bỏ qua điểm trùng).",
                    )
                }
                REQ_EXPORT -> {
                    val cams = CameraStore.all(this)
                    contentResolver.openOutputStream(uri)?.use { it.write(CameraIO.toCsv(cams).toByteArray(Charsets.UTF_8)) }
                    toast("Đã xuất ${cams.size} điểm.")
                }
            }
        } catch (e: Exception) {
            toast("Lỗi đọc/ghi file: ${e.message}")
        }
        build()
    }

    companion object {
        private const val REQ_IMPORT = 51
        private const val REQ_EXPORT = 52
    }
}
