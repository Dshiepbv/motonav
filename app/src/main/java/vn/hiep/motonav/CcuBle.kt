package vn.hiep.motonav

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bước 2/3 của "bắt cặp BLE" (Y-Connect) — dò ngược từ lớp n3.o của NConnect: sau khi có mã CCU +
 * passkey (xem [CcuAuth]), quét Bluetooth năng lượng thấp (BLE) tìm đúng màn xe theo tên quảng bá
 * "YCCU_<mã CCU>", mở kết nối GATT, dò dịch vụ, rồi đăng ký nhận dữ liệu (notify/indicate) trên 7
 * characteristic mà app gốc đăng ký. CHƯA xác thực (bước 3, dùng mã CCU + passkey) — bản này chỉ
 * để xác nhận bước kết nối/đăng ký tự nó có chạy đúng trên thiết bị thật không.
 */
object CcuBle {
    private val SERVICE_UUID = UUID.fromString("AFA2CDF4-ECCF-46A7-A5EA-9DA428C0157A")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // 7 characteristic app gốc đăng ký nhận dữ liệu — (UUID, dùng indicate thay vì notify?)
    private val NOTIFY_CHARS = listOf(
        UUID.fromString("9C810D26-B605-4306-8C1C-755A1BA3066C") to false,
        UUID.fromString("75AB2788-87F6-4F61-BA9C-6F85A12600D1") to false, // DDT
        UUID.fromString("77808451-E42F-4F6B-83ED-3AB44AA3D16A") to false,
        UUID.fromString("347456EE-A588-4DD5-BD98-62BE3672E27A") to false,
        UUID.fromString("E974B9A1-FA31-43C6-91AE-09931F3F6EF1") to false,
        UUID.fromString("56DC2D58-CBE1-4BA9-A104-E9B8EB4DA95C") to true,
        UUID.fromString("AB8D233A-CC58-4932-B397-A3751911E021") to true,
    )

    @Volatile var running = false
        private set
    @Volatile var stage: String = "Chưa thử"
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var subscribedCount = 0
        private set
    val expectedCount = NOTIFY_CHARS.size

    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    @Volatile private var gatt: BluetoothGatt? = null
    private val descQueue = ArrayDeque<BluetoothGattDescriptor>()
    @Volatile private var attempt = 0 // 0, 1, 2 — xe gốc cũng thử tối đa 3 lần mới bỏ cuộc

    fun connectOnce(ctx: Context, ccuCode: String?, passkey: String?) {
        if (ccuCode.isNullOrBlank()) {
            error = "Chưa có mã CCU — bấm bước 1 trước"
            return
        }
        if (!busy.compareAndSet(false, true)) return
        running = true
        error = null
        subscribedCount = 0
        attempt = 0
        stage = "Đang quét BLE tìm xe…"
        val targetName = "YCCU_$ccuCode"
        try {
            val a = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (a == null || !a.isEnabled) {
                fail("Bluetooth đang tắt")
                return
            }
            val scanner = a.bluetoothLeScanner
            if (scanner == null) {
                fail("Máy không hỗ trợ quét BLE")
                return
            }
            val found = AtomicBoolean(false)
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = try { result.device.name } catch (_: SecurityException) { null }
                    if (name == targetName && found.compareAndSet(false, true)) {
                        try { scanner.stopScan(this) } catch (_: Exception) {}
                        TripLog.ev("ccuBle", "found" to name, "addr" to result.device.address)
                        connectGatt(ctx, result.device)
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    fail("Quét BLE lỗi (mã $errorCode)")
                }
            }
            try {
                scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            } catch (e: SecurityException) {
                fail("Thiếu quyền quét Bluetooth")
                return
            }
            handler.postDelayed({
                if (found.compareAndSet(false, true)) {
                    try { scanner.stopScan(cb) } catch (_: Exception) {}
                    fail("Không tìm thấy \"$targetName\" qua BLE sau 10 giây (xe đã bật, trong tầm sóng?)")
                }
            }, 10_000)
        } catch (e: SecurityException) {
            fail("Thiếu quyền Bluetooth")
        } catch (e: Exception) {
            fail(e.message ?: "Lỗi không rõ")
        }
    }

    private fun fail(msg: String) {
        error = msg
        stage = "Lỗi"
        running = false
        busy.set(false)
    }

    private fun connectGatt(ctx: Context, device: BluetoothDevice) {
        // Autoconnect=true ở lần thử thứ 3 (giống app gốc: "cách i2+1/3", lần cuối tự kết nối nền)
        val autoConnect = attempt == 2
        stage = if (attempt == 0) "Đang mở kết nối GATT…" else "Đang mở kết nối GATT (lần thử ${attempt + 1}/3)…"
        try {
            try { gatt?.close() } catch (_: Exception) {}
            val g = device.connectGatt(ctx, autoConnect, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        stage = "Đã nối GATT, đang dò dịch vụ…"
                        try { g.discoverServices() } catch (_: Exception) {}
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        try { g.close() } catch (_: Exception) {}
                        gatt = null
                        if (!running) return
                        if (attempt < 2) {
                            attempt++
                            TripLog.ev("ccuBle", "retry" to attempt, "status" to status)
                            handler.postDelayed({ if (running) connectGatt(ctx, device) }, 1500)
                        } else {
                            fail("Mất kết nối GATT sau 3 lần thử (status=$status)")
                        }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail("Dò dịch vụ GATT lỗi (status=$status)")
                        return
                    }
                    val service = g.getService(SERVICE_UUID)
                    if (service == null) {
                        fail("Không thấy dịch vụ ${SERVICE_UUID} trên xe")
                        return
                    }
                    stage = "Đã thấy dịch vụ, đang đăng ký nhận dữ liệu…"
                    descQueue.clear()
                    var missing = 0
                    for ((uuid, indicate) in NOTIFY_CHARS) {
                        val c = service.getCharacteristic(uuid)
                        if (c == null) {
                            missing++
                            TripLog.ev("ccuBle", "missingChar" to uuid.toString())
                            continue
                        }
                        try { g.setCharacteristicNotification(c, true) } catch (_: Exception) {}
                        val d = c.getDescriptor(CCCD_UUID)
                        if (d != null) descQueue.addLast(d.also { it.setIndicateOrNotify(indicate) })
                    }
                    if (missing > 0) TripLog.ev("ccuBle", "missingCount" to missing)
                    writeNextDescriptor(g)
                }

                override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) subscribedCount++
                    writeNextDescriptor(g)
                }
            }, BluetoothDevice.TRANSPORT_LE)
            gatt = g
        } catch (e: SecurityException) {
            fail("Thiếu quyền kết nối Bluetooth")
        } catch (e: Exception) {
            fail(e.message ?: "Không mở được GATT")
        }
    }

    private fun BluetoothGattDescriptor.setIndicateOrNotify(indicate: Boolean) {
        @Suppress("DEPRECATION")
        value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    }

    private fun writeNextDescriptor(g: BluetoothGatt) {
        val d = descQueue.pollFirst()
        if (d == null) {
            stage = "Xong — đã đăng ký $subscribedCount/$expectedCount characteristic"
            running = false
            busy.set(false)
            return
        }
        try {
            @Suppress("DEPRECATION")
            val ok = g.writeDescriptor(d)
            if (!ok) writeNextDescriptor(g)
        } catch (e: SecurityException) {
            fail("Thiếu quyền ghi GATT")
        } catch (e: Exception) {
            writeNextDescriptor(g)
        }
    }
}
