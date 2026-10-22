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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        MapLibre.getInstance(this)
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
                }
            }
            m.addOnMapLongClickListener { p ->
                confirmDestination(p.latitude, p.longitude, "Điểm đã chọn trên bản đồ")
                true
            }
            loadStyle()
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

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(bg)
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))

        // --- Cột trên: thẻ rẽ, cảnh báo, ô tìm kiếm ---
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(10f), dp(10f), 0)
        }
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
        row.addView(turnArrow, LinearLayout.LayoutParams(dp(70f), dp(70f)))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), 0, 0, 0)
        }
        turnDist = tv(34f, Color.WHITE, true)
        col.addView(turnDist)
        turnText = tv(22f, Color.WHITE, true).apply { maxLines = 2 }
        col.addView(turnText)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        turnCard.addView(row)
        thenText = tv(16f, Color.parseColor("#CFEFE3")).apply { visibility = View.GONE }
        turnCard.addView(thenText)
        top.addView(turnCard, LinearLayout.LayoutParams(-1, -2))

        alertBanner = tv(18f, Color.BLACK, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            visibility = View.GONE
        }
        top.addView(alertBanner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })

        searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(235, 22, 26, 32))
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
        searchInput = EditText(this).apply {
            hint = "Tìm địa điểm (VietMap)…"
            setHintTextColor(muted)
            setTextColor(Color.WHITE)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        searchBox.addView(searchInput, LinearLayout.LayoutParams(-1, -2))
        val hint = tv(13f, muted).apply {
            text = "Hoặc: mở Google Maps → chọn địa điểm → Chia sẻ → MotoNav.\nGiữ lâu trên bản đồ để chọn điểm đến."
        }
        searchBox.addView(hint)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sc = ScrollView(this).apply { addView(results) }
        searchBox.addView(sc, LinearLayout.LayoutParams(-1, -2))
        top.addView(searchBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        setupSearch()

        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // --- Góc trái dưới: biển giới hạn + tốc độ + camera ---
        val speedBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(Color.argb(215, 11, 13, 16), 14f)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        }
        sign = SpeedSignView(this)
        speedBox.addView(sign, LinearLayout.LayoutParams(dp(74f), dp(74f)))
        speedText = tv(32f, Color.WHITE, true).apply {
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
        speedBox.addView(camBox, LinearLayout.LayoutParams(dp(92f), -2).apply { topMargin = dp(6f) })
        root.addView(
            speedBox,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(10f); bottomMargin = dp(96f)
            },
        )

        // --- Thanh dưới ---
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(240, 22, 26, 32), 0f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(10f))
        }
        etaText = tv(20f, Color.WHITE, true)
        bottom.addView(etaText)
        statusText = tv(13f, muted).apply { maxLines = 2 }
        bottom.addView(statusText)
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
        recenterBtn = btn("Về vị trí", Color.parseColor("#252B33")) {
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
        btns.addView(btn("Cài đặt", Color.parseColor("#252B33")) { askKey() }, lp())
        bottom.addView(btns)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

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
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 7)
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
        if (prefs.vietmapKey.isBlank() || prefs.vietmapTileKey.isBlank()) askKey()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (!Live.serviceRunning) {
                Live.userStopped = false
                DriveService.start(this)
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 7)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            DriveService.start(this)
        }
    }

    private fun askKey(after: (() -> Unit)? = null) {
        KeyDialog.show(this, prefs) {
            loadStyle()
            after?.invoke()
        }
    }

    // ---------- Bản đồ ----------

    private fun isNight(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 18 || h < 6
    }

    private fun loadStyle() {
        val m = map ?: return
        val key = prefs.vietmapTileKey.ifBlank { prefs.vietmapKey }
        if (key.isBlank()) return
        m.setStyle(Style.Builder().fromUri(VietMap.styleUrl(key, isNight()))) { s ->
            style = s
            shownRouteVersion = -1
            labelFont = findFont(s)
            addLayers(s)
            render()
        }
    }

    /** Lấy font chữ có sẵn trong kiểu bản đồ VietMap để nhãn tên đường hiển thị được. */
    private fun findFont(s: Style): Array<String>? {
        for (l in s.layers) {
            if (l is SymbolLayer) {
                val f = try { l.textFont?.value } catch (_: Exception) { null }
                if (f != null && f.isNotEmpty()) return f
            }
        }
        return null
    }

    private val emptyFc = """{"type":"FeatureCollection","features":[]}"""

    private fun addLayers(s: Style) {
        s.addSource(GeoJsonSource("mn-route", emptyFc))
        s.addSource(GeoJsonSource("mn-me", emptyFc))
        s.addSource(GeoJsonSource("mn-turn", emptyFc))
        s.addSource(GeoJsonSource("mn-dest", emptyFc))

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
                PropertyFactory.lineColor("#38A3FF"),
                PropertyFactory.lineWidth(8f),
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
        s.addLayer(
            CircleLayer("mn-me-dot", "mn-me").withProperties(
                PropertyFactory.circleRadius(11f),
                PropertyFactory.circleColor("#1E88E5"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(4f),
                PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
            ),
        )
    }

    private fun point(lat: Double, lon: Double, label: String? = null): String {
        val props = if (label != null) """{"label":${JSONObject.quote(label)}}""" else "{}"
        return """{"type":"Feature","properties":$props,"geometry":{"type":"Point","coordinates":[$lon,$lat]}}"""
    }

    private fun src(id: String): GeoJsonSource? = style?.getSource(id) as? GeoJsonSource

    private fun updateMap() {
        val m = map ?: return
        if (style == null) return
        val nav = Live.ownNav
        val r = Live.route

        if (nav && r != null) {
            if (shownRouteVersion != Live.routeVersion) {
                shownRouteVersion = Live.routeVersion
                src("mn-route")?.setGeoJson(r.toGeoJsonLine())
            }
            src("mn-dest")?.setGeoJson(point(Live.destLat, Live.destLon))
            val lab = Live.maneuverLabel
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
        }

        if (!Live.gpsOk) return
        val useSnap = nav && r != null && !Live.offRoute
        val lat = if (useSnap) Live.snapLat else Live.lat
        val lon = if (useSnap) Live.snapLon else Live.lon
        src("mn-me")?.setGeoJson(point(lat, lon))

        if (follow) {
            val spd = Live.speedKmh
            var zoom = when {
                spd > 50 -> 16.0
                spd > 25 -> 16.8
                else -> 17.4
            }
            if (nav && Live.navMeters in 0..150) zoom = 17.8
            val h = mapView.height.toDouble()
            val pos = CameraPosition.Builder()
                .target(LatLng(lat, lon))
                .zoom(zoom)
                .tilt(if (nav) 58.0 else 40.0)
                .bearing(if (Live.heading >= 0) Live.heading else m.cameraPosition.bearing)
                .padding(0.0, h * 0.42, 0.0, 0.0)
                .build()
            m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 900)
        }
    }

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
        statusText.text = Live.routeError ?: Live.routeStatus ?: (Live.destName?.takeIf { nav }?.let { "Đến: $it" } ?: "")
        statusText.setTextColor(if (Live.routeError != null) danger else muted)

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
