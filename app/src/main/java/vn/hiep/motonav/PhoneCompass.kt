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
 * Hướng của điện thoại theo la bàn (từ trường + gia tốc, giống Google Maps).
 * Trả về hướng mà mặt trước màn hình "nhìn tới" – dùng được cả khi điện thoại
 * nằm ngang trên bàn lẫn khi gắn đứng trên giá xe, dọc hay ngang.
 */
class PhoneCompass(private val ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    // La bàn kiểu Google Maps: cảm biến từ trường + gia tốc (không dùng con quay)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnet: Sensor? = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val r = FloatArray(9)
    private val grav = FloatArray(3)
    private val mag = FloatArray(3)
    private var hasG = false
    private var hasM = false
    private var sx = 0.0
    private var sy = 0.0
    private var has = false
    private var declination = 0f
    private var declAt = 0L

    /** Hướng thật (độ, 0 = Bắc), -1 nếu chưa có. */
    @Volatile var bearing = -1.0
        private set

    val available: Boolean get() = accel != null && magnet != null
    private var running = false

    fun start() {
        if (running || !available) return
        running = true
        has = false; hasG = false; hasM = false
        sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
        sm.registerListener(this, magnet, SensorManager.SENSOR_DELAY_UI)
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
        val a = 0.15f
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                if (!hasG) { e.values.copyInto(grav, 0, 0, 3); hasG = true }
                else for (i in 0..2) grav[i] += a * (e.values[i] - grav[i])
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (!hasM) { e.values.copyInto(mag, 0, 0, 3); hasM = true }
                else for (i in 0..2) mag[i] += a * (e.values[i] - mag[i])
            }
            else -> return
        }
        if (!hasG || !hasM) return
        if (!SensorManager.getRotationMatrix(r, null, grav, mag)) return
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
            val k = 0.2
            sx += k * (ex - sx); sy += k * (ey - sy)
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
