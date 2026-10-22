package vn.hiep.motonav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

/** Biển tốc độ giới hạn tròn viền đỏ. */
class SpeedSignView(ctx: Context) : View(ctx) {
    var limit: Int? = null
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    var estimated = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E53935") }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val grey = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3F47")
        style = Paint.Style.STROKE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val greyText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280")
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.96f
        val lim = limit
        if (lim == null) {
            grey.strokeWidth = r * 0.14f
            canvas.drawCircle(cx, cy, r - grey.strokeWidth / 2, grey)
            greyText.textSize = r * 0.7f
            canvas.drawText("--", cx, cy + greyText.textSize * 0.35f, greyText)
            return
        }
        red.alpha = if (estimated) 170 else 255
        canvas.drawCircle(cx, cy, r, red)
        canvas.drawCircle(cx, cy, r * 0.78f, white)
        val s = lim.toString()
        text.textSize = if (s.length >= 3) r * 0.75f else r * 0.95f
        canvas.drawText(s, cx, cy + text.textSize * 0.35f, text)
    }
}
