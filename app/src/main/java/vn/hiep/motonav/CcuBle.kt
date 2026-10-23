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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bước 2+3 của "bắt cặp BLE" (Y-Connect) — dò ngược từ lớp n3.o của NConnect: sau khi có mã CCU +
 * passkey (xem [CcuAuth]), quét Bluetooth năng lượng thấp (BLE) tìm đúng màn xe theo tên quảng bá
 * "YCCU_<mã CCU>", mở kết nối GATT, dò dịch vụ, đăng ký nhận dữ liệu (notify/indicate) trên 7
 * characteristic mà app gốc đăng ký, rồi gửi "hello" + "AuthenticationV2" (mã CCU + passkey) qua
 * kênh DDT (giống bước 4/5 trong n3.o.java — hàm m()/f()/g()). Lưu ý: khuôn gói "khởi đầu DDT"
 * (hàm f() trong bản gốc, gọi queue op "E1(gatt,13,characteristic)") không dò ra được chính xác từ
 * mã đã giải dịch (lớp D.E1 không đọc được) — phần đó là suy đoán hợp lý nhất, cần xe thật xác nhận.
 */
object CcuBle {
    private val SERVICE_UUID = UUID.fromString("AFA2CDF4-ECCF-46A7-A5EA-9DA428C0157A")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Kênh "hello"/lệnh (f8736L trong bản gốc) — chỉ ghi, không đăng ký notify
    private val HELLO_UUID = UUID.fromString("B606C7F9-E5A1-4E75-B313-2A920054A8EB")
    // Kênh dữ liệu DDT từng đoạn (f8740P) — chỉ ghi, không đăng ký notify
    private val DATA_UUID = UUID.fromString("E998AEAB-EA8F-4063-83AA-2017C1A44484")

    // 7 characteristic app gốc đăng ký nhận dữ liệu — (UUID, dùng indicate thay vì notify?)
    private val NOTIFY_CHARS = listOf(
        UUID.fromString("9C810D26-B605-4306-8C1C-755A1BA3066C") to false,
        UUID.fromString("75AB2788-87F6-4F61-BA9C-6F85A12600D1") to false, // DDT (f8738N) — cũng là kênh "khởi đầu" ghi ở bước 3
        UUID.fromString("77808451-E42F-4F6B-83ED-3AB44AA3D16A") to false,
        UUID.fromString("347456EE-A588-4DD5-BD98-62BE3672E27A") to false,
        UUID.fromString("E974B9A1-FA31-43C6-91AE-09931F3F6EF1") to false,
        UUID.fromString("56DC2D58-CBE1-4BA9-A104-E9B8EB4DA95C") to true,
        UUID.fromString("AB8D233A-CC58-4932-B397-A3751911E021") to true,
    )
    private val DDT_UUID = NOTIFY_CHARS[1].first

    @Volatile var running = false
        private set
    @Volatile var stage: String = "Chưa thử"
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var subscribedCount = 0
        private set
    val expectedCount = NOTIFY_CHARS.size
    @Volatile var missingChars: String? = null // UUID rút gọn các characteristic KHÔNG đăng ký được, để debug
        private set

    // Bước 3: gửi hello + AuthenticationV2
    @Volatile var authStage: String = "Chưa gửi"
        private set
    @Volatile var authError: String? = null
        private set
    @Volatile var lastNotify: String? = null // gói notify/indicate gần nhất xe gửi về (để debug phản hồi)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    @Volatile private var gatt: BluetoothGatt? = null
    private val descQueue = ArrayDeque<BluetoothGattDescriptor>()
    private val missingUuids = mutableListOf<String>()
    @Volatile private var attempt = 0 // 0, 1, 2 — xe gốc cũng thử tối đa 3 lần mới bỏ cuộc
    @Volatile private var connGen = 0 // đổi mỗi lần bắt đầu/hết một lượt kết nối GATT, để huỷ timeout cũ
    @Volatile private var seqCounter = 0
    @Volatile private var authCcuCode: String? = null
    @Volatile private var authPasskey: String? = null

    // Liên kết BLE này hay nghẽn/rớt ngay cả với app gốc (xác nhận thực tế: phải tắt/bật Bluetooth
    // hoặc bấm thử lại nhiều lần mới vào được) — nên tự lặp lại CẢ vòng quét+kết nối vài lần thay vì
    // bắt người dùng phải tự bấm lại, giống kiểu "bấm qua bấm lại" mà các app khác cũng cần.
    private const val MAX_ROUNDS = 4
    @Volatile private var round = 0
    @Volatile private var savedCtx: Context? = null
    @Volatile private var savedDeviceAddr: String? = null
    @Volatile private var btReceiver: BroadcastReceiver? = null

