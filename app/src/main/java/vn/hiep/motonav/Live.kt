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
    /** Khoảng cách tới chỗ rẽ (mét), -1 nếu không đọc được. */
    @Volatile var navMeters = -1

    // Vị trí và tốc độ
    @Volatile var serviceRunning = false
    @Volatile var userStopped = false
    @Volatile var appVisible = false
    /** null = không biết (chưa có quyền), true/false = Google Maps đang/không ở màn hình */
    @Volatile var mapsForeground: Boolean? = null
    @Volatile var gpsOk = false
    @Volatile var lat = 0.0
    @Volatile var lon = 0.0
    @Volatile var heading = -1.0
    /** Hướng của con đường phía trước (tuyến dẫn đường hoặc đoạn đường đang đi), -1 nếu chưa biết. */
    @Volatile var roadBearing = -1.0
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

    // Dẫn đường riêng (VietMap)
    @Volatile var ownNav = false
    @Volatile var route: Route? = null
    @Volatile var routeVersion = 0
    @Volatile var routeStatus: String? = null
    @Volatile var routeError: String? = null
    @Volatile var destLat = 0.0
    @Volatile var destLon = 0.0
    @Volatile var destName: String? = null
    @Volatile var remainingM = 0.0
    @Volatile var etaMs = 0L
    @Volatile var snapLat = 0.0
    @Volatile var snapLon = 0.0
    @Volatile var offRoute = false
    @Volatile var maneuverLat = 0.0
    @Volatile var maneuverLon = 0.0
    @Volatile var maneuverLabel: String? = null
    @Volatile var maneuverSign = 0
    @Volatile var thenText: String? = null
    @Volatile var thenSign = 0
    /** Các tuyến để chọn (tuyến đang đi là phần tử [choiceSelected]). */
    @Volatile var routeChoices: List<Route> = emptyList()
    @Volatile var choiceSelected = 0
    @Volatile var choicesVersion = 0
    @Volatile var maneuverStreet: String? = null
    @Volatile var routeSeg = 0
    @Volatile var routeProgress = 0.0
    @Volatile var nextLeg: String? = null       // GeoJSON đoạn đường sắp rẽ vào (tô sáng)
    @Volatile var junctionJson: String? = null  // GeoJSON nhãn tên các ngã rẽ phía trước
    @Volatile var junctionHasTurn = false
    @Volatile var text3dJson: String? = null
    @Volatile var text3dVersion = 0
    @Volatile var junctionVersion = 0
    @Volatile var osm: OsmSnapshot? = null

    // Tốc độ giới hạn sắp đổi phía trước (dọc tuyến)
    @Volatile var nextLimit: Int? = null
    @Volatile var nextLimitDist = -1
    @Volatile var nextLimitLat = 0.0
    @Volatile var nextLimitLon = 0.0
    @Volatile var nextLimitEst = false

    // Biểu tượng cảnh báo trên bản đồ (GeoJSON)
    @Volatile var alertsJson: String? = null
    @Volatile var alertsVersion = 0

    // Khu dân cư & tuyến có camera
    @Volatile var urban: Boolean? = null
    @Volatile var urbanEst = true
    @Volatile var camStreet: String? = null
    @Volatile var officialProgress: String? = null

    /** Biển báo phía trước: (loại, giá trị, khoảng cách m; -1 = đoạn đang đi). Tối đa 3. */
    @Volatile var signsAhead: List<Triple<Int, Int, Int>> = emptyList()
    /** Biển báo phía trước có toạ độ, để cắm lên bản đồ (GeoJSON). */
    @Volatile var signsJson: String? = null
    @Volatile var signsVersion = 0

    // Thời tiết dọc tuyến
    @Volatile var wxJson: String? = null
    @Volatile var wxVersion = 0
    @Volatile var wxSummary: String? = null

    // Dải chữ cảnh báo (hiện kèm mọi âm thanh)
    @Volatile var alertText: String? = null
    @Volatile var alertUntil = 0L
    @Volatile var alertUrgent = false

    fun activeAlert(): String? =
        if (android.os.SystemClock.elapsedRealtime() < alertUntil) alertText else null

    private val meterRe = Regex("""(\d+(?:[.,]\d+)?)\s?(km|m|ft|mi)""", RegexOption.IGNORE_CASE)

    fun parseMeters(d: String?): Int {
        val m = meterRe.find(d ?: return -1) ?: return -1
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return -1
        return when (m.groupValues[2].lowercase()) {
            "km" -> (v * 1000).toInt()
            "mi" -> (v * 1609).toInt()
            "ft" -> (v * 0.3048).toInt()
            else -> v.toInt()
        }
    }

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
        navMeters = parseMeters(distance)
        changed()
    }

    fun clearNav() {
        navActive = false
        navArrow = null
        navDistance = null
        navInstruction = null
        navExtra = null
        navMeters = -1
        changed()
    }
}
