package vn.hiep.motonav

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * Giai đoạn 1 của dự án "đẩy dẫn đường lên màn TFT của xe": gửi một kênh CHỮ rất nhẹ
 * (mũi tên rẽ + khoảng cách + tên đường + giờ đến, vài chục byte mỗi lần, chỉ gửi khi có đổi)
 * qua Bluetooth Classic (RFCOMM) tới bộ điều khiển màn hình của xe — KHÔNG gửi hình ảnh.
 *
 * Khuôn gói tin và UUID dịch vụ dùng lại từ ứng dụng NConnect/TFT-Link (dò ra bằng cách đọc
 * ngược APK của ứng dụng đó, với mục đích duy nhất là nói đúng "ngôn ngữ" mà bộ điều khiển màn
 * hình xe Yamaha đang hiểu — không sao chép mã nguồn hay tài sản nào của họ). Phần phía xe không
 * đổi, phần phía điện thoại là của MotoNav, dữ liệu lấy từ chính tuyến đường MotoNav đang dẫn.
 *
 * Xe yêu cầu "bắt tay" 3 bước trước khi chịu nhận dữ liệu (dò được từ cùng APK):
 *  1) Xe gửi số serial (cmd 0x42) → điện thoại trả cmd 0x51 + cmd 0x21 (báo khả năng hỗ trợ).
 *  2) Xe gửi mã phần mềm + một "khoá" (cmd 0x53) → điện thoại trả lại đúng khoá đó qua cmd 0x54.
 *  3) Xe xác nhận (cmd 0x52, giá trị 1) → điện thoại gửi một loạt gói cấu hình ban đầu (cmd 2, 31,
 *     10, 14, 11, 13, 3, 17, 12) — xong bước này xe mới thực sự hiển thị/nhận dữ liệu dẫn đường.
 *
 * CHƯA XÁC NHẬN với thiết bị thật, cần chạy thử trên xe:
 *  - [IconMap]: mã mũi tên rẽ bên xe hiểu cho từng loại rẽ (trái/phải/vòng xuyến/quay đầu…).
 */
object TftLink {
    private val SERVICE_UUID: UUID = UUID.fromString("00007220-0000-1000-8000-00805F9B34FB")
    private val MAGIC = byteArrayOf(110, 65, 108, 64) // "nAl@"

    private val CRC_TABLE = IntArray(256).also { t ->
        for (i in 0..255) {
            var c = i shl 24
            repeat(8) {
                val carry = c and Int.MIN_VALUE
                c = c shl 1
                if (carry != 0) c = c xor 79764919
            }
            t[i] = c
        }
    }

    private fun crc(from: Int, to: Int, seed: Int, data: ByteArray): Int {
        var c = seed
        for (i in from until to) {
            c = CRC_TABLE[((c ushr 24) xor (data[i].toInt() and 255)) and 255] xor (c shl 8)
        }
        return c
    }

    private fun writeLe32(buf: ByteArray, at: Int, v: Int) {
        buf[at] = v.toByte()
        buf[at + 1] = (v ushr 8).toByte()
        buf[at + 2] = (v ushr 16).toByte()
        buf[at + 3] = (v ushr 24).toByte()
    }

    /** Đóng gói: [magic 4][ver=1][const=6][cmd][độ dài payload LE4][sub][crc32 LE4][payload]. */
    private fun pack(payload: ByteArray, cmd: Int, sub: Int): ByteArray {
        val out = ByteArray(payload.size + 16)
        System.arraycopy(MAGIC, 0, out, 0, 4)
        out[4] = 1
        out[5] = 6
        out[6] = cmd.toByte()
        writeLe32(out, 7, payload.size)
        out[11] = sub.toByte()
        System.arraycopy(payload, 0, out, 16, payload.size)
        writeLe32(out, 12, crc(16, out.size, crc(0, 12, -1, out), out))
        return out
    }

    private val ascii = Charsets.US_ASCII

