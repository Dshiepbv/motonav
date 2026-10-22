package vn.hiep.motonav

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val bg = Color.parseColor("#0B0D10")
    private val card = Color.parseColor("#161A20")
    private val muted = Color.parseColor("#9AA3AE")
    private val danger = Color.parseColor("#FF3B30")
    private val warn = Color.parseColor("#FF9500")

    private lateinit var prefs: Prefs

    private lateinit var navCard: LinearLayout
    private lateinit var navArrow: ImageView
    private lateinit var navDist: TextView
    private lateinit var navInstr: TextView
    private lateinit var navExtra: TextView
    private lateinit var navHint: TextView

    private lateinit var speedCard: LinearLayout
    private lateinit var sign: SpeedSignView
    private lateinit var signLabel: TextView
    private lateinit var speedText: TextView
    private lateinit var camBox: LinearLayout
    private lateinit var camTitle: TextView
    private lateinit var camDist: TextView
    private lateinit var streetText: TextView
    private lateinit var statusText: TextView
    private lateinit var permBar: TextView
    private lateinit var toggleBtn: Button

    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        Live.appVisible = true
        Live.addListener(listener)
        ensurePermissionsAndStart()
        render()
    }

    override fun onPause() {
        Live.appVisible = false
        Live.changed()
        Live.removeListener(listener)
        super.onPause()
    }

    // ---------- Giao diện ----------

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun rounded(color: Int, radiusDp: Float = 18f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun label(sizeSp: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun buildUi(): View {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val pad = dp(12f)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(pad, pad, pad, pad)
        }

        permBar = label(15f, Color.BLACK, true).apply {
            background = rounded(warn, 12f)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            visibility = View.GONE
            setOnClickListener { askOverlayPermission() }
        }
        root.addView(permBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8f) })

        val content = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        // --- Thẻ dẫn đường ---
        navCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(card)
            setPadding(pad, pad, pad, pad)
        }
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        navArrow = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        navRow.addView(navArrow, LinearLayout.LayoutParams(dp(130f), dp(130f)))
        navDist = label(52f, Color.WHITE, true).apply { setPadding(dp(14f), 0, 0, 0) }
        navRow.addView(navDist, LinearLayout.LayoutParams(-2, -2))
        navCard.addView(navRow, LinearLayout.LayoutParams(-2, -2))
        navInstr = label(24f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            maxLines = 3
        }
        navCard.addView(navInstr, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        navExtra = label(16f, muted).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        }
        navCard.addView(navExtra, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        navHint = label(17f, muted).apply {
            gravity = Gravity.CENTER
            text = "Bấm \"Google Maps\" để dẫn đường.\nBiển giới hạn, tốc độ và đếm ngược camera sẽ nổi ngay trên bản đồ."
        }
        navCard.addView(navHint, LinearLayout.LayoutParams(-1, -2))

        // --- Thẻ tốc độ ---
        speedCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(card)
            setPadding(pad, pad, pad, pad)
        }
        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val signCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        sign = SpeedSignView(this)
        signCol.addView(sign, LinearLayout.LayoutParams(dp(150f), dp(150f)))
        signLabel = label(14f, muted).apply { gravity = Gravity.CENTER }
        signCol.addView(signLabel, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4f) })
        speedRow.addView(signCol, LinearLayout.LayoutParams(-2, -2))

        val speedCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24f), 0, 0, 0)
        }
        speedText = label(92f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        speedCol.addView(speedText, LinearLayout.LayoutParams(-2, -2))
        speedCol.addView(label(18f, muted).apply { text = "km/h" }, LinearLayout.LayoutParams(-2, -2))
        speedRow.addView(speedCol, LinearLayout.LayoutParams(-2, -2))
        speedCard.addView(speedRow, LinearLayout.LayoutParams(-2, -2))

        camBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(danger, 14f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            visibility = View.GONE
        }
        camTitle = label(22f, Color.WHITE, true).apply { maxLines = 2 }
        camBox.addView(camTitle, LinearLayout.LayoutParams(0, -2, 1f))
        camDist = label(40f, Color.WHITE, true).apply { includeFontPadding = false }
        camBox.addView(camDist, LinearLayout.LayoutParams(-2, -2))
        speedCard.addView(camBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })

        streetText = label(18f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        }
        speedCard.addView(streetText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })

        if (landscape) {
            content.addView(navCard, LinearLayout.LayoutParams(0, -1, 1.2f).apply { rightMargin = dp(10f) })
            content.addView(speedCard, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            content.addView(navCard, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(10f) })
            content.addView(speedCard, LinearLayout.LayoutParams(-1, 0, 1.1f))
        }

        statusText = label(13f, muted).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        }
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })

        // --- Hàng nút ---
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = rounded(Color.parseColor("#252B33"), 12f)
            setOnClickListener { onClick() }
        }
        val lp = { LinearLayout.LayoutParams(0, dp(52f), 1f).apply { setMargins(dp(4f), dp(6f), dp(4f), 0) } }
        bar.addView(btn("Google Maps") { openMaps() }, lp())
        bar.addView(btn("+ Camera") { addCamera() }, lp())
        bar.addView(btn("Cài đặt") { showSettings() }, lp())
        toggleBtn = btn("Dừng") { toggleService() }
        bar.addView(toggleBtn, lp())
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        return root
    }

    private fun render() {
        if (!::sign.isInitialized) return

        // Dẫn đường
        if (Live.navActive) {
            navHint.visibility = View.GONE
            navArrow.visibility = View.VISIBLE
            navArrow.setImageBitmap(Live.navArrow)
            navDist.text = Live.navDistance ?: ""
            navInstr.text = Live.navInstruction ?: ""
            navExtra.text = Live.navExtra ?: ""
            navInstr.visibility = if (Live.navInstruction.isNullOrBlank()) View.GONE else View.VISIBLE
            navExtra.visibility = if (Live.navExtra.isNullOrBlank()) View.GONE else View.VISIBLE
        } else {
            navHint.visibility = View.VISIBLE
            navArrow.visibility = View.GONE
            navDist.text = ""
            navInstr.visibility = View.GONE
            navExtra.visibility = View.GONE
        }

        // Tốc độ
        sign.limit = Live.limit
        sign.estimated = Live.limitEstimated
        signLabel.text = when {
            Live.limit == null -> "Chưa rõ giới hạn"
            Live.limitEstimated -> "Ước tính theo luật"
            else -> "Theo biển báo"
        }
        speedText.text = if (Live.gpsOk && Live.speedKmh >= 0) Live.speedKmh.toString() else "--"
        speedText.setTextColor(if (Live.overspeed) danger else Color.WHITE)
        speedCard.background = rounded(if (Live.overspeed) Color.parseColor("#3A1214") else card)

        // Camera
        val cam = Live.camText
        if (cam != null) {
            camBox.visibility = View.VISIBLE
            camBox.background = rounded(if (Live.camKind == CamKind.SPEED) danger else Color.parseColor("#E65100"), 14f)
            camTitle.text = "⚠ $cam"
            camDist.text = "${Live.camDistance} m"
        } else {
            camBox.visibility = View.GONE
        }

        streetText.text = Live.street ?: ""
        statusText.text = when {
            !Live.serviceRunning -> "Đã dừng theo dõi"
            !Live.gpsOk -> Live.status.ifBlank { "Đang chờ GPS…" }
            else -> Live.status
        }
        toggleBtn.text = if (Live.serviceRunning) "Dừng" else "Chạy"

        // Quyền hiện ô nổi trên Google Maps
        if (prefs.overlay && !Settings.canDrawOverlays(this)) {
            permBar.visibility = View.VISIBLE
            permBar.text = "Chưa cho phép hiện ô tốc độ nổi trên Google Maps. Chạm để cấp."
        } else {
            permBar.visibility = View.GONE
        }
    }

    // ---------- Quyền ----------

    private fun hasLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationAccess(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains(packageName)
    }

    private var askedOnce = false

    private fun ensurePermissionsAndStart() {
        if (!hasLocation()) {
            if (askedOnce) return
            askedOnce = true
            val perms = ArrayList<String>()
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(perms.toTypedArray(), 1)
            return
        }
        if (!Live.serviceRunning && !Live.userStopped) DriveService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasLocation()) {
            DriveService.start(this)
            if (prefs.overlay && !Settings.canDrawOverlays(this)) askOverlayPermission()
        } else {
            Toast.makeText(this, "MotoNav cần quyền vị trí để đo tốc độ.", Toast.LENGTH_LONG).show()
        }
    }

    private fun askOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle("Hiện tốc độ nổi trên Google Maps")
            .setMessage(
                "MotoNav sẽ hiện một ô nhỏ (biển giới hạn, tốc độ, đếm ngược camera) đè lên bản đồ Google Maps.\n\n" +
                    "Bấm \"Cho phép\", tìm MotoNav và bật \"Cho phép hiển thị trên ứng dụng khác\".",
            )
            .setPositiveButton("Cho phép") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Để sau", null)
            .show()
    }

    private fun showPermissionHelp() {
        AlertDialog.Builder(this)
            .setTitle("Cho phép đọc chỉ dẫn Google Maps")
            .setMessage(
                "Để hiện mũi tên rẽ, MotoNav cần đọc thông báo dẫn đường của Google Maps.\n\n" +
                    "1. Bấm \"Mở cài đặt\", tìm MotoNav và bật lên.\n\n" +
                    "2. Nếu công tắc bị mờ hoặc báo \"Cài đặt bị hạn chế\" (Android 13 trở lên): " +
                    "bấm \"Thông tin ứng dụng\" → nút ⋮ góc trên phải → \"Cho phép cài đặt bị hạn chế\", " +
                    "rồi làm lại bước 1.",
            )
            .setPositiveButton("Mở cài đặt") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNeutralButton("Thông tin ứng dụng") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                )
            }
            .setNegativeButton("Để sau", null)
            .show()
    }

    // ---------- Nút ----------

    private fun toggleService() {
        if (Live.serviceRunning) {
            Live.userStopped = true
            DriveService.stop(this)
        } else {
            Live.userStopped = false
            if (hasLocation()) DriveService.start(this) else {
                askedOnce = false
                ensurePermissionsAndStart()
            }
        }
    }

    private fun openMaps() {
        if (prefs.overlay && !Settings.canDrawOverlays(this)) {
            askOverlayPermission()
            return
        }
        if (!Live.serviceRunning) {
            Live.userStopped = false
            if (hasLocation()) DriveService.start(this)
        }
        val i = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
        if (i != null) startActivity(i) else Toast.makeText(this, "Chưa cài Google Maps", Toast.LENGTH_SHORT).show()
    }

    private fun addCamera() {
        if (!Live.gpsOk) {
            Toast.makeText(this, "Chưa có GPS, chờ một chút rồi thử lại.", Toast.LENGTH_SHORT).show()
            return
        }
        val lat = Live.lat
        val lon = Live.lon
        val hdg = Live.heading
        val kinds = arrayOf("Camera phạt nguội", "Camera tốc độ", "Camera đèn đỏ")
        val codes = intArrayOf(CamKind.FINE, CamKind.SPEED, CamKind.RED_LIGHT)
        AlertDialog.Builder(this)
            .setTitle("Đánh dấu camera tại vị trí này")
            .setItems(kinds) { _, which ->
                CameraStore.add(this, lat, lon, codes[which], hdg)
                val note = if (hdg >= 0) "chỉ cảnh báo theo hướng anh đang đi" else "cảnh báo cả hai chiều"
                Toast.makeText(this, "Đã lưu ${kinds[which].lowercase()} ($note).", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun showSettings() {
        val areaNames = arrayOf("Tự động", "Luôn trong khu dân cư", "Luôn ngoài khu dân cư")
        val camCount = CameraStore.all(this).size
        val items = arrayOf(
            "Loại xe: " + if (prefs.moped) "Xe gắn máy (≤ 50cc, tối đa 40)" else "Xe mô tô (trên 50cc)",
            "Khu vực: " + areaNames[prefs.areaMode],
            "Ước tính khi thiếu biển: " + if (prefs.estimate) "Bật" else "Tắt",
            "Báo vượt tốc khi quá: +${prefs.tolerance} km/h",
            "Giọng nói: " + if (prefs.voice) "Bật" else "Tắt",
            "Đọc giới hạn khi đổi đoạn đường: " + if (prefs.announceLimit) "Bật" else "Tắt",
            "Xoá camera tự thêm gần nhất (đang có $camCount)",
            "Xoá tất cả camera tự thêm",
            "Hướng dẫn cấp quyền đọc Google Maps",
            "Nhập danh sách camera từ file (KML, CSV, JSON)",
            "Xuất danh sách camera ra file CSV",
            "Ô nổi trên Google Maps: " + if (prefs.overlay) "Bật" else "Tắt",
            "Cỡ ô nổi: " + when (prefs.overlayScale) {
                0.8f -> "Nhỏ"
                1.3f -> "Lớn"
                else -> "Vừa"
            },
        )
        AlertDialog.Builder(this)
            .setTitle("Cài đặt")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> prefs.moped = !prefs.moped
                    1 -> prefs.areaMode = (prefs.areaMode + 1) % 3
                    2 -> prefs.estimate = !prefs.estimate
                    3 -> prefs.tolerance = when (prefs.tolerance) {
                        0 -> 3
                        3 -> 5
                        5 -> 10
                        else -> 0
                    }
                    4 -> prefs.voice = !prefs.voice
                    5 -> prefs.announceLimit = !prefs.announceLimit
                    6 -> {
                        val ok = Live.gpsOk && CameraStore.removeNearest(this, Live.lat, Live.lon)
                        Toast.makeText(
                            this,
                            if (ok) "Đã xoá camera gần nhất." else "Không có camera tự thêm nào trong 300 m.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    7 -> {
                        AlertDialog.Builder(this)
                            .setMessage("Xoá tất cả $camCount camera anh đã tự thêm?")
                            .setPositiveButton("Xoá") { _, _ -> CameraStore.clear(this) }
                            .setNegativeButton("Huỷ", null)
                            .show()
                        return@setItems
                    }
                    8 -> {
                        showPermissionHelp()
                        return@setItems
                    }
                    9 -> {
                        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*")
                        startActivityForResult(i, REQ_IMPORT)
                        return@setItems
                    }
                    10 -> {
                        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("text/csv")
                            .putExtra(Intent.EXTRA_TITLE, "camera-motonav.csv")
                        startActivityForResult(i, REQ_EXPORT)
                        return@setItems
                    }
                }
                when (which) {
                    11 -> prefs.overlay = !prefs.overlay
                    12 -> prefs.overlayScale = when (prefs.overlayScale) {
                        0.8f -> 1f
                        1f -> 1.3f
                        else -> 0.8f
                    }
                }
                Live.changed()
                if (which <= 5 || which >= 11) showSettings()
            }
            .setNegativeButton("Đóng", null)
            .show()
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
                    val msg = if (items.isEmpty()) "Không tìm thấy điểm camera nào trong file."
                    else "Đã nhập $added camera mới (đọc được ${items.size} điểm, bỏ qua điểm trùng)."
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
                REQ_EXPORT -> {
                    val cams = CameraStore.all(this)
                    contentResolver.openOutputStream(uri)?.use { it.write(CameraIO.toCsv(cams).toByteArray(Charsets.UTF_8)) }
                    Toast.makeText(this, "Đã xuất ${cams.size} camera.", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi đọc/ghi file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQ_IMPORT = 41
        private const val REQ_EXPORT = 42
    }
}
