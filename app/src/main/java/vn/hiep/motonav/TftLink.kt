package vn.hiep.motonav

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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

    // ---------- Giai đoạn 2: kênh ảnh (khung bản đồ sketch) — gửi cmd 0/sub 1, có ACK ----------

    /** Độ phân giải màn hình thật của xe, dò được ở bước bắt tay 2/3 (cmd 0x53). Mặc định
     * 480x234 (model không phải "006-B4160") cho tới khi dò ra chính xác. */
    @Volatile var screenW: Int = 480
        private set
    @Volatile var screenH: Int = 234
        private set

    @Volatile private var frameSeq: Int = 0
    @Volatile private var ackedFrame: Int = -1
    private val ackLock = Object()

    @Volatile var frameTxCount: Int = 0
        private set
    @Volatile var lastFrameError: String? = null
        private set
    @Volatile var lastFrameAckMs: Long = -1
        private set

    /**
     * Gửi một khung ảnh JPEG lên màn xe (cmd 0 / sub 1 — dò được từ NConnect: payload =
     * [loại khung][số thứ tự LE16][ảnh JPEG, đệm tối thiểu 2050 byte]). Chờ xe trả ACK (cmd 0x50)
     * đúng số thứ tự này tối đa 6 giây (giống app gốc) — quá 2 giây thì coi là chậm nhưng vẫn chờ
     * tiếp, quá 6 giây thì bỏ qua khung này (coi như gửi xong, không chặn khung sau).
     * CHỈ gọi khi [authed] — chưa bắt tay xong thì xe sẽ không hiểu/ACK.
     */
    fun sendFrame(jpeg: ByteArray, tag: String? = null): Boolean {
        if (!authed) return false
        val seq = (frameSeq + 1) and 0xFFFF
        frameSeq = seq
        var body = jpeg
        if (body.size <= 2048) body = body.copyOf(2050)
        val payload = ByteArray(body.size + 3)
        payload[0] = 3 // loại khung — 3 = bình thường (giống app gốc lúc không tạm dừng)
        payload[1] = (seq and 0xFF).toByte()
        payload[2] = ((seq ushr 8) and 0xFF).toByte()
        System.arraycopy(body, 0, payload, 3, body.size)
        val sentAt = System.currentTimeMillis()
        if (!send(pack(payload, 0, 1))) {
            lastFrameError = "Gửi khung lỗi (mất kết nối)"
            return false
        }
        val deadline2s = sentAt + 2000
        val deadline6s = sentAt + 6000
        var timedOut = false
        var interrupted = false
        synchronized(ackLock) {
            while (running.get() && connected && ackedFrame != seq) {
                val now = System.currentTimeMillis()
                if (now >= deadline6s) {
                    lastFrameError = "Khung #$seq không được ACK sau 6 giây"
                    timedOut = true
                    break // giống app gốc: hết giờ vẫn coi là xong, không chặn khung sau
                }
                val waitMs = if (now < deadline2s) deadline2s - now else deadline6s - now
                try {
                    (ackLock as Object).wait(waitMs.coerceAtLeast(1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    interrupted = true
                    break
                }
            }
        }
        if (interrupted) return false
        // ĐÃ THỬ (v6.48→v6.51) tự đóng socket ép kết nối lại khi ACK chậm liên tiếp, với hy vọng
        // giúp màn xe thoát "Lỗi kết nối" nhanh hơn. Thực tế qua 2 video người dùng gửi: lúc đứng
        // yên chưa có tuyến thì tự gây ra đúng cái lỗi nó định tránh (đã sửa ở v6.51: bỏ áp dụng lúc
        // đó); nhưng lúc ĐANG dẫn đường thật (khung ảnh nặng hơn khung chờ) thì ACK chậm liên tục,
        // nên cứ 2 khung là ép kết nối lại — xe vừa bắt tay lại xong lại dính luôn khung chậm tiếp
        // theo, tạo thành vòng lặp liên tục báo "Lỗi kết nối" mỗi khi chuyển qua khung bản đồ thật.
        // Tức là KHÔNG giúp gì mà còn làm xấu hơn tình trạng gốc (xe chỉ chậm/đứng hình chứ không
        // liên tục bật to hộp lỗi). Bỏ hẳn việc chủ động đóng kết nối — giữ lại nhịp gửi thích ứng
        // (giãn ra khi ACK chậm) để không dồn thêm khung, còn lại để xe tự xử lý ACK chậm như
        // NConnect gốc vẫn vậy (cùng giới hạn phần cứng, không phải lỗi protocol sửa được từ xa).
        if (timedOut) {
            lastFrameAckMs = 6000 // để sketchLoop giãn nhịp tiếp theo ra xa, không dồn thêm khung
        } else {
            lastFrameAckMs = System.currentTimeMillis() - sentAt
            lastFrameError = null
            frameTxCount++
        }
        // Ghi vào nhật ký chuyến đi (dung lượng khung thật gửi đi + thời gian ACK) — để sau này xem
        // lại có thể đối chiếu xem ACK bắt đầu chậm hẳn từ cỡ khung bao nhiêu byte, tìm ra một mức
        // dung lượng "an toàn" thay vì đoán mò bằng chất lượng JPEG.
        try {
            TripLog.ev("tftFrame", "bytes" to jpeg.size, "ackMs" to (if (timedOut) null else lastFrameAckMs), "timedOut" to timedOut, "tag" to tag)
        } catch (_: Exception) {
        }
        return true
    }

    // ---------- Khung bản đồ "sketch" thật: nét trắng trên nền đen, hướng lên = hướng đang đi ----------

    /** Dữ liệu mới nhất để vẽ khung sketch — [updateSketch] ghi (gọi ~mỗi giây, rẻ), luồng nền
     * riêng (tách khỏi kênh chữ) đọc ra vẽ + gửi theo nhịp chậm hơn, không chặn tftWatch. */
    @Volatile private var skActive = false
    @Volatile private var skRoute: Route? = null
    @Volatile private var skLat = 0.0
    @Volatile private var skLon = 0.0
    @Volatile private var skHeadingDeg = -1.0
    @Volatile private var skManLat = 0.0
    @Volatile private var skManLon = 0.0
    @Volatile private var skSign = 0
    @Volatile private var skRoundabout = false
    @Volatile private var skWays: List<Way>? = null
    @Volatile private var skSpeedKmh: Int = -1
    @Volatile private var skLimitKmh: Int? = null
    @Volatile private var skDistanceM: Int = -1
    @Volatile private var skNextStreet: String? = null
    @Volatile private var skRemainingM: Double = 0.0
    @Volatile private var skEtaMs: Long = 0L
    // Zoom thủ công bằng nút vật lý trên xe (51 lên = xa hơn, 52 xuống = gần hơn), cộng dồn vào
    // công thức maxAhead theo tốc độ. -3..+3, 0 = mặc định.
    @Volatile private var skZoomStep: Int = 0
    // "Ghim" chỉ số điểm gần nhất tìm được ở khung trước, để khung sau chỉ dò quanh đó thay vì
    // quét lại toàn tuyến — tránh nhảy lung tung sang điểm xa (một đoạn đường khác của tuyến tình
    // cờ ở gần) làm đường xanh biến mất/vẽ sai rồi tự "sửa" lại ở khung sau.
    @Volatile private var skIdxHint: Int = -1
    private var skIdxHintRoute: Route? = null

    fun updateSketch(
        active: Boolean,
        route: Route?,
        lat: Double,
        lon: Double,
        headingDeg: Double,
        maneuverLat: Double,
        maneuverLon: Double,
        sign: Int,
        roundabout: Boolean,
        ways: List<Way>? = null,
        speedKmh: Int = -1,
        limitKmh: Int? = null,
        distanceM: Int = -1,
        nextStreet: String? = null,
        remainingM: Double = 0.0,
        etaMs: Long = 0L,
    ) {
        skActive = active
        skRoute = route
        skLat = lat
        skLon = lon
        skHeadingDeg = headingDeg
        skManLat = maneuverLat
        skManLon = maneuverLon
        skSign = sign
        skRoundabout = roundabout
        skLimitKmh = limitKmh
        skWays = ways
        skSpeedKmh = speedKmh
        skDistanceM = distanceM
        skNextStreet = nextStreet
        skRemainingM = remainingM
        skEtaMs = etaMs
    }

    /** Vẽ khung sketch: đường đi phía trước (~220m) thành nét trắng trên nền đen, xe luôn ở giữa
     * dưới và hướng lên trên (heading-up), chấm tại điểm rẽ. Tối giản tối đa cho nhẹ băng thông —
     * không tô màu, không bo nét dày, JPEG chất lượng thấp (nội dung gần như đen/trắng nên nén rất
     * nhỏ dù chất lượng thấp). */
    private fun buildSketchFrame(): ByteArray {
        val w = screenW
        val h = screenH
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val route = skRoute
        val anchorX = w / 2f
        // Nhích điểm xe lên khỏi mép dưới (0.82->0.72) — theo phản hồi thực tế: khi tuyến rẽ ngang
        // ngay gần vị trí hiện tại, đoạn rẽ bị khuất sát đáy khung, không thấy đường đi đâu.
        val anchorY = h * 0.72f
        if (route == null || route.lats.size < 2) {
            // Khung "chờ" — gửi đều đặn khi chưa có tuyến (chưa dẫn đường/mất GPS/vừa tới nơi) để
            // xe không coi là mất kết nối vì im lặng quá lâu.
            // Bỏ chữ "MotoNav" theo yêu cầu "bỏ logo cho nó rộng" — chỉ còn dòng trạng thái, để
            // khoảng trống thoáng hơn.
            val p2 = Paint().apply { color = Color.WHITE; textSize = h * 0.13f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
            c.drawText("Chờ tuyến đường…", w / 2f, h * 0.52f, p2)
        } else {
            val proj = LocalProjection(skLat, skLon)
            // Tìm điểm gần vị trí hiện tại nhất trên tuyến. "Ghim" quanh chỉ số khung trước (nếu
            // cùng tuyến) để tránh nhảy xa sang một đoạn khác của tuyến tình cờ ở gần — nguyên nhân
            // đường xanh thỉnh thoảng biến mất rồi lại hiện (báo lỗi thực tế từ xe). Chỉ quét lại
            // toàn tuyến khi chưa có điểm ghim, đổi tuyến, hoặc điểm ghim cũ lệch quá xa (>80m, có
            // thể do mất GPS/dừng lâu/đi xa khỏi tuyến thật).
            val hintValid = skIdxHintRoute === route && skIdxHint in route.lats.indices
            var idx0: Int
            var best = Double.MAX_VALUE
            if (hintValid) {
                val lo = (skIdxHint - 15).coerceAtLeast(0)
                val hi = (skIdxHint + 120).coerceAtMost(route.lats.size - 1)
                var bi = skIdxHint
                for (i in lo..hi) {
                    val d = Geo.distance(skLat, skLon, route.lats[i], route.lons[i])
                    if (d < best) { best = d; bi = i }
                }
                idx0 = bi
            } else {
                idx0 = 0
            }
            if (!hintValid || best > 80.0) {
                var fi = 0
                var fbest = Double.MAX_VALUE
                for (i in route.lats.indices) {
                    val d = Geo.distance(skLat, skLon, route.lats[i], route.lons[i])
                    if (d < fbest) { fbest = d; fi = i }
                }
                idx0 = fi
            }
            skIdxHint = idx0
            skIdxHintRoute = route
            val cum0 = route.cum[idx0]
            // "Dài hơn" (chạy 60km/h hết đường xanh) nhưng vẫn phải "thấy rõ điểm rẽ" (2km tuyến tính
            // cũ bị quá bé ở gần) — hai yêu cầu mâu thuẫn nếu chiếu thẳng khoảng cách sang pixel theo
            // 1 tỉ lệ cố định. Giải quyết bằng phép chiếu "nén xa, giãn gần" (căn bậc hai theo bán
            // kính, giống kiểu bản đồ fisheye): đoạn gần xe chiếm nhiều pixel hơn tỉ lệ thật (dễ thấy
            // khúc rẽ ngay trước mắt), đoạn xa bị nén lại nhưng vẫn hiện đủ hình dạng/hướng đi xa tới
            // maxAhead. Nhờ vậy nâng lại maxAhead lên hẳn (1.8km mặc định) mà điểm rẽ gần vẫn to rõ.
            val zoomMul = when (skZoomStep) {
                -3 -> 0.4; -2 -> 0.55; -1 -> 0.75; 1 -> 1.3; 2 -> 1.65; 3 -> 2.0; else -> 1.0
            }
            // Theo yêu cầu: dưới 40km/h phóng to gấp đôi (maxAhead giảm 1 nửa) — tốc độ thấp thường
            // là đường nhỏ/khu đông dân cư, cần thấy rõ chi tiết gần hơn là nhìn xa.
            val spdMul = if (skSpeedKmh in 1..39) 0.5 else 1.0
            val maxAhead = (1800.0 * zoomMul * spdMul).coerceIn(400.0, 3600.0)
            val hRad = Math.toRadians(if (skHeadingDeg >= 0) skHeadingDeg else 0.0)
            val cosH = Math.cos(hRad); val sinH = Math.sin(hRad)
            // px trên "mét đã nén" — chỉnh theo đoạn xa nhất sẽ vẽ, để luôn vừa khung hình.
            val scale = (h * 0.68f) / maxAhead.toFloat()
            // rx/ry (mét thật, đã xoay theo hướng xe) -> toạ độ pixel, nén bán kính theo căn bậc hai
            // (mũ 0.55) quanh vị trí xe: gần xe gần như không nén (dễ thấy rẽ), xa xe nén mạnh hơn.
            fun toScreen(rx: Double, ry: Double): FloatArray {
                val dist = Math.hypot(rx, ry)
                if (dist < 1.0) return floatArrayOf(anchorX, anchorY)
                val warped = maxAhead * Math.pow((dist / maxAhead).coerceAtMost(2.0), 0.55)
                val ux = rx / dist; val uy = ry / dist
                return floatArrayOf(
                    anchorX + (ux * warped * scale).toFloat(),
                    anchorY - (uy * warped * scale).toFloat()
                )
            }

            // Nhánh đường xung quanh (nét trắng mảnh) — thử lại (trước đã bỏ vì sợ nặng JPEG, giờ
            // thử lại theo yêu cầu). Lọc bớt: chỉ các way có điểm đầu nằm trong bán kính vẽ (lọc
            // nhanh bằng khoảng cách theo độ, không gọi Geo.distance cho từng điểm), tối đa 25 way,
            // tối đa 60 điểm/way (giữ nhẹ, đủ hình dạng) để JPEG không phình quá to.
            // Tên đường nhánh đã thử (v6.76) nhưng chữ quá nhỏ/mờ trên màn xe, không đọc được —
            // bỏ hẳn theo phản hồi thực tế, chỉ giữ nét trắng để biết hình dạng/có lối rẽ hay không.
            val ways = skWays
            if (ways != null) {
                val degRadius = (maxAhead / 111320.0) * 1.3
                val branchPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true }
                var drawnWays = 0
                for (wy in ways) {
                    if (drawnWays >= 18) break
                    if (wy.lats.isEmpty()) continue
                    if (Math.abs(wy.lats[0] - skLat) > degRadius || Math.abs(wy.lons[0] - skLon) > degRadius) continue
                    val bp = android.graphics.Path()
                    var bStarted = false
                    var any = false
                    val step = (wy.lats.size / 45).coerceAtLeast(1)
                    var j = 0
                    while (j < wy.lats.size) {
                        val dx = proj.x(wy.lons[j]); val dy = proj.y(wy.lats[j])
                        val rx = dx * cosH - dy * sinH; val ry = dx * sinH + dy * cosH
                        if (Math.abs(rx) <= maxAhead * 1.1 && ry >= -maxAhead * 0.3 && ry <= maxAhead) {
                            val (sx, sy) = toScreen(rx, ry)
                            if (!bStarted) { bp.moveTo(sx, sy); bStarted = true } else bp.lineTo(sx, sy)
                            any = true
                        } else {
                            bStarted = false
                        }
                        j += step
                    }
                    if (any) { c.drawPath(bp, branchPaint); drawnWays++ }
                }
            }

            // Với maxAhead lớn (tới 4km), tuyến có thể có hàng nghìn điểm trong đoạn này — nếu vẽ
            // hết sẽ làm khung JPEG phình to, chậm, có thể gây mất kết nối lại (đã sửa ở bản
            // trước). Dò trước điểm cuối nằm trong maxAhead rồi giãn bước vẽ để tối đa ~260 điểm,
            // vẫn giữ đủ hình dạng đường ở độ phân giải nhỏ của màn dash.
            var iEnd = idx0
            while (iEnd < route.lats.size && route.cum[iEnd] - cum0 <= maxAhead) iEnd++
            val ptCount = iEnd - idx0
            val pathStep = (ptCount / 260).coerceAtLeast(1)
            val path = android.graphics.Path()
            var started = false
            var i = idx0
            while (i < iEnd) {
                val dx = proj.x(route.lons[i])
                val dy = proj.y(route.lats[i])
                val rx = dx * cosH - dy * sinH
                val ry = dx * sinH + dy * cosH
                val (sx, sy) = toScreen(rx, ry)
                if (!started) { path.moveTo(sx, sy); started = true } else path.lineTo(sx, sy)
                i += pathStep
            }
            // Đảm bảo luôn vẽ tới điểm cuối thực (iEnd-1) kể cả khi bước nhảy bỏ qua nó.
            if (started && (iEnd - 1) >= idx0 && (iEnd - 1 - idx0) % pathStep != 0) {
                val li = (iEnd - 1).coerceAtMost(route.lats.size - 1)
                val dx = proj.x(route.lons[li]); val dy = proj.y(route.lats[li])
                val rx = dx * cosH - dy * sinH; val ry = dx * sinH + dy * cosH
                val (lx, ly) = toScreen(rx, ry)
                path.lineTo(lx, ly)
            }
            // Tuyến chính màu xanh, dày hơn nữa (4f->6f) để nổi bật rõ giữa các nhánh đường trắng.
            val linePaint = Paint().apply { color = Color.rgb(60, 140, 255); style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
            c.drawPath(path, linePaint)
            // Bỏ chấm tròn đánh dấu điểm rẽ (trước đây gây rối mắt) nhưng theo yêu cầu mới: chỉ vào
            // đúng đoạn sắp rẽ 1 chú thích tên đường (đường kẻ mảnh nối từ điểm rẽ tới nhãn), để biết
            // tên đường sắp rẽ vào mà không cần vẽ tên lên tất cả các nhánh (quá rối, đã bỏ ở trên).
            val nextSt = skNextStreet
            if (!nextSt.isNullOrBlank()) {
                val mdx = proj.x(skManLon); val mdy = proj.y(skManLat)
                val mrx = mdx * cosH - mdy * sinH; val mry = mdx * sinH + mdy * cosH
                val (mx, my) = toScreen(mrx, mry)
                val labelPaint = Paint().apply { color = Color.WHITE; textSize = h * 0.05f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
                val bgPaint = Paint().apply { color = Color.argb(190, 0, 0, 0); style = Paint.Style.FILL }
                val leaderPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }
                // Nhãn đặt lệch lên-phải điểm rẽ một khoảng cố định theo pixel (không phụ thuộc
                // zoom), tránh đè lên tam giác xe ở giữa-dưới khung.
                val lx = (mx + h * 0.16f).coerceAtMost(w - h * 0.02f)
                val ly = (my - h * 0.1f).coerceAtLeast(h * 0.1f)
                c.drawLine(mx, my, lx, ly, leaderPaint)
                c.drawCircle(mx, my, h * 0.018f, leaderPaint)
                val tw = labelPaint.measureText(nextSt)
                val padX = h * 0.02f; val padY = h * 0.018f
                val boxLeft = (lx - tw / 2f - padX).coerceAtLeast(2f)
                val boxRight = (boxLeft + tw + padX * 2f).coerceAtMost(w - 2f)
                c.drawRect(boxLeft, ly - h * 0.045f - padY, boxRight, ly + h * 0.015f + padY, bgPaint)
                c.drawText(nextSt, (boxLeft + boxRight) / 2f - tw / 2f, ly, labelPaint)
            }
        }
        // Tam giác chỉ vị trí xe, luôn hướng lên (heading-up) — to hơn trước (0.06->0.085) theo yêu
        // cầu "điểm định vị to ra chút" để dễ thấy giữa tuyến xanh/nhánh trắng.
        val carPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
        val carR = h * 0.085f
        val carPath = android.graphics.Path().apply {
            moveTo(anchorX, anchorY - carR)
            lineTo(anchorX - carR * 0.7f, anchorY + carR * 0.6f)
            lineTo(anchorX + carR * 0.7f, anchorY + carR * 0.6f)
            close()
        }
        c.drawPath(carPath, carPaint)
        // Biển tốc độ tối đa, góc trên bên phải — theo yêu cầu. Vẽ kiểu biển báo VN (viền đỏ, nền
        // trắng, số đen) để nhận ra ngay là giới hạn tốc độ chứ không phải số liệu khác.
        val lim = skLimitKmh
        var limBottomY = h * 0.03f
        if (lim != null && lim > 0) {
            val cx = w * 0.90f; val cy = h * 0.16f; val r = h * 0.105f
            val ringPaint = Paint().apply { color = Color.rgb(220, 30, 30); style = Paint.Style.FILL; isAntiAlias = true }
            val facePaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
            val numPaint = Paint().apply { color = Color.BLACK; textSize = r * 1.15f; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
            c.drawCircle(cx, cy, r, ringPaint)
            c.drawCircle(cx, cy, r * 0.78f, facePaint)
            val fm = numPaint.fontMetrics
            c.drawText(lim.toString(), cx, cy - (fm.ascent + fm.descent) / 2f, numPaint)
            limBottomY = cy + r
        }
        // Dưới ô giới hạn tốc độ: ETA (giờ đến), thời gian còn lại, quãng đường còn lại — giống Waze.
        if (skActive && skRoute != null) {
            val statPaint = Paint().apply { color = Color.WHITE; textSize = h * 0.05f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
            val cx = w * 0.90f
            val remKm = skRemainingM / 1000.0
            val remTxt = if (remKm >= 10) "${remKm.toInt()} km" else String.format("%.1f km", remKm)
            val remMin = (skEtaMs / 60000L).toInt()
            val arriveCal = java.util.Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() + skEtaMs }
            val etaTxt = String.format("%02d:%02d", arriveCal.get(java.util.Calendar.HOUR_OF_DAY), arriveCal.get(java.util.Calendar.MINUTE))
            var sy = limBottomY + h * 0.07f
            c.drawText(etaTxt, cx, sy, statPaint)
            sy += h * 0.065f
            c.drawText("$remMin ph", cx, sy, statPaint)
            sy += h * 0.065f
            c.drawText(remTxt, cx, sy, statPaint)
        }
        // Thẻ chỉ dẫn góc trên-trái: mũi tên hướng rẽ + khoảng cách (giống Waze) — theo yêu cầu.
        if (skActive && skRoute != null && skDistanceM >= 0) {
            val boxW = w * 0.46f; val boxH = h * 0.26f
            val bg = Paint().apply { color = Color.argb(200, 25, 25, 25); style = Paint.Style.FILL }
            c.drawRect(0f, 0f, boxW, boxH, bg)
            // Mũi tên (tam giác xoay theo góc rẽ — dùng chung công thức góc với bản điện thoại).
            val arrowCx = boxW * 0.28f; val arrowCy = boxH * 0.52f; val arrowR = boxH * 0.3f
            val angle = Maneuver.angle(skSign)
            c.save()
            c.rotate(angle, arrowCx, arrowCy)
            val arrowPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
            val arrowPath = android.graphics.Path().apply {
                moveTo(arrowCx, arrowCy - arrowR)
                lineTo(arrowCx - arrowR * 0.8f, arrowCy + arrowR * 0.7f)
                lineTo(arrowCx, arrowCy + arrowR * 0.3f)
                lineTo(arrowCx + arrowR * 0.8f, arrowCy + arrowR * 0.7f)
                close()
            }
            c.drawPath(arrowPath, arrowPaint)
            c.restore()
            val distTxt = if (skDistanceM >= 1000) String.format("%.1f km", skDistanceM / 1000.0) else "$skDistanceM m"
            val distCx = boxW * 0.66f
            val distPaint = Paint().apply { color = Color.WHITE; textSize = boxH * 0.34f; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
            c.drawText(distTxt, distCx, boxH * 0.42f, distPaint)
            val nextSt2 = skNextStreet
            if (!nextSt2.isNullOrBlank()) {
                val stPaint = Paint().apply { color = Color.rgb(230, 230, 230); textSize = boxH * 0.2f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
                c.drawText(nextSt2, distCx, boxH * 0.75f, stPaint)
            }
        }
        val out = ByteArrayOutputStream()
        // Chất lượng thấp hơn trước (35→22): nội dung chỉ nét trắng trên nền đen, hạ chất lượng gần
        // như không đổi hình mà khung nhỏ hơn, xe tải/giải mã nhanh hơn, bớt xoay.
        bmp.compress(Bitmap.CompressFormat.JPEG, 22, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Luồng nền riêng gửi khung sketch — tách khỏi [update] (kênh chữ, gọi mỗi giây từ ngoài) để
     * việc chờ ACK (có thể mất vài giây nếu xe đang nghẽn) không làm chậm kênh chữ. Tự nhịp chậm lại
     * khi xe không ACK kịp. */
    private fun sketchLoop() {
        // Nghỉ trước khung ảnh đầu tiên — lý do: log thực tế (video + log 20261024-213431) cho thấy
        // dù đã nghỉ 1 giây (bản cũ), khung ảnh đầu tiên vẫn mất tới 4,46 giây mới được xe ACK — và
        // ngay trong khoảng chờ đó, màn xe đã tự hiện "Lỗi kết nối" (video người dùng quay lúc vừa mở
        // màn, xe báo lỗi gần như ngay lập tức). Có vẻ 1 giây là chưa đủ cho xe xử lý xong loạt 9 gói
        // cấu hình ban đầu ([sendInitBurst]) + bắt tay xong rồi mới sẵn sàng nhận khung ảnh. Nâng lên
        // 3 giây để thử — vẫn chỉ là thử nghiệm, chưa chắc chắn hết hẳn, vì có thể bản chất là xe xử
        // lý JPEG đầu tiên chậm (cold start phần vẽ màn hình của xe) chứ không riêng việc tranh chấp
        // với loạt gói cấu hình.
        sleepQuiet(3000)
        while (running.get() && connected) {
            if (authed) {
                // LUÔN gửi đều đặn kể cả lúc chưa dẫn đường/mất GPS (khung "chờ") — không bao giờ
                // im lặng hoàn toàn sau khi bắt tay xong. Lý do: quan sát thực tế trên xe, nếu kênh
                // ảnh ngừng gửi (lúc chưa có tuyến, hoặc lúc vừa tới nơi/route về null) thì màn xe tự
                // báo "mất kết nối" sau một lúc im lặng — xe có vẻ cần thấy dữ liệu liên tục mới coi
                // là còn kết nối, chứ không chỉ cần bắt tay xong một lần.
                // Trừ lúc đang chạy test hiệu chỉnh (runCalibration) — để không chen khung vào giữa,
                // làm lẫn lộn số thứ tự/ACK của bộ test.
                if (!calibRunning && !routeTestRunning) {
                    try {
                        sendFrame(buildSketchFrame())
                    } catch (_: Exception) {
                    }
                }
                // ĐÃ THỬ (v6.58→v6.64): giãn nhịp gửi khi ACK gần đây chậm (có lúc chờ thêm tới 7s),
                // với giả thuyết gửi dồn lúc xe còn đang xử lý sẽ làm xe báo "Lỗi kết nối". Log thực
                // tế (20261024-213431, 23 phút, video kèm theo) cho thấy điều NGƯỢC LẠI mới là vấn đề:
                // hầu hết khung đều cuối cùng vẫn được ACK (309 khung, chỉ 8 khung thật sự timeout) —
                // tức kênh KHÔNG hề chết — nhưng vì mỗi lần ACK chậm lại càng chờ lâu hơn mới gửi khung
                // kế tiếp, khoảng lặng giữa 2 lần gửi nhiều lúc lên tới 10–19 giây, khớp đúng những lúc
                // video cho thấy xe tự hiện "Lỗi kết nối" — xe có vẻ tự đếm giờ im lặng riêng, không
                // quan tâm khung cũ cuối cùng có được ACK hay không.
                // v6.67: bám sát tốc độ ACK thực tế thay vì chờ cố định [base] — nhưng chặn trần ở
                // [base] (2.5s/4s) để tránh im lặng dài.
                // v6.68 thử nghiệm: nút test gửi 100 khung "dạng đường" cách đều CỐ ĐỊNH 500ms (không
                // phụ thuộc ACK) — log 20261024-222404 cho kết quả rất tốt: 100/100 khung thành công,
                // 0 timeout, ACK trung bình chỉ 367ms, khoảng lặng xa nhất 5.3s. Ngay sau khi test xong,
                // quay lại nhịp cũ (base 2.5–4s) thì dính ngay 1 khung timeout thật. Bằng chứng cho thấy
                // nhịp nhanh cố định ~500ms ổn định hơn hẳn base 2.5–4s hiện tại. Áp dụng luôn nhịp này
                // cho khung thật, bỏ hẳn [base] làm trần — chỉ còn sàn 400ms (an toàn, không dồn khung).
                val ack = lastFrameAckMs
                val interval = if (ack > 0) (ack + 200) else 500L
                sleepQuiet(interval.coerceIn(400L, 3000L))
            } else {
                sleepQuiet(400)
            }
        }
    }

    /** Ảnh test đơn giản (chữ + khung viền) để kiểm tra riêng kênh ảnh có khiến xe hiện icon bản
     * đồ hay không, trước khi làm khung bản đồ sketch thật. */
    fun buildTestFrame(label: String = "MOTONAV TEST"): ByteArray {
        val w = screenW
        val h = screenH
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val border = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }
        c.drawRect(4f, 4f, (w - 4).toFloat(), (h - 4).toFloat(), border)
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = (h / 5f)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        c.drawText(label, w / 2f, h / 2f + textPaint.textSize / 3f, textPaint)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 35, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Gửi liên tục vài khung ảnh test (cách nhau 1 giây) để xem xe có phản ứng gì không — chạy
     * nền, không chặn luồng gọi. [count] = 0 nghĩa là gửi liên tục cho tới khi [stopTestFrames]. */
    @Volatile private var testFramesRunning = false
    fun sendTestFrames(count: Int = 20) {
        if (testFramesRunning) return
        testFramesRunning = true
        Thread({
            var n = 0
            var i = 0
            while (testFramesRunning && running.get() && (count == 0 || i < count)) {
                i++
                val jpeg = buildTestFrame("TEST #$i")
                if (sendFrame(jpeg)) n++
                sleepQuiet(1500)
            }
            testFramesRunning = false; calibRunning = false
        }, "tft-test-frames").apply { isDaemon = true; start() }
    }

    fun stopTestFrames() {
        testFramesRunning = false; calibRunning = false; routeTestRunning = false
    }

    /** Vẽ khung "dạng đường" giả (tuyến zigzag + điểm rẽ, giống hệt cách [buildSketchFrame] vẽ khung
     * thật) nhưng KHÔNG cần có tuyến/GPS thật — dùng để test riêng việc gửi liên tục nhịp nhanh có
     * đúng là nguyên nhân hay không, tách khỏi mọi phụ thuộc vào dữ liệu dẫn đường thật. */
    private fun buildFakeRouteFrame(i: Int): ByteArray {
        val w = screenW
        val h = screenH
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val anchorX = w / 2f
        val anchorY = h * 0.82f
        val path = android.graphics.Path()
        val rnd = java.util.Random(i.toLong())
        var x = anchorX; var y = anchorY
        path.moveTo(x, y)
        repeat(30) {
            x = (x + (rnd.nextFloat() - 0.5f) * w * 0.08f).coerceIn(0f, w.toFloat())
            y = (y - h * 0.02f).coerceIn(0f, h.toFloat())
            path.lineTo(x, y)
        }
        val linePaint = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
        c.drawPath(path, linePaint)
        val manPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
        c.drawCircle(x, y, h * 0.035f, manPaint)
        val carPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
        val carR = h * 0.06f
        val carPath = android.graphics.Path().apply {
            moveTo(anchorX, anchorY - carR)
            lineTo(anchorX - carR * 0.7f, anchorY + carR * 0.6f)
            lineTo(anchorX + carR * 0.7f, anchorY + carR * 0.6f)
            close()
        }
        c.drawPath(carPath, carPaint)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 22, out) // cùng chất lượng 22 như khung thật
        bmp.recycle()
        return out.toByteArray()
    }

    @Volatile var routeTestRunning = false
        private set

    /** Gửi [count] khung "dạng đường" giả, cách nhau cố định [intervalMs] (không phụ thuộc ACK) —
     * để kiểm tra riêng xem gửi nhịp nhanh cố định có tự gây "Lỗi kết nối" hay không, tách khỏi mọi
     * giả thuyết về giãn nhịp/ACK đã thử trước đó. */
    fun sendRouteTestFrames(count: Int = 100, intervalMs: Long = 500) {
        if (routeTestRunning) return
        stopTestFrames()
        routeTestRunning = true
        Thread({
            var i = 0
            while (routeTestRunning && running.get() && i < count) {
                i++
                val jpeg = try { buildFakeRouteFrame(i) } catch (_: Exception) { continue }
                sendFrame(jpeg, tag = "route-test-${intervalMs}ms")
                sleepQuiet(intervalMs)
            }
            routeTestRunning = false
        }, "tft-route-test").apply { isDaemon = true; start() }
    }

    fun stopRouteTestFrames() {
        routeTestRunning = false
    }

    // ---------- Test hiệu chỉnh: 5 loại khung x 10, tăng dần độ phức tạp, có ghi nhật ký ----------

    /** Vẽ 1 khung test theo [kind] (0..4, xem [CALIB_NAMES]) và chỉ số [i] (1..10, đổi chút nội
     * dung mỗi khung để JPEG không nén trùng y hệt nhau giữa các lần). */
    private fun buildCalibFrame(kind: Int, i: Int): ByteArray {
        val w = screenW
        val h = screenH
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        when (kind) {
            0 -> { // chữ đơn giản — vài ký tự to, gần giống buildTestFrame
                val p = Paint().apply { color = Color.WHITE; textSize = h * 0.18f; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
                c.drawText("TEST $i", w / 2f, h / 2f, p)
            }
            1 -> { // nhiều chữ — mô phỏng khung chữ dẫn đường đầy đủ (nhiều dòng, chữ nhỏ)
                val p = Paint().apply { color = Color.WHITE; textSize = h * 0.07f; isAntiAlias = true }
                val lines = listOf(
                    "Rẽ trái vào Nguyễn Thị Minh Khai", "200 m tới ngã tư Hai Bà Trưng",
                    "Tốc độ tối đa 50 km/h", "Camera phạt nguội phía trước",
                    "Đến lúc 14:4$i", "Quãng đường còn 3.$i km", "Dự kiến còn 12 phút nữa",
                )
                var y = h * 0.12f
                for (ln in lines) { c.drawText(ln, 8f, y, p); y += h * 0.12f }
            }
            2 -> { // siêu nhẹ — gần như trống, chỉ 1 chấm nhỏ đổi vị trí
                val p = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
                c.drawCircle(20f + i * 3f, 20f, 4f, p)
            }
            3 -> { // đường phức tạp — zigzag nhiều đoạn (tăng chi tiết nét so với tuyến bình thường)
                val p = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
                val path = android.graphics.Path()
                val rnd = java.util.Random(i.toLong())
                var x = w * 0.1f; var y = h * 0.9f
                path.moveTo(x, y)
                repeat(40) {
                    x = (x + (rnd.nextFloat() - 0.3f) * w * 0.04f).coerceIn(0f, w.toFloat())
                    y = (y - h * 0.02f).coerceIn(0f, h.toFloat())
                    path.lineTo(x, y)
                }
                c.drawPath(path, p)
            }
            else -> { // bản đồ nặng — lưới nền + tuyến ngoằn ngoèo + chữ + điểm rẽ, nặng hơn khung sketch thường
                val gridP = Paint().apply { color = Color.rgb(70, 70, 70); style = Paint.Style.STROKE; strokeWidth = 1f }
                var gx = 0f
                while (gx < w) { c.drawLine(gx, 0f, gx, h.toFloat(), gridP); gx += 24f }
                var gy = 0f
                while (gy < h) { c.drawLine(0f, gy, w.toFloat(), gy, gridP); gy += 24f }
                val routeP = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }
                val path = android.graphics.Path()
                val rnd = java.util.Random(i.toLong() + 99)
                var x = w * 0.5f; var y = h * 0.95f
                path.moveTo(x, y)
                repeat(25) {
                    x = (x + (rnd.nextFloat() - 0.5f) * w * 0.12f).coerceIn(0f, w.toFloat())
                    y = (y - h * 0.03f).coerceIn(0f, h.toFloat())
                    path.lineTo(x, y)
                }
                c.drawPath(path, routeP)
                val textP = Paint().apply { color = Color.WHITE; textSize = h * 0.06f; isAntiAlias = true }
                c.drawText("Nguyễn Thị Minh Khai", 8f, h * 0.1f, textP)
                c.drawText("Pasteur", 8f, h * 0.2f, textP)
                val manP = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
                c.drawCircle(x, y, h * 0.04f, manP)
            }
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 22, out) // cùng chất lượng 22 như khung thật, để số liệu so sánh được
        bmp.recycle()
        return out.toByteArray()
    }

    val CALIB_NAMES = listOf("chu-don-gian", "nhieu-chu", "sieu-nhe", "duong-phuc-tap", "ban-do-nang")

    @Volatile private var calibRunning = false
    val calibActive: Boolean get() = calibRunning

    /** Chạy 5 loại khung x 10 khung/loại, tăng dần độ phức tạp, cách nhau 1.2s — mỗi khung ghi
     * (loại, dung lượng, ACK) vào nhật ký chuyến đi (sự kiện "tftFrame", trường "tag") để sau này
     * đối chiếu tìm ngưỡng dung lượng mà xe bắt đầu chậm hẳn/mất kết nối. Dừng ngay nếu mất kết nối
     * giữa chừng (ghi lại luôn ở lần "tftFrame" cuối, không cần đoán). */
    fun runCalibration() {
        if (calibRunning || !authed) return
        stopTestFrames()
        calibRunning = true
        Thread({
            try {
                TripLog.ev("tftCalibStart", "batches" to CALIB_NAMES.size, "perBatch" to 10)
                for (kind in CALIB_NAMES.indices) {
                    if (!calibRunning || !running.get() || !connected) break
                    val name = CALIB_NAMES[kind]
                    TripLog.ev("tftCalibBatch", "name" to name)
                    for (i in 1..10) {
                        if (!calibRunning || !running.get() || !connected) break
                        val jpeg = try { buildCalibFrame(kind, i) } catch (_: Exception) { continue }
                        sendFrame(jpeg, tag = name)
                        sleepQuiet(1200)
                    }
                }
            } finally {
                TripLog.ev("tftCalibEnd")
                calibRunning = false
            }
        }, "tft-calib").apply { isDaemon = true; start() }
    }

    fun stopCalibration() {
        calibRunning = false
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

    /** Thời điểm bắt đầu lệnh ghi (write) hiện tại ra socket, 0 nghĩa là không có lệnh ghi nào đang
     * chạy. BluetoothSocket không có cách đặt timeout cho write() của Android — nếu xe không đọc
     * kịp (bộ đệm RFCOMM phía xe đầy), lệnh write() có thể treo vô thời hạn mà không ném lỗi, khiến
     * TOÀN BỘ luồng gửi (cả kênh chữ và kênh ảnh, dùng chung [send] có khoá synchronized) bị đứng im
     * — không phải do CPU đói hay do hệ thống chặn nền. [writeWatchdog] theo dõi biến này để ép đóng
     * kết nối nếu một lệnh ghi treo quá lâu, cho xe bắt tay lại từ đầu. */
    @Volatile private var writeStartedAt: Long = 0L

    /** Đã bắt tay xong (3 bước) — xe sẵn sàng hiển thị/nhận dữ liệu. Chỉ gửi kênh chữ khi true. */
    @Volatile var authed: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile private var lastSentKey: String = ""
    @Volatile private var failCount = 0
    // Tên hiển thị ở nhãn nguồn dưới tam giác vị trí trên xe (gói cmd 3/1, ban đầu chỉ gửi 1 lần
    // lúc bắt tay là "MotoNav") — nay cập nhật lại theo tên đường đang đi, chỉ gửi khi đổi.
    @Volatile private var lastSentName: String = "MotoNav"

    /** Gọi khi xe gửi cmd 49 (bấm nút "Dừng dẫn đường" trên màn xe) — DriveService gán để dừng
     * dẫn đường thật khi người dùng bấm nút này trên đồng hồ xe thay vì trong app. */
    @Volatile var onStopRequested: (() -> Unit)? = null

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
        lastSentName = "MotoNav"
        rxBytes = 0
        handshakeStep = 0
        lastCmdSeen = -1
        txCount = 0
        lastTxAt = 0
        testFramesRunning = false; calibRunning = false
        writeStartedAt = 0L
        synchronized(ackLock) { ackedFrame = -1; (ackLock as Object).notifyAll() }
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
                // QUAN TRỌNG: mỗi lần connect() lỗi mà KHÔNG đóng socket vừa tạo sẽ rò tài nguyên
                // Bluetooth gốc (native fd) — gọi connect() thất bại liên tục (vài chục lần) sẽ làm
                // adapter kẹt dần, phải buộc tắt app (process chết, tài nguyên native mới được dọn)
                // mới gọi lại được. Lỗi thực tế đã gặp: "gọi liên tục nhưng không bao giờ bắt tay
                // được, phải tắt app mở lại mới nhận". Luôn đóng mọi socket tạo hụt trước khi thử
                // tiếp hoặc bỏ cuộc vòng này.
                var secureSock: android.bluetooth.BluetoothSocket? = null
                var insecureSock: android.bluetooth.BluetoothSocket? = null
                try {
                    try {
                        a.cancelDiscovery()
                    } catch (_: Exception) {
                    }
                    val sock = try {
                        secureSock = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                        secureSock.connect()
                        secureSock
                    } catch (_: IOException) {
                        try {
                            secureSock?.close()
                        } catch (_: Exception) {
                        }
                        insecureSock = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
                        insecureSock.connect()
                        insecureSock
                    }
                    onConnected(sock, sock.inputStream, sock.outputStream)
                } catch (e: SecurityException) {
                    try { secureSock?.close() } catch (_: Exception) {}
                    try { insecureSock?.close() } catch (_: Exception) {}
                    lastError = "Thiếu quyền Bluetooth"
                    sleepQuiet(10_000)
                } catch (e: IOException) {
                    try { secureSock?.close() } catch (_: Exception) {}
                    try { insecureSock?.close() } catch (_: Exception) {}
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
        lastSentName = "MotoNav"
        rxBytes = 0
        handshakeStep = 0
        lastCmdSeen = -1
        txCount = 0
        lastTxAt = 0
        frameSeq = 0
        frameTxCount = 0
        lastFrameError = null
        lastFrameAckMs = -1
        synchronized(ackLock) { ackedFrame = -1 }
        // Nâng mức ưu tiên CPU cho 2 luồng này (ngang mức luồng vẽ màn hình của hệ thống) — lý do:
        // người dùng báo thực tế là xe dis ngay lúc vừa mở màn bản đồ, máy còn cầm tay, màn hình
        // CHƯA tắt (tức không phải do hệ điều hành chặn nền lúc màn hình tối). Mở màn bản đồ là lúc
        // máy dồn việc (tải tile bản đồ, dựng lại layout, bắt GPS...) chiếm CPU vài giây — nếu luồng
        // gửi khung ảnh lên xe bị đói CPU đúng lúc đó, khung gửi trễ, xe tự báo "Lỗi kết nối" dù kết
        // nối Bluetooth vẫn còn sống. Ưu tiên cao giúp hệ điều hành vẫn chia CPU cho 2 luồng này dù
        // luồng giao diện đang bận.
        Thread({
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Exception) {}
            readLoop(input, socket)
        }, "tft-reader").apply { isDaemon = true; start() }
        Thread({
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Exception) {}
            sketchLoop()
        }, "tft-sketch").apply { isDaemon = true; start() }
        Thread({ writeWatchdog() }, "tft-writewatch").apply { isDaemon = true; start() }
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
                        0x53 -> { // 2/3: xe gửi mã phần mềm + khoá (toàn bộ payload XOR với 10) → trả
                            // lại đúng khoá (đã XOR) qua 0x54; 12 byte đầu sau khi XOR là mã phần mềm
                            // ASCII, dùng để biết đúng độ phân giải màn hình (dò từ NConnect — thiếu
                            // bước XOR này trước đây khiến khoá trả lại sai và chưa từng dò được W/H).
                            val dec = ByteArray(payload.size) { i -> (payload[i].toInt() xor 10).toByte() }
                            val codeLen = minOf(12, dec.size)
                            val code = String(dec, 0, codeLen, ascii).substringBefore(' ')
                            screenW = 480
                            screenH = if (code.startsWith("006-B4160")) 240 else 234
                            val key = if (dec.size > 12) dec.copyOfRange(12, dec.size) else ByteArray(0)
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
                        0x50 -> { // ACK khung ảnh: payload 2 byte LE = số thứ tự khung xe vừa nhận
                            val seq = if (payload.size >= 2) {
                                (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
                            } else if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0
                            synchronized(ackLock) {
                                ackedFrame = seq
                                (ackLock as Object).notifyAll()
                            }
                        }
                        // Xe bấm nút trên màn (chỉ xảy ra khi màn sketch đang hiện, tức kênh ảnh đã
                        // chạy): trước đây KHÔNG xử lý các cmd này — dò theo NConnect thêm vào đây,
                        // dù chỉ log/gọi callback (không chắc có phải nguyên nhân gây mất kết nối lúc
                        // bấm nút hay không, cần xem lại sau khi lên bản mới).
                        48 -> { // menu đồng hồ: chọn mục (zoom +/- cũng gửi qua đây ở một số bản) — chỉ log
                            TripLog.ev("tftBtn", "cmd" to "48 menu")
                        }
                        49 -> { // nút "Dừng dẫn đường" trên màn xe
                            TripLog.ev("tftBtn", "cmd" to "49 stop")
                            onStopRequested?.invoke()
                        }
                        51 -> { // nút LÊN / zoom+ : vẽ xa hơn
                            skZoomStep = (skZoomStep + 1).coerceIn(-3, 3)
                            TripLog.ev("tftBtn", "cmd" to "51 up/zoom+", "zoomStep" to skZoomStep)
                        }
                        52 -> { // nút XUỐNG / zoom- : vẽ gần hơn
                            skZoomStep = (skZoomStep - 1).coerceIn(-3, 3)
                            TripLog.ev("tftBtn", "cmd" to "52 down/zoom-", "zoomStep" to skZoomStep)
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
            testFramesRunning = false; calibRunning = false
            synchronized(ackLock) { (ackLock as Object).notifyAll() }
        }
    }

    @Synchronized
    private fun send(bytes: ByteArray): Boolean {
        val o = out ?: return false
        writeStartedAt = System.currentTimeMillis()
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
        } finally {
            writeStartedAt = 0L
        }
    }

    /** Theo dõi riêng (không đụng khoá synchronized của [send]) — nếu lệnh ghi hiện tại treo quá
     * 15 giây, coi như kết nối đã chết (xe không đọc kịp/đứng) và ép đóng socket để xe bắt tay lại từ
     * đầu, thay vì để app treo im lặng không giới hạn thời gian như log thực tế từng ghi được (khoảng
     * trống 77 giây liên tục ngay giữa lúc đang dẫn đường thật, trong khi mọi khung khác đều cách
     * nhau tối đa ~7 giây theo thiết kế [sketchLoop]).
     * NGƯỠNG CŨ (4 giây, bản v6.58) QUÁ NHẠY: màn xe này vốn đã hay chậm xử lý vài giây một cách
     * bình thường (thấy rõ ở log hiệu chỉnh dung lượng khung trước đó, ACK hay mất 3-6 giây dù
     * không lỗi gì) — 4 giây bắt oan cả những lần chỉ đang chậm, ép đóng kết nối liên tục ngay giữa
     * lúc đang bắt tay/gửi khung, biến thành vòng lặp kết nối-rồi-rớt không bao giờ ổn định (người
     * dùng báo thực tế: mở lại chục lần vẫn không nhận được gì, hoặc nhận 1 khung rồi đứng luôn —
     * đúng kiểu reset giữa chừng liên tục). Nới lên 15 giây: vẫn bắt được kiểu treo thật kéo dài hàng
     * chục giây, nhưng không đụng tới độ chậm vài giây vốn là bình thường của phần cứng này. */
    private fun writeWatchdog() {
        while (running.get() && connected) {
            val started = writeStartedAt
            if (started != 0L && System.currentTimeMillis() - started > 15_000) {
                lastError = "Gửi treo quá 15 giây (xe không đọc kịp) — ép đóng kết nối để bắt tay lại"
                try {
                    TripLog.ev("tftWriteStall", "ms" to (System.currentTimeMillis() - started))
                } catch (_: Exception) {
                }
                try {
                    activeSocket?.close()
                } catch (_: Exception) {
                }
                break
            }
            sleepQuiet(500)
        }
    }

    /**
     * Gọi liên tục (khoảng mỗi giây) từ dịch vụ chạy nền khi đang dẫn đường. Tự lọc trùng để
     * không gửi lại những gì chưa đổi — giữ đúng tinh thần "kênh chữ nhẹ" không nghẽn Bluetooth.
     */
    fun update(active: Boolean, sign: Int, roundabout: Boolean, distanceM: Int, road: String, etaMinutes: Int, street: String? = null) {
        if (!authed) return
        // Nhãn nguồn dưới tam giác vị trí trên xe — theo yêu cầu, thay chữ "MotoNav" cố định bằng
        // tên đường đang đi (rơi về "MotoNav" khi chưa rõ tên đường/chưa dẫn đường). Tách khỏi
        // "active" ở dưới để vẫn cập nhật được kể cả lúc không dẫn đường (ví dụ đi lạc ra khỏi
        // tuyến), chỉ gửi khi thật sự đổi để không tốn băng thông.
        val wantName = if (active && !street.isNullOrBlank()) street else "MotoNav"
        if (wantName != lastSentName) {
            send(namePacket(wantName))
            lastSentName = wantName
        }
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
