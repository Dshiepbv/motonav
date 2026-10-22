package vn.hiep.motonav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * Biển "Bắt đầu khu đông dân cư" (xanh, hình nhà) / "Hết khu đông dân cư" (có gạch chéo đỏ).
 * urban = null thì ẩn.
 */
class UrbanSignView(ctx: Context) : View(ctx) {
    var urban: Boolean? = null
        set(v) {
            if (field != v) {
                field = v
                visibility = if (v == null) GONE else VISIBLE
                invalidate()
            }
        }
    var estimated = true
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }

    override fun onDraw(c: Canvas) {
        val u = urban ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w * 0.12f
        bg.color = if (u) Color.parseColor("#1565C0") else Color.parseColor("#455A64")
        val rect = RectF(0f, 0f, w, h)
        c.drawRoundRect(rect, r, r, bg)
        border.strokeWidth = w * 0.04f
        val inset = w * 0.06f
        c.drawRoundRect(RectF(inset, inset, w - inset, h - inset), r * 0.7f, r * 0.7f, border)

        // Dãy nhà
        val baseY = h * 0.62f
        val left = w * 0.16f
        val right = w * 0.84f
        val unit = (right - left) / 4
        val p = Path()
        p.moveTo(left, baseY)
        p.lineTo(left, h * 0.42f); p.lineTo(left + unit * 0.5f, h * 0.30f); p.lineTo(left + unit, h * 0.42f)
        p.lineTo(left + unit, h * 0.24f); p.lineTo(left + unit * 2, h * 0.24f)
        p.lineTo(left + unit * 2, h * 0.36f); p.lineTo(left + unit * 2.5f, h * 0.26f); p.lineTo(left + unit * 3, h * 0.36f)
        p.lineTo(left + unit * 3, h * 0.30f); p.lineTo(right, h * 0.30f)
        p.lineTo(right, baseY)
        p.close()
        c.drawPath(p, white)

        txt.textSize = h * 0.13f
        c.drawText(if (u) "KHU DÂN CƯ" else "NGOÀI KDC", w / 2, h * 0.82f, txt)
        if (estimated) {
            txt.textSize = h * 0.09f
            c.drawText("ước tính", w / 2, h * 0.93f, txt)
        }

        if (!u) {
            red.strokeWidth = w * 0.08f
            c.drawLine(w * 0.12f, h * 0.88f, w * 0.88f, h * 0.12f, red)
        }
    }
}
