package vn.hiep.motonav

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * build-393 (Sếp: "bên luồng BLE có gì can thiệp"): chỉ NGHE thụ động gói quảng bá BLE của xe (CCU/TFT) trong lúc
 * dẫn đường, ghi vào log để đối chiếu với các lần màn xe kẹt nhịp 10 s. Không kết nối, không gửi gì cho xe.
 * Mỗi giây ghi 1 dòng `bleAdv` cho mỗi thiết bị của xe: số gói, RSSI, khe hở lớn nhất giữa 2 gói; ghi thêm
 * `bleAdvData` khi nội dung gói quảng bá đổi, và `bleAdvGap` ngay khi xe im lặng > 1,5 s rồi phát lại.
 */
@SuppressLint("MissingPermission")
object BikeBleWatch {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cb: ScanCallback? = null
    private var tftAddr: String = ""
    private var tftName: String = ""
    private class Acc { var n = 0; var rssiSum = 0; var maxGap = 0L; var last = 0L; var data = ""; var name = ""; var conn = false }
    private val acc = HashMap<String, Acc>()
    private val seenOther = HashSet<String>()
    private var restartAt = 0L
    private var allN = 0
    private var pausedUntil = 0L
    private var paused = false

    private fun isBike(addr: String, name: String?): Boolean {
        if (addr.equals(tftAddr, true)) return true
        val nm = name ?: return false
        if (tftName.isNotEmpty() && nm == tftName) return true
        val u = nm.uppercase()
        return u.startsWith("YCCU") || u.contains("YAMAHA") || u.contains("NMAX")
    }

    fun start(ctx: Context, addr: String) {
        if (cb != null) return
        val a = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        if (!a.isEnabled) return
        val sc = a.bluetoothLeScanner ?: return
        tftAddr = addr
        tftName = try { if (addr.isNotBlank()) a.getRemoteDevice(addr).name ?: "" else "" } catch (_: Exception) { "" }
        val c = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val ad = r.device.address ?: return
                val nm = try { r.device.name } catch (_: SecurityException) { null } ?: r.scanRecord?.deviceName
                if (!isBike(ad, nm)) {
                    // Gói của xe có thể không mang tên / dùng địa chỉ ngẫu nhiên → ghi 1 lần mỗi thiết bị lạ (tối đa 300) để soi sau.
                    synchronized(acc) { allN++; if (seenOther.size < 300 && seenOther.add(ad)) TripLog.ev("bleSeen", "addr" to ad, "name" to (nm ?: ""), "rssi" to r.rssi,
                        "mfr" to (r.scanRecord?.manufacturerSpecificData?.let { m -> (0 until m.size()).joinToString(",") { Integer.toHexString(m.keyAt(it)) } } ?: "")) }
                    return
                }
                val now = SystemClock.elapsedRealtime()
                val hex = r.scanRecord?.bytes?.let { b -> val n = b.size.coerceAtMost(40); buildString { for (i in 0 until n) append(String.format("%02x", b[i])) } }?.trimEnd('0') ?: ""
                synchronized(acc) {
                    val x = acc.getOrPut(ad) { Acc() }
                    if (x.last > 0) {
                        val g = now - x.last
                        if (g > x.maxGap) x.maxGap = g
                        if (g > 1500) TripLog.ev("bleAdvGap", "addr" to ad, "name" to (nm ?: ""), "gapMs" to g)
                    }
                    x.last = now; x.n++; x.rssiSum += r.rssi; x.name = nm ?: x.name; x.conn = r.isConnectable
                    if (hex != x.data) { x.data = hex; TripLog.ev("bleAdvData", "addr" to ad, "name" to (nm ?: ""), "hex" to hex, "conn" to r.isConnectable, "rssi" to r.rssi) }
                }
            }
            override fun onScanFailed(errorCode: Int) { TripLog.ev("bleAdvErr", "code" to errorCode); cb = null }
        }
        try {
            sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).setReportDelay(0).build(), c)
        } catch (e: Exception) { TripLog.ev("bleAdvErr", "msg" to (e.message ?: e.javaClass.simpleName)); return }
        cb = c
        restartAt = SystemClock.elapsedRealtime() + 25 * 60_000L
        TripLog.ev("bleAdvStart", "tftAddr" to addr, "tftName" to tftName)
        main.postDelayed(tick(ctx, sc), 1000)
    }

    private fun tick(ctx: Context, sc: android.bluetooth.le.BluetoothLeScanner): Runnable = object : Runnable {
        override fun run() {
            val c = cb ?: return
            synchronized(acc) {
                val now = SystemClock.elapsedRealtime()
                for ((ad, x) in acc) {
                    val silent = if (x.last > 0) now - x.last else -1
                    TripLog.ev("bleAdv", "addr" to ad, "name" to x.name, "n" to x.n, "rssi" to (if (x.n > 0) x.rssiSum / x.n else 0), "maxGapMs" to x.maxGap, "silentMs" to silent, "conn" to x.conn)
                    x.n = 0; x.rssiSum = 0; x.maxGap = 0
                }
                if (acc.isEmpty() || now / 1000 % 10 == 0L) TripLog.ev("bleAll", "n" to allN, "devs" to seenOther.size)
                allN = 0
            }
            // build-395: bảo vệ đường truyền ảnh — xe im (không có gói 0x41) > 3 s → ngừng quét 20 s.
            val now2 = SystemClock.elapsedRealtime()
            val quiet = TftLink.msSinceBike41()
            if (!paused && quiet > 3000) {
                try { sc.stopScan(c) } catch (_: Exception) {}
                paused = true; pausedUntil = now2 + 20_000L
                TripLog.ev("bleAdvPause", "bikeQuietMs" to quiet)
            } else if (paused && now2 >= pausedUntil) {
                try { sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), c) } catch (_: Exception) {}
                paused = false; restartAt = now2 + 25 * 60_000L
                TripLog.ev("bleAdvResume", "bikeQuietMs" to quiet)
            }
            // Android tự hạ máy quét chạy > 30 phút xuống chế độ yếu → khởi động lại sau 25 phút.
            if (!paused && SystemClock.elapsedRealtime() >= restartAt) {
                try { sc.stopScan(c) } catch (_: Exception) {}
                try { sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), c) } catch (_: Exception) {}
                restartAt = SystemClock.elapsedRealtime() + 25 * 60_000L
                TripLog.ev("bleAdvRestart")
            }
            main.postDelayed(this, 1000)
        }
    }

    fun stop(ctx: Context) {
        val c = cb ?: return
        cb = null; paused = false
        try { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner?.stopScan(c) } catch (_: Exception) {}
        main.removeCallbacksAndMessages(null)
        synchronized(acc) { acc.clear(); seenOther.clear(); allN = 0 }
        TripLog.ev("bleAdvStop")
    }
}
