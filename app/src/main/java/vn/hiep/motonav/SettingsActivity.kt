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

    /** Dấu chấm than nhỏ cạnh tiêu đề: chạm để xem ghi chú chi tiết, không chiếm chỗ khi không cần. */
    private fun infoIcon(note: String, title: String): TextView = tv(12f, Color.parseColor("#7CC4FF"), true).apply {
        text = "ⓘ"
        setPadding(dp(8f), dp(1f), dp(10f), dp(1f))
        setOnClickListener {
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(title)
                .setMessage(note)
                .setPositiveButton("Đã hiểu", null)
                .show()
        }
    }

    /** Tiêu đề mục: huy hiệu biểu tượng tròn + tên, trực quan hơn là chỉ chữ in hoa. */
    private fun header(icon: String, t: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(22f), 0, dp(8f))
        }
        row.addView(
            tv(14f).apply {
                text = icon
                gravity = Gravity.CENTER
                background = rounded(Color.parseColor("#1C2229"), 9f)
            },
            LinearLayout.LayoutParams(dp(28f), dp(28f)),
        )
        row.addView(
            tv(13f, Color.parseColor("#5DD3A6"), true).apply { text = t.uppercase() },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10f) },
        )
        list.addView(row)
        group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 16f)
            setPadding(dp(14f), dp(4f), dp(14f), dp(4f))
        }
        list.addView(group)
    }

    private fun divider() {
        val g = group ?: return
        if (g.childCount == 0) return
        g.addView(View(this).apply { setBackgroundColor(Color.parseColor("#262C34")) }, LinearLayout.LayoutParams(-1, 1))
    }

    private fun labelCol(title: String, sub: String?, note: String? = null, titleColor: Int = Color.WHITE): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val top = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(tv(16f, titleColor).apply { text = title })
        if (note != null) top.addView(infoIcon(note, title))
        addView(top)
        if (sub != null) addView(tv(13f, muted).apply { text = sub; setPadding(0, dp(2f), 0, 0) })
    }

    /** Hàng có công tắc bật/tắt. */
    private fun toggle(title: String, sub: String?, value: Boolean, note: String? = null, onChange: (Boolean) -> Unit) {
        divider()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12f), 0, dp(12f))
        }
        row.addView(labelCol(title, sub, note), LinearLayout.LayoutParams(0, -2, 1f))
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
    private fun choice(title: String, sub: String?, options: List<String>, selected: Int, note: String? = null, onPick: (Int) -> Unit) {
        divider()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12f), 0, dp(12f))
        }
        box.addView(labelCol(title, sub, note))
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
    private fun action(title: String, sub: String?, color: Int = Color.WHITE, note: String? = null, onClick: () -> Unit) {
        divider()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14f), 0, dp(14f))
            setOnClickListener { onClick() }
        }
        row.addView(labelCol(title, sub, note, color), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(tv(20f, muted).apply { text = "›" })
        group?.addView(row)
    }

    private fun status(ok: Boolean) = if (ok) "✅ Đã bật" else "⚠️ Chưa bật – chạm để cấp"

    private fun build() {
        list.removeAllViews()
        list.addView(tv(26f, Color.WHITE, true).apply { text = "Cài đặt" })

        header("🧪", "Thử nghiệm")
        toggle(
            "Khớp đường kiểu mới (HMM)",
            null,
            prefs.hmmMatch,
            note = "Xét cả chuỗi vị trí vừa đi để biết xe đang ở đường nào – ít nhầm đường song song, cầu vượt, đường gom. Tắt: dùng cách cũ (cách mới vẫn chạy ngầm để ghi nhật ký so sánh)",
        ) { prefs.hmmMatch = it }
        toggle(
            "Dự đoán vị trí giữa 2 lần GPS",
            null,
            prefs.predict,
            note = "Mũi tên và bản đồ chạy liên tục theo tốc độ thay vì nhích từng giây",
        ) { prefs.predict = it }

        header("🔋", "Pin")
        toggle(
            "Tiết kiệm pin",
            null,
            prefs.batterySaver,
            note = "Chỉ giữ dẫn đường, tốc độ, camera: bản đồ 15 khung hình/giây, tắt nhà 3D, chữ 3D, la bàn, thời tiết, kiểm tra nền thưa hơn",
        ) { on ->
            prefs.batterySaver = on
            if (on) {
                AlertDialog.Builder(this)
                    .setTitle("Hạn chế các app khác")
                    .setMessage(
                        "Android không cho một app tắt hoạt động của app khác. Để máy chỉ tập trung dẫn đường, " +
                            "anh bật thêm \"Trình tiết kiệm pin\" của máy: nó chặn app nền, đồng bộ, hiệu ứng… " +
                            "MotoNav vẫn chạy bình thường vì đang dẫn đường ở chế độ ưu tiên.",
                    )
                    .setPositiveButton("Mở Trình tiết kiệm pin") { _, _ ->
                        try {
                            startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    }
                    .setNegativeButton("Để sau", null)
                    .show()
            }
        }

        header("🗺️", "Dẫn đường & bản đồ")
        toggle("Xoay bản đồ theo hướng đi", null, prefs.rotateMap, note = "Như Google Maps: đang chạy theo hướng đường, dừng/chậm theo la bàn. Tắt thì luôn hướng Bắc lên trên") {
            prefs.rotateMap = it
        }
        toggle("Tự thu phóng theo tốc độ", null, prefs.autoZoom, note = "Thu nhỏ dần khi chạy trên 20, 40, 60 km/h") { prefs.autoZoom = it }
        choice("Nền bản đồ", null, listOf("Tự động", "Sáng", "Tối"), prefs.mapTheme, note = "Tự động: nền tối từ 18h đến 6h sáng") {
            prefs.mapTheme = it
        }
        toggle("Tên đường chữ nổi 3D tại ngã rẽ", null, prefs.text3d, note = "Tên các đường ở ngã rẽ phía trước dựng thành chữ khối đứng dọc theo đường") {
            prefs.text3d = it
        }
        toggle("Thời tiết dọc tuyến", null, prefs.weather, note = "Biểu tượng nắng/mưa tại chỗ đổi thời tiết, theo giờ anh tới. Dữ liệu Open-Meteo.com (CC BY 4.0)") {
            prefs.weather = it
        }
        toggle("Tô màu kẹt xe trên tuyến", null, prefs.traffic, note = "Dữ liệu kẹt xe của VietMap: cam = chậm, đỏ = kẹt, đỏ sẫm = kẹt nặng") {
            prefs.traffic = it
        }
        val keysOk = prefs.vietmapKey.isNotBlank()
        action("Mã API VietMap", if (keysOk) "✅ Đã nhập" else "⚠️ Chưa nhập – cần để tìm đường", note = "Dùng cho tìm đường, tìm địa điểm, kẹt xe") {
            KeyDialog.show(this, prefs) { build() }
        }

        header("⚡", "Tốc độ giới hạn")
        choice("Loại xe", null, listOf("Mô tô (>50cc)", "Xe gắn máy (≤50cc)"), if (prefs.moped) 1 else 0) {
            prefs.moped = it == 1
        }
        choice("Khu vực", null, listOf("Tự động", "Trong khu dân cư", "Ngoài khu dân cư"), prefs.areaMode, note = "Dùng khi đường chưa có biển trên bản đồ") {
            prefs.areaMode = it
        }
        toggle("Ước tính khi thiếu biển", null, prefs.estimate, note = "Tính theo Thông tư 38/2024 cho xe máy") { prefs.estimate = it }
        val tols = listOf(0, 3, 5, 10)
        choice("Báo vượt tốc khi quá", null, tols.map { "+$it" }, tols.indexOf(prefs.tolerance).coerceAtLeast(0)) {
            prefs.tolerance = tols[it]
        }

        header("🔊", "Âm thanh & cảnh báo")
        toggle("Giọng đọc", null, prefs.voice, note = "Tiếng bíp và dải chữ cảnh báo luôn bật") { prefs.voice = it }
        toggle("Đọc giới hạn khi đổi đoạn đường", null, prefs.announceLimit) { prefs.announceLimit = it }

        header("🪟", "Ô nổi trên Google Maps")
        toggle("Hiện ô nổi", null, prefs.overlay, note = "Biển giới hạn, tốc độ, cảnh báo nổi trên Google Maps") { prefs.overlay = it }
        val scales = listOf(0.8f, 1f, 1.3f)
        choice("Cỡ ô nổi", null, listOf("Nhỏ", "Vừa", "Lớn"), scales.indexOf(prefs.overlayScale).let { if (it < 0) 1 else it }) {
            prefs.overlayScale = scales[it]
        }

        header("📷", "Camera theo thông báo CSGT")
        val (okN, totalN) = OfficialCameras.status(this)
        action(
            "Đã định vị $okN/$totalN điểm",
            Live.officialProgress,
            note = "Ngã tư do CSGT TP.HCM, Hà Nội, Đắk Lắk công bố. App tự tìm toạ độ trên OpenStreetMap. Chạm vào dòng này để xem nguồn và các điểm chưa định vị được",
        ) {
            val failed = OfficialCameras.failedTitles(this)
            val srcs = try { OfficialCameras.entries(this).map { it.src }.distinct() } catch (_: Exception) { emptyList() }
            AlertDialog.Builder(this)
                .setTitle("Nguồn dữ liệu")
                .setMessage(
                    srcs.joinToString("\n") { "• $it" } +
                        (if (failed.isNotEmpty()) "\n\nChưa tìm được trên bản đồ:\n" + failed.joinToString("\n") { "– $it" } else ""),
                )
                .setPositiveButton("Đóng", null)
                .show()
        }
        action("Định vị lại từ đầu", null, note = "Dùng khi bản đồ OSM vừa được cập nhật") {
            OfficialCameras.reset(this)
            toast("Sẽ định vị lại ở lần bật theo dõi tốc độ kế tiếp")
            Live.userStopped = false
            DriveService.stop(this)
            android.os.Handler(mainLooper).postDelayed({ DriveService.start(this) }, 800)
            build()
        }

        header("⚠️", "Camera & điểm cảnh báo")
        val n = CameraStore.all(this).size
        action("Nhập danh sách từ file", "Đang có $n điểm", note = "Hỗ trợ file KML (Google My Maps), CSV, JSON") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT,
            )
        }
        action("Xuất danh sách ra file CSV", null, note = "Để chia sẻ cho người khác") {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/csv").putExtra(Intent.EXTRA_TITLE, "canh-bao-motonav.csv"),
                REQ_EXPORT,
            )
        }
        action("Xoá điểm gần vị trí hiện tại", null, note = "Trong bán kính 300 m") {
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
        // Tính năng "vùng tốc độ cố định" (vẽ ranh giới / gán theo đoạn đường) đã tạm bỏ —
        // chỉ còn dọn dữ liệu cũ nếu máy đã từng lưu lúc thử nghiệm trước đây.
        val zp = ZonePolygons.count(this)
        val zn = WayLimits.count(this)
        if (zp > 0 || zn > 0) {
            action("Xoá dữ liệu vùng tốc độ cố định cũ (${zp + zn})", null, Color.parseColor("#FF6B60"), note = "Tính năng này đã tạm bỏ") {
                AlertDialog.Builder(this)
                    .setMessage("Xoá toàn bộ dữ liệu vùng/đoạn đường tốc độ cố định đã lưu trước đây?")
                    .setPositiveButton("Xoá") { _, _ -> ZonePolygons.clear(this); WayLimits.clear(this); build() }
                    .setNegativeButton("Huỷ", null)
                    .show()
            }
        }

        header("⚙️", "Hệ thống")
        val packOk = try { OfflinePack.open(this) } catch (_: Exception) { false }
        action(
            "Dữ liệu ngoại tuyến TP.HCM",
            if (packOk) "✅ Đã có (ngày ${OfflinePack.date}, ${OfflinePack.file(this).length() / 1_000_000} MB)" else "Chưa tải",
            note = "Tải một lần đường, tốc độ, camera, biển báo của TP.HCM mở rộng → nhanh hơn, mất mạng vẫn chạy. Tự cập nhật hằng tuần trên GitHub. Chạm để tải/cập nhật",
        ) { Updater.downloadPack(this, prefs) { build() } }
        action(
            "Gửi nhật ký chuyến đi",
            null,
            note = "Gửi các chuyến gần nhất (đã nén) qua Zalo / Drive / Gmail để kiểm tra lỗi. Nhật ký chỉ lưu trong app, tự xoá sau 7 ngày",
        ) {
            val files = TripLog.files(this).take(3)
            if (files.isEmpty()) {
                toast("Chưa có nhật ký nào")
            } else {
                val uris = ArrayList(files.map { TripLogProvider.uri(this, it) })
                val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "application/gzip"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    putExtra(Intent.EXTRA_SUBJECT, "Nhật ký MotoNav")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Gửi nhật ký"))
            }
        }
        action(
            "Cập nhật phần mềm",
            "Đang dùng bản ${Updater.currentVersion(this)}",
            Color.parseColor("#7CC4FF"),
            note = "Tải bản mới từ GitHub và cài luôn (không lưu file trong máy)",
        ) { Updater.check(this, prefs) }
        toggle("Theo dõi tốc độ & camera", null, Live.serviceRunning, note = "Chạy nền khi dùng app") { on ->
            if (on) {
                Live.userStopped = false
                DriveService.start(this)
            } else {
                Live.userStopped = true
                DriveService.stop(this)
            }
        }
        action(
            "Máy Xiaomi / Redmi / POCO",
            null,
            note = "Thông tin ứng dụng → Quyền khác → bật \"Hiển thị cửa sổ bật lên khi chạy trong nền\" để ô nổi hiện được",
        ) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        action(
            "Quyền tự bật/tắt ô nổi theo Google Maps",
            status(ForegroundApp.hasPermission(this)),
            note = "Tìm MotoNav → bật \"Cho phép truy cập dữ liệu sử dụng\"",
        ) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        action("Quyền hiện ô nổi", status(Settings.canDrawOverlays(this))) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val notif = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        action("Quyền đọc chỉ dẫn Google Maps", status(notif), note = "Android 13+: Thông tin ứng dụng → ⋮ → Cho phép cài đặt bị hạn chế") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        action("Thông tin ứng dụng", null, note = "Pin: chọn \"Không hạn chế\" để không bị tắt khi chạy nền") {
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