    /** Bỏ dấu tiếng Việt, chỉ giữ ký tự ASCII in được — màn xe không có font Unicode. */
    private fun stripDiacritics(s: String): String {
        val noD = Normalizer.normalize(s.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
        val noMark = Pattern.compile("\\p{Mn}+").matcher(noD).replaceAll("")
        val asciiOnly = Pattern.compile("[^\\x20-\\x7E]").matcher(noMark).replaceAll("")
        return Pattern.compile(" {2,}").matcher(asciiOnly).replaceAll(" ").trim()
    }

    private fun asciiBytes(s: String, maxLen: Int): ByteArray {
        val clean = stripDiacritics(s).ifBlank { " " }
        var b = clean.toByteArray(ascii)
        if (b.size > maxLen) b = b.copyOf(maxLen)
        return b
    }

    // ---------- Gói tin "bắt tay" + cấu hình ban đầu ----------

    /** Như cách NConnect làm: payload 2 byte [value, 0], sub luôn = 0. */
    private fun gPacket(cmd: Int, value: Int): ByteArray = pack(byteArrayOf(value.toByte(), 0), cmd, 0)

    /** cmd 14 / sub 1: một chuỗi kiểu "mục menu" — [7,26,độ dài,0] + chữ ASCII. */
    private fun menuStringPacket(s: String): ByteArray {
        val bytes = asciiBytes(s, 144)
        val out = ByteArrayOutputStream()
        out.write(7); out.write(26); out.write(bytes.size); out.write(0)
        out.write(bytes)
        return pack(out.toByteArray(), 14, 1)
    }

    /** cmd 3 / sub 1: tên hiển thị (ví dụ tên người lái) — chữ ASCII, bỏ dấu. */
    private fun namePacket(s: String): ByteArray = pack(asciiBytes(s, 64), 3, 1)

    /** cmd 17 / sub 1: tốc độ + đơn vị — dùng trong gói cấu hình ban đầu (giá trị 0, chỉ để báo đơn vị). */
    private fun speedPacket(speedKmh: Int, flags: Int): ByteArray {
        val v = if (flags and 2 != 0) 0 else speedKmh
        val unit = "km/h".toByteArray(ascii)
        val out = ByteArrayOutputStream()
        val bits = java.lang.Float.floatToIntBits(v.toFloat())
        out.write(bits); out.write(bits ushr 8); out.write(bits ushr 16); out.write(bits ushr 24)
        out.write(unit.size)
        out.write(unit)
        return pack(out.toByteArray(), 17, 1)
    }

    /** Sau khi bắt tay xong (bước 3), gửi một loạt gói cấu hình ban đầu — xe cần thấy đủ bộ này. */
    private fun sendInitBurst() {
        send(gPacket(2, 0))            // chưa dẫn đường
        send(gPacket(31, 1))
        send(gPacket(10, 0))           // chưa lưu "nhà"
        send(menuStringPacket("300 m"))
        send(gPacket(11, 0))           // chưa lưu "chỗ làm"
        send(gPacket(13, 1))
        send(namePacket("MotoNav"))
        send(speedPacket(0, 3))
        send(gPacket(12, 1))
        lastSentKey = "" // để lần update() kế tiếp gửi lại đúng trạng thái dẫn đường hiện tại
    }

    // ---------- Gói tin kênh chữ (sau khi đã bắt tay xong) ----------

    /** cmd 2 / sub 0: báo đang/không dẫn đường. */
    private fun navActivePacket(active: Boolean): ByteArray = pack(byteArrayOf(if (active) 1 else 0), 2, 0)

    /** cmd 4 / sub 1: mũi tên rẽ + khoảng cách (m) + chỉ dẫn (tên đường/câu rẽ). */
    private fun turnPacket(icon: Int, distanceM: Float, road: String): ByteArray {
        val unit = asciiBytes("m", 16)
        val roadB = asciiBytes(road, 144)
        val out = ByteArrayOutputStream()
        out.write(icon and 0xFF)
        val bits = java.lang.Float.floatToIntBits(distanceM)
        out.write(bits)
        out.write(bits ushr 8)
        out.write(bits ushr 16)
        out.write(bits ushr 24)
        out.write(unit.size)
        out.write(roadB.size)
        out.write(unit)
        out.write(roadB)
        return pack(out.toByteArray(), 4, 1)
    }

    /** cmd 1 / sub 1: giờ đến còn lại (phút), 0..1439. */
    private fun etaPacket(minutes: Int): ByteArray {
        val m = minutes.coerceIn(0, 1439)
        return pack(byteArrayOf(m.toByte(), (m shr 8).toByte(), 0, 0), 1, 1)
    }

    /**
     * Mã mũi tên rẽ: từ mã "sign" chuẩn GraphHopper mà MotoNav đang dùng (xem [Maneuver]) sang mã
     * số bên xe hiểu. CHƯA XÁC NHẬN — đoán theo nhóm rẽ phổ biến, cần chạy thử thực tế trên xe rồi
     * chỉnh lại bảng này nếu mũi tên hiện sai loại.
     */
    object IconMap {
        fun from(sign: Int, roundabout: Boolean): Int = when {
            roundabout -> 6
            sign <= -2 -> 1 // rẽ trái / quẹo gắt trái
            sign == -1 -> 2 // chếch trái
            sign == 0 -> 0 // đi thẳng
            sign == 1 -> 3 // chếch phải
            sign >= 2 && sign <= 3 -> 4 // rẽ phải / quẹo gắt phải
            sign == 4 || sign == 5 -> 5 // đến nơi / điểm dừng
            sign == -7 || sign == 7 -> 0 // đi về bên trái/phải
            sign == -8 || sign == -98 || sign == 8 -> 7 // quay đầu
            else -> 0
        }
    }

    // ---------- Kết nối RFCOMM (song song: vừa chờ xe gọi tới, vừa tự gọi tới xe) ----------

    private val running = AtomicBoolean(false)
    private val busy = AtomicBoolean(false) // đang có 1 kết nối sống, không nhận thêm
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var activeSocket: Closeable? = null
    @Volatile private var out: OutputStream? = null

    @Volatile var connected: Boolean = false
        private set

    /** Đã bắt tay xong (3 bước) — xe sẵn sàng hiển thị/nhận dữ liệu. Chỉ gửi kênh chữ khi true. */
    @Volatile var authed: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile private var lastSentKey: String = ""
    @Volatile private var failCount = 0

    // ---------- Chẩn đoán (xem ở màn Cài đặt khi chưa bắt tay xong được) ----------
    /** Tổng số byte đã đọc được từ socket — 0 nghĩa là xe chưa gửi gì cả (kể cả rác). */
    @Volatile var rxBytes: Long = 0
        private set

    /** Bước bắt tay xa nhất đã nhận được từ xe: 0=chưa có gì, 1=đã nhận cmd 0x42 (serial),
     * 2=đã nhận cmd 0x53 (mã phần mềm+khoá), 3=đã nhận cmd 0x52 xác nhận (value=1). */
    @Volatile var handshakeStep: Int = 0
        private set

    /** cmd của gói tin gần nhất nhận được (để biết xe có gửi cmd lạ nào khác không). */
    @Volatile var lastCmdSeen: Int = -1
        private set

    /** Số lần gửi kênh chữ (turnPacket) thành công kể từ khi bắt tay xong — 0 nghĩa là chưa hề
     * gửi gói dẫn đường nào (ví dụ chưa có tuyến/chưa dẫn đường thật), khác với lỗi gửi. */
    @Volatile var txCount: Int = 0
        private set

    /** Thời điểm (millis hệ thống) gửi kênh chữ gần nhất thành công — dùng để biết kết nối có
     * còn "sống" và đang thực sự truyền hay đã âm thầm rớt kết nối từ lúc nào. */
    @Volatile var lastTxAt: Long = 0
        private set

    /** Gom các byte đọc được từ socket thành từng gói tin hoàn chỉnh theo khuôn [magic]...[payload]. */
    private class FrameReader {
        private var buf = ByteArray(0)

        fun feed(data: ByteArray, len: Int): List<Triple<Int, Int, ByteArray>> {
            buf += data.copyOf(len)
            val frames = ArrayList<Triple<Int, Int, ByteArray>>()
            while (true) {
                val m = indexOfMagic(buf)
                if (m < 0) {
                    if (buf.size > 3) buf = buf.copyOfRange(buf.size - 3, buf.size)
                    break
                }
                if (m > 0) buf = buf.copyOfRange(m, buf.size)
                if (buf.size < 16) break
                val plen = readLe32(buf, 7)
                if (plen < 0 || plen > 1_000_000) {
                    buf = buf.copyOfRange(4, buf.size) // rác/lệch khung: bỏ qua, dò lại từ sau magic này
                    continue
                }
                val total = 16 + plen
                if (buf.size < total) break
                val cmd = buf[6].toInt() and 0xFF
                val sub = buf[11].toInt() and 0xFF
                val payload = buf.copyOfRange(16, total)
                frames.add(Triple(cmd, sub, payload))
                buf = buf.copyOfRange(total, buf.size)
            }
            return frames
        }

        private fun indexOfMagic(b: ByteArray): Int {
            if (b.size < 4) return -1
            outer@ for (i in 0..b.size - 4) {
                for (k in 0..3) if (b[i + k] != MAGIC[k]) continue@outer
                return i
            }
            return -1
        }

        private fun readLe32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
    }

    fun start(ctx: Context, deviceAddr: String?) {
        if (!running.compareAndSet(false, true)) return
        lastError = null
        try {
            val a = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (a == null || !a.isEnabled) {
                lastError = "Bluetooth đang tắt"
                running.set(false)
                return
            }
            Thread({ serverLoop(a) }, "tft-server").apply { isDaemon = true; start() }
            if (!deviceAddr.isNullOrBlank()) {
                Thread({ clientLoop(a, deviceAddr) }, "tft-client").apply { isDaemon = true; start() }
            }
        } catch (e: SecurityException) {
            lastError = "Thiếu quyền Bluetooth"
            running.set(false)
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        try {
            activeSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        activeSocket = null
        out = null
        connected = false
        authed = false
        lastSentKey = ""
        rxBytes = 0
        handshakeStep = 0
        lastCmdSeen = -1
        txCount = 0
        lastTxAt = 0
    }

    private fun serverLoop(a: BluetoothAdapter) {
        while (running.get()) {
            try {
                if (serverSocket == null) {
                    serverSocket = try {
                        a.listenUsingRfcommWithServiceRecord("NaviBluetoothSecure", SERVICE_UUID)
                    } catch (_: IOException) {
                        a.listenUsingInsecureRfcommWithServiceRecord("NaviBluetoothInsecure", SERVICE_UUID)
                    }
                }
                val sock = serverSocket?.accept() ?: continue
                onConnected(sock, sock.inputStream, sock.outputStream)
            } catch (e: SecurityException) {
                lastError = "Thiếu quyền Bluetooth"
                sleepQuiet(10_000)
            } catch (e: IOException) {
                if (!running.get()) return
                try {
                    serverSocket?.close()
                } catch (_: Exception) {
                }
                serverSocket = null
                sleepQuiet(2_000)
            }
        }
    }

    private fun clientLoop(a: BluetoothAdapter, addr: String) {
        val device: BluetoothDevice = try {
            a.getRemoteDevice(addr)
        } catch (_: Exception) {
            lastError = "Địa chỉ xe không hợp lệ"
            return
        }
        while (running.get()) {
            if (!busy.get()) {
                try {
                    try {
                        a.cancelDiscovery()
                    } catch (_: Exception) {
                    }
                    val sock = try {
                        device.createRfcommSocketToServiceRecord(SERVICE_UUID).also { it.connect() }
                    } catch (_: IOException) {
                        device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID).also { it.connect() }
                    }
                    onConnected(sock, sock.inputStream, sock.outputStream)
                } catch (e: SecurityException) {
                    lastError = "Thiếu quyền Bluetooth"
                    sleepQuiet(10_000)
                } catch (e: IOException) {
                    failCount++
                    lastError = "Không gọi được tới xe (lần $failCount)"
                }
            }
            sleepQuiet(4_000)
        }
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun onConnected(socket: Closeable, input: InputStream, output: OutputStream) {
        if (!busy.compareAndSet(false, true)) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            return
        }
        activeSocket = socket
        out = output
        connected = true
        authed = false
        lastError = null
        failCount = 0
        lastSentKey = ""
        rxBytes = 0
        handshakeStep = 0
        lastCmdSeen = -1
        txCount = 0
        lastTxAt = 0
        Thread({ readLoop(input, socket) }, "tft-reader").apply { isDaemon = true; start() }
    }

    /** Đọc gói tin từ xe, chạy máy trạng thái bắt tay 3 bước rồi mới cho phép gửi kênh chữ. */
    private fun readLoop(input: InputStream, socket: Closeable) {
        val fb = FrameReader()
        val buf = ByteArray(2048)
        var step2Done = false
        var step3Done = false
        try {
            while (running.get()) {
                val n = input.read(buf)
                if (n < 0) break
                rxBytes += n
                for ((cmd, sub, payload) in fb.feed(buf, n)) {
                    lastCmdSeen = cmd
                    when (cmd) {
                        0x42 -> { // 1/3: xe gửi serial → trả khả năng hỗ trợ
                            // Xe có thể gửi lại cmd này nhiều lần (loan báo định kỳ) kể cả sau khi đã
                            // qua bước 2/3, 3/3 — KHÔNG được coi là "bắt đầu lại từ đầu" và reset các
                            // bước đã qua, nếu không sẽ bị kẹt mãi dù xe đã xác nhận xong (lỗi đã gặp
                            // thực tế: xe báo đã tới bước 3/3 nhưng app vẫn chưa coi là bắt tay xong).
                            if (handshakeStep < 1) handshakeStep = 1
                            send(pack(byteArrayOf(1, 0), 0x51, 0))
                            send(pack(byteArrayOf(0x7c, 6, 0, 1, 0, 0, 0, 0), 0x21, 1))
                        }
                        0x53 -> { // 2/3: xe gửi mã phần mềm + khoá → trả lại đúng khoá
                            val key = if (payload.size > 12) payload.copyOfRange(12, payload.size) else ByteArray(0)
                            send(pack(key, 0x54, 1))
                            step2Done = true
                            if (handshakeStep < 2) handshakeStep = 2
                        }
                        0x52 -> { // 3/3: xe xác nhận (payload[0] == 1)
                            val v = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0
                            if (v == 1) {
                                step3Done = true
                                if (handshakeStep < 3) handshakeStep = 3
                            }
                        }
                    }
                    if (step2Done && step3Done && !authed) {
                        authed = true
                        sendInitBurst()
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            activeSocket = null
            out = null
            connected = false
            authed = false
            busy.set(false)
        }
    }

    @Synchronized
    private fun send(bytes: ByteArray): Boolean {
        val o = out ?: return false
        return try {
            o.write(bytes)
            o.flush()
            true
        } catch (e: IOException) {
            lastError = "Gửi lỗi: ${e.message}"
            try {
                activeSocket?.close()
            } catch (_: Exception) {
            }
            false
        }
    }

    /**
     * Gọi liên tục (khoảng mỗi giây) từ dịch vụ chạy nền khi đang dẫn đường. Tự lọc trùng để
     * không gửi lại những gì chưa đổi — giữ đúng tinh thần "kênh chữ nhẹ" không nghẽn Bluetooth.
     */
    fun update(active: Boolean, sign: Int, roundabout: Boolean, distanceM: Int, road: String, etaMinutes: Int) {
        if (!authed) return
        if (!active) {
            if (lastSentKey != "off") {
                send(navActivePacket(false))
                lastSentKey = "off"
            }
            return
        }
        val icon = IconMap.from(sign, roundabout)
        // Làm tròn khoảng cách theo bậc 5 m để không gửi lại chỉ vì GPS nhích vài centimet.
        val key = "$icon|${distanceM / 5}|$road|$etaMinutes"
        if (key == lastSentKey) return
        if (lastSentKey == "off" || lastSentKey.isEmpty()) send(navActivePacket(true))
        if (send(turnPacket(icon, distanceM.toFloat(), road))) {
            txCount++
            lastTxAt = System.currentTimeMillis()
        }
        send(etaPacket(etaMinutes))
        lastSentKey = key
    }
}
