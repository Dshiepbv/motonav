package vn.hiep.motonav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View

/** Nút báo cảnh báo hình tam giác vàng nhạt có dấu "!". */
class WarnButton(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE58A")
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3D3200")
        style = Paint.Style.STROKE
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3D3200")
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    init {
        isClickable = true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w * 0.12f
        fill.pathEffect = CornerPathEffect(r)
        stroke.pathEffect = CornerPathEffect(r)
        shadow.pathEffect = CornerPathEffect(r)
        stroke.strokeWidth = w * 0.06f
        fun tri(dy: Float) = Path().apply {
            moveTo(w / 2, h * 0.08f + dy)
            lineTo(w * 0.95f, h * 0.9f + dy)
            lineTo(w * 0.05f, h * 0.9f + dy)
            close()
        }
        c.drawPath(tri(h * 0.04f), shadow)
        val t = tri(0f)
        c.drawPath(t, fill)
        c.drawPath(t, stroke)
        mark.textSize = h * 0.5f
        c.drawText("!", w / 2, h * 0.8f, mark)
    }
}
