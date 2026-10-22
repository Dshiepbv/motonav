package vn.hiep.motonav

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Dẫn đường trên bản đồ riêng của MotoNav (bản đồ VietMap, nghiêng 3D theo hướng đi),
 * có nhãn tên đường lớn ngay tại chỗ rẽ.
 */
class NavActivity : Activity() {

    private val bg = Color.parseColor("#0B0D10")
    private val green = Color.parseColor("#0B6E4F")
    private val danger = Color.parseColor("#FF3B30")
    private val muted = Color.parseColor("#9AA3AE")

    private lateinit var prefs: Prefs
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var shownRouteVersion = -1
    private var follow = true
    private var labelFont: Array<String>? = null
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    // giao diện
    private lateinit var turnCard: LinearLayout
    private lateinit var turnArrow: ImageView
    private lateinit var turnDist: TextView
    private lateinit var turnText: TextView
    private lateinit var thenText: TextView
    private lateinit var alertBanner: TextView
    private lateinit var searchBox: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var results: LinearLayout
    private lateinit var sign: SpeedSignView
    private lateinit var speedText: TextView
    private lateinit var camBox: TextView
    private lateinit var etaText: TextView
    private lateinit var statusText: TextView
    private lateinit var endBtn: Button
    private lateinit var recenterBtn: Button