    private class WriteOp(val uuid: UUID, val value: ByteArray)
    private val writeQueue = ArrayDeque<WriteOp>()

    private fun nextSeq(): Int {
        seqCounter = (seqCounter + 1) and 0xFF
        if (seqCounter == 0) seqCounter = 1
        return seqCounter
    }

    private fun shortU(u: UUID): String = u.toString().substring(0, 8)

    fun connectOnce(ctx: Context, deviceAddr: String, ccuCode: String?, passkey: String?) {
        if (ccuCode.isNullOrBlank() || passkey.isNullOrBlank()) {
            error = "Chưa có mã CCU/passkey — bấm bước 1 trước"
            return
        }
        if (!busy.compareAndSet(false, true)) {
            // Đang chạy dở (có thể bị kẹt do GATT treo) — huỷ lượt cũ, bắt đầu lại từ đầu thay vì im lặng
            TripLog.ev("ccuBle", "forceRestart" to true)
            running = false
            connGen++
            try { gatt?.disconnect() } catch (_: Exception) {}
            try { gatt?.close() } catch (_: Exception) {}
            gatt = null
            descQueue.clear()
            writeQueue.clear()
            unregisterBtReceiver()
            busy.set(false)
            if (!busy.compareAndSet(false, true)) return
        }
        running = true
        error = null
        authError = null
        authStage = "Chưa gửi"
        lastNotify = null
        subscribedCount = 0
        missingChars = null
        missingUuids.clear()
        attempt = 0
        round = 0
        authCcuCode = ccuCode
        authPasskey = passkey
        val appCtx = ctx.applicationContext ?: ctx
        savedCtx = appCtx
        savedDeviceAddr = deviceAddr
        val a = (appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (a == null || !a.isEnabled) {
            fail("Bluetooth đang tắt — anh bật Bluetooth lên rồi bấm lại nhé")
            return
        }
        // Theo dõi Bluetooth bị tắt GIỮA CHỪNG (vd người dùng tắt tay để "reset") — nếu không, cả
        // vòng quét/kết nối sẽ treo vô ích đến khi hết giờ thay vì báo lỗi ngay.
        unregisterBtReceiver()
        val recv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                    TripLog.ev("ccuBle", "btTurnedOffMidRun" to state)
                    fail("Bluetooth bị tắt giữa chừng — anh bật lại rồi bấm lại nhé")
                }
            }
        }
        btReceiver = recv
        try { appCtx.registerReceiver(recv, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)) } catch (_: Exception) {}
        startRound(appCtx, deviceAddr, ccuCode)
    }

    private fun unregisterBtReceiver() {
        val r = btReceiver
        if (r != null) {
            try { savedCtx?.unregisterReceiver(r) } catch (_: Exception) {}
            btReceiver = null
        }
    }

    private fun startRound(ctx: Context, deviceAddr: String, ccuCode: String) {
        stage = if (round == 0) "Đang quét BLE tìm xe…" else "Đang quét BLE tìm xe… (vòng ${round + 1}/$MAX_ROUNDS)"
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
                    retryRoundOrFail(ctx, deviceAddr, ccuCode, "Quét BLE lỗi (mã $errorCode)")
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
                    // Không thấy quảng bá BLE theo tên (hay gặp do throttle quét nền/ROM, hoặc địa chỉ BLE
                    // dùng dạng ngẫu nhiên khác gói quảng bá) — app gốc cũng có đường dự phòng này (hàm
                    // d() dùng thẳng địa chỉ Bluetooth cổ điển đã ghép đôi khi không quét được): thử kết
                    // nối GATT trực tiếp bằng địa chỉ MAC đã biết thay vì chịu thua.
                    TripLog.ev("ccuBle", "scanTimeoutFallbackDirect" to deviceAddr)
                    try {
                        val dev = a.getRemoteDevice(deviceAddr)
                        stage = "Không thấy qua quét — đang thử kết nối thẳng theo địa chỉ đã ghép đôi…"
                        connectGatt(ctx, dev)
                    } catch (e: Exception) {
                        retryRoundOrFail(ctx, deviceAddr, ccuCode, "Không tìm thấy \"$targetName\" qua BLE, và không kết nối thẳng được (${e.message})")
                    }
                }
            }, 10_000)
        } catch (e: SecurityException) {
            fail("Thiếu quyền Bluetooth")
        } catch (e: Exception) {
            retryRoundOrFail(ctx, deviceAddr, ccuCode, e.message ?: "Lỗi không rõ")
        }
    }

    // Liên kết BLE này hay nghẽn ngay cả với app gốc trên xe này (xác nhận thực tế) — thay vì báo lỗi
    // ngay, tự thử lại CẢ vòng (quét lại từ đầu) vài lần trước khi thật sự chịu thua.
    private fun retryRoundOrFail(ctx: Context, deviceAddr: String, ccuCode: String, msg: String) {
        if (!running) return
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        connGen++
        attempt = 0
        if (round < MAX_ROUNDS - 1) {
            round++
            TripLog.ev("ccuBle", "roundRetry" to round, "reason" to msg)
            stage = "Chưa được ($msg) — thử lại cả vòng (${round + 1}/$MAX_ROUNDS) sau 2 giây…"
            handler.postDelayed({ if (running) startRound(ctx, deviceAddr, ccuCode) }, 2_000)
        } else {
            fail("$msg (đã thử $MAX_ROUNDS vòng — thử tắt/bật Bluetooth trên máy rồi bấm lại)")
        }
    }

    private fun fail(msg: String) {
        error = msg
        stage = "Lỗi"
        running = false
        busy.set(false)
        unregisterBtReceiver()
    }

    private fun failAuth(msg: String) {
        unregisterBtReceiver()
        authError = msg
        authStage = "Lỗi"
        running = false
        busy.set(false)
    }

    private fun connectGatt(ctx: Context, device: BluetoothDevice) {
        // Autoconnect=true ở lần thử thứ 3 (giống app gốc: "cách i2+1/3", lần cuối tự kết nối nền)
        val autoConnect = attempt == 2
        stage = if (attempt == 0) "Đang mở kết nối GATT…" else "Đang mở kết nối GATT (lần thử ${attempt + 1}/3)…"
        val myGen = ++connGen
        // autoConnect=true (lần thử cuối) có thể treo vô thời hạn nếu xe không quảng bá kết nối được —
        // canh giờ như app gốc (d(): 35s bình thường / 45s khi autoConnect) để không bị kẹt mãi.
        handler.postDelayed({
            if (running && connGen == myGen) {
                TripLog.ev("ccuBle", "connectTimeout" to attempt)
                try { gatt?.disconnect() } catch (_: Exception) {}
                try { gatt?.close() } catch (_: Exception) {}
                gatt = null
                if (attempt < 2) {
                    attempt++
                    connectGatt(ctx, device)
                } else {
                    retryRoundOrFail(ctx, savedDeviceAddr ?: "", authCcuCode ?: "", "Kết nối GATT không phản hồi sau 3 lần thử (hết giờ)")
                }
            }
        }, if (autoConnect) 45_000 else 35_000)
        try {
            try { gatt?.close() } catch (_: Exception) {}
            val g = device.connectGatt(ctx, autoConnect, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        connGen++ // huỷ timeout đang chờ của lượt này
                        stage = "Đã nối GATT, đang dò dịch vụ…"
                        try { g.discoverServices() } catch (_: Exception) {}
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        try { g.close() } catch (_: Exception) {}
                        gatt = null
                        if (!running || connGen != myGen) return
                        connGen++
                        if (attempt < 2) {
                            attempt++
                            TripLog.ev("ccuBle", "retry" to attempt, "status" to status)
                            handler.postDelayed({ if (running) connectGatt(ctx, device) }, 1500)
                        } else {
                            retryRoundOrFail(ctx, savedDeviceAddr ?: "", authCcuCode ?: "", "Mất kết nối GATT sau 3 lần thử (status=$status)")
                        }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    connGen++ // huỷ timeout đang chờ của lượt này (đã có phản hồi, dù thành hay bại)
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        retryRoundOrFail(ctx, savedDeviceAddr ?: "", authCcuCode ?: "", "Dò dịch vụ GATT lỗi (status=$status)")
                        return
                    }
                    val service = g.getService(SERVICE_UUID)
                    if (service == null) {
                        retryRoundOrFail(ctx, savedDeviceAddr ?: "", authCcuCode ?: "", "Không thấy dịch vụ ${SERVICE_UUID} trên xe")
                        return
                    }
                    stage = "Đã thấy dịch vụ, đang đăng ký nhận dữ liệu…"
                    descQueue.clear()
                    for ((uuid, indicate) in NOTIFY_CHARS) {
                        val c = service.getCharacteristic(uuid)
                        if (c == null) {
                            missingUuids.add("${shortU(uuid)} (không có trên xe)")
                            TripLog.ev("ccuBle", "missingChar" to uuid.toString())
                            continue
                        }
                        try { g.setCharacteristicNotification(c, true) } catch (_: Exception) {}
                        val d = c.getDescriptor(CCCD_UUID)
                        if (d != null) {
                            descQueue.addLast(d.also { it.setIndicateOrNotify(indicate) })
                        } else {
                            missingUuids.add("${shortU(uuid)} (thiếu CCCD)")
                        }
                    }
                    writeNextDescriptor(g)
                }

                override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        subscribedCount++
                    } else {
                        missingUuids.add("${shortU(descriptor.characteristic.uuid)} (ghi CCCD lỗi status=$status)")
                        TripLog.ev("ccuBle", "cccdFail" to descriptor.characteristic.uuid.toString(), "status" to status)
                    }
                    writeNextDescriptor(g)
                }

                override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    TripLog.ev("ccuBle", "writeDone" to shortU(characteristic.uuid), "status" to status)
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failAuth("Ghi ${shortU(characteristic.uuid)} lỗi (status=$status)")
                        return
                    }
                    if (characteristic.uuid == HELLO_UUID) {
                        // Gửi "hello" xong — thử đọc kênh DDT trước khi gửi dữ liệu xác thực: DDT
                        // (75ab2788) không ghi được (đã xác nhận), nhưng có thể cần ĐỌC nó một lần để
                        // "đánh thức"/đồng bộ trạng thái phía xe trước khi nó chấp nhận ghi dữ liệu
                        // (lần trước ghi thẳng dữ liệu bị xe từ chối: status=3, write not permitted).
                        readDdtTrigger(g)
                    } else {
                        runNextWrite(g)
                    }
                }

                override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    @Suppress("DEPRECATION") val v = characteristic.value ?: ByteArray(0)
                    val hex = v.joinToString(" ") { String.format("%02X", it) }
                    TripLog.ev("ccuBle", "ddtRead" to shortU(characteristic.uuid), "status" to status, "hex" to hex)
                    authStage = "Đang gửi gói dữ liệu xác thực…"
                    runNextWrite(g)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    val v = characteristic.value ?: ByteArray(0)
                    val hex = v.joinToString(" ") { String.format("%02X", it) }
                    lastNotify = "${shortU(characteristic.uuid)}: $hex"
                    TripLog.ev("ccuBle", "notify" to shortU(characteristic.uuid), "hex" to hex)
                }
            }, BluetoothDevice.TRANSPORT_LE)
            gatt = g
        } catch (e: SecurityException) {
            fail("Thiếu quyền kết nối Bluetooth")
        } catch (e: Exception) {
            fail(e.message ?: "Không mở được GATT")
        }
    }

    private fun readDdtTrigger(g: BluetoothGatt) {
        val service = g.getService(SERVICE_UUID)
        val c = service?.getCharacteristic(DDT_UUID)
        if (c == null) {
            TripLog.ev("ccuBle", "ddtReadSkip" to "characteristic null")
            authStage = "Đang gửi gói dữ liệu xác thực…"
            runNextWrite(g)
            return
        }
        authStage = "Đang đọc kênh DDT…"
        try {
            val ok = g.readCharacteristic(c)
            if (!ok) {
                TripLog.ev("ccuBle", "ddtReadSkip" to "readCharacteristic trả false")
                authStage = "Đang gửi gói dữ liệu xác thực…"
                runNextWrite(g)
            }
        } catch (e: Exception) {
            TripLog.ev("ccuBle", "ddtReadSkip" to (e.message ?: "lỗi"))
            authStage = "Đang gửi gói dữ liệu xác thực…"
            runNextWrite(g)
        }
    }

    private fun BluetoothGattDescriptor.setIndicateOrNotify(indicate: Boolean) {
        @Suppress("DEPRECATION")
        value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    }

    private fun writeNextDescriptor(g: BluetoothGatt) {
        val d = descQueue.pollFirst()
        if (d == null) {
            missingChars = if (missingUuids.isEmpty()) null else missingUuids.joinToString("; ")
            stage = "Đã đăng ký $subscribedCount/$expectedCount — đang gửi hello + xác thực…"
            startAuth(g)
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

    // Bước 3 (dò theo n3.j.java case 3 + default, n3.o.java m()/f()/g()):
    //   1) "hello" cmd=2 → kênh f8736L: [0x00,0x02, seq,0,0,0x12,0x34]
    //   2) AuthenticationV2 → đóng gói [1,1] + mã CCU (14B) + passkey (6B), gửi từng đoạn tới kênh
    //      f8740P. Trước đây đoán có thêm một gói "khởi đầu" ghi vào kênh DDT (f8738N, hàm f() gốc,
    //      op "E1(gatt,13,characteristic)") — test thật cho thấy characteristic đó KHÔNG ghi được
    //      (writeCharacteristic trả false ngay, tức sai kiểu ghi hoặc không có quyền ghi): nhiều khả
    //      năng "E1" không phải một lệnh ghi dữ liệu mà chỉ là thao tác đăng ký lại notify cho DDT
    //      (đã làm ở bước 2 rồi) — nên bỏ bước ghi riêng này, đi thẳng vào gửi từng đoạn dữ liệu.
    private fun startAuth(g: BluetoothGatt) {
        val code = authCcuCode
        val pass = authPasskey
        if (code == null || pass == null) {
            failAuth("Thiếu mã CCU/passkey")
            return
        }
        writeQueue.clear()
        val seqHello = nextSeq()
        writeQueue.addLast(WriteOp(HELLO_UUID, byteArrayOf(0x00, 0x02, seqHello.toByte(), 0, 0, 0x12, 0x34)))

        val authPayload = byteArrayOf(1, 1) + code.toByteArray(Charsets.US_ASCII) + pass.toByteArray(Charsets.US_ASCII)
        val seqAuth = nextSeq()

        var offset = 0
        val chunkSize = 20 // giống g() gốc: max(20, min(180, mtu-4)) với mtu mặc định 23
        while (offset < authPayload.size) {
            val end = minOf(authPayload.size, offset + chunkSize)
            val header = byteArrayOf(seqAuth.toByte(), (offset ushr 16).toByte(), (offset ushr 8).toByte(), offset.toByte())
            writeQueue.addLast(WriteOp(DATA_UUID, header + authPayload.copyOfRange(offset, end)))
            offset = end
        }
        authStage = "Đang gửi hello…"
        runNextWrite(g)
    }

    @Suppress("DEPRECATION")
    private fun runNextWrite(g: BluetoothGatt) {
        val op = writeQueue.pollFirst()
        if (op == null) {
            authStage = "Đã gửi xong hello + AuthenticationV2 — chờ xe phản hồi (xem mục notify)"
            running = false
            busy.set(false)
            return
        }
        val service = g.getService(SERVICE_UUID)
        val c = service?.getCharacteristic(op.uuid)
        if (c == null) {
            failAuth("Không thấy characteristic ${shortU(op.uuid)} để ghi")
            return
        }
        authStage = when (op.uuid) {
            HELLO_UUID -> "Đang gửi hello…"
            else -> "Đang gửi gói dữ liệu xác thực…"
        }
        // Chọn đúng kiểu ghi theo property thật của characteristic — ghi sai kiểu (có hồi đáp/không hồi
        // đáp) khiến writeCharacteristic() trả false ngay mà không hề chạm tới sóng. Nếu characteristic
        // không hỗ trợ ghi kiểu nào cả thì bỏ qua op này (không phải lỗi chết) và sang op kế tiếp.
        val canWriteDefault = (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val canWriteNoResp = (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        if (!canWriteDefault && !canWriteNoResp) {
            TripLog.ev("ccuBle", "skipNonWritable" to shortU(op.uuid), "props" to c.properties)
            runNextWrite(g)
            return
        }
        c.writeType = if (canWriteDefault) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        try {
            c.value = op.value
            val ok = g.writeCharacteristic(c)
            if (!ok) failAuth("Không ghi được ${shortU(op.uuid)} (writeCharacteristic trả false, props=${c.properties})")
        } catch (e: SecurityException) {
            failAuth("Thiếu quyền ghi GATT")
        } catch (e: Exception) {
            failAuth(e.message ?: "Lỗi ghi GATT")
        }
    }
}
