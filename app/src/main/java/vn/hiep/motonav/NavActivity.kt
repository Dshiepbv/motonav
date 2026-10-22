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

        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isRotateGesturesEnabled = true
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    follow = false
                    recenterBtn.visibility = View.VISIBLE
                    btnRow.visibility = View.VISIBLE
                }
            }
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

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun rounded(color: Int, r: Float = 16f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun tv(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private var landscape = false
    private lateinit var bottomBar: LinearLayout
    private lateinit var speedBox: LinearLayout

    private fun makeViews() {
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
        row.addView(turnArrow, LinearLayout.LayoutParams(dp(66f), dp(66f)))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), 0, 0, 0)
        }
        turnDist = tv(32f, Color.WHITE, true)
        col.addView(turnDist)
        turnText = tv(21f, Color.WHITE, true).apply { maxLines = 2 }
        col.addView(turnText)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        turnCard.addView(row)
        thenText = tv(16f, Color.parseColor("#CFEFE3")).apply { visibility = View.GONE }
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
            hint = "🔍  Đi đâu?"
            setHintTextColor(muted)
            setTextColor(Color.WHITE)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        searchBox.addView(searchInput, LinearLayout.LayoutParams(-1, -2))
        searchBox.addView(tv(13f, muted).apply {
            text = "Hoặc: Google Maps → chọn địa điểm → Chia sẻ → MotoNav. Giữ lâu trên bản đồ để chọn điểm đến."
        })
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
        speedBox.addView(sign, LinearLayout.LayoutParams(dp(70f), dp(70f)))
        speedText = tv(30f, Color.WHITE, true).apply {
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

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(10f))
        }
        etaText = tv(20f, Color.WHITE, true)
        bottomBar.addView(etaText)
        statusText = tv(13f, muted).apply { maxLines = 3 }
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

        // Nút "!" báo cảnh báo góc dưới phải (kiểu Waze)
        reportBtn = TextView(this).apply {
            text = "!"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF8C00"))
                setStroke(dp(3f), Color.WHITE)
            }
            elevation = dp(6f).toFloat()
            setOnClickListener { AppActions.markAlert(this@NavActivity) }
        }
    }

    private lateinit var menuBtn: TextView
    private lateinit var reportBtn: TextView
    private lateinit var btnRow: LinearLayout

    private fun showMenu() {
        val items = arrayOf(
            "⚙   Cài đặt",
            "🗺   Mở Google Maps (ô nổi tốc độ)",
            "⏱   Màn hình tốc độ lớn",
            "⚠️   Báo cảnh báo tại đây",
            if (Live.serviceRunning) "⏸   Tạm dừng theo dõi tốc độ" else "▶   Bật theo dõi tốc độ",
        )
        AlertDialog.Builder(this)
            .setTitle("MotoNav")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, SettingsActivity::class.java))
                    1 -> AppActions.openGoogleMaps(this, prefs)
                    2 -> startActivity(Intent(this, MainActivity::class.java))
                    3 -> AppActions.markAlert(this)
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
        landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        makeViews()
        val root = FrameLayout(this)
        root.setBackgroundColor(bg)

        if (!landscape) {
            // ---- Màn hình dọc: thẻ rẽ trên, bản đồ giữa, thanh dưới ----
            root.addView(mapView, FrameLayout.LayoutParams(-1, -1))
            // Hàng trên: nút ☰ + (thẻ rẽ / ô tìm kiếm)
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(10f), dp(10f), dp(10f), 0)
            }
            topRow.addView(menuBtn, LinearLayout.LayoutParams(dp(50f), dp(50f)).apply { rightMargin = dp(8f) })
            val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            top.addView(turnCard, LinearLayout.LayoutParams(-1, -2))
            top.addView(alertBanner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
            top.addView(searchBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
            topRow.addView(top, LinearLayout.LayoutParams(0, -2, 1f))
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
            root.addView(reportBtn, FrameLayout.LayoutParams(dp(64f), dp(64f), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(14f) })
            bottomBar.addOnLayoutChangeListener { _, _, top2, _, bottom2, _, _, _, _ ->
                val lp = reportBtn.layoutParams as FrameLayout.LayoutParams
                val m = (bottom2 - top2) + dp(14f)
                if (lp.bottomMargin != m) {
                    lp.bottomMargin = m
                    reportBtn.requestLayout()
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
            val scroll = ScrollView(this).apply { isFillViewport = true }
            val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            inner.addView(menuBtn, LinearLayout.LayoutParams(dp(50f), dp(50f)).apply { bottomMargin = dp(8f) })
            inner.addView(turnCard, LinearLayout.LayoutParams(-1, -2))
            inner.addView(alertBanner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
            inner.addView(searchBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
            scroll.addView(inner, FrameLayout.LayoutParams(-1, -2))
            panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            panel.addView(bottomBar, LinearLayout.LayoutParams(-1, -2))
            row.addView(panel, LinearLayout.LayoutParams(0, -1, 0.42f))

            val mapBox = FrameLayout(this)
            mapBox.addView(mapView, FrameLayout.LayoutParams(-1, -1))
            mapBox.addView(
                speedBox,
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                    leftMargin = dp(10f); topMargin = dp(16f)
                },
            )
            mapBox.addView(
                reportBtn,
                FrameLayout.LayoutParams(dp(60f), dp(60f), Gravity.BOTTOM or Gravity.END).apply {
                    rightMargin = dp(14f); bottomMargin = dp(14f)
                },
            )
            row.addView(mapBox, LinearLayout.LayoutParams(0, -1, 0.58f))
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
                ui.post { startNavTo(p.lat, p.lon, s.name) }
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

    private fun addLayers(s: Style) {
        s.addSource(GeoJsonSource("mn-route", emptyFc))
        s.addSource(GeoJsonSource("mn-me", emptyFc))
        s.addSource(GeoJsonSource("mn-turn", emptyFc))
        s.addSource(GeoJsonSource("mn-dest", emptyFc))
        s.addSource(GeoJsonSource("mn-next", emptyFc))
        s.addSource(GeoJsonSource("mn-junction", emptyFc))

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
        s.addImage("mn-car", carIcon())
        s.addLayer(
            SymbolLayer("mn-me-arrow", "mn-me").withProperties(
                PropertyFactory.iconImage("mn-car"),
                PropertyFactory.iconSize(1f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            ),
        )
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
            src("mn-dest")?.setGeoJson(point(Live.destLat, Live.destLon))
            if (shownJunction != Live.junctionVersion) {
                shownJunction = Live.junctionVersion
                src("mn-junction")?.setGeoJson(Live.junctionJson ?: emptyFc)
            }
            src("mn-next")?.setGeoJson(Live.nextLeg ?: emptyFc)
            val lab = if (Live.junctionHasTurn) "" else Live.maneuverLabel
            if (lab != null && Live.navMeters in 0..1500) {
                src("mn-turn")?.setGeoJson(point(Live.maneuverLat, Live.maneuverLon, lab))
            } else {
                src("mn-turn")?.setGeoJson(emptyFc)
            }
        } else if (shownRouteVersion != -2) {
            shownRouteVersion = -2
            src("mn-route")?.setGeoJson(emptyFc)
            src("mn-turn")?.setGeoJson(emptyFc)
            src("mn-dest")?.setGeoJson(emptyFc)
            src("mn-next")?.setGeoJson(emptyFc)
            src("mn-junction")?.setGeoJson(emptyFc)
            shownJunction = -1
        }

        if (!Live.gpsOk) return
        val useSnap = nav && r != null && !Live.offRoute
        val lat = if (useSnap) Live.snapLat else Live.lat
        val lon = if (useSnap) Live.snapLon else Live.lon
        src("mn-me")?.setGeoJson(point(lat, lon))

        if (follow) {
            val spd = Live.speedKmh
            var zoom = if (!prefs.autoZoom) 17.0 else when {
                spd >= 65 -> 15.4
                spd >= 50 -> 16.0
                spd >= 30 -> 16.6
                else -> 17.3
            }
            if (nav && Live.navMeters in 0..150) zoom = maxOf(zoom, 17.6)
            val h = mapView.height.toDouble()
            // Đặt xe ở giữa, sát phía dưới vùng bản đồ còn nhìn thấy
            val covered = if (landscape) 0.0 else bottomBar.height.toDouble()
            val carY = h - covered - dp(70f)
            val topPad = (2 * carY - h).coerceIn(0.0, h * 0.8)
            val bearing = when {
                !prefs.rotateMap -> 0.0
                Live.heading >= 0 -> Live.heading
                else -> m.cameraPosition.bearing
            }
            val pos = CameraPosition.Builder()
                .target(LatLng(lat, lon))
                .zoom(zoom)
                .tilt(if (prefs.rotateMap) (if (nav) 55.0 else 40.0) else 0.0)
                .bearing(bearing)
                .padding(0.0, topPad, 0.0, 0.0)
                .build()
            m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 900)
        }
        // Mũi tên xe xoay theo hướng đi
        val hdg = if (Live.heading >= 0) Live.heading else lastHeading
        lastHeading = hdg
        try {
            val st = style
            if (st != null && st.isFullyLoaded) st.getLayer("mn-me-arrow")?.setProperties(PropertyFactory.iconRotate(hdg.toFloat()))
        } catch (_: Exception) {
        }
    }

    private var lastHeading = 0.0
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
        } else {
            turnCard.visibility = View.GONE
        }
        searchBox.visibility = if (nav) View.GONE else View.VISIBLE
        endBtn.visibility = if (nav) View.VISIBLE else View.GONE
        btnRow.visibility = if (endBtn.visibility == View.VISIBLE || recenterBtn.visibility == View.VISIBLE) View.VISIBLE else View.GONE

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
        statusText.text = mapErr ?: Live.routeError ?: Live.routeStatus ?: (Live.destName?.takeIf { nav }?.let { "Đến: $it" } ?: "")
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
        render()
    }

    override fun onPause() {
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
