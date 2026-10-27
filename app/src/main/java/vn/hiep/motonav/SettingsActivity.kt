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
        autoUpdatePending = intent?.getBooleanExtra(EXTRA_AUTO_UPDATE, false) == true
    }

    override fun onResume() {
        super.onResume()
        build()
        fillPanel()
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

    /** Hàng "Cập nhật phần mềm" — bấm là tự kiểm tra rồi tải+cài luôn, thanh tiến độ hiện ngay dưới
     * dòng này (không mở bảng hỏi riêng), đúng kiểu NConnect. */
    private fun updateRow(autoStart: Boolean = false) {
        divider()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10f), 0, dp(10f))
        }
        val sub = tv(11.5f, muted).apply { text = "Đang dùng bản ${Updater.currentVersion(this@SettingsActivity)}" }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv(14f, Color.parseColor("#7CC4FF")).apply { text = "Cập nhật phần mềm" })
                addView(sub)
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        top.addView(tv(18f, muted).apply { text = "›" })
        box.addView(top)
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#7CC4FF"))
        }
        box.addView(bar, LinearLayout.LayoutParams(-1, dp(5f)).apply { topMargin = dp(8f) })
        group?.addView(box)

        fun setState(st: Updater.UpdateState) {
            when (st) {
                is Updater.UpdateState.Checking -> {
                    bar.visibility = View.GONE
                    sub.text = "Đang kiểm tra bản mới…"
                }
                is Updater.UpdateState.UpToDate -> {
                    bar.visibility = View.GONE
                    sub.text = "Đang dùng bản ${st.ver} — đã là mới nhất"
                }
                is Updater.UpdateState.Downloading -> {
                    bar.visibility = View.VISIBLE
                    bar.progress = st.pct
                    sub.text = "Đang tải bản mới… ${st.doneMB}/${st.totalMB} MB"
                }
                is Updater.UpdateState.Installing -> {
                    bar.visibility = View.VISIBLE
                    bar.progress = 100
                    sub.text = "Đang cài…"
                }
                is Updater.UpdateState.Error -> {
                    bar.visibility = View.GONE
                    sub.text = "Lỗi: ${st.msg}"
                }
            }
        }
        top.setOnClickListener { Updater.checkInline(this, prefs, ::setState) }
        if (autoStart) Updater.checkInline(this, prefs, ::setState)
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

    // ---------- Trang chính kiểu lưới biểu tượng theo nhóm; bấm vào hiện khung nhỏ ở giữa để chỉnh ----------

    private class Tile(
        val icon: Int,
        val label: String,
        val summary: String? = null,
        val summaryColor: Int? = null,
        val onTap: () -> Unit,
    )

    /** Các mục đã chắc chắn dùng (bật sẵn, không cần chỉnh nữa) tạm ẩn — bấm dòng cuối trang để hiện lại. */
    private var showHidden = false
    private var autoUpdatePending = false

    private var panel: android.app.Dialog? = null
    private var panelFill: (() -> Unit)? = null
    private var panelBody: LinearLayout? = null

    private val okGreen = Color.parseColor("#5DD3A6")
    private val warnOrange = Color.parseColor("#FFB454")

    private val iconAccent = Color.parseColor("#5DD3A6")
    private val iconAccent2 = Color.parseColor("#7CC4FF")

    /** Ô nền bo góc (squircle) màu accent mờ, icon nét mảnh cùng màu ở giữa. */
    private fun iconBox(icon: Int, accent: Int, sizeDp: Float, iconDp: Float): View =
        android.widget.FrameLayout(this).apply {
            background = rounded((accent and 0x00FFFFFF) or 0x24000000, sizeDp * 0.32f)
            addView(
                android.widget.ImageView(this@SettingsActivity).apply {
                    setImageResource(icon)
                    setColorFilter(accent)
                },
                android.widget.FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER),
            )
        }

    private fun tileView(t: Tile, accent: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        setPadding(dp(4f), dp(12f), dp(4f), dp(8f))
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.parseColor("#26FFFFFF")), null, rounded(Color.WHITE, 14f),
        )
        isClickable = true
        setOnClickListener { t.onTap() }
        addView(iconBox(t.icon, accent, 52f, 26f), LinearLayout.LayoutParams(dp(52f), dp(52f)))
        // Tên: luôn chiếm đúng 2 dòng (căn giữa) để dòng trạng thái các ô cùng hàng thẳng nhau.
        addView(
            tv(13f, Color.parseColor("#EEF1F4")).apply {
                text = t.label
                gravity = Gravity.CENTER
                setLines(2)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(0f, 0.95f)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) },
        )
        addView(
            tv(11.5f, t.summaryColor ?: Color.parseColor("#C2CAD3")).apply {
                text = t.summary ?: ""
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2f) },
        )
    }

    /** Một nhóm: khung bo 16dp viền mảnh, tiêu đề chữ hoa, lưới CỐ ĐỊNH 3 cột, mỗi ô cùng kích thước. */
    private fun section(title: String, tiles: List<Tile>, accent: Int = iconAccent) {
        if (tiles.isEmpty()) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(16f).toFloat()
                setStroke(1, Color.parseColor("#2E3741"))
            }
            setPadding(dp(12f), dp(14f), dp(12f), dp(10f))
        }
        box.addView(tv(11.5f, Color.parseColor("#AEB7C1"), true).apply {
            text = title.uppercase()
            letterSpacing = 0.08f
            setPadding(dp(6f), 0, dp(6f), dp(10f))
        })
        box.addView(View(this).apply { setBackgroundColor(Color.parseColor("#232A32")) }, LinearLayout.LayoutParams(-1, 1))
        val cols = 3
        val tileH = dp(138f)
        var row: LinearLayout? = null
        tiles.forEachIndexed { i, t ->
            if (i % cols == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(6f), 0, 0)
                }
                box.addView(row)
            }
            row!!.addView(tileView(t, accent), LinearLayout.LayoutParams(0, tileH, 1f).apply { setMargins(dp(4f), 0, dp(4f), 0) })
        }
        val rem = tiles.size % cols
        if (rem != 0) repeat(cols - rem) {
            row!!.addView(View(this), LinearLayout.LayoutParams(0, tileH, 1f).apply { setMargins(dp(4f), 0, dp(4f), 0) })
        }
        list.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f) })
    }

    /** Khung nhỏ nổi giữa màn hình chứa các tuỳ chỉnh của 1 mục. Bấm ra ngoài (hoặc ✕) để đóng. */
    private fun openPanel(icon: Int, title: String, fill: () -> Unit) {
        panel?.let { old -> panel = null; old.dismiss() }
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 18f, panelBorder, 1f)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6f))
        }
        top.addView(iconBox(icon, iconAccent, 36f, 20f), LinearLayout.LayoutParams(dp(36f), dp(36f)))
        top.addView(tv(16f, Color.WHITE, true).apply { text = title; setPadding(dp(12f), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(tv(18f, muted).apply {
            text = "✕"
            setPadding(dp(14f), dp(4f), dp(4f), dp(4f))
            setOnClickListener { d.dismiss() }
        })
        outer.addView(top)
        val maxH = (resources.displayMetrics.heightPixels * 0.7).toInt()
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
            }
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        d.setContentView(outer)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.setCanceledOnTouchOutside(true)
        d.setOnDismissListener {
            if (panel === d) {
                panel = null
                panelFill = null
                panelBody = null
                build()
            }
        }
        panel = d
        panelFill = fill
        panelBody = body
        fillPanel()
        d.show()
        val w = minOf((resources.displayMetrics.widthPixels * 0.92).toInt(), dp(560f))
        d.window?.setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        d.window?.setDimAmount(0.6f)
    }

    private fun fillPanel() {
        val b = panelBody ?: return
        val f = panelFill ?: return
        b.removeAllViews()
        group = b
        f()
        group = null
    }

    /** Vẽ lại trang chính (tóm tắt dưới mỗi biểu tượng) và khung đang mở (nếu có). */
    private fun refresh() {
        build()
        fillPanel()
    }

    /** Dòng chữ mờ trong khung (thông tin, không bấm). */
    private fun noteRow(text: String, color: Int = muted) {
        divider()
        group?.addView(tv(12.5f, color).apply { this.text = text; setPadding(0, dp(10f), 0, dp(10f)) })
    }

    private fun build() {
        list.removeAllViews()
        list.addView(tv(26f, Color.WHITE, true).apply { text = "Cài đặt" })

        val packOk = try { OfflinePack.open(this) } catch (_: Exception) { false }
        val usageOk = ForegroundApp.hasPermission(this)
        val overlayOk = Settings.canDrawOverlays(this)
        val notifOk = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        val permsOk = usageOk && overlayOk && notifOk
        val nPoints = CameraStore.all(this).size
        val themes = listOf("Tự động", "Sáng", "Tối")
        val sizes = listOf("Nhỏ", "Vừa", "Lớn")
        val scales = listOf(0.8f, 1f, 1.3f)

        section(
            "Dẫn đường",
            listOf(
                Tile(R.drawable.ic_set_map, "Bản đồ", "Nền ${themes.getOrElse(prefs.mapTheme) { "?" }}" + if (prefs.rotateMap) " · xoay" else "") {
                    openPanel(R.drawable.ic_set_map, "Bản đồ") { fillMap() }
                },
                Tile(R.drawable.ic_set_routing, "Định tuyến", "Xe: ${prefs.goongVehicle}") { openPanel(R.drawable.ic_set_routing, "Định tuyến") { fillRouting() } },
                Tile(R.drawable.ic_set_sound, "Âm thanh", if (prefs.voice) "Giọng đọc bật" else "Tắt giọng đọc") {
                    openPanel(R.drawable.ic_set_sound, "Âm thanh & cảnh báo") { fillSound() }
                },
                Tile(
                    R.drawable.ic_set_tft, "Màn TFT xe",
                    when {
                        !prefs.tftLink -> "Tắt"
                        TftLink.authed -> "Đã kết nối"
                        else -> "Chưa kết nối"
                    },
                    when {
                        !prefs.tftLink -> null
                        TftLink.authed -> okGreen
                        else -> warnOrange
                    },
                ) { openPanel(R.drawable.ic_set_tft, "Màn TFT xe") { fillTft() } },
            ),
        )

        section(
            "Tốc độ & cảnh báo",
            listOf(
                Tile(R.drawable.ic_set_speed, "Giới hạn tốc độ", "${if (prefs.moped) "≤50cc" else "Mô tô"} · báo +${prefs.tolerance}") {
                    openPanel(R.drawable.ic_set_speed, "Tốc độ giới hạn") { fillSpeed() }
                },
                Tile(R.drawable.ic_set_alert, "Điểm cảnh báo", "$nPoints điểm") { openPanel(R.drawable.ic_set_alert, "Camera & điểm cảnh báo") { fillAlerts() } },
                Tile(
                    R.drawable.ic_set_overlay, "Ô nổi Maps",
                    if (prefs.overlay) "Bật · ${sizes.getOrElse(scales.indexOf(prefs.overlayScale)) { "Vừa" }}" else "Tắt",
                ) { openPanel(R.drawable.ic_set_overlay, "Ô nổi trên Google Maps") { fillOverlay() } },
            ),
        )

        section(
            "Hệ thống",
            listOf(
                Tile(R.drawable.ic_set_battery, "Pin", if (prefs.batterySaver) "Tiết kiệm" else "Bình thường") { openPanel(R.drawable.ic_set_battery, "Pin") { fillBattery() } },
                Tile(
                    R.drawable.ic_set_offline, "Dữ liệu offline",
                    if (packOk) "Ngày ${OfflinePack.date}" else "Chưa tải",
                    if (packOk) null else warnOrange,
                ) { openPanel(R.drawable.ic_set_offline, "Dữ liệu ngoại tuyến") { fillOffline() } },
                Tile(
                    R.drawable.ic_set_background, "Chạy nền",
                    if (!permsOk) "Thiếu quyền" else if (Live.serviceRunning) "Đang chạy" else "Đang dừng",
                    if (!permsOk) warnOrange else null,
                ) { openPanel(R.drawable.ic_set_background, "Chạy nền & quyền") { fillBackground() } },
                Tile(R.drawable.ic_set_sendlog, "Gửi nhật ký", "3 chuyến gần nhất") { AppActions.sendTripLogs(this) },
                Tile(R.drawable.ic_set_update, "Cập nhật", "Bản ${Updater.currentVersion(this)}") { openUpdatePanel(auto = false) },
            ),
        )

        if (showHidden) {
            section(
                "Nâng cao (đã ẩn)",
                listOf(
                    Tile(R.drawable.ic_set_flask, "Thử nghiệm", if (prefs.hmmMatch) "HMM bật" else "HMM tắt") {
                        openPanel(R.drawable.ic_set_flask, "Thử nghiệm") { fillExperimental() }
                    },
                    Tile(R.drawable.ic_set_key, "Mã API", "Goong / VietMap") { openPanel(R.drawable.ic_set_key, "Mã API & VietMap") { fillKeys() } },
                    Tile(R.drawable.ic_set_camera, "Camera CSGT", OfficialCameras.status(this).let { "${it.first}/${it.second} điểm" }) {
                        openPanel(R.drawable.ic_set_camera, "Camera theo thông báo CSGT") { fillOfficialCams() }
                    },
                    Tile(R.drawable.ic_set_wrench, "Chẩn đoán TFT") { openPanel(R.drawable.ic_set_wrench, "Chẩn đoán màn TFT") { fillTftDiag() } },
                    Tile(R.drawable.ic_set_phone, "Hướng dẫn máy") { openPanel(R.drawable.ic_set_phone, "Hướng dẫn theo hãng máy") { fillDeviceGuides() } },
                ),
                iconAccent2,
            )
        }

        val pill = tv(13f, iconAccent2, true).apply {
            text = if (showHidden) "Ẩn các mục nâng cao" else "Hiện các mục nâng cao"
            gravity = Gravity.CENTER
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#337CC4FF")),
                GradientDrawable().apply {
                    setColor(Color.parseColor("#1A7CC4FF"))
                    cornerRadius = dp(999f).toFloat()
                    setStroke(dp(1f), Color.parseColor("#667CC4FF"))
                },
                null,
            )
            isClickable = true
            setOnClickListener {
                showHidden = !showHidden
                build()
            }
        }
        list.addView(pill, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(18f)
            bottomMargin = dp(10f)
        })

        if (autoUpdatePending) {
            autoUpdatePending = false
            openUpdatePanel(auto = true)
        }
    }

    private fun openUpdatePanel(auto: Boolean) {
        var first = auto
        openPanel(R.drawable.ic_set_update, "Cập nhật phần mềm") {
            updateRow(first)
            first = false
        }
    }

    // ---------- Nội dung từng khung ----------

    private fun fillMap() {
        toggle("Xoay bản đồ theo hướng đi", null, prefs.rotateMap, note = "Như Google Maps: đang chạy thì bản đồ xoay theo hướng đi, dừng/chậm thì bản đồ đứng yên, chỉ mũi tên xoay. Tắt thì luôn hướng Bắc lên trên") {
            prefs.rotateMap = it
        }
        toggle("Tự thu phóng theo tốc độ", null, prefs.autoZoom, note = "Thu nhỏ dần khi chạy trên 20, 40, 60 km/h") { prefs.autoZoom = it }
        choice("Nền bản đồ", null, listOf("Tự động", "Sáng", "Tối"), prefs.mapTheme, note = "Tự động: nền tối từ 18h đến 6h sáng") {
            prefs.mapTheme = it
        }
        toggle("Thời tiết dọc tuyến", null, prefs.weather, note = "Biểu tượng nắng/mưa tại chỗ đổi thời tiết, theo giờ anh tới. Dữ liệu Open-Meteo.com (CC BY 4.0)") {
            prefs.weather = it
        }
    }

    private fun fillRouting() {
        val vehicles = listOf("car", "bike", "taxi", "truck", "hd")
        choice(
            "Loại xe gửi cho Goong",
            null,
            vehicles,
            vehicles.indexOf(prefs.goongVehicle).coerceAtLeast(0),
            note = "Tài liệu Goong không ghi rõ giá trị nào cho xe máy. \"car\" ưu tiên đường ô tô, né hẻm nhỏ. \"bike\" gần với xe 2 bánh hơn. \"hd\" dành cho xe ôm công nghệ. Thử trên 1-2 tuyến quen, chọn cái dẫn đúng thực tế nhất",
        ) {
            prefs.goongVehicle = vehicles[it]
        }
    }

    private fun fillSound() {
        toggle("Giọng đọc", null, prefs.voice, note = "Tiếng bíp và dải chữ cảnh báo luôn bật") { prefs.voice = it }
        toggle("Đọc giới hạn khi đổi đoạn đường", null, prefs.announceLimit) { prefs.announceLimit = it }
    }

    private fun fillSpeed() {
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
    }

    private fun fillOverlay() {
        toggle("Hiện ô nổi", null, prefs.overlay, note = "Biển giới hạn, tốc độ, cảnh báo nổi trên Google Maps") { prefs.overlay = it }
        val scales = listOf(0.8f, 1f, 1.3f)
        choice("Cỡ ô nổi", null, listOf("Nhỏ", "Vừa", "Lớn"), scales.indexOf(prefs.overlayScale).let { if (it < 0) 1 else it }) {
            prefs.overlayScale = scales[it]
        }
    }

    private fun fillBattery() {
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
    }

    private fun fillAlerts() {
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
            refresh()
        }
        action("Xoá tất cả điểm tự thêm", null, Color.parseColor("#FF6B60")) {
            AlertDialog.Builder(this)
                .setMessage("Xoá tất cả $n điểm cảnh báo đã thêm?")
                .setPositiveButton("Xoá") { _, _ -> CameraStore.clear(this); refresh() }
                .setNegativeButton("Huỷ", null)
                .show()
        }
        // Tính năng "vùng tốc độ cố định" đã tạm bỏ — chỉ còn dọn dữ liệu cũ nếu máy từng lưu.
        val zp = ZonePolygons.count(this)
        val zn = WayLimits.count(this)
        if (zp > 0 || zn > 0) {
            action("Xoá dữ liệu vùng tốc độ cố định cũ (${zp + zn})", null, Color.parseColor("#FF6B60"), note = "Tính năng này đã tạm bỏ") {
                AlertDialog.Builder(this)
                    .setMessage("Xoá toàn bộ dữ liệu vùng/đoạn đường tốc độ cố định đã lưu trước đây?")
                    .setPositiveButton("Xoá") { _, _ -> ZonePolygons.clear(this); WayLimits.clear(this); refresh() }
                    .setNegativeButton("Huỷ", null)
                    .show()
            }
        }
    }

    private fun fillTft() {
        toggle(
            "Đẩy dẫn đường lên màn xe",
            if (prefs.tftDevice.isNotBlank()) "Thiết bị: ${prefs.tftDeviceName.ifBlank { prefs.tftDevice }}" else "Chưa chọn thiết bị",
            prefs.tftLink,
            note = "Gửi bản đồ dẫn đường, mũi tên rẽ, khoảng cách, giờ đến lên màn xe qua Bluetooth.",
        ) { on ->
            if (on && !hasBtPermission()) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT_PERM)
                toast("Cho phép quyền Bluetooth rồi bật lại")
            } else {
                prefs.tftLink = on
            }
            refresh()
        }
        action(
            "Chọn thiết bị màn xe",
            if (prefs.tftDevice.isNotBlank()) "Đang chọn: ${prefs.tftDeviceName.ifBlank { prefs.tftDevice }}" else "Ghép đôi (pair) với màn xe trong Cài đặt Bluetooth của máy trước, rồi chọn ở đây",
            note = "Danh sách lấy từ các thiết bị Bluetooth đã ghép đôi (pair) với điện thoại.",
        ) { pickTftDevice() }
        if (!prefs.tftLink) return
        val tftStatus = when {
            TftLink.authed -> {
                val n = TftLink.frameTxCount
                if (n > 0) "✅ Đã kết nối — đã gửi $n khung ảnh" else "✅ Đã kết nối — chờ dẫn đường để gửi khung ảnh"
            }
            TftLink.connected -> "🔄 Đã nối Bluetooth, đang bắt tay với xe (bước ${TftLink.handshakeStep}/3)…"
            else -> "⚠️ Chưa kết nối" + (TftLink.lastError?.let { " – $it" } ?: "")
        }
        action(
            "Trạng thái kết nối",
            tftStatus,
            note = "Chạm để xem lại trạng thái mới nhất. Cần bật \"Theo dõi tốc độ & camera\" (hoặc đang dẫn đường) để app chạy nền và kết nối.",
        ) { refresh() }
    }

    private fun fillOffline() {
        val packOk = try { OfflinePack.open(this) } catch (_: Exception) { false }
        action(
            "Dữ liệu ngoại tuyến TP.HCM",
            if (packOk) "✅ Đã có (ngày ${OfflinePack.date}, ${OfflinePack.file(this).length() / 1_000_000} MB) — chạm để cập nhật" else "Chưa tải — chạm để tải",
            note = "Tải một lần đường, tốc độ, camera, biển báo của TP.HCM mở rộng → nhanh hơn, mất mạng vẫn chạy. Tự cập nhật hằng tuần trên GitHub",
        ) { Updater.downloadPack(this, prefs) { refresh() } }
    }

    private fun fillBackground() {
        toggle("Theo dõi tốc độ & camera", null, Live.serviceRunning, note = "Chạy nền khi dùng app") { on ->
            if (on) {
                Live.userStopped = false
                DriveService.start(this)
            } else {
                Live.userStopped = true
                DriveService.stop(this)
            }
        }
        // Quyền đã cấp thì ẩn đi cho gọn — chỉ hiện quyền còn thiếu.
        val usageOk = ForegroundApp.hasPermission(this)
        val overlayOk = Settings.canDrawOverlays(this)
        val notifOk = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        if (usageOk && overlayOk && notifOk) {
            noteRow("✅ Đã cấp đủ quyền (tự bật/tắt ô nổi, hiện ô nổi, đọc chỉ dẫn Google Maps)", okGreen)
        }
        if (!usageOk) {
            action("Quyền tự bật/tắt ô nổi theo Google Maps", status(false), note = "Tìm MotoNav → bật \"Cho phép truy cập dữ liệu sử dụng\"") {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        if (!overlayOk) {
            action("Quyền hiện ô nổi", status(false)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        if (!notifOk) {
            action("Quyền đọc chỉ dẫn Google Maps", status(false), note = "Android 13+: Thông tin ứng dụng → ⋮ → Cho phép cài đặt bị hạn chế") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
    }

    private fun fillExperimental() {
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
    }

    private fun fillKeys() {
        val goongStatus = if (prefs.goongKey.isBlank() && prefs.goongTileKey.isBlank()) "Chưa nhập mã — đang dùng VietMap" else "✅ Đang dùng Goong (đã bỏ VietMap)"
        action(
            "Mã API Goong",
            goongStatus,
            note = "Có mã REST API là app dùng thẳng Goong định tuyến, không dự phòng VietMap. Có thêm Map Tiles Key là bản đồ nền cũng tự chuyển sang Goong",
        ) { GoongKeyDialog.show(this, prefs) { refresh() } }
        val keysStatus = when {
            prefs.vietmapKey.isBlank() -> "⚠️ Chưa nhập mã default"
            prefs.vietmapTileKey.isBlank() -> "⚠️ Thiếu mã \"public tile\""
            else -> "✅ Đã nhập đủ 2 mã"
        }
        action("Mã API VietMap", keysStatus, note = "Chỉ dùng khi KHÔNG có mã Goong. Cần 2 mã: \"default\" (tìm đường/tìm kiếm) và \"public tile\" (bản đồ nền)") {
            KeyDialog.show(this, prefs) { refresh() }
        }
        toggle("Tô màu kẹt xe trên tuyến (VietMap)", null, prefs.traffic, note = "Chỉ có tác dụng khi định tuyến bằng VietMap — Goong chưa có dữ liệu kẹt xe") {
            prefs.traffic = it
        }
        toggle("Tránh đường cao tốc (VietMap)", null, prefs.avoidHighway, note = "Chỉ có tác dụng khi định tuyến bằng VietMap — Goong chưa hỗ trợ né cao tốc") {
            prefs.avoidHighway = it
        }
    }

    private fun fillOfficialCams() {
        val (okN, totalN) = OfficialCameras.status(this)
        action(
            "Đã định vị $okN/$totalN điểm",
            Live.officialProgress,
            note = "Ngã tư do CSGT TP.HCM, Hà Nội, Đắk Lắk công bố. App tự tìm toạ độ trên OpenStreetMap. Chạm để xem nguồn và các điểm chưa định vị được",
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
            refresh()
        }
    }

    private fun fillTftDiag() {
        if (!prefs.tftLink) {
            noteRow("Bật \"Đẩy dẫn đường lên màn xe\" (mục Màn TFT xe) trước.")
            return
        }
        val frameStatus = when {
            !TftLink.authed -> "Cần bắt tay xong với xe trước"
            TftLink.lastFrameError != null && TftLink.frameTxCount == 0 -> "⚠️ ${TftLink.lastFrameError}"
            TftLink.frameTxCount > 0 -> {
                val ackMs = TftLink.lastFrameAckMs
                val ackTxt = if (ackMs >= 0) "ACK sau ${ackMs}ms" else "chưa có ACK lần nào"
                "✅ Đã gửi ${TftLink.frameTxCount} khung (${TftLink.screenW}x${TftLink.screenH}) — $ackTxt" +
                    (TftLink.lastFrameError?.let { " · $it" } ?: "")
            }
            else -> "Chưa gửi khung nào — chạm để gửi thử 20 khung chữ"
        }
        action("Kênh ảnh — gửi thử 20 khung chữ", frameStatus, note = "Gửi 20 khung \"TEST #n\" (không cần đang dẫn đường) để kiểm tra riêng kênh ảnh.") {
            if (TftLink.authed) {
                TftLink.stopTestFrames()
                TftLink.sendTestFrames(20)
                toast("Đang gửi 20 khung ảnh test…")
            } else {
                toast("Chưa bắt tay xong với xe")
            }
            refresh()
        }
        action(
            "Test 100 khung \"dạng đường\" cách nhau 500ms",
            if (TftLink.routeTestRunning) "🔄 Đang gửi — chạm để dừng giữa chừng" else "Tuyến zigzag giả, cách đều 500ms, không chờ ACK",
            note = "Kiểm tra riêng nhịp gửi nhanh cố định có gây \"Lỗi kết nối\" không. Các khung có tag \"route-test-500ms\" trong nhật ký.",
        ) {
            if (TftLink.routeTestRunning) {
                TftLink.stopRouteTestFrames()
                toast("Đã dừng test")
            } else if (TftLink.authed) {
                TftLink.sendRouteTestFrames(100, 500)
                toast("Đang gửi 100 khung dạng đường, cách nhau 500ms…")
            } else {
                toast("Chưa bắt tay xong với xe")
            }
            refresh()
        }
        action(
            "Test hiệu chỉnh dung lượng khung (5 loại × 10)",
            if (TftLink.calibActive) "🔄 Đang chạy — chạm để dừng giữa chừng" else "Chữ đơn giản → nhiều chữ → siêu nhẹ → đường phức tạp → bản đồ nặng",
            note = "Mỗi khung tự ghi dung lượng + thời gian ACK vào nhật ký. Đứng yên lúc chạy.",
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
            refresh()
        }
        val ccuStatus = when {
            CcuAuth.running -> "🔄 Đang hỏi xe…"
            CcuAuth.ccuCode != null -> "✅ Mã CCU: \"${CcuAuth.ccuCode}\" · Passkey: \"${CcuAuth.passkey}\""
            CcuAuth.error != null -> "⚠️ ${CcuAuth.error}"
            else -> "Chưa thử"
        }
        action("Thử nghiệm: dò mã CCU + passkey (bước 1 BLE)", ccuStatus, note = "Bước đầu để làm hiện icon dẫn đường trên màn xe — xong tự chuyển sang bước 2+3.") {
            if (!hasBleScanPermission() || !hasBtPermission()) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), REQ_BLE_PERM)
            }
            CcuAuth.queryOnce(this, prefs.tftDevice)
            refresh()
        }
        val bleStatus = when {
            CcuBle.running -> "🔄 ${CcuBle.stage}"
            CcuBle.error != null -> "⚠️ ${CcuBle.error}"
            CcuBle.authError != null -> "⚠️ Đăng ký ${CcuBle.subscribedCount}/${CcuBle.expectedCount} OK, nhưng bước gửi xác thực lỗi: ${CcuBle.authError}"
            CcuBle.subscribedCount > 0 -> "✅ Đăng ký ${CcuBle.subscribedCount}/${CcuBle.expectedCount} · ${CcuBle.authStage}"
            else -> "Chưa thử"
        }
        val bleNote = buildString {
            append("Cần bấm bước 1 lấy mã CCU trước. Quét BLE tìm đúng màn xe, kết nối GATT, đăng ký nhận dữ liệu rồi gửi hello + xác thực.")
            CcuBle.missingChars?.let { append("\nChưa đăng ký được: "); append(it) }
            CcuBle.lastNotify?.let { append("\nXe phản hồi gần nhất: "); append(it) }
        }
        action("Thử nghiệm: quét + kết nối BLE + xác thực (bước 2+3)", bleStatus, note = bleNote) {
            if (!hasBleScanPermission() || !hasBtPermission()) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), REQ_BLE_PERM)
            } else {
                CcuBle.connectOnce(this, prefs.tftDevice, CcuAuth.ccuCode, CcuAuth.passkey)
            }
            refresh()
        }
    }

    private fun fillDeviceGuides() {
        action("Máy Xiaomi / Redmi / POCO", null, note = "Thông tin ứng dụng → Quyền khác → bật \"Hiển thị cửa sổ bật lên khi chạy trong nền\" để ô nổi hiện được") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        action(
            "Máy OPPO / Realme / OnePlus (ColorOS)",
            null,
            note = "Cài đặt → Pin → Quản lý pin ứng dụng → MotoNav → bật \"Cho phép chạy nền\"; và Cài đặt → Ứng dụng → Quản lý khởi động → bật MotoNav. Thiếu bước này app hay bị tạm dừng luồng nền vài giây, làm màn xe báo \"Lỗi kết nối\".",
        ) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
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
            refresh()
        }
        if (requestCode == REQ_BLE_PERM && hasBtPermission() && hasBleScanPermission()) {
            CcuBle.connectOnce(this, prefs.tftDevice, CcuAuth.ccuCode, CcuAuth.passkey)
            refresh()
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
                    refresh()
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
        refresh()
    }

    companion object {
        const val EXTRA_AUTO_UPDATE = "autoUpdate"
        private const val REQ_IMPORT = 51
        private const val REQ_EXPORT = 52
        private const val REQ_BT_PERM = 53
        private const val REQ_BLE_PERM = 54
    }
}
