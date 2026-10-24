package vn.hiep.motonav

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
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
            setPadding(dp(12f), dp(10f), dp(12f), dp(16f))
        }
        scroll.addView(list)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun rounded(color: Int, r: Float = 14f, strokeColor: Int = 0, strokeDp: Float = 0f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (strokeDp > 0f) setStroke(dp(strokeDp), strokeColor)
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

    private val panelBorder = Color.parseColor("#2A323C")

    /** Tiêu đề mục: huy hiệu biểu tượng tròn + tên, nhóm bên dưới gói trong khung viền gọn. */
    private fun header(icon: String, t: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(14f), 0, dp(5f))
        }
        row.addView(
            tv(12f).apply {
                text = icon
                gravity = Gravity.CENTER
                background = rounded(Color.parseColor("#1C2229"), 7f)
            },
            LinearLayout.LayoutParams(dp(22f), dp(22f)),
        )
        row.addView(
            tv(12f, Color.parseColor("#5DD3A6"), true).apply { text = t.uppercase() },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) },
        )
        list.addView(row)
        group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 12f, panelBorder, 1f)
            setPadding(dp(12f), dp(2f), dp(12f), dp(2f))
        }
        list.addView(group)
    }

    private fun divider() {
        val g = group ?: return
        if (g.childCount == 0) return
        g.addView(View(this).apply { setBackgroundColor(Color.parseColor("#20262D")) }, LinearLayout.LayoutParams(-1, 1))
    }

    private fun labelCol(title: String, sub: String?, note: String? = null, titleColor: Int = Color.WHITE): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val top = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(tv(14f, titleColor).apply { text = title })
        if (note != null) top.addView(infoIcon(note, title))
        addView(top)
        if (sub != null) addView(tv(11.5f, muted).apply { text = sub; setPadding(0, dp(1f), 0, 0) })
    }

    /** Hộp 2 nấc TẮT/BẬT gọn (thay cho Switch hệ thống, đỡ chiếm chỗ). */
    private fun pillToggle(value: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.parseColor("#0F1216"), 8f)
            setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
        }
        val off = tv(10.5f, muted, true).apply { text = "TẮT"; gravity = Gravity.CENTER; setPadding(dp(8f), dp(5f), dp(8f), dp(5f)) }
        val on = tv(10.5f, muted, true).apply { text = "BẬT"; gravity = Gravity.CENTER; setPadding(dp(8f), dp(5f), dp(8f), dp(5f)) }
        var cur = value
        fun paint() {
            off.background = if (!cur) rounded(Color.parseColor("#FF6B60"), 6f) else null
            off.setTextColor(if (!cur) Color.WHITE else muted)
            on.background = if (cur) rounded(accent, 6f) else null
            on.setTextColor(if (cur) Color.WHITE else muted)
        }
        paint()
        fun set(v: Boolean) {
            if (v == cur) return
            cur = v
            paint()
            onChange(v)
            Live.changed()
        }
        off.setOnClickListener { set(false) }
        on.setOnClickListener { set(true) }
        box.addView(off)
        box.addView(on)
        box.tag = Runnable { set(!cur) } // dòng cả hàng bấm được, không chỉ riêng hộp
        return box
    }

    /** Hàng có công tắc bật/tắt. */
    private fun toggle(title: String, sub: String?, value: Boolean, note: String? = null, onChange: (Boolean) -> Unit) {
        divider()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, dp(8f))
        }
        row.addView(labelCol(title, sub, note), LinearLayout.LayoutParams(0, -2, 1f))
        val pill = pillToggle(value, onChange)
        row.setOnClickListener { (pill.tag as Runnable).run() }
        row.addView(pill)
        group?.addView(row)
    }

    /** Hàng chọn một trong nhiều mức (nút bấm liền nhau). */
    private fun choice(title: String, sub: String?, options: List<String>, selected: Int, note: String? = null, onPick: (Int) -> Unit) {
        divider()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8f), 0, dp(8f))
        }
        box.addView(labelCol(title, sub, note))
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.parseColor("#0F1216"), 9f)
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        }
        val views = ArrayList<TextView>()
        options.forEachIndexed { i, o ->
            val t = tv(12.5f, Color.WHITE, true).apply {
                text = o
                gravity = Gravity.CENTER
                setPadding(dp(4f), dp(7f), dp(4f), dp(7f))
            }
            views.add(t)
            seg.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }
        fun paint(sel: Int) {
            views.forEachIndexed { i, v ->
                v.background = if (i == sel) rounded(accent, 7f) else null
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
        box.addView(seg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        group?.addView(box)
    }

    /** Hàng bấm để mở một thao tác. */
    private fun action(title: String, sub: String?, color: Int = Color.WHITE, note: String? = null, onClick: () -> Unit) {
        divider()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, dp(10f))
            setOnClickListener { onClick() }
        }
        row.addView(labelCol(title, sub, note, color), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(tv(18f, muted).apply { text = "›" })
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
        toggle("Thời tiết dọc tuyến", null, prefs.weather, note = "Biểu tượng nắng/mưa tại chỗ đổi thời tiết, theo giờ anh tới. Dữ liệu Open-Meteo.com (CC BY 4.0)") {
            prefs.weather = it
        }
        toggle("Tô màu kẹt xe trên tuyến", null, prefs.traffic, note = "Dữ liệu kẹt xe của VietMap: cam = chậm, đỏ = kẹt, đỏ sẫm = kẹt nặng") {
            prefs.traffic = it
        }
        toggle("Tránh đường cao tốc", null, prefs.avoidHighway, note = "Tuyến tìm được sẽ né đường cao tốc (motorway) khi có thể. Phà luôn được né sẵn, không cần bật. Chưa né được đường đất/chưa trải nhựa — API tìm đường không hỗ trợ") {
            prefs.avoidHighway = it
        }
        val keysOk = prefs.vietmapKey.isNotBlank()
        action("Mã API VietMap", if (keysOk) "✅ Đã nhập" else "⚠️ Chưa nhập – cần để tìm đường", note = "Dùng cho tìm đường, tìm địa điểm, kẹt xe") {
            KeyDialog.show(this, prefs) { build() }
        }
        val gKeyOk = prefs.googleDirKey.isNotBlank()
        action("Mã API Google Directions", if (gKeyOk) "✅ Đã nhập" else "Chưa nhập (tuỳ chọn)", note = "Tuỳ chọn — thử nghiệm, chưa test kỹ. Có thể thông minh hơn VietMap (né đường có chắn) nhưng cần tự tạo mã + bật thanh toán trên Google Cloud") {
            KeyDialog.showGoogle(this, prefs) { build() }
        }
        if (gKeyOk) {
            toggle("Dùng Google Directions để tìm đường", null, prefs.useGoogleDirections, note = "Thay VietMap bằng Google khi tìm/đổi tuyến. Không có dữ liệu kẹt xe tô màu khi dùng Google. Tự động dùng lại VietMap nếu Google lỗi") {
                prefs.useGoogleDirections = it
            }
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

        header("📟", "Màn TFT xe (Bluetooth)")
        toggle(
            "Đẩy dẫn đường lên màn xe",
            if (prefs.tftDevice.isNotBlank()) "Thiết bị: ${prefs.tftDeviceName.ifBlank { prefs.tftDevice }}" else "Chưa chọn thiết bị",
            prefs.tftLink,
            note = "Gửi mũi tên rẽ, khoảng cách, tên đường, giờ đến — dạng CHỮ rất nhẹ qua Bluetooth, không gửi hình ảnh nên không nghẽn/disconnect như các app khác. Thử nghiệm giai đoạn 1: mã mũi tên rẽ bên màn xe chưa được xác nhận 100%, cần chạy thử thực tế.",
        ) { on ->
            if (on && !hasBtPermission()) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT_PERM)
                toast("Cho phép quyền Bluetooth rồi bật lại")
            } else {
                prefs.tftLink = on
            }
        }
        action(
            "Chọn thiết bị màn xe",
            if (prefs.tftDevice.isNotBlank()) "Đang chọn: ${prefs.tftDeviceName.ifBlank { prefs.tftDevice }}" else "Ghép đôi (pair) với màn xe trong Cài đặt Bluetooth của máy trước, rồi chọn ở đây",
            note = "Danh sách lấy từ các thiết bị Bluetooth đã ghép đôi (pair) với điện thoại.",
        ) { pickTftDevice() }
        if (prefs.tftLink) {
            val tftStatus = when {
                TftLink.authed -> {
                    val n = TftLink.txCount
                    val txDetail = when {
                        n <= 0 && Live.ownNav && Live.offRoute -> " — chưa gửi (đang lệch tuyến, chờ bắt lại)"
                        n <= 0 -> " — chưa gửi gói dẫn đường nào (chưa dẫn đường thật/chưa có tuyến)"
                        else -> {
                            val agoS = (System.currentTimeMillis() - TftLink.lastTxAt) / 1000
                            " — đã gửi $n lần, lần cuối $agoS giây trước"
                        }
                    }
                    "✅ Đã bắt tay xong, đang gửi dữ liệu$txDetail"
                }
                TftLink.connected -> {
                    val step = TftLink.handshakeStep
                    val detail = if (TftLink.rxBytes <= 0L) {
                        " (chưa nhận được gì từ xe — 0 byte)"
                    } else {
                        " (đã nhận ${TftLink.rxBytes} byte, bước $step/3, cmd gần nhất 0x${TftLink.lastCmdSeen.toString(16)})"
                    }
                    "🔄 Đã nối Bluetooth, đang bắt tay với xe…$detail"
                }
                else -> "⚠️ Chưa kết nối" + (TftLink.lastError?.let { " – $it" } ?: "")
            }
            action(
                "Kiểm tra trạng thái kết nối",
                tftStatus,
                note = "Chạm để xem lại trạng thái mới nhất. Cần bật \"Theo dõi tốc độ & camera\" (hoặc đang dẫn đường) để app chạy nền và thử kết nối.",
            ) { build() }

            // ---- Giai đoạn 2: kênh ảnh (sketch tuyến đường thật khi đang dẫn đường + nút test) ----
            val frameStatus = when {
                !TftLink.authed -> "Cần bắt tay xong (dòng trên) trước đã"
                TftLink.lastFrameError != null && TftLink.frameTxCount == 0 -> "⚠️ ${TftLink.lastFrameError}"
                TftLink.frameTxCount > 0 -> {
                    val ackMs = TftLink.lastFrameAckMs
                    val ackTxt = if (ackMs >= 0) "ACK sau ${ackMs}ms" else "chưa có ACK lần nào"
                    "✅ Đã gửi ${TftLink.frameTxCount} khung ảnh (${TftLink.screenW}x${TftLink.screenH}) — $ackTxt" +
                        (TftLink.lastFrameError?.let { " · $it" } ?: "")
                }
                else -> "Chưa gửi khung nào — tự gửi khi đang dẫn đường thật, hoặc chạm để gửi thử 20 khung chữ"
            }
            action(
                "Kênh ảnh (giai đoạn 2) — khung bản đồ sketch",
                frameStatus,
                note = "Khi đang dẫn đường thật, app tự vẽ khung nét trắng trên nền đen (hình dạng đoạn đường + điểm rẽ sắp tới, xe luôn hướng lên) và gửi lên màn xe mỗi ~1,5 giây — tách riêng khỏi kênh chữ nên không làm chậm kênh chữ. Chạm nút này để gửi thử 20 khung chữ \"TEST #n\" (không cần đang dẫn đường) — dùng để kiểm tra riêng kênh ảnh.",
            ) {
                if (TftLink.authed) {
                    TftLink.stopTestFrames()
                    TftLink.sendTestFrames(20)
                    toast("Đang gửi 20 khung ảnh test…")
                } else {
                    toast("Chưa bắt tay xong với xe")
                }
                build()
            }
            action(
                "Test hiệu chỉnh dung lượng khung (5 loại × 10)",
                if (TftLink.calibActive) "🔄 Đang chạy — chạm để dừng giữa chừng" else "Chạm để chạy: chữ đơn giản → nhiều chữ → siêu nhẹ → đường phức tạp → bản đồ nặng",
                note = "Gửi lần lượt 5 loại khung (mỗi loại 10 khung, cách nhau 1,2s, cùng chất lượng JPEG với khung thật) từ nhẹ tới nặng, mỗi khung tự ghi dung lượng + thời gian ACK vào nhật ký chuyến đi. Gửi xong, vào \"Gửi nhật ký chuyến đi\" ở trên để lấy ra đối chiếu loại nào/dung lượng bao nhiêu thì xe bắt đầu chậm hẳn hoặc mất kết nối. Đứng yên lúc chạy để không lẫn với khung dẫn đường thật.",
            ) {
                if (TftLink.calibActive) {
                    TftLink.stopCalibration()
                    toast("Đã dừng test hiệu chỉnh")
                } else if (TftLink.authed) {
                    TftLink.runCalibration()
                    toast("Đang chạy test hiệu chỉnh (50 khung, ~1-2 phút)…")
                } else {
                    toast("Chưa bắt tay xong với xe")
                }
                build()
            }

            // ---- Chẩn đoán bước 1/3 của "bắt cặp BLE" (thử nghiệm, chưa phải tính năng chính) ----
            val ccuStatus = when {
                CcuAuth.running -> "🔄 Đang hỏi xe…"
                CcuAuth.ccuCode != null -> "✅ Mã CCU: \"${CcuAuth.ccuCode}\" · Passkey: \"${CcuAuth.passkey}\""
                CcuAuth.error != null -> "⚠️ ${CcuAuth.error}"
                else -> "Chưa thử"
            }
            action(
                "Thử nghiệm: dò mã CCU + passkey (bước 1 BLE)",
                ccuStatus,
                note = "Bước đầu để làm hiện icon dẫn đường trên màn xe (khác kênh dữ liệu ở trên) — xong sẽ tự chuyển sang bước 2+3 luôn (giống app gốc, không chờ bấm nút riêng). Chạm để dò lại.",
            ) {
                if (!hasBleScanPermission() || !hasBtPermission()) {
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), REQ_BLE_PERM)
                }
                CcuAuth.queryOnce(this, prefs.tftDevice)
                build()
            }

            // ---- Chẩn đoán bước 2+3: quét + kết nối BLE, đăng ký nhận dữ liệu, gửi hello + xác thực ----
            val bleStatus = when {
                CcuBle.running -> "🔄 ${CcuBle.stage}"
                CcuBle.error != null -> "⚠️ ${CcuBle.error}"
                CcuBle.authError != null -> "⚠️ Đăng ký ${CcuBle.subscribedCount}/${CcuBle.expectedCount} OK, nhưng bước gửi xác thực lỗi: ${CcuBle.authError}"
                CcuBle.subscribedCount > 0 -> "✅ Đăng ký ${CcuBle.subscribedCount}/${CcuBle.expectedCount} · ${CcuBle.authStage}"
                else -> "Chưa thử"
            }
            val bleNote = buildString {
                append("Cần bấm bước 1 lấy mã CCU trước. Quét BLE tìm đúng màn xe, kết nối GATT, đăng ký nhận dữ liệu rồi gửi hello + xác thực (mã CCU/passkey) — chạm để thử lại.")
                CcuBle.missingChars?.let { append("\nChưa đăng ký được: ") ; append(it) }
                CcuBle.lastNotify?.let { append("\nXe phản hồi gần nhất: ") ; append(it) }
            }
            action(
                "Thử nghiệm: quét + kết nối BLE + xác thực (bước 2+3)",
                bleStatus,
                note = bleNote,
            ) {
                if (!hasBleScanPermission() || !hasBtPermission()) {
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), REQ_BLE_PERM)
                } else {
                    CcuBle.connectOnce(this, prefs.tftDevice, CcuAuth.ccuCode, CcuAuth.passkey)
                }
                build()
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
            "Máy OPPO / Realme / OnePlus (ColorOS)",
            null,
            note = "Ngoài \"Thông tin ứng dụng → Pin → Không hạn chế\" bên dưới, ColorOS còn có mục riêng: Cài đặt → Pin → Quản lý pin ứng dụng → MotoNav → bật \"Cho phép chạy nền\"; và Cài đặt → Ứng dụng → Quản lý khởi động → bật MotoNav. Thiếu bước này app hay bị hệ điều hành tạm dừng luồng nền vài giây, đúng kiểu làm màn xe báo \"Lỗi kết nối\" dù nội dung khung ảnh không hề nặng.",
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

    private fun hasBtPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasBleScanPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_PERM && hasBtPermission()) {
            prefs.tftLink = true
            build()
        }
        if (requestCode == REQ_BLE_PERM && hasBtPermission() && hasBleScanPermission()) {
            CcuBle.connectOnce(this, prefs.tftDevice, CcuAuth.ccuCode, CcuAuth.passkey)
            build()
        }
    }

    /** Chọn trong các thiết bị Bluetooth đã ghép đôi (pair) với máy — màn TFT của xe phải pair trước. */
    private fun pickTftDevice() {
        if (!hasBtPermission()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT_PERM)
            return
        }
        try {
            val adapter = (getSystemService(BluetoothManager::class.java))?.adapter
            if (adapter == null || !adapter.isEnabled) {
                toast("Bật Bluetooth của máy trước")
                return
            }
            val devices = adapter.bondedDevices.toList()
            if (devices.isEmpty()) {
                toast("Chưa ghép đôi (pair) với thiết bị nào. Vào Cài đặt Bluetooth của máy để pair với màn xe trước.")
                return
            }
            val names = devices.map { "${it.name ?: "(không tên)"}\n${it.address}" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Chọn màn TFT xe")
                .setItems(names) { _, i ->
                    val d = devices[i]
                    prefs.tftDevice = d.address
                    prefs.tftDeviceName = d.name ?: d.address
                    toast("Đã chọn ${d.name ?: d.address}")
                    build()
                }
                .setNegativeButton("Huỷ", null)
                .show()
        } catch (_: SecurityException) {
            toast("Thiếu quyền Bluetooth")
        }
    }

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
        private const val REQ_BT_PERM = 53
        private const val REQ_BLE_PERM = 54
    }
}
