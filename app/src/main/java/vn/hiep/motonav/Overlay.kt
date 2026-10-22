package vn.hiep.motonav

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Ô nổi đè lên Google Maps: biển giới hạn tốc độ xe máy, tốc độ hiện tại,
 * khung đếm ngược camera. Kéo để di chuyển, giữ lâu để mở MotoNav.
 * Chạy trên luồng chính, do DriveService quản lý.
 */
class Overlay(private val ctx: Context, private val prefs: Prefs) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var turnBox: LinearLayout
    private lateinit var turnArrow: android.widget.ImageView
    private lateinit var turnDist: TextView
    private lateinit var turnName: TextView
    private lateinit var overBanner: TextView
    private lateinit var alertBanner: TextView
    private var blink = false
    private lateinit var sign: SpeedSignView
    private lateinit var speed: TextView
    private lateinit var unit: TextView
    private lateinit var camBox: LinearLayout
    private lateinit var camTitle: TextView
    private lateinit var camDist: TextView
    private var builtScale = -1f

    private val danger = Color.parseColor("#FF3B30")
    private val orange = Color.parseColor("#E65100")

    private fun px(dp: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, ctx.resources.displayMetrics).toInt()

    private fun rounded(color: Int, r: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = px(r).toFloat()
    }

    private fun canShow() = Settings.canDrawOverlays(ctx)

    /** Gọi mỗi khi trạng thái đổi. */
    fun update() {
        val want = prefs.overlay && Live.serviceRunning && !Live.appVisible && canShow()
        if (!want) {
            hide()
            return
        }
        val scale = prefs.overlayScale
        if (root != null && scale != builtScale) hide()
        if (root == null) show(scale)
        render()
    }

    private fun show(scale: Float) {
        builtScale = scale
        fun d(v: Float) = px(v * scale)

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(Color.argb(215, 11, 13, 16), 16f * scale)
            setPadding(d(8f), d(8f), d(8f), d(8f))
        }
        // Dải chữ cảnh báo: luôn hiện kèm âm thanh
        alertBanner = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f * scale)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            maxLines = 4
            setPadding(d(8f), d(6f), d(8f), d(6f))
            visibility = View.GONE
        }
        box.addView(alertBanner, LinearLayout.LayoutParams(d(170f), -2).apply { bottomMargin = d(6f) })

        // Thẻ rẽ: hiện khi còn ≤ 300 m tới chỗ rẽ, ghi rõ tên đường sẽ vào
        turnBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(Color.parseColor("#0B6E4F"), 12f * scale)
            setPadding(d(8f), d(6f), d(8f), d(6f))
            visibility = View.GONE
        }
        val turnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        turnArrow = android.widget.ImageView(ctx).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        turnRow.addView(turnArrow, LinearLayout.LayoutParams(d(44f), d(44f)))
        turnDist = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26f * scale)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(d(6f), 0, 0, 0)
        }
        turnRow.addView(turnDist, LinearLayout.LayoutParams(-2, -2))
        turnBox.addView(turnRow, LinearLayout.LayoutParams(-2, -2))
        turnName = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f * scale)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 3
        }
        turnBox.addView(turnName, LinearLayout.LayoutParams(d(170f), -2))
        box.addView(turnBox, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = d(6f) })

        sign = SpeedSignView(ctx)
        box.addView(sign, LinearLayout.LayoutParams(d(78f), d(78f)))
        speed = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 34f * scale)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        box.addView(speed, LinearLayout.LayoutParams(-2, -2).apply { topMargin = d(4f) })
        unit = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f * scale)
            setTextColor(Color.parseColor("#9AA3AE"))
            text = "km/h"
        }
        box.addView(unit, LinearLayout.LayoutParams(-2, -2))

        overBanner = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f * scale)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            text = "QUÁ TỐC ĐỘ"
            background = rounded(danger, 8f * scale)
            setPadding(d(6f), d(4f), d(6f), d(4f))
            visibility = View.GONE
        }
        box.addView(overBanner, LinearLayout.LayoutParams(d(96f), -2).apply { topMargin = d(6f) })

        camBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(d(6f), d(4f), d(6f), d(4f))
            visibility = View.GONE
        }
        camTitle = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f * scale)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        camBox.addView(camTitle, LinearLayout.LayoutParams(-2, -2))
        camDist = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24f * scale)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        camBox.addView(camDist, LinearLayout.LayoutParams(-2, -2))
        box.addView(camBox, LinearLayout.LayoutParams(d(96f), -2).apply { topMargin = d(6f) })

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.overlayX >= 0) prefs.overlayX else px(12f)
            y = if (prefs.overlayY >= 0) prefs.overlayY else px(260f)
        }
        attachDrag(box, p)
        try {
            wm.addView(box, p)
            root = box
            params = p
        } catch (_: Exception) {
            root = null
        }
    }

    private fun attachDrag(v: View, p: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        val longPress = Runnable {
            if (!moved) {
                val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    ctx.startActivity(i)
                } catch (_: Exception) {
                }
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x
                    startY = p.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    main.postDelayed(longPress, 700)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        moved = true
                        main.removeCallbacks(longPress)
                    }
                    if (moved) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        try {
                            wm.updateViewLayout(v, p)
                        } catch (_: Exception) {
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (moved) {
                        prefs.overlayX = p.x
                        prefs.overlayY = p.y
                    }
                }
            }
            true
        }
    }

    private fun render() {
        if (root == null) return
        // Dải chữ cảnh báo
        val a = Live.activeAlert()
        if (a != null) {
            alertBanner.visibility = View.VISIBLE
            alertBanner.text = a
            if (Live.alertUrgent) {
                alertBanner.background = rounded(danger, 10f * builtScale)
                alertBanner.setTextColor(Color.WHITE)
            } else {
                alertBanner.background = rounded(Color.parseColor("#FFD60A"), 10f * builtScale)
                alertBanner.setTextColor(Color.BLACK)
            }
        } else {
            alertBanner.visibility = View.GONE
        }

        // Thẻ rẽ
        val m = Live.navMeters
        if (Live.navActive && m in 0..300 && !Live.navInstruction.isNullOrBlank()) {
            turnBox.visibility = View.VISIBLE
            turnArrow.setImageBitmap(Live.navArrow)
            turnArrow.visibility = if (Live.navArrow != null) View.VISIBLE else View.GONE
            turnDist.text = Live.navDistance ?: ""
            turnName.text = Live.navInstruction
            turnBox.background = rounded(
                if (m <= 80) Color.parseColor("#1565C0") else Color.parseColor("#0B6E4F"), 12f * builtScale,
            )
        } else {
            turnBox.visibility = View.GONE
        }

        // Nhấp nháy khi vượt tốc
        blink = !blink
        if (Live.overspeed) {
            overBanner.visibility = View.VISIBLE
            overBanner.alpha = if (blink) 1f else 0.35f
        } else {
            overBanner.visibility = View.GONE
        }

        sign.limit = Live.limit
        sign.estimated = Live.limitEstimated
        speed.text = if (Live.gpsOk && Live.speedKmh >= 0) Live.speedKmh.toString() else "--"
        speed.setTextColor(if (Live.overspeed) danger else Color.WHITE)
        val cam = Live.camText
        if (cam != null) {
            camBox.visibility = View.VISIBLE
            camBox.background = rounded(if (Live.camKind == CamKind.SPEED) danger else orange, 10f * builtScale)
            camTitle.text = CamKind.short(Live.camKind)
            camDist.text = "${Live.camDistance} m"
        } else {
            camBox.visibility = View.GONE
        }
    }

    fun hide() {
        val r = root ?: return
        try {
            wm.removeView(r)
        } catch (_: Exception) {
        }
        root = null
    }
}
