package vn.hiep.motonav

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Hướng của điện thoại (la bàn + con quay hồi chuyển, cảm biến ROTATION_VECTOR).
 * Trả về hướng mà mặt trước màn hình "nhìn tới" – dùng được cả khi điện thoại
 * nằm ngang trên bàn lẫn khi gắn đứng trên giá xe, dọc hay ngang.
 */
class PhoneCompass(private val ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val r = FloatArray(9)
    private var sx = 0.0
    private var sy = 0.0
    private var has = false
    private var declination = 0f
    private var declAt = 0L

    /** Hướng thật (độ, 0 = Bắc), -1 nếu chưa có. */
    @Volatile var bearing = -1.0
        private set

    val available: Boolean get() = sensor != null
    private var running = false

    fun start() {
        if (running || sensor == null) return
        running = true
        has = false
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!running) return
        running = false
        sm.unregisterListener(this)
        bearing = -1.0
    }

    @Suppress("DEPRECATION")
    private fun rotation(): Int = try {
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    } catch (_: Exception) {
        Surface.ROTATION_0
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, e.values)
        // Cột của ma trận = trục máy trong hệ (Đông, Bắc, Lên)
        // Hướng "lên" của màn hình theo chiều xoay hiện tại
        val (ue, un) = when (rotation()) {
            Surface.ROTATION_90 -> -r[0] to -r[3]
            Surface.ROTATION_180 -> -r[1] to -r[4]
            Surface.ROTATION_270 -> r[0] to r[3]
            else -> r[1] to r[4]
        }
        // Mặt lưng máy (-Z): hướng nhìn khi điện thoại dựng đứng
        val be = -r[2]
        val bn = -r[5]
        val fe = (ue + be).toDouble()
        val fn = (un + bn).toDouble()
        val len = sqrt(fe * fe + fn * fn)
        if (len < 0.15) return // máy úp/ngửa lưng chừng, không rõ hướng
        val ex = fe / len
        val ey = fn / len
        // Lọc mượt (vector đơn vị để không nhảy qua 0°/360°)
        if (!has) {
            sx = ex; sy = ey; has = true
        } else {
            val a = 0.12
            sx += a * (ex - sx); sy += a * (ey - sy)
        }
        val now = System.currentTimeMillis()
        if (now - declAt > 600_000 && Live.gpsOk) {
            declAt = now
            declination = try {
                GeomagneticField(Live.lat.toFloat(), Live.lon.toFloat(), 0f, now).declination
            } catch (_: Exception) {
                0f
            }
        }
        bearing = Geo.norm(Math.toDegrees(atan2(sx, sy)) + declination)
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    companion object {
        /** Nội suy góc theo đường ngắn nhất. */
        fun lerpAngle(from: Double, to: Double, t: Double): Double {
            var d = (to - from) % 360.0
            if (d > 180) d -= 360.0
            if (d < -180) d += 360.0
            return Geo.norm(from + d * t)
        }
    }
}