    private val listener: () -> Unit = { render() }
    private var styleError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        MapLibre.getInstance(this)
        MapHttp.key = prefs.vietmapTileKey.ifBlank { prefs.vietmapKey }
        MapHttp.install()
        try { MapHttp.captureLogs() } catch (_: Exception) {}
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        setContentView(buildUi())
        applyImmersive()

        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isRotateGesturesEnabled = true
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    follow = false
                    recenterBtn.visibility = View.VISIBLE
                    btnRow.visibility = View.VISIBLE
                    // Không chạm thêm 8 giây → tự quay lại bám theo xe
                    ui.removeCallbacks(autoFollow)
                    ui.postDelayed(autoFollow, 8000)
                }
            }
            m.addOnCameraMoveListener { positionCar() }
            m.addOnMapLongClickListener { p ->
                confirmDestination(p.latitude, p.longitude, "Điểm đã chọn trên bản đồ")
                true
            }
            loadStyle()
        }
        mapView.addOnDidFailLoadingMapListener { msg ->
            if (ofmTried == OFM_DARK) {
                // Kiểu tối không tải được → quay về kiểu sáng
                ofmTried = OFM_STYLE
                style = null
                map?.setStyle(Style.Builder().fromUri(OFM_STYLE)) { s ->
                    style = s
                    shownRouteVersion = -1
                    shownJunction = -1
                    labelFont = findFont(s)
                    addLayers(s)
                    render()
                }
                return@addOnDidFailLoadingMapListener
            }
            styleError = "Không tải được kiểu bản đồ VietMap: $msg. Kiểm tra mã Tilemap trong Cài đặt."
            render()
        }

        ensureReady()
        handleIntent(intent)
    }

    // ---------- Giao diện ----------

    /** Hệ số phóng giao diện theo cỡ màn hình (điện thoại nhỏ ↔ máy gập/máy tính bảng). */
    private var uiScale = 1f
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v * uiScale, resources.displayMetrics).toInt()

    private fun rounded(color: Int, r: Float = 16f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun tv(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size * uiScale)
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private var landscape = false
    private var panelWidthDp = 300f
    private lateinit var bottomBar: LinearLayout
    private lateinit var speedBox: LinearLayout

    private lateinit var carView: CarMarkerView
    private var carLat = 0.0
    private var carLon = 0.0
    private var carHdg = 0.0
    private var carShown = false

    /** Đặt mũi tên xe theo vị trí trên màn hình của toạ độ xe. */
    private fun positionCar() {
        val m = map ?: return
        if (!::carView.isInitialized) return
        if (!carShown || !Live.gpsOk) { carView.visibility = View.INVISIBLE; return }
        val p = try { m.projection.toScreenLocation(LatLng(carLat, carLon)) } catch (_: Exception) { return }
        val cp = m.cameraPosition
        carView.visibility = View.VISIBLE
        carView.place(p.x, p.y, (carHdg - cp.bearing).toFloat(), cp.tilt.toFloat())
    }

    private fun makeViews() {
        carView = CarMarkerView(this, carIcon()).apply { visibility = View.INVISIBLE }
        turnCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(green)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            visibility = View.GONE
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        turnArrow = ImageView(this)
        val ls = landscape
        row.addView(turnArrow, LinearLayout.LayoutParams(dp(if (ls) 52f else 66f), dp(if (ls) 52f else 66f)))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), 0, 0, 0)
        }
        turnDist = tv(if (ls) 26f else 32f, Color.WHITE, true)
        col.addView(turnDist)
        turnText = tv(if (ls) 18f else 21f, Color.WHITE, true).apply { maxLines = 2 }
        col.addView(turnText)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        turnCard.addView(row)
        thenText = tv(if (ls) 15f else 17f, Color.parseColor("#E3F6EE"), true).apply {
            visibility = View.GONE
            maxLines = 2
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(8f), 0, 0)
        }
        turnCard.addView(thenText)

        alertBanner = tv(18f, Color.BLACK, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            visibility = View.GONE
        }

        searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(238, 22, 26, 32))
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
        searchInput = EditText(this).apply {
            hint = "🔍  Bạn muốn đi đâu?"
            setHintTextColor(muted)
            setTextColor(Color.WHITE)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        searchBox.addView(searchInput, LinearLayout.LayoutParams(-1, -2))
        // Nhà · Công ty · Yêu thích
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6f), 0, 0)
        }
        fun chip(label: String, onClick: () -> Unit, onLong: () -> Unit) = TextView(this).apply {
            text = label
            contentDescription = when (label) { "🏠" -> "Nhà"; "💼" -> "Công ty"; else -> "Yêu thích" }
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = rounded(Color.parseColor("#2C343E"), 20f)
            setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
            setOnClickListener { onClick() }
            setOnLongClickListener { onLong(); true }
        }
        val clp = { LinearLayout.LayoutParams(dp(60f), -2).apply { setMargins(0, 0, dp(8f), 0) } }
        chips.addView(chip("🏠", { goSaved("home") }, { setPlaceDialog("home") }), clp())
        chips.addView(chip("💼", { goSaved("work") }, { setPlaceDialog("work") }), clp())
        chips.addView(chip("⭐", { favoritesDialog() }, { favoritesDialog() }), clp())
        searchBox.addView(chips, LinearLayout.LayoutParams(-1, -2))
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sc = ScrollView(this).apply { addView(results) }
        searchBox.addView(sc, LinearLayout.LayoutParams(-1, -2))
        setupSearch()

        speedBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(Color.argb(220, 11, 13, 16), 14f)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        }
        sign = SpeedSignView(this)
        speedBox.addView(sign, LinearLayout.LayoutParams(dp(if (ls) 58f else 70f), dp(if (ls) 58f else 70f)))
        speedText = tv(if (ls) 26f else 30f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        speedBox.addView(speedText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4f) })
        speedBox.addView(tv(11f, muted).apply { text = "km/h" })
        camBox = tv(14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
            visibility = View.GONE
        }
        speedBox.addView(camBox, LinearLayout.LayoutParams(dp(90f), -2).apply { topMargin = dp(6f) })
        camStreetBadge = tv(12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            text = "📷 Tuyến có\ncamera phạt nguội"
            background = rounded(Color.parseColor("#B23C00"), 8f)
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            visibility = View.GONE
        }
        speedBox.addView(camStreetBadge, LinearLayout.LayoutParams(dp(90f), -2).apply { topMargin = dp(6f) })
        // Biển tốc độ sắp đổi + đếm ngược (hiện khi còn ≤ 200 m)
        nextBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(Color.parseColor("#2C343E"), 10f)
            setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
            visibility = View.GONE
        }
        nextBox.addView(tv(11f, muted).apply { text = "Sắp tới" })
        nextSign = SpeedSignView(this)
        nextBox.addView(nextSign, LinearLayout.LayoutParams(dp(46f), dp(46f)).apply { topMargin = dp(2f) })
        nextDist = tv(16f, Color.parseColor("#FFD60A"), true).apply { gravity = Gravity.CENTER }
        nextBox.addView(nextDist)
        speedBox.addView(nextBox, LinearLayout.LayoutParams(dp(90f), -2).apply { topMargin = dp(6f) })
        // Biển báo phía trước (cấm rẽ, cấm dừng đỗ…) kèm số mét
        signsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        speedBox.addView(signsBox, LinearLayout.LayoutParams(dp(90f), -2).apply { topMargin = dp(4f) })

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(10f))
        }
        choicesPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(8f))
        }
        bottomBar.addView(choicesPanel, LinearLayout.LayoutParams(-1, -2))
        etaText = tv(if (ls) 18f else 20f, Color.WHITE, true)
        bottomBar.addView(etaText)
        statusText = tv(13f, muted).apply { maxLines = if (ls) 2 else 3 }
        bottomBar.addView(statusText)
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, color: Int, onClick: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = rounded(color, 12f)
            setOnClickListener { onClick() }
        }
        val lp = { LinearLayout.LayoutParams(0, dp(48f), 1f).apply { setMargins(dp(4f), dp(6f), dp(4f), 0) } }
        recenterBtn = btn("◎ Về vị trí", Color.parseColor("#1565C0")) {
            follow = true
            recenterBtn.visibility = View.GONE
            render()
        }
        recenterBtn.visibility = View.GONE
        btns.addView(recenterBtn, lp())
        endBtn = btn("Kết thúc", danger) {
            DriveService.stopNav(this)
            follow = true
        }
        btns.addView(endBtn, lp())
        altBtn = btn("↺ Tuyến khác", Color.parseColor("#2C343E")) {
            DriveService.send(this, DriveService.ACTION_NAV_ALTS)
        }
        btns.addView(altBtn, 1, lp())
        bottomBar.addView(btns)
        btnRow = btns

        // Nút ☰ (menu) góc trên trái
        menuBtn = TextView(this).apply {
            text = "☰"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 22, 26, 32))
                setStroke(dp(1f), Color.parseColor("#3A424C"))
            }
            elevation = dp(4f).toFloat()
            setOnClickListener { showMenu() }
        }

        // Nút tam giác vàng nhạt báo cảnh báo, góc dưới phải (kiểu Waze)
        urbanSign = UrbanSignView(this).apply {
            elevation = dp(4f).toFloat()
            visibility = View.GONE
        }
        reportBtn = WarnButton(this).apply {
            elevation = dp(6f).toFloat()
            setOnClickListener { AppActions.markAlert(this@NavActivity) }
        }
    }

    private lateinit var menuBtn: TextView
    private lateinit var choicesPanel: LinearLayout
    private lateinit var altBtn: Button
    private var shownChoices = -1

    /** Bảng chọn tuyến: mỗi dòng một tuyến (thời gian, quãng đường), chạm để đổi. */
    private fun renderChoices() {
        if (shownChoices == Live.choicesVersion) return
        shownChoices = Live.choicesVersion
        val list = Live.routeChoices
        choicesPanel.removeAllViews()
        if (list.size < 2 || !Live.ownNav) {
            choicesPanel.visibility = View.GONE
            return
        }
        choicesPanel.visibility = View.VISIBLE
        choicesPanel.addView(tv(14f, muted).apply { text = "Chọn tuyến (chạm để đổi):" })
        val fastest = list.minOf { it.timeMs }
        list.forEachIndexed { i, r ->
            val sel = i == Live.choiceSelected
            val mins = (r.timeMs / 60000).coerceAtLeast(1)
            val extra = if (r.timeMs == fastest) " · nhanh nhất" else " · +${((r.timeMs - fastest) / 60000).coerceAtLeast(1)} phút"
            val row = tv(17f, Color.WHITE, sel).apply {
                text = (if (sel) "● " else "○ ") + "Tuyến ${i + 1}: $mins phút · ${NavEngine.fmtDist(r.distance)}$extra"
                background = rounded(if (sel) green else Color.parseColor("#2C343E"), 10f)
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                setOnClickListener { DriveService.send(this@NavActivity, DriveService.ACTION_NAV_CHOOSE, i) }
            }
            choicesPanel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        }
        choicesPanel.addView(tv(16f, Color.WHITE, true).apply {
            text = "✓ Đi tuyến đã chọn"
            gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#1565C0"), 10f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setOnClickListener { DriveService.send(this@NavActivity, DriveService.ACTION_NAV_CHOICES_DONE) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
    }
    private lateinit var nextBox: LinearLayout
    private lateinit var signsBox: LinearLayout
    private var shownSigns: List<Triple<Int, Int, Int>>? = null
    private lateinit var nextSign: SpeedSignView
    private lateinit var nextDist: TextView
    private lateinit var reportBtn: WarnButton
    private lateinit var urbanSign: UrbanSignView
    private lateinit var camStreetBadge: TextView
    private lateinit var btnRow: LinearLayout

    // ---------- Nhà / Công ty / Yêu thích ----------

    /** Khi khác null: chọn kết quả tìm kiếm sẽ lưu thành "home" / "work" / "fav" thay vì dẫn đường. */
    private var saveTarget: String? = null

    private fun label(kind: String) = when (kind) {
        "home" -> "Nhà"
        "work" -> "Công ty"
        else -> "Yêu thích"
    }

    private fun goSaved(kind: String) {
        val p = if (kind == "home") Places.home(this) else Places.work(this)
        if (p == null) setPlaceDialog(kind) else startNavTo(p.lat, p.lon, label(kind))
    }

    private fun setPlaceDialog(kind: String) {
        val cur = when (kind) {
            "home" -> Places.home(this)
            "work" -> Places.work(this)
            else -> null
        }
        val items = ArrayList<String>()
        items.add("📍  Dùng vị trí hiện tại")
        items.add("🔍  Tìm địa chỉ…")
        if (cur != null) items.add("🗑  Xoá ${label(kind)}")
        AlertDialog.Builder(this)
            .setTitle(if (cur == null) "Lưu ${label(kind)}" else "${label(kind)}: ${cur.name}")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        if (!Live.gpsOk) {
                            toast("Chưa có GPS")
                        } else if (kind == "fav") {
                            askName { n -> savePlace(kind, SavedPlace(n, Live.lat, Live.lon)) }
                        } else {
                            savePlace(kind, SavedPlace(label(kind), Live.lat, Live.lon))
                        }
                    }
                    1 -> {
                        saveTarget = kind
                        searchInput.hint = "🔍  Tìm địa chỉ để lưu làm ${label(kind)}…"
                        searchInput.requestFocus()
                        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(searchInput, 0)
                    }
                    2 -> {
                        if (kind == "home") Places.setHome(this, null) else Places.setWork(this, null)
                        toast("Đã xoá ${label(kind)}")
                    }
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun askName(done: (String) -> Unit) {
        val input = EditText(this).apply { hint = "Tên địa điểm (vd: Quán cà phê quen)" }
        AlertDialog.Builder(this)
            .setTitle("Đặt tên")
            .setView(input)
            .setPositiveButton("Lưu") { _, _ -> done(input.text.toString().ifBlank { "Địa điểm yêu thích" }) }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun savePlace(kind: String, p: SavedPlace) {
        when (kind) {
            "home" -> Places.setHome(this, p)
            "work" -> Places.setWork(this, p)
            else -> Places.addFavorite(this, p)
        }
        saveTarget = null
        searchInput.hint = "🔍  Bạn muốn đi đâu?"
        searchInput.setText("")
        results.removeAllViews()
        hideKeyboard()
        toast("Đã lưu ${label(kind)}: ${p.name}")
    }

    private fun favoritesDialog() {
        val favs = Places.favorites(this)
        val items = ArrayList<String>()
        favs.forEach { items.add("⭐  ${it.name}") }
        items.add("➕  Thêm vị trí hiện tại")
        items.add("➕  Thêm bằng tìm kiếm")
        if (favs.isNotEmpty()) items.add("🗑  Xoá một mục…")
        AlertDialog.Builder(this)
            .setTitle("Yêu thích")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which < favs.size -> startNavTo(favs[which].lat, favs[which].lon, favs[which].name)
                    which == favs.size -> {
                        if (!Live.gpsOk) toast("Chưa có GPS")
                        else askName { n -> savePlace("fav", SavedPlace(n, Live.lat, Live.lon)) }
                    }
                    which == favs.size + 1 -> {
                        saveTarget = "fav"
                        searchInput.hint = "🔍  Tìm địa chỉ để thêm vào Yêu thích…"
                        searchInput.requestFocus()
                        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(searchInput, 0)
                    }
                    else -> {
                        AlertDialog.Builder(this)
                            .setTitle("Chọn mục cần xoá")
                            .setItems(favs.map { it.name }.toTypedArray()) { _, i ->
                                Places.removeFavorite(this, i)
                                toast("Đã xoá ${favs[i].name}")
                            }
                            .setNegativeButton("Huỷ", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    /** Ghi chú sự cố ngay lúc đang chạy: lưu kèm thời gian + vị trí vào nhật ký chuyến đi. */
    private fun noteDialog() {
        val kinds = arrayOf(
            "Biển tốc độ sai / mất biển",
            "Bản đồ giật, nháy, xoay lung tung",
            "Tìm đường sai / bắt quay đầu",
            "Báo lệch tuyến sai",
            "Cảnh báo sai (camera, biển báo)",
            "Tên đường 3D sai / lộn xộn",
            "Khác (gõ chữ)",
        )
        AlertDialog.Builder(this)
            .setTitle("Ghi chú sự cố")
            .setItems(kinds) { _, i ->
                if (i == kinds.size - 1) {
                    val ed = EditText(this).apply { hint = "Mô tả ngắn…" }
                    AlertDialog.Builder(this).setTitle("Ghi chú").setView(ed)
                        .setPositiveButton("Lưu") { _, _ -> saveNote(ed.text.toString()) }
                        .setNegativeButton("Huỷ", null).show()
                } else {
                    saveNote(kinds[i])
                }
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun saveNote(text: String) {
        TripLog.ev("note", "text" to text, "lat" to Live.lat, "lon" to Live.lon, "spd" to Live.speedKmh,
            "limit" to Live.limit, "street" to Live.street, "nav" to Live.ownNav, "off" to Live.offRoute)
        toast("Đã ghi vào nhật ký: $text")
    }

    private fun showMenu() {
        val items = arrayOf(
            "📝   Ghi chú sự cố (vào nhật ký)",
            "⚙   Cài đặt",
            "🗺   Mở Google Maps",
            "⏱   Màn hình tốc độ lớn",
            if (Live.serviceRunning) "⏸   Tạm dừng theo dõi tốc độ" else "▶   Bật theo dõi tốc độ",
        )
        AlertDialog.Builder(this)
            .setTitle("MotoNav")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> noteDialog()
                    1 -> startActivity(Intent(this, SettingsActivity::class.java))
                    2 -> AppActions.openGoogleMaps(this, prefs)
                    3 -> startActivity(Intent(this, MainActivity::class.java))
                    4 -> if (Live.serviceRunning) {
                        Live.userStopped = true
                        DriveService.stop(this)
                    } else {
                        Live.userStopped = false
                        DriveService.start(this)
                    }
                }
            }
            .show()
    }

    private fun buildUi(): View {
        // Bố cục theo tỉ lệ màn hình thật (16:9, 21:9, màn vuông máy gập, chia đôi màn hình…)
        val cfg = resources.configuration
        val wDp = cfg.screenWidthDp.toFloat().coerceAtLeast(1f)
        val hDp = cfg.screenHeightDp.toFloat().coerceAtLeast(1f)
        landscape = wDp >= hDp * 1.2f // gần vuông (máy gập mở) dùng bố cục dọc
        uiScale = (cfg.smallestScreenWidthDp / 392f).coerceIn(0.85f, 1.3f)
        panelWidthDp = (wDp * 0.34f).coerceIn(260f, 420f)
        makeViews()
        val root = FrameLayout(this)
        root.setBackgroundColor(bg)

        if (!landscape) {
            // ---- Màn hình dọc: thẻ rẽ trên, bản đồ giữa, thanh dưới ----
            root.addView(mapView, FrameLayout.LayoutParams(-1, -1))
            root.addView(carView, FrameLayout.LayoutParams(dp(60f), dp(60f)))
            // Hàng trên: nút ☰ + (thẻ rẽ / ô tìm kiếm)
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(10f), dp(10f), dp(10f), 0)
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            top.addView(turnCard, LinearLayout.LayoutParams(-1, -2))
            top.addView(alertBanner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
            top.addView(searchBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
            topRow.addView(top, LinearLayout.LayoutParams(0, -2, 1f))
            topRow.addView(menuBtn, LinearLayout.LayoutParams(dp(50f), dp(50f)).apply { leftMargin = dp(8f) })
            root.addView(topRow, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

            bottomBar.background = rounded(Color.argb(242, 22, 26, 32), 0f)
            root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

            // Ô biển giới hạn + tốc độ: sát cạnh trái, ở khoảng 1/3 màn hình từ trên xuống
            root.addView(speedBox, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(10f) })
            root.post {
                val h = root.height
                val want = (h / 3) - speedBox.height / 2
                val minTop = topRow.bottom + dp(8f)
                (speedBox.layoutParams as FrameLayout.LayoutParams).topMargin = maxOf(want, minTop)
                speedBox.requestLayout()
            }

            // Nút "!" góc dưới phải, nằm trên thanh dưới
            root.addView(reportBtn, FrameLayout.LayoutParams(dp(68f), dp(62f), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(14f) })
            // Biển khu dân cư ngay trên nút tam giác
            root.addView(urbanSign, FrameLayout.LayoutParams(dp(62f), dp(62f), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(17f) })
            bottomBar.addOnLayoutChangeListener { _, _, top2, _, bottom2, _, _, _, _ ->
                val lp = reportBtn.layoutParams as FrameLayout.LayoutParams
                val m = (bottom2 - top2) + dp(14f)
                if (lp.bottomMargin != m) {
                    lp.bottomMargin = m
                    reportBtn.requestLayout()
                    val up = urbanSign.layoutParams as FrameLayout.LayoutParams
                    up.bottomMargin = m + dp(62f) + dp(10f)
                    urbanSign.requestLayout()
                }
            }
        } else {
            // ---- Màn hình ngang: bảng thông tin trái, bản đồ phải ----
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#12161B"))
                setPadding(dp(10f), dp(10f), dp(10f), dp(6f))
            }
            // Tất cả xếp dọc trong một cột cuộn được: thẻ rẽ trên, giờ đến + nút dưới cùng.
            // Không bao giờ chồng lên nhau; màn thấp quá thì cuộn.
            val scroll = ScrollView(this).apply { isFillViewport = true }
            val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            inner.addView(turnCard, LinearLayout.LayoutParams(-1, -2))
            inner.addView(alertBanner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
            inner.addView(searchBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
            inner.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
            bottomBar.setPadding(dp(2f), dp(8f), dp(2f), dp(4f))
            inner.addView(bottomBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
            scroll.addView(inner, FrameLayout.LayoutParams(-1, -1))
            panel.addView(scroll, LinearLayout.LayoutParams(-1, -1))
            // Bảng trái rộng cố định theo màn hình (≈1/3, 260–420 dp), bản đồ lấy hết phần còn lại
            row.addView(panel, LinearLayout.LayoutParams(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, panelWidthDp, resources.displayMetrics).toInt(), -1))

            val mapBox = FrameLayout(this)
            mapBox.addView(mapView, FrameLayout.LayoutParams(-1, -1))
            mapBox.addView(carView, FrameLayout.LayoutParams(dp(60f), dp(60f)))
            mapBox.addView(
                speedBox,
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                    leftMargin = dp(8f); topMargin = dp(8f)
                },
            )
            mapBox.addView(
                menuBtn,
                FrameLayout.LayoutParams(dp(50f), dp(50f), Gravity.TOP or Gravity.END).apply {
                    rightMargin = dp(12f); topMargin = dp(12f)
                },
            )
            mapBox.addView(
                reportBtn,
                FrameLayout.LayoutParams(dp(64f), dp(58f), Gravity.BOTTOM or Gravity.END).apply {
                    rightMargin = dp(14f); bottomMargin = dp(14f)
                },
            )
            mapBox.addView(
                urbanSign,
                FrameLayout.LayoutParams(dp(58f), dp(58f), Gravity.BOTTOM or Gravity.END).apply {
                    rightMargin = dp(17f); bottomMargin = dp(14f + 58f + 10f)
                },
            )
            row.addView(mapBox, LinearLayout.LayoutParams(0, -1, 1f))
            root.addView(row, FrameLayout.LayoutParams(-1, -1))
        }
        return root
    }

    // ---------- Tìm kiếm ----------

    private var searchSeq = 0
    private val searchRunnable = Runnable { doSearch(searchInput.text.toString()) }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                ui.removeCallbacks(searchRunnable)
                if ((s?.length ?: 0) >= 3) ui.postDelayed(searchRunnable, 700) else results.removeAllViews()
            }
        })
        searchInput.setOnEditorActionListener { _, _, _ ->
            ui.removeCallbacks(searchRunnable)
            doSearch(searchInput.text.toString())
            true
        }
    }

    private fun doSearch(q: String) {
        val text = q.trim()
        if (text.length < 2) return
        val key = prefs.vietmapKey
        if (key.isBlank()) {
            askKey()
            return
        }
        val seq = ++searchSeq
        val lat = if (Live.gpsOk) Live.lat else null
        val lon = if (Live.gpsOk) Live.lon else null
        io.execute {
            val res = try {
                VietMap.autocomplete(key, text, lat, lon)
            } catch (e: Exception) {
                ui.post { toast(e.message ?: "Lỗi tìm kiếm") }
                return@execute
            }
            ui.post { if (seq == searchSeq) showResults(res) }
        }
    }

    private fun showResults(list: List<Suggestion>) {
        results.removeAllViews()
        if (list.isEmpty()) {
            results.addView(tv(15f, muted).apply { text = "Không tìm thấy kết quả" })
            return
        }
        for (s in list.take(8)) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8f), 0, dp(8f))
                setOnClickListener { pickSuggestion(s) }
            }
            item.addView(tv(17f, Color.WHITE, true).apply { text = s.name })
            item.addView(tv(13f, muted).apply { text = s.address; maxLines = 2 })
            results.addView(item)
        }
    }

    private fun pickSuggestion(s: Suggestion) {
        hideKeyboard()
        val key = prefs.vietmapKey
        io.execute {
            try {
                val p = VietMap.place(key, s.refId)
                ui.post {
                    val target = saveTarget
                    if (target != null) {
                        savePlace(target, SavedPlace(s.name, p.lat, p.lon))
                    } else {
                        startNavTo(p.lat, p.lon, s.name)
                    }
                }
            } catch (e: Exception) {
                ui.post { toast(e.message ?: "Lỗi lấy toạ độ") }
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
    }

    // ---------- Điểm đến từ Google Maps / link / geo: ----------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val shared: String? = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                i.getStringExtra(Intent.EXTRA_SUBJECT),
                i.getStringExtra(Intent.EXTRA_TEXT),
            ).joinToString("\n").ifBlank { null }
            Intent.ACTION_VIEW -> i.dataString
            else -> null
        }
        if (shared.isNullOrBlank()) return
        i.action = null // tránh xử lý lại khi xoay màn hình
        statusText.text = "Đang đọc địa điểm từ Google Maps…"
        io.execute {
            val (res, query) = try {
                GoogleLink.resolve(shared)
            } catch (_: Exception) {
                Pair(null, GoogleLink.nameFromText(shared))
            }
            ui.post {
                if (res != null) {
                    startNavTo(res.lat, res.lon, res.name)
                } else if (!query.isNullOrBlank()) {
                    searchInput.setText(query)
                    doSearch(query)
                    toast("Link không có toạ độ, chọn địa điểm trong danh sách")
                } else {
                    toast("Không đọc được địa điểm từ nội dung chia sẻ")
                }
            }
        }
    }

    private fun confirmDestination(lat: Double, lon: Double, name: String) {
        AlertDialog.Builder(this)
            .setMessage("Dẫn đường tới điểm này?")
            .setPositiveButton("Đi") { _, _ -> startNavTo(lat, lon, name) }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun startNavTo(lat: Double, lon: Double, name: String?) {
        if (prefs.vietmapKey.isBlank()) {
            askKey { startNavTo(lat, lon, name) }
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(basePerms(), 7)
            toast("Cho phép vị trí rồi chọn lại điểm đến")
            return
        }
        Live.userStopped = false
        results.removeAllViews()
        searchInput.setText("")
        hideKeyboard()
        follow = true
        shownRouteVersion = -1
        DriveService.startNav(this, lat, lon, name)
    }

    // ---------- Mã API ----------

    private fun ensureReady() {
        if (prefs.vietmapKey.isBlank()) askKey()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (!Live.serviceRunning) {
                Live.userStopped = false
                DriveService.start(this)
            }
        } else {
            requestPermissions(basePerms(), 7)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            DriveService.start(this)
        }
    }

    private fun basePerms(): Array<String> {
        val l = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l.toTypedArray()
    }

    private fun askKey(after: (() -> Unit)? = null) {
        KeyDialog.show(this, prefs) {
            loadStyle()
            after?.invoke()
        }
    }

    // ---------- Bản đồ ----------

    private fun isNight(): Boolean {
        when (prefs.mapTheme) {
            1 -> return false
            2 -> return true
        }
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 18 || h < 6
    }

    private var loadedStyleId: String? = null

    private fun loadStyle() {
        val m = map ?: return
        val night = isNight()
        val useVm = false // Bản đồ nền luôn dùng OpenFreeMap (VietMap chỉ dùng cho tìm đường)
        val key = prefs.vietmapTileKey.ifBlank { prefs.vietmapKey }
        loadedStyleId = "${prefs.mapSource}/$key/$night/${prefs.vietmapStyleUrl}"
        styleError = null
        MapHttp.reset()
        val onLoaded = { s: Style ->
            if (night) try { brightenDark(s) } catch (_: Exception) {}
            style = s
            shownRouteVersion = -1
            shownJunction = -1
            labelFont = findFont(s)
            addLayers(s)
            render()
        }
        if (!useVm || key.isBlank()) {
            // OpenFreeMap: bản đồ OpenStreetMap miễn phí, không cần mã
            val ofm = if (night) OFM_DARK else OFM_STYLE
            ofmTried = ofm
            style = null
            m.setStyle(Style.Builder().fromUri(ofm)) { s -> onLoaded(s) }
            return
        }
        MapHttp.key = key
        val custom = prefs.vietmapStyleUrl
        val lat = if (Live.gpsOk) Live.lat else 10.7769
        val lon = if (Live.gpsOk) Live.lon else 106.7009
        io.execute {
            val res = try {
                VietMap.findWorkingStyle(key, night, custom, lat, lon)
            } catch (e: Exception) {
                ui.post {
                    styleError = (e.message ?: "Không tải được bản đồ VietMap") + " → đang dùng OpenFreeMap"
                    m.setStyle(Style.Builder().fromUri(OFM_STYLE)) { s -> onLoaded(s) }
                }
                return@execute
            }
            ui.post {
                vmDiag = "VietMap: " + res.url.substringAfter("vietmap.vn").substringBefore("?") + " · " + res.log
                m.setStyle(Style.Builder().fromJson(res.json)) { s -> onLoaded(s) }
            }
        }
    }

    private var vmDiag: String? = null
    private var ofmTried: String? = null

    /** Lấy font chữ có sẵn trong kiểu bản đồ VietMap để nhãn tên đường hiển thị được. */
    /** Nền tối: làm sáng đường và chữ tên đường để dễ nhìn ban đêm. */
    private fun brightenDark(s: Style) {
        // Nhà khối 3D cho nền tối (giống nền sáng)
        try {
            if (s.getSource("openmaptiles") != null && s.getLayer("mn-building-3d") == null) {
                val ext = org.maplibre.android.style.layers.FillExtrusionLayer("mn-building-3d", "openmaptiles").apply {
                    sourceLayer = "building"
                    minZoom = 14f
                    setProperties(
                        PropertyFactory.fillExtrusionColor("#4C5A6B"),
                        PropertyFactory.fillExtrusionHeight(
                            Expression.coalesce(Expression.toNumber(Expression.get("render_height")), Expression.literal(8f)),
                        ),
                        PropertyFactory.fillExtrusionBase(
                            Expression.coalesce(Expression.toNumber(Expression.get("render_min_height")), Expression.literal(0f)),
                        ),
                        PropertyFactory.fillExtrusionOpacity(0.85f),
                    )
                }
                s.addLayer(ext)
            }
        } catch (_: Exception) {
        }
        for (l in s.layers) {
            val id = l.id.lowercase()
            when (l) {
                is org.maplibre.android.style.layers.BackgroundLayer ->
                    l.setProperties(PropertyFactory.backgroundColor("#1C2530"))
                is LineLayer -> {
                    val sl = (l.sourceLayer ?: "").lowercase()
                    if (sl == "transportation" || id.contains("road") || id.contains("highway") || id.contains("street")) {
                        val major = id.contains("motorway") || id.contains("trunk") || id.contains("primary")
                        val color = when {
                            id.contains("casing") -> "#10161D"
                            major -> "#D9A85B"
                            id.contains("secondary") || id.contains("tertiary") -> "#9FB0C2"
                            else -> "#6F8296"
                        }
                        l.setProperties(PropertyFactory.lineColor(color))
                    }
                }
                is org.maplibre.android.style.layers.FillLayer -> {
                    val sl = (l.sourceLayer ?: "").lowercase()
                    if (sl == "water") l.setProperties(PropertyFactory.fillColor("#24435E"))
                    if (sl == "building") l.setProperties(PropertyFactory.fillColor("#2A3440"))
                }
                is SymbolLayer -> {
                    if (id.startsWith("mn-")) continue
                    val sl = (l.sourceLayer ?: "").lowercase()
                    val roadName = sl == "transportation_name"
                    l.setProperties(
                        PropertyFactory.textColor(if (roadName) "#FFFFFF" else "#C9D3DD"),
                        PropertyFactory.textHaloColor("#05080B"),
                        PropertyFactory.textHaloWidth(if (roadName) 2.2f else 1.6f),
                    )
                }
            }
        }
    }

    private fun findFont(s: Style): Array<String>? {
        for (l in s.layers) {
            if (l is SymbolLayer) {
                val f = try { l.textFont?.value } catch (_: Exception) { null }
                if (f != null && f.isNotEmpty()) return f
            }
        }
        return null
    }

    companion object {
        private const val OFM_STYLE = "https://tiles.openfreemap.org/styles/liberty"
        private const val OFM_DARK = "https://tiles.openfreemap.org/styles/fiord"
    }

    private val emptyFc = """{"type":"FeatureCollection","features":[]}"""

    /** Tiết kiệm pin: giới hạn 15 khung hình/giây, ẩn nhà khối 3D. */
    private fun applySaver() {
        val saver = prefs.batterySaver
        try { mapView.setMaximumFps(if (saver) 15 else 60) } catch (_: Throwable) {}
        try {
            val st = style
            if (st != null && st.isFullyLoaded) {
                for (l in st.layers) {
                    if (l is org.maplibre.android.style.layers.FillExtrusionLayer && l.id != "mn-text3d-layer") {
                        l.setProperties(PropertyFactory.visibility(if (saver) Property.NONE else Property.VISIBLE))
                    }
                }
            }
        } catch (_: Exception) {}
        if (saver) compass.stop()
    }

    private fun addLayers(s: Style) {
        lastJson.clear()
        s.addSource(GeoJsonSource("mn-route", emptyFc))
        s.addSource(GeoJsonSource("mn-me", emptyFc))
        s.addSource(GeoJsonSource("mn-turn", emptyFc))
        s.addSource(GeoJsonSource("mn-dest", emptyFc))
        s.addSource(GeoJsonSource("mn-next", emptyFc))
        s.addSource(GeoJsonSource("mn-junction", emptyFc))

        // Các tuyến thay thế (xám, nằm dưới tuyến chính)
        s.addSource(GeoJsonSource("mn-choices", emptyFc))
        s.addLayer(
            LineLayer("mn-choices-line", "mn-choices").withProperties(
                PropertyFactory.lineColor("#8A96A3"),
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        shownChoicesMap = -1
        s.addLayer(
            LineLayer("mn-route-casing", "mn-route").withProperties(
                PropertyFactory.lineColor("#0D47A1"),
                PropertyFactory.lineWidth(13f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        s.addLayer(
            LineLayer("mn-route-line", "mn-route").withProperties(
                // Màu theo kẹt xe: xanh = thông thoáng/không rõ, cam = chậm, đỏ = kẹt, đỏ sẫm = kẹt nặng
                PropertyFactory.lineColor(
                    Expression.match(
                        Expression.coalesce(Expression.get("c"), Expression.literal("unknown")),
                        Expression.color(Color.parseColor("#38A3FF")),
                        Expression.stop("moderate", Expression.color(Color.parseColor("#FF9F0A"))),
                        Expression.stop("heavy", Expression.color(Color.parseColor("#FF3B30"))),
                        Expression.stop("severe", Expression.color(Color.parseColor("#8E0000"))),
                    ),
                ),
                PropertyFactory.lineWidth(8f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        // Đoạn đường sắp rẽ vào: tô vàng nổi bật
        s.addLayer(
            LineLayer("mn-next-casing", "mn-next").withProperties(
                PropertyFactory.lineColor("#5C4500"),
                PropertyFactory.lineWidth(15f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        s.addLayer(
            LineLayer("mn-next-line", "mn-next").withProperties(
                PropertyFactory.lineColor("#FFD60A"),
                PropertyFactory.lineWidth(9f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        s.addLayer(
            CircleLayer("mn-dest-dot", "mn-dest").withProperties(
                PropertyFactory.circleRadius(10f),
                PropertyFactory.circleColor("#E53935"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
        s.addLayer(
            CircleLayer("mn-turn-dot", "mn-turn").withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor("#FFD60A"),
                PropertyFactory.circleStrokeColor("#0B6E4F"),
                PropertyFactory.circleStrokeWidth(4f),
            ),
        )
        // Nhãn tên đường lớn dựng ngay tại chỗ rẽ
        val label = SymbolLayer("mn-turn-label", "mn-turn").withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textSize(24f),
            PropertyFactory.textColor("#FFFFFF"),
            PropertyFactory.textHaloColor("#0B6E4F"),
            PropertyFactory.textHaloWidth(4f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_BOTTOM),
            PropertyFactory.textOffset(arrayOf(0f, -1.2f)),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
            PropertyFactory.textPitchAlignment(Property.TEXT_PITCH_ALIGNMENT_VIEWPORT),
            PropertyFactory.textMaxWidth(12f),
        )
        labelFont?.let { label.setProperties(PropertyFactory.textFont(it)) }
        s.addLayer(label)

        // Tên các ngã rẽ phía trước: chữ dựng đứng (luôn thẳng như biển), đường sắp rẽ vào to + vàng
        val isTurn = Expression.eq(Expression.get("turn"), Expression.literal(true))
        val jl = SymbolLayer("mn-junction-label", "mn-junction").withProperties(
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textSize(Expression.switchCase(isTurn, Expression.literal(23f), Expression.literal(16f))),
            PropertyFactory.textColor(
                Expression.switchCase(isTurn, Expression.color(Color.parseColor("#FFD60A")), Expression.color(Color.WHITE)),
            ),
            PropertyFactory.textHaloColor(
                Expression.switchCase(isTurn, Expression.color(Color.parseColor("#0B3D2E")), Expression.color(Color.parseColor("#1B2027"))),
            ),
            PropertyFactory.textHaloWidth(4f),
            PropertyFactory.textHaloBlur(0.5f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_BOTTOM),
            PropertyFactory.textPitchAlignment(Property.TEXT_PITCH_ALIGNMENT_VIEWPORT),
            PropertyFactory.textRotationAlignment(Property.TEXT_ROTATION_ALIGNMENT_VIEWPORT),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
            PropertyFactory.textMaxWidth(9f),
            PropertyFactory.symbolSortKey(Expression.switchCase(isTurn, Expression.literal(1f), Expression.literal(0f))),
        )
        labelFont?.let { jl.setProperties(PropertyFactory.textFont(it)) }
        s.addLayer(jl)
        s.addLayer(
            CircleLayer("mn-junction-dot", "mn-junction").withProperties(
                PropertyFactory.circleRadius(Expression.switchCase(isTurn, Expression.literal(6f), Expression.literal(4f))),
                PropertyFactory.circleColor(
                    Expression.switchCase(isTurn, Expression.color(Color.parseColor("#FFD60A")), Expression.color(Color.WHITE)),
                ),
                PropertyFactory.circleStrokeColor("#1B2027"),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
        // Biểu tượng cảnh báo cắm trên bản đồ (camera, ổ gà, CSGT, đường sắt, trường học, chợ)
        for (k in 0..8) s.addImage("alert-$k", alertIcon(k))
        s.addSource(GeoJsonSource("mn-alerts", Live.alertsJson ?: emptyFc))
        s.addLayer(
            SymbolLayer("mn-alerts-icon", "mn-alerts").withProperties(
                PropertyFactory.iconImage(Expression.concat(Expression.literal("alert-"), Expression.toString(Expression.get("k")))),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
            ),
        )
        // Tên đường chữ khối 3D đứng tại ngã rẽ
        s.addSource(GeoJsonSource("mn-text3d", emptyFc))
        val isTurn3d = Expression.eq(Expression.get("k"), Expression.literal(1))
        s.addLayer(
            org.maplibre.android.style.layers.FillExtrusionLayer("mn-text3d-layer", "mn-text3d").withProperties(
                PropertyFactory.fillExtrusionColor(
                    Expression.switchCase(isTurn3d, Expression.color(Color.parseColor("#FFE58A")), Expression.color(Color.parseColor("#F4F4F0"))),
                ),
                PropertyFactory.fillExtrusionBase(Expression.toNumber(Expression.get("b"))),
                PropertyFactory.fillExtrusionHeight(Expression.toNumber(Expression.get("h"))),
                PropertyFactory.fillExtrusionOpacity(0.95f),
            ),
        )
        shownText3d = -1

        // Thời tiết dọc tuyến: bong bóng nắng/mưa đặt cạnh tuyến
        s.addSource(GeoJsonSource("mn-wx", emptyFc))
        s.addLayer(
            SymbolLayer("mn-wx-icon", "mn-wx").withProperties(
                PropertyFactory.iconImage(Expression.get("img")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM_LEFT),
                PropertyFactory.iconOffset(arrayOf(10f, -4f)),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
            ),
        )
        shownWx = -1

        // Biển báo phía trước cắm trên tuyến (cấm dừng đỗ, cấm rẽ…) kèm số mét
        s.addSource(GeoJsonSource("mn-signs", emptyFc))
        val sl = SymbolLayer("mn-signs-icon", "mn-signs").withProperties(
            PropertyFactory.iconImage(Expression.get("img")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
            PropertyFactory.textField(Expression.get("d")),
            PropertyFactory.textSize(13f),
            PropertyFactory.textColor("#FFFFFF"),
            PropertyFactory.textHaloColor("#111111"),
            PropertyFactory.textHaloWidth(3f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
            PropertyFactory.textPitchAlignment(Property.TEXT_PITCH_ALIGNMENT_VIEWPORT),
        )
        labelFont?.let { sl.setProperties(PropertyFactory.textFont(it)) }
        s.addLayer(sl)
        shownSignsMap = -1

        // Biển tốc độ sắp đổi, cắm ngay tại điểm đổi
        s.addSource(GeoJsonSource("mn-limit", emptyFc))
        val lim = SymbolLayer("mn-limit-sign", "mn-limit").withProperties(
            PropertyFactory.iconImage(Expression.get("img")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
            PropertyFactory.textField(Expression.get("d")),
            PropertyFactory.textSize(15f),
            PropertyFactory.textColor("#FFD60A"),
            PropertyFactory.textHaloColor("#111111"),
            PropertyFactory.textHaloWidth(3f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
            PropertyFactory.textPitchAlignment(Property.TEXT_PITCH_ALIGNMENT_VIEWPORT),
        )
        labelFont?.let { lim.setProperties(PropertyFactory.textFont(it)) }
        s.addLayer(lim)
        shownAlerts = Live.alertsVersion

        // Mũi tên xe: vẽ bằng CarMarkerView (không nháy)
        ui.post { applySaver() }
    }

    private var shownAlerts = -1
    private var shownThenSign = -999
    private var shownSignsMap = -1
    private var shownWx = -1
    private var shownText3d = -1

    /** Bong bóng thời tiết: biểu tượng + nhiệt độ hoặc % mưa. */
    private fun wxIcon(cat: Int, night: Boolean, label: String): android.graphics.Bitmap {
        val w = dp(74f)
        val h = dp(38f)
        val b = android.graphics.Bitmap.createBitmap(w, h + dp(6f), android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = when {
            cat >= WxCat.RAIN -> Color.parseColor("#1565C0")
            cat == WxCat.CHANCE -> Color.parseColor("#4F7FB0")
            else -> Color.parseColor("#F5F7FA")
        }
        c.drawRoundRect(android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, p)
        // đuôi nhỏ chỉ về tuyến
        val tail = android.graphics.Path().apply {
            moveTo(dp(8f).toFloat(), h.toFloat() - 2); lineTo(dp(2f).toFloat(), (h + dp(6f)).toFloat()); lineTo(dp(18f).toFloat(), h.toFloat() - 2); close()
        }
        c.drawPath(tail, p)
        val t = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = h * 0.55f }
        c.drawText(WxCat.emoji(cat, night), dp(6f).toFloat(), h * 0.7f, t)
        t.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        t.textSize = h * 0.42f
        t.color = if (cat >= WxCat.CHANCE) Color.WHITE else Color.parseColor("#1B2027")
        c.drawText(label, dp(36f).toFloat(), h * 0.66f, t)
        return b
    }
    private var shownChoicesMap = -1

    /** Biểu tượng cảnh báo: vòng tròn trắng viền màu, có hình bên trong, chân nhọn. */
    private fun alertIcon(kind: Int): android.graphics.Bitmap {
        val w = dp(40f)
        val h = dp(48f)
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val col = Color.parseColor(CamKind.color(kind))
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val cx = w / 2f
        val r = w / 2f - dp(2f)
        // chân nhọn
        p.color = col
        val tail = android.graphics.Path().apply {
            moveTo(cx - r * 0.45f, r * 1.6f); lineTo(cx, h.toFloat()); lineTo(cx + r * 0.45f, r * 1.6f); close()
        }
        c.drawPath(tail, p)
        c.drawCircle(cx, r + dp(2f), r, p)
        p.color = Color.WHITE
        c.drawCircle(cx, r + dp(2f), r * 0.78f, p)
        val t = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = r * 1.0f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        c.drawText(CamKind.emoji(kind), cx, r + dp(2f) + t.textSize * 0.36f, t)
        return b
    }

    /** Biển tốc độ (vòng đỏ, số đen) dùng cắm trên bản đồ. */
    private fun limitIcon(v: Int): android.graphics.Bitmap {
        val s = dp(44f)
        val b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val r = s / 2f - dp(1f)
        p.color = Color.parseColor("#E53935")
        c.drawCircle(s / 2f, s / 2f, r, p)
        p.color = Color.WHITE
        c.drawCircle(s / 2f, s / 2f, r * 0.76f, p)
        p.color = Color.BLACK
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textAlign = android.graphics.Paint.Align.CENTER
        p.textSize = if (v >= 100) r * 0.75f else r * 0.95f
        c.drawText(v.toString(), s / 2f, s / 2f + p.textSize * 0.35f, p)
        return b
    }

    /** Mũi tên xe màu xanh viền trắng. */
    private fun carIcon(): android.graphics.Bitmap {
        val size = dp(46f)
        val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val f = size.toFloat()
        val path = android.graphics.Path().apply {
            moveTo(f / 2, f * 0.08f)
            lineTo(f * 0.86f, f * 0.9f)
            lineTo(f / 2, f * 0.7f)
            lineTo(f * 0.14f, f * 0.9f)
            close()
        }
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E88E5") }
        val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = f * 0.07f
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        c.drawPath(path, fill)
        c.drawPath(path, stroke)
        return b
    }

    private fun point(lat: Double, lon: Double, label: String? = null): String {
        val props = if (label != null) """{"label":${JSONObject.quote(label)}}""" else "{}"
        return """{"type":"Feature","properties":$props,"geometry":{"type":"Point","coordinates":[$lon,$lat]}}"""
    }

    private fun src(id: String): GeoJsonSource? = try {
        val s = style
        if (s != null && s.isFullyLoaded) s.getSource(id) as? GeoJsonSource else null
    } catch (_: Exception) {
        null
    }

    /** Chỉ nạp lại dữ liệu khi nội dung đổi – nạp lại liên tục làm biểu tượng/chữ trên bản đồ nháy. */
    private val lastJson = HashMap<String, String>()
    private fun setOnce(id: String, js: String) {
        if (lastJson[id] == js) return
        val s = src(id) ?: return
        lastJson[id] = js
        s.setGeoJson(js)
    }

    private fun updateMap() {
        val m = map ?: return
        if (style == null) return
        val nav = Live.ownNav
        val r = Live.route

        if (nav && r != null) {
            if (shownRouteVersion != Live.routeVersion) {
                shownRouteVersion = Live.routeVersion
                src("mn-route")?.setGeoJson(if (prefs.traffic) r.toCongestionGeoJson() else r.toGeoJsonLine())
            }
            setOnce("mn-dest", point(Live.destLat, Live.destLon))
            if (shownJunction != Live.junctionVersion) {
                shownJunction = Live.junctionVersion
                setOnce("mn-junction", Live.junctionJson ?: emptyFc)
            }
            setOnce("mn-next", Live.nextLeg ?: emptyFc)
            val lab = if (Live.junctionHasTurn) "" else Live.maneuverLabel
            if (lab != null && Live.navMeters in 0..1500) {
                setOnce("mn-turn", point(Live.maneuverLat, Live.maneuverLon, lab))
            } else {
                setOnce("mn-turn", emptyFc)
            }
        } else if (shownRouteVersion != -2) {
            shownRouteVersion = -2
            src("mn-route")?.setGeoJson(emptyFc)
            setOnce("mn-turn", emptyFc)
            setOnce("mn-dest", emptyFc)
            setOnce("mn-next", emptyFc)
            setOnce("mn-junction", emptyFc)
            shownJunction = -1
        }

        if (shownChoicesMap != Live.choicesVersion) {
            shownChoicesMap = Live.choicesVersion
            val list = Live.routeChoices
            if (list.size > 1) {
                val feats = list.filterIndexed { i, _ -> i != Live.choiceSelected }.joinToString(",") { it.toGeoJsonLine() }
                src("mn-choices")?.setGeoJson("""{"type":"FeatureCollection","features":[$feats]}""")
            } else {
                src("mn-choices")?.setGeoJson(emptyFc)
            }
        }
        if (shownText3d != Live.text3dVersion) {
            shownText3d = Live.text3dVersion
            src("mn-text3d")?.setGeoJson(Live.text3dJson ?: emptyFc)
        }
        if (shownWx != Live.wxVersion) {
            shownWx = Live.wxVersion
            val js = Live.wxJson
            if (js != null) {
                try {
                    val st = style
                    if (st != null && st.isFullyLoaded) {
                        Regex(""""img":"(wx-(\d+)-(\d)-([^"]*))"""").findAll(js).forEach { m ->
                            val id = m.groupValues[1]
                            if (st.getImage(id) == null) {
                                st.addImage(id, wxIcon(m.groupValues[2].toInt(), m.groupValues[3] == "1", m.groupValues[4]))
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            src("mn-wx")?.setGeoJson(js ?: emptyFc)
        }
        if (shownSignsMap != Live.signsVersion) {
            shownSignsMap = Live.signsVersion
            val js = Live.signsJson
            if (js != null) {
                // Tạo hình biển còn thiếu
                try {
                    val st = style
                    if (st != null && st.isFullyLoaded) {
                        Regex(""""t":(\d+),"v":(\d+)""").findAll(js).forEach { m ->
                            val t = m.groupValues[1].toInt()
                            val v = m.groupValues[2].toInt()
                            val id = "sign-$t-$v"
                            if (st.getImage(id) == null) st.addImage(id, SignType.icon(t, v, dp(34f)))
                        }
                    }
                } catch (_: Exception) {
                }
            }
            src("mn-signs")?.setGeoJson(js ?: emptyFc)
        }
        if (shownAlerts != Live.alertsVersion) {
            shownAlerts = Live.alertsVersion
            src("mn-alerts")?.setGeoJson(Live.alertsJson ?: emptyFc)
        }
        val nl = Live.nextLimit
        if (nl != null && Live.nextLimitDist in 0..320) {
            try {
                val st = style
                if (st != null && st.isFullyLoaded && st.getImage("lim-$nl") == null) st.addImage("lim-$nl", limitIcon(nl))
            } catch (_: Exception) {
            }
            val js = """{"type":"Feature","properties":{"img":"lim-$nl"},"geometry":{"type":"Point","coordinates":[${Live.nextLimitLon},${Live.nextLimitLat}]}}"""
            setOnce("mn-limit", js)
        } else {
            setOnce("mn-limit", emptyFc)
        }

        if (!Live.gpsOk) return
        val useSnap = nav && r != null && !Live.offRoute
        val lat = if (useSnap) Live.snapLat else Live.lat
        val lon = if (useSnap) Live.snapLon else Live.lon
        // Đang bám xe với vòng lặp mượt: camTick tự đặt vị trí mũi tên; còn lại đặt ở vị trí thật
        carShown = true
        if (!(camLoop && follow && camCur != null)) { carLat = lat; carLon = lon }

        if (follow) {
            val spd = Live.speedKmh
            var zoom = if (!prefs.autoZoom) 17.0 else when {
                spd >= 60 -> 15.4
                spd >= 40 -> 16.0
                spd >= 20 -> 16.6
                else -> 17.3
            }
            if (nav && Live.navMeters in 0..150) zoom = maxOf(zoom, 17.6)
            val h = mapView.height.toDouble()
            // Đặt xe ở giữa, sát phía dưới vùng bản đồ còn nhìn thấy
            val covered = if (landscape) 0.0 else bottomBar.height.toDouble()
            // Màn ngang thấp: đặt xe sát dưới hơn, thu nhỏ chút để thấy xa phía trước
            val carY = h - covered - dp(if (landscape) 42f else 70f)
            if (landscape) zoom -= 0.3
            val topPad = (2 * carY - h).coerceIn(0.0, h * 0.8)
            val course = when {
                // Đang bám tuyến: theo hướng tuyến phía trước (đường luôn dọc màn hình)
                nav && r != null && !Live.offRoute && Live.roadBearing >= 0 -> Live.roadBearing
                // Đang chạy: theo hướng đoạn đường, nếu lệch nhiều so với GPS thì theo GPS
                Live.roadBearing >= 0 && (Live.heading < 0 || Geo.angleDiff(Live.roadBearing, Live.heading) < 70) -> Live.roadBearing
                Live.heading >= 0 -> Live.heading
                else -> -1.0
            }
            // Hướng GPS thô (lệch tuyến, chưa khớp đường) dao động khi lách xe/đổi làn → vùng chết lớn hơn
            camCourseRaw = course >= 0 && course == Live.heading && course != Live.roadBearing
            camCourse = course
            TripLog.ev("view", "b" to camBearing, "src" to (if (camUsingCompass) "compass" else if (camCourseRaw) "gps" else "road"),
                "cmp" to compass.bearing, "course" to course, "rb" to Live.roadBearing, "hd" to Live.heading)
            if (prefs.rotateMap) {
                // Vòng lặp camTick xoay + trượt bản đồ mượt (kiểu Google Maps), ở đây chỉ đặt đích
                val old = camTarget
                if (old == null || old[0] != lat || old[1] != lon) {
                    camFixAt = android.os.SystemClock.uptimeMillis()
                    camFixProg = if (useSnap) Live.routeProgress else -1.0
                }
                camTarget = doubleArrayOf(lat, lon, zoom, if (nav) 55.0 else 40.0, topPad)
                startCamLoop()
            } else {
                stopCamLoop()
                val pos = CameraPosition.Builder()
                    .target(LatLng(lat, lon))
                    .zoom(zoom)
                    .tilt(0.0)
                    .bearing(0.0)
                    .padding(0.0, topPad, 0.0, 0.0)
                    .build()
                m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 900)
            }
        } else {
            stopCamLoop()
        }
        // Mũi tên xe xoay theo hướng đi
        val hdg = when {
            useSnap && Live.roadBearing >= 0 -> Live.roadBearing
            Live.heading >= 0 -> Live.heading
            Live.roadBearing >= 0 -> Live.roadBearing
            else -> lastHeading
        }
        lastHeading = hdg
        if (!camUsingCompass) carHdg = hdg
        positionCar()
    }

    private var lastHeading = 0.0

    // ---------- Xoay bản đồ theo la bàn / con quay điện thoại ----------
    private val compass by lazy { PhoneCompass(this) }
    private var camTarget: DoubleArray? = null
    private var camCur: DoubleArray? = null
    private var camBearing = -1.0
    private var camLoop = false

    private var camCourse = -1.0
    private var camAimed = -1.0       // hướng đích sau vùng chết
    private var camMoving = false
    private var camBearingOk = false
    private var camCourseRaw = false
    private var camUsingCompass = false

    private fun startCamLoop() {
        if (prefs.batterySaver) compass.stop() else compass.start()
        if (camLoop) return
        camLoop = true
        camCur = null
        ui.post(camTick)
    }

    private fun stopCamLoop() {
        if (!camLoop) return
        camLoop = false
        ui.removeCallbacks(camTick)
        compass.stop()
        camCur = null
        camBearing = -1.0
        camAimed = -1.0
        camUsingCompass = false
    }

    /**
     * Chọn hướng bản đồ giống Google Maps:
     * đang chạy (≥ 10 km/h) → theo hướng đường đi; chậm/dừng (< 5 km/h) → theo la bàn điện thoại.
     * Có vùng chết vài độ để bản đồ không rung, và giới hạn tốc độ xoay.
     */
    private fun pickBearing(): Pair<Double, Boolean> {
        val spd = Live.speedKmh
        if (spd >= 10) camMoving = true else if (spd in 0..4 || !Live.gpsOk) camMoving = false
        val cmp = if (prefs.batterySaver) -1.0 else compass.bearing
        return if ((camMoving && camCourse >= 0) || cmp < 0) camCourse to false else cmp to true
    }

    private var camFixAt = 0L
    private var camFixProg = -1.0

    /**
     * Dự đoán vị trí xe giữa 2 lần GPS (tối đa 1,2 giây): đang bám tuyến thì trượt dọc tuyến,
     * còn lại đi thẳng theo hướng chạy. Nhờ vậy mũi tên chạy liên tục như Google Maps.
     */
    private fun predicted(t: DoubleArray): DoubleArray {
        if (!prefs.predict) return t
        val v = Live.speedKmh / 3.6
        if (v < 1.5 || camFixAt == 0L) return t
        val dt = ((android.os.SystemClock.uptimeMillis() - camFixAt) / 1000.0).coerceIn(0.0, 1.2)
        val ds = v * dt
        if (ds < 0.3) return t
        val r = Live.route
        val out = t.copyOf()
        if (r != null && camFixProg >= 0 && !Live.offRoute) {
            val target = camFixProg + ds
            var i = Live.routeSeg.coerceIn(0, r.lats.size - 2)
            while (i < r.lats.size - 2 && r.cum[i + 1] < target) i++
            val segLen = r.cum[i + 1] - r.cum[i]
            val f = if (segLen < 0.01) 0.0 else ((target - r.cum[i]) / segLen).coerceIn(0.0, 1.0)
            out[0] = r.lats[i] + f * (r.lats[i + 1] - r.lats[i])
            out[1] = r.lons[i] + f * (r.lons[i + 1] - r.lons[i])
        } else {
            val hd = if (camCourse >= 0) camCourse else return t
            val rad = Math.toRadians(hd)
            out[0] = t[0] + Math.cos(rad) * ds / 110540.0
            out[1] = t[1] + Math.sin(rad) * ds / (111320.0 * Math.cos(Math.toRadians(t[0])))
        }
        return out
    }

    private val camTick = object : Runnable {
        override fun run() {
            if (!camLoop) return
            val saver = prefs.batterySaver
            ui.postDelayed(this, if (saver) 120L else 40L)
            // Tiết kiệm pin: 8 khung hình/giây thay vì 25, bước di chuyển lớn hơn để tốc độ trượt như cũ
            val k = if (saver) 3 else 1
            val m = map ?: return
            val t = camTarget ?: return
            if (!follow) { camCur = null; return }
            val cp = m.cameraPosition
            var c = camCur
            val (b0, _) = pickBearing()
            // Lần đầu (mới mở app / vừa bám lại) hoặc đích ở xa: nhảy thẳng tới, không trượt qua bản đồ
            val far = c == null || Geo.distance(c[0], c[1], t[0], t[1]) > 150 || kotlin.math.abs(c[2] - t[2]) > 1.5
            if (far) {
                c = t.copyOf()
                camCur = c
                camBearing = if (b0 >= 0) b0 else cp.bearing
                camAimed = camBearing
                camBearingOk = b0 >= 0
                carLat = c[0]; carLon = c[1]
                if (b0 >= 0 && camUsingCompass) carHdg = b0
                val pos0 = CameraPosition.Builder().target(LatLng(c[0], c[1])).zoom(c[2]).tilt(c[3])
                    .bearing(camBearing).padding(0.0, c[4], 0.0, 0.0).build()
                try { m.moveCamera(CameraUpdateFactory.newCameraPosition(pos0)) } catch (_: Exception) {}
                positionCar()
                return
            }
            c!!
            var moved = false
            val tg = predicted(t)
            for (i in 0..4) {
                val a0 = if (i < 2) (if (tg !== t) 0.30 else 0.10) else 0.08
                val a = 1 - Math.pow(1 - a0, k.toDouble())
                val d = tg[i] - c[i]
                if (kotlin.math.abs(d) > (if (i < 2) 1e-7 else 0.002)) { c[i] += d * a; moved = true }
            }
            val (b, fromCompass) = pickBearing()
            camUsingCompass = fromCompass
            if (b >= 0 && !camBearingOk) {
                // Hướng đầu tiên vừa có (la bàn/GPS): đặt luôn, không quay vòng
                camBearingOk = true
                camBearing = b; camAimed = b; carHdg = b
                moved = true
            } else if (b >= 0) {
                // Vùng chết: la bàn 5°, hướng đường 3° (hoặc đổi nguồn thì nhận ngay)
                val dead = when {
                    fromCompass -> 5.0
                    camCourseRaw -> 12.0 // GPS thô: bỏ qua lách xe, đổi làn
                    else -> 4.0          // hướng đường/tuyến: đã ổn định sẵn
                }
                if (camAimed < 0 || Geo.angleDiff(b, camAimed) > dead) camAimed = b
                var d = (camAimed - camBearing) % 360.0
                if (d > 180) d -= 360.0
                if (d < -180) d += 360.0
                if (kotlin.math.abs(d) > 0.2) {
                    // Xoay êm: ~12%/khung hình, tối đa 3,5° mỗi 40 ms (~90°/s)
                    val step = (d * (1 - Math.pow(0.88, k.toDouble()))).coerceIn(-3.5 * k, 3.5 * k)
                    camBearing = Geo.norm(camBearing + step)
                    moved = true
                }
            }
            if (fromCompass && b >= 0) carHdg = PhoneCompass.lerpAngle(carHdg, b, 0.3)
            carLat = c[0]; carLon = c[1]
            if (!moved) { positionCar(); return }
            val pos = CameraPosition.Builder()
                .target(LatLng(c[0], c[1]))
                .zoom(c[2])
                .tilt(c[3])
                .bearing(camBearing)
                .padding(0.0, c[4], 0.0, 0.0)
                .build()
            try { m.moveCamera(CameraUpdateFactory.newCameraPosition(pos)) } catch (_: Exception) {}
            positionCar()
        }
    }
    private val autoFollow = Runnable {
        if (!follow) {
            follow = true
            recenterBtn.visibility = View.GONE
            render()
        }
    }
    private var shownJunction = -1

    // ---------- Vẽ trạng thái ----------

    private fun render() {
        val nav = Live.ownNav

        // Thẻ rẽ
        if (nav && Live.route != null && Live.navInstruction != null) {
            turnCard.visibility = View.VISIBLE
            turnArrow.setImageBitmap(Live.navArrow)
            turnDist.text = Live.navDistance ?: ""
            turnText.text = Live.navInstruction
            turnCard.background = rounded(if (Live.navMeters in 0..70) Color.parseColor("#1565C0") else green)
            val t = Live.thenText
            thenText.visibility = if (t != null) View.VISIBLE else View.GONE
            thenText.text = t ?: ""
            if (t != null && shownThenSign != Live.thenSign) {
                shownThenSign = Live.thenSign
                val d = android.graphics.drawable.BitmapDrawable(resources, ArrowIcon.get(Live.thenSign, dp(26f)))
                d.setBounds(0, 0, dp(26f), dp(26f))
                thenText.setCompoundDrawables(d, null, null, null)
                thenText.compoundDrawablePadding = dp(8f)
            }
        } else {
            turnCard.visibility = View.GONE
        }
        searchBox.visibility = if (nav) View.GONE else View.VISIBLE
        endBtn.visibility = if (nav) View.VISIBLE else View.GONE
        altBtn.visibility = if (nav && Live.route != null && Live.routeChoices.isEmpty()) View.VISIBLE else View.GONE
        btnRow.visibility = if (endBtn.visibility == View.VISIBLE || recenterBtn.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        renderChoices()

        // Cảnh báo chữ
        val a = Live.activeAlert()
        if (a != null) {
            alertBanner.visibility = View.VISIBLE
            alertBanner.text = a
            if (Live.alertUrgent) {
                alertBanner.background = rounded(danger, 12f)
                alertBanner.setTextColor(Color.WHITE)
            } else {
                alertBanner.background = rounded(Color.parseColor("#FFD60A"), 12f)
                alertBanner.setTextColor(Color.BLACK)
            }
        } else {
            alertBanner.visibility = View.GONE
        }

        // Tốc độ
        sign.limit = Live.limit
        sign.estimated = Live.limitEstimated
        speedText.text = if (Live.gpsOk && Live.speedKmh >= 0) Live.speedKmh.toString() else "--"
        speedText.setTextColor(if (Live.overspeed) danger else Color.WHITE)
        val sa = Live.signsAhead
        val sameKinds = shownSigns?.let { old -> old.size == sa.size && old.indices.all { old[it].first == sa[it].first && old[it].second == sa[it].second } } == true
        if (sa != shownSigns && sameKinds) {
            // Cùng các biển, chỉ đổi số mét: sửa chữ, không vẽ lại hình (không nháy)
            shownSigns = sa
            for ((k, tr) in sa.withIndex()) (signsBox.getChildAt(k * 2 + 1) as? TextView)?.text = if (tr.third < 0) "Đoạn này" else "${tr.third} m"
        } else if (sa != shownSigns) {
            shownSigns = sa
            signsBox.removeAllViews()
            for ((t, v, d) in sa) {
                signsBox.addView(ImageView(this).apply { setImageBitmap(SignType.icon(t, v, dp(44f))) }, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { topMargin = dp(6f) })
                signsBox.addView(tv(15f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    text = if (d < 0) "Đoạn này" else "$d m"
                })
            }
        }
        urbanSign.urban = Live.urban
        urbanSign.estimated = Live.urbanEst
        camStreetBadge.visibility = if (Live.camStreet != null) View.VISIBLE else View.GONE
        val nl = Live.nextLimit
        if (nl != null && Live.nextLimitDist in 0..200) {
            nextBox.visibility = View.VISIBLE
            nextSign.limit = nl
            nextSign.estimated = Live.nextLimitEst
            nextDist.text = "${Live.nextLimitDist} m"
        } else {
            nextBox.visibility = View.GONE
        }
        if (Live.camText != null) {
            camBox.visibility = View.VISIBLE
            camBox.text = CamKind.short(Live.camKind) + "\n" + Live.camDistance + " m"
            camBox.background = rounded(if (Live.camKind == CamKind.SPEED) danger else Color.parseColor("#E65100"), 10f)
        } else {
            camBox.visibility = View.GONE
        }

        // Thanh dưới
        if (nav && Live.route != null) {
            val min = Live.etaMs / 60000
            val arrive = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + Live.etaMs))
            etaText.text = "${if (min < 1) "<1" else min.toString()} phút · ${NavEngine.fmtDist(Live.remainingM)} · đến $arrive"
        } else if (nav) {
            etaText.text = Live.destName ?: "Đang dẫn đường"
        } else {
            etaText.text = Live.street ?: "Chọn điểm đến"
        }
        val mapErr = MapHttp.problem() ?: styleError ?: if (false) {
            // Chế độ VietMap: luôn hiện chẩn đoán để tìm lỗi bản đồ trắng
            listOfNotNull(vmDiag, "Thư viện: tải OK ${MapHttp.okCount}, lỗi ${MapHttp.failCount}", MapHttp.lastTileInfo, MapHttp.lastLog).joinToString(" · ")
        } else null
        statusText.text = mapErr ?: Live.routeError ?: Live.routeStatus
            ?: listOfNotNull(Live.destName?.takeIf { nav }?.let { "Đến: $it" }, Live.wxSummary?.takeIf { nav }?.let { "☂ $it" })
                .joinToString("\n")
        statusText.setTextColor(if (Live.routeError != null || mapErr != null) danger else muted)

        updateMap()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- Vòng đời MapView ----------

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        Live.appVisible = true
        Live.addListener(listener)
        // Quay lại từ Cài đặt: đổi mã hoặc chế độ đêm thì tải lại bản đồ
        val key = prefs.vietmapTileKey.ifBlank { prefs.vietmapKey }
        if (map != null && loadedStyleId != null && loadedStyleId != "${prefs.mapSource}/$key/${isNight()}/${prefs.vietmapStyleUrl}") loadStyle()
        applySaver()
        render()
    }

    override fun onPause() {
        stopCamLoop()
        Live.appVisible = false
        Live.removeListener(listener)
        Live.changed()
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    /** Xoay màn hình: dựng lại giao diện nhưng giữ nguyên bản đồ (không tải lại, không mất tuyến). */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val q = if (::searchInput.isInitialized) searchInput.text.toString() else ""
        (mapView.parent as? android.view.ViewGroup)?.removeView(mapView)
        setContentView(buildUi())
        applyImmersive()
        if (q.isNotEmpty()) searchInput.setText(q)
        shownChoices = -1
        shownSigns = null
        shownThenSign = -999
        recenterBtn.visibility = if (follow) View.GONE else View.VISIBLE
        camCur = null
        render()
    }

    /** Luôn toàn màn hình: ẩn thanh trạng thái + thanh điều hướng (vuốt từ mép để hiện lại). */
    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        mapView.onDestroy()
        io.shutdownNow()
        super.onDestroy()
    }
}
