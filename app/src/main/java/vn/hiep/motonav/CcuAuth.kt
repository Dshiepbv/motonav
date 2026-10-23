package vn.hiep.motonav

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.InputStream
import java.util.UUID

/**
 * Bước 1/3 của "bắt cặp BLE" (Y-Connect) — dò ngược từ app NConnect: trước khi mở được kết nối
 * BLE với bộ điều khiển màn hình xe (bước làm icon/menu dẫn đường "mở khoá"), điện thoại phải
 * hỏi qua MỘT kết nối Bluetooth Classic KHÁC (không phải kênh nAl@ đã dùng ở Giai đoạn 1) để lấy
 * "mã CCU" (14 ký tự ASCII — chính là phần sau "YCCU_" trong tên Bluetooth của xe) và một
 * "passkey" (6 ký tự ASCII) dùng để xác thực ở bước BLE sau đó.
 *
 * Khuôn gói tin bước này KHÁC với khuôn [nAl@...] đã dùng cho kênh dẫn đường:
 *   Yêu cầu : [0x01][độ dài payload, 4 byte BE][payload][0x04]
 *   Payload yêu cầu: 2 byte lệnh (BE) = 313 (0x0139), không có thân.
 *   Phản hồi mong đợi: lệnh = 314 (0x013A), payload dài đúng 22 byte:
 *       byte[2..16)  = mã CCU (14 ký tự ASCII)
 *       byte[16..22) = passkey (6 ký tự ASCII)
 *
 * CHỈ DÙNG ĐỂ CHẨN ĐOÁN bước này riêng lẻ — chưa nối tiếp sang bước BLE (giai đoạn sau).
 */
object CcuAuth {
    private val UUID_CCU = UUID.fromString("00008911-0000-1000-8000-00805f9b34fb")
    private val UUID_SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    @Volatile var running = false
        private set
    @Volatile var ccuCode: String? = null
        private set
    @Volatile var passkey: String? = null
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var lastTriedAt: Long = 0
        private set

    fun queryOnce(ctx: Context, deviceAddr: String) {
        if (running) return
        if (deviceAddr.isBlank()) {
            error = "Chưa chọn thiết bị màn xe"
            return
        }
        running = true
        error = null
        lastTriedAt = System.currentTimeMillis()
        Thread({
            var lastErr: String? = null
            var ok = false
            try {
                val a = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                if (a == null || !a.isEnabled) {
                    lastErr = "Bluetooth đang tắt"
                } else {
                    val device = a.getRemoteDevice(deviceAddr)
                    try { a.cancelDiscovery() } catch (_: Exception) {}
                    // Thử lần lượt: CCU bảo mật (secure), CCU không bảo mật (insecure), SPP chuẩn không bảo mật
                    val attempts = listOf(Triple(UUID_CCU, false, "CCU secure"), Triple(UUID_CCU, true, "CCU insecure"), Triple(UUID_SPP, true, "SPP insecure"))
                    for ((uuid, insecure, label) in attempts) {
                        var sock: BluetoothSocket? = null
                        try {
                            TripLog.ev("ccuAuth", "try" to label)
                            sock = if (insecure) device.createInsecureRfcommSocketToServiceRecord(uuid) else device.createRfcommSocketToServiceRecord(uuid)
                            sock.connect()
                            val req = byteArrayOf(1, 0, 0, 0, 2, 1, 57, 4)
                            sock.outputStream.write(req)
                            sock.outputStream.flush()
                            val result = readResponse(sock.inputStream)
                            if (result != null) {
                                ccuCode = result.first
                                passkey = result.second
                                ok = true
                                TripLog.ev("ccuAuth", "ok" to label, "code" to result.first)
                                break
                            } else {
                                lastErr = "Không có phản hồi hợp lệ ($label)"
                            }
                        } catch (e: Exception) {
                            lastErr = "${e.javaClass.simpleName}: ${e.message} ($label)"
                        } finally {
                            try { sock?.close() } catch (_: Exception) {}
                        }
                    }
                    if (!ok && lastErr == null) lastErr = "Không lấy được thông tin (xe đã bật và đã ghép đôi chưa?)"
                }
            } catch (e: Exception) {
                lastErr = e.message
            }
            if (!ok) error = lastErr
            running = false
        }, "ccu-spp-probe").apply { isDaemon = true; start() }
    }

    private fun readResponse(input: InputStream): Pair<String, String>? {
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            val b = input.read()
            if (b < 0) return null
            if (b == 1) {
                val lenB = ByteArray(4)
                if (!readFully(input, lenB)) return null
                val len = ((lenB[0].toInt() and 255) shl 24) or ((lenB[1].toInt() and 255) shl 16) or
                    ((lenB[2].toInt() and 255) shl 8) or (lenB[3].toInt() and 255)
                if (len <= 0 || len > 4096) continue
                val payload = ByteArray(len)
                if (!readFully(input, payload)) return null
                val term = input.read()
                if (term != 4) continue
                val cmd = ((payload[0].toInt() and 255) shl 8) or (payload[1].toInt() and 255)
                if (cmd == 314 && payload.size == 22) {
                    val code = String(payload, 2, 14, Charsets.US_ASCII)
                    val pass = String(payload, 16, 6, Charsets.US_ASCII)
                    return code to pass
                }
            }
        }
        return null
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }
}
