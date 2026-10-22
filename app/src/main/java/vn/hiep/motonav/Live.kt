package vn.hiep.motonav

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Trạng thái hiện tại, chia sẻ giữa dịch vụ chạy nền và màn hình. */
object Live {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    // Dẫn đường (đọc từ thông báo Google Maps)
    @Volatile var navActive = false
    @Volatile var navArrow: Bitmap? = null
    @Volatile var navDistance: String? = null
    @Volatile var navInstruction: String? = null
    @Volatile var navExtra: String? = null

    // Vị trí và tốc độ
    @Volatile var serviceRunning = false
    @Volatile var userStopped = false
    @Volatile var appVisible = false
    @Volatile var gpsOk = false
    @Volatile var lat = 0.0
    @Volatile var lon = 0.0
    @Volatile var heading = -1.0
    @Volatile var speedKmh = -1

    // Tốc độ giới hạn
    @Volatile var limit: Int? = null
    @Volatile var limitEstimated = false
    @Volatile var street: String? = null
    @Volatile var overspeed = false

    // Camera
    @Volatile var camText: String? = null
    @Volatile var camDistance = 0
    @Volatile var camKind = -1

    @Volatile var status: String = ""

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    fun changed() {
        main.post { for (l in listeners) l() }
    }

    fun setNav(arrow: Bitmap?, distance: String?, instruction: String?, extra: String?) {
        navActive = true
        navArrow = arrow
        navDistance = distance
        navInstruction = instruction
        navExtra = extra
        changed()
    }

    fun clearNav() {
        navActive = false
        navArrow = null
        navDistance = null
        navInstruction = null
        navExtra = null
        changed()
    }
}
