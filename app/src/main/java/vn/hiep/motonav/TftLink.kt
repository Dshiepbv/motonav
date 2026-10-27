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

    // Phương án B (log 115807: các đợt mất ACK bắt đầu cách nhau đúng bội số 5s, chủ yếu 10s —
    // nhịp đến từ phía xe, nghi do app trả lời lại gói bắt tay mỗi lần xe "chào" 0x42 định kỳ):
    // theo dõi khung đang chờ ACK để log ACK lệch số thứ tự/đến trễ, và thời điểm 0x42 gần nhất.
    @Volatile private var waitingSeq: Int = -1
    @Volatile private var waitingSentAt: Long = 0L
    @Volatile private var last42At: Long = 0L

    /** ACK [ack] có "phủ" khung [seq] không: đúng số, hoặc số mới hơn (xe báo đã nhận tới khung
     * sau đó — kiểu ACK tích luỹ), tính cả vòng tràn 16 bit. */
    private fun ackCovers(ack: Int, seq: Int): Boolean {
        if (ack < 0) return false
        if (ack == seq) return true
        return ((ack - seq) and 0xFFFF) in 1 until 0x8000
    }

    @Volatile var frameTxCount: Int = 0
        private set
    @Volatile var lastFrameError: String? = null
        private set
    @Volatile var lastFrameAckMs: Long = -1
        private set

    // Thời gian tối đa chờ ACK trước khi [sendFrame] bỏ chờ, theo yêu cầu "gửi sau 1-2s mà chưa
    // qua thì hủy lệnh gửi hình mới luôn" — ưu tiên độ liền lạc (khung mới liên tục) hơn xác nhận
    // từng khung đã tới xe. App gốc (NConnect) chờ tới 6s; rút còn 1,5s rồi 1s là thử nghiệm riêng
    // của MotoNav. Đối chiếu log thực tế (26/10): các lần "xoay" kéo dài (7-23s liên tục mất ACK)
    // xảy ra ngay cả khi GPS/app vẫn chạy bình thường — là xe không ACK kịp (giới hạn phần cứng),
    // không phải do App chờ lâu — nên rút ngắn thêm chỉ giúp bỏ chờ sớm hơn ở MỖI khung lẻ, không
    // chữa được chuỗi dài; vẫn rút theo yêu cầu để nhịp gửi bám sát 1-2s hơn.
    private const val ACK_WAIT_MS = 1000L

    // build-214, theo log 20261027-144231 (2346 khung, 15,2% quá 1s): khung KHÔNG mất — xe chỉ
    // "đứng" 3,5–6s theo nhịp 5/10s rồi ACK dồn cả loạt khung đã cũ. Gửi thêm khung trong lúc xe
    // đứng chỉ chất thêm hàng đợi khung lỗi thời. Nay chỉ còn MỘT khung "đang bay": quá ACK_WAIT_MS
    // thì vẫn chờ tiếp (không gửi khung mới) tới STALL_WAIT_MS, xe hồi là gửi ngay khung mới nhất.
    // 6s vẫn dưới ngưỡng im lặng 10–19s khiến xe báo "Lỗi kết nối".
    private const val STALL_WAIT_MS = 6000L

    // Thống kê "đứng hình" — tftStat mỗi 60s: tỉ lệ thời gian xe không nhận khung mới (đây mới là
    // con số người lái thấy, khác với tỉ lệ khung quá 1s trước đây — 1 lần đứng từng bị đếm 4 khung).
    private var statFrom = 0L
    private var statFrames = 0
    private var statSlow = 0
    private var statLost = 0
    private var statStallMs = 0L
    private var totFrames = 0
    private var totSlow = 0
    private var totLost = 0
    private var totStallMs = 0L
    private var totFrom = 0L

    // build-221 — thử nghiệm nhịp gửi, tự luân phiên mỗi 2 phút (2 cửa sổ tftStat) để so trong CÙNG
    // một chuyến: 0 = A (nghỉ 400 ms như cũ), 1 = B (né cửa sổ dự đoán xe đứng theo nhịp 10 s),
    // 2 = C (nghỉ 900 ms). Thước đo chung để so công bằng: gapPct = % thời gian màn KHÔNG có khung
    // mới quá 1 s (khoảng cách giữa 2 lần xe ACK, trừ 1 s) — tính cả lúc app CỐ Ý chờ ở nhịp B/C,
    // nên nhịp nào gửi thưa đi cũng không "ăn gian" được con số.
    @Volatile private var paceMode = 0
    private var statWindows = 0
    private var lastAckAt = 0L
    private var statGapMs = 0L
    private var totGapMs = 0L
    private val gapByMode = LongArray(3)
    private val spanByMode = LongArray(3)
    @Volatile private var lastStallStart = 0L

    /** Tốc độ đồng hồ xe (km/h) — byte thứ 2 của gói cmd 0x41 xe gửi ~1 lần/giây (giải mã từ log
     * 27/10: khớp tốc độ GPS ±1–2 km/h). -1 = chưa nhận. */
    @Volatile var bikeSpeedKmh: Int = -1
        private set

    /** Còn bao lâu (ms) mới hết cửa sổ rủi ro quanh mốc đứng dự đoán (lần đứng gần nhất + bội số
     * 10 s): từ 400 ms trước mốc tới 800 ms sau mốc. 0 = gửi được ngay. */
    private fun riskWaitMs(now: Long): Long {
        val last = lastStallStart
        if (last == 0L || now - last > 120_000) return 0L
        val ph = ((now - last) % 10_000 + 10_000) % 10_000
        return when {
            ph >= 9_600 -> 10_000 - ph + 800
            ph < 800 -> 800 - ph
            else -> 0L
        }
    }

    private fun recordFrameStat(ackMs: Long, lost: Boolean) {
        val now = System.currentTimeMillis()
        if (statFrom == 0L) statFrom = now
        if (totFrom == 0L) totFrom = now
        statFrames++; totFrames++
        if (ackMs > ACK_WAIT_MS) {
            val stall = ackMs - ACK_WAIT_MS / 2
            statSlow++; totSlow++
            statStallMs += stall; totStallMs += stall
            if (lost) { statLost++; totLost++ }
            lastStallStart = now - ackMs
            try { TripLog.ev("tftStall", "ms" to ackMs, "lost" to lost, "mode" to paceMode) } catch (_: Exception) {}
        }
        if (!lost) {
            if (lastAckAt != 0L) {
                val gap = (now - lastAckAt - 1000).coerceAtLeast(0)
                statGapMs += gap; totGapMs += gap
            }
            lastAckAt = now
        }
        val span = now - statFrom
        if (span >= 60_000) {
            val totSpan = (now - totFrom).coerceAtLeast(1)
            val m = paceMode
            gapByMode[m] += statGapMs; spanByMode[m] += span
            fun pct(a: Long, b: Long) = if (b <= 0) -1.0 else Math.round(a * 1000.0 / b) / 10.0
            try {
                TripLog.ev("tftStat", "mode" to "ABC"[m].toString(), "frames" to statFrames, "slow" to statSlow, "lost" to statLost,
                    "freezePct" to pct(statStallMs, span), "gapPct" to pct(statGapMs, span),
                    "bikeKmh" to bikeSpeedKmh,
                    "totFrames" to totFrames, "totSlowPct" to Math.round(totSlow * 1000.0 / totFrames.coerceAtLeast(1)) / 10.0,
                    "totFreezePct" to pct(totStallMs, totSpan), "totGapPct" to pct(totGapMs, totSpan), "totLost" to totLost,
                    "gapA" to pct(gapByMode[0], spanByMode[0]), "gapB" to pct(gapByMode[1], spanByMode[1]), "gapC" to pct(gapByMode[2], spanByMode[2]))
            } catch (_: Exception) {}
            statFrom = now; statFrames = 0; statSlow = 0; statLost = 0; statStallMs = 0L; statGapMs = 0L
            statWindows++
            paceMode = (statWindows / 2) % 3
        }
    }

    /** Vài khung JPEG gần nhất đã gửi lên xe — ghi vào nhật ký khi bấm "Gửi nhật ký" để xem lại
     * đúng hình xe nhận được (trước đây chỉ đoán qua ảnh chụp màn xe). */
    private const val RECENT_FRAMES = 10
    private val recentFrames = ArrayDeque<Pair<Int, ByteArray>>()

    fun dumpRecentFrames() {
        val list = synchronized(recentFrames) { recentFrames.toList() }
        for ((seq, jpg) in list) {
            try {
                TripLog.ev("tftShot", "seq" to seq, "jpg" to android.util.Base64.encodeToString(jpg, android.util.Base64.NO_WRAP))
            } catch (_: Exception) {}
        }
    }

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
        waitingSeq = seq
        waitingSentAt = sentAt
        if (!send(pack(payload, 0, 1))) {
            lastFrameError = "Gửi khung lỗi (mất kết nối)"
            return false
        }
        // Trước đây chờ ACK tới 6 giây (giống app gốc) trước khi coi khung này "xong" để
        // [sketchLoop] rảnh tay dựng khung kế tiếp — nhưng theo yêu cầu mới nhất "chủ chốt là sự
        // liền lạc trên TFT": nếu quá ACK_WAIT_MS (1,5s, giữa khoảng 1-2s yêu cầu) mà xe chưa ACK
        // thì BỎ CHỜ luôn, không chặn tiếp — để khung MỚI (vị trí/tuyến tươi nhất) được dựng+gửi
        // sớm hơn thay vì kẹt lại chờ xác nhận một khung đã có thể lỗi thời. ACK trễ của khung này
        // (nếu rốt cuộc vẫn tới) không gây lỗi gì — chỉ đơn giản không còn ai đứng đợi nó ở đây nữa
        // (case 0x50 vẫn cập nhật [ackedFrame] bình thường). CHƯA xác nhận trên xe thật việc rút
        // ngắn này có thực sự giảm "xoay" hay khiến xe dồn khung tệ hơn — cần chạy thử thực tế.
        synchronized(recentFrames) {
            recentFrames.addLast(seq to jpeg)
            while (recentFrames.size > RECENT_FRAMES) recentFrames.removeFirst()
        }
        val deadline = sentAt + STALL_WAIT_MS
        var timedOut = false
        var interrupted = false
        synchronized(ackLock) {
            while (running.get() && connected && !ackCovers(ackedFrame, seq)) {
                val now = System.currentTimeMillis()
                if (now >= deadline) {
                    lastFrameError = "Khung #$seq chưa ACK sau ${STALL_WAIT_MS}ms — bỏ chờ, gửi khung mới"
                    timedOut = true
                    break
                }
                try {
                    (ackLock as Object).wait((deadline - now).coerceAtLeast(1))
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
        val waited = System.currentTimeMillis() - sentAt
        if (timedOut) {
            lastFrameAckMs = STALL_WAIT_MS
        } else {
            lastFrameAckMs = waited
            lastFrameError = null
            frameTxCount++
        }
        recordFrameStat(waited, timedOut)
        // Ghi vào nhật ký chuyến đi (dung lượng khung thật gửi đi + thời gian ACK) — để sau này xem
        // lại có thể đối chiếu xem ACK bắt đầu chậm hẳn từ cỡ khung bao nhiêu byte, tìm ra một mức
        // dung lượng "an toàn" thay vì đoán mò bằng chất lượng JPEG.
        try {
            // timedOut giữ nghĩa cũ (quá ACK_WAIT_MS) để so được với log các bản trước; lost = quá
            // STALL_WAIT_MS vẫn không có ACK.
            TripLog.ev("tftFrame", "seq" to seq, "bytes" to jpeg.size, "ackMs" to (if (timedOut) null else lastFrameAckMs),
                "timedOut" to (timedOut || waited > ACK_WAIT_MS), "lost" to (if (timedOut) true else null), "tag" to tag)
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
    // Hướng hợp lệ gần nhất (khi xe đứng yên/chạy chậm, Live.heading = -1 vì GPS course không đáng
    // tin ở tốc độ thấp — DriveService.onLocation dòng ~401). Trước đây lúc đó khung lấy mặc định
    // 0° (coi như hướng Bắc) làm bản đồ đột ngột "xoay ngang" mỗi lần dừng xe rồi lại đúng khi chạy
    // tiếp. Giữ nguyên hướng cuối cùng đã biết thay vì nhảy về 0° để bản đồ không tự xoay khi dừng.
    @Volatile private var skLastGoodHeadingDeg = 0.0
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
    // Khoảng cách từ chỗ rẽ sắp tới -> chỗ rẽ kế tiếp nữa, để luôn nhìn xa đủ qua khỏi chỗ rẽ sắp
    // tới (không cắt cụt đường xanh đúng ngay chỗ rẽ) theo phản hồi thực tế.
    @Volatile private var skThenDistM: Double = 0.0
    @Volatile private var skEtaMs: Long = 0L
    @Volatile private var skCurrentStreet: String? = null
    // Thời tiết dự báo lúc tới điểm đến — icon góc trên-phải, cạnh biển tốc độ, theo yêu cầu.
    @Volatile private var skWxCat: Int? = null
    @Volatile private var skWxLabel: String? = null
    @Volatile private var skWxNight: Boolean = false
    // Zoom thủ công bằng nút vật lý trên xe (51 lên = xa hơn, 52 xuống = gần hơn), cộng dồn vào
    // công thức maxAhead theo tốc độ. -3..+3, 0 = mặc định.
    @Volatile private var skZoomStep: Int = 0
    // Mức zoom theo tốc độ (0=xa/>=40, 1=vừa/20-39, 2=gần/<20) — nhớ lại mức trước đó và chỉ đổi
    // khi tốc độ vượt hẳn qua ngưỡng (có biên trễ ~2-3km/h mỗi bên) để đứng yên/chạy chậm quanh
    // mốc 20km/h không làm bản đồ zoom ra zoom vào liên tục do nhiễu tốc độ GPS.
    @Volatile private var skSpdTier: Int = 2
    // Góc "nghiêng camera 3D" hiện tại (0=phẳng kiểu cũ, 1=nghiêng đầy đủ kiểu Waze) — trườn dần
    // tới mức mục tiêu theo skSpdTier mỗi khung thay vì nhảy khựng, xem buildSketchFrame(). Đã dò
    // công thức + kiểm chứng trước trên mockup artifact (TFT Phạm Văn Đồng) theo yêu cầu thực tế.
    @Volatile private var skTiltT: Double = 1.0
    @Volatile private var skTiltLastMs: Long = 0L
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
        currentStreet: String? = null,
        wxCat: Int? = null,
        wxLabel: String? = null,
        wxNight: Boolean = false,
        thenDistM: Double = 0.0,
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
        skCurrentStreet = currentStreet
        skWxCat = wxCat
        skWxLabel = wxLabel
        skWxNight = wxNight
        skDistanceM = distanceM
        skNextStreet = nextStreet
        skRemainingM = remainingM
        skEtaMs = etaMs
        skThenDistM = thenDistM
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
        // "var" vì ở nhánh có tuyến, anchorY được tính lại theo toScreen(0,0) — vị trí xe trên màn
        // đổi theo mức nghiêng camera 3D (skTiltT), xem bên dưới.
        var anchorY = h * 0.72f
        if (route == null || route.lats.size < 2) {
            // Khung "chờ" — gửi đều đặn khi chưa có tuyến (chưa dẫn đường/mất GPS/vừa tới nơi) để
            // xe không coi là mất kết nối vì im lặng quá lâu.
            // Logo NMAX NAVI ở giữa màn (bản nền tối: nét tối đảo sang trắng, giữ màu đỏ — chỉ
            // ~5KB JPEG, nhẹ hơn khung dẫn đường). Chưa nạp được logo thì về dòng chữ cũ.
            val logo = logoBmp
            if (logo != null) {
                val lh = h * 0.82f
                val lw = lh * logo.width / logo.height
                val dst = android.graphics.RectF((w - lw) / 2f, (h - lh) / 2f, (w + lw) / 2f, (h + lh) / 2f)
                c.drawBitmap(logo, null, dst, Paint().apply { isFilterBitmap = true; isAntiAlias = true })
            } else {
                val p2 = Paint().apply { color = Color.WHITE; textSize = h * 0.13f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
                c.drawText("Chờ tuyến đường…", w / 2f, h * 0.52f, p2)
            }
        } else {
            val proj = LocalProjection(skLat, skLon)
            // Tìm điểm gần vị trí hiện tại nhất trên tuyến. "Ghim" quanh chỉ số khung trước (nếu
            // cùng tuyến) để tránh nhảy xa sang một đoạn khác của tuyến tình cờ ở gần — nguyên nhân
            // đường xanh thỉnh thoảng biến mất rồi lại hiện (báo lỗi thực tế từ xe). Chỉ quét lại
            // toàn tuyến khi chưa có điểm ghim, đổi tuyến, hoặc điểm ghim cũ lệch quá xa (>80m, có
            // thể do mất GPS/dừng lâu/đi xa khỏi tuyến thật).
            // Theo phản hồi thực tế (đường to có "đường song hành" chạy song song sát bên — rất
            // phổ biến ở các đại lộ VN, thường kèm điểm quay đầu/giao cắt): trong cửa sổ ghim trên,
            // điểm GẦN NHẤT theo khoảng cách đơn thuần đôi lúc lại là một điểm xa hẳn về TIẾN TRÌNH
            // dọc tuyến (route.cum) — ví dụ một đoạn tuyến quay đầu/lượn qua làn song song chạm lại
            // gần vị trí xe. Khi đó idx0 bị nhảy vọt qua khỏi khúc rẽ, khiến đoạn vẽ (idx0..iEnd0)
            // co ngắn lại đúng lúc gần tới khúc quẹo — nhìn như "đường xanh biến mất", và lưới dò
            // nhánh đường (dùng chung idx0..iEnd0) cũng thiếu đoạn đó nên nhánh thẳng tiếp không lên
            // hình. Phạt thêm theo mức tiến trình nhảy quá xa (>150m, khó xảy ra thật giữa 2 khung
            // liên tiếp) để ưu tiên điểm LIỀN MẠCH hơn điểm gần tuyệt đối khi có nhiều điểm cùng gần
            // vị trí GPS (giao lộ/làn song song/vòng quay đầu).
            val hintValid = skIdxHintRoute === route && skIdxHint in route.lats.indices
            var idx0: Int
            var best = Double.MAX_VALUE
            if (hintValid) {
                val lo = (skIdxHint - 15).coerceAtLeast(0)
                val hi = (skIdxHint + 120).coerceAtMost(route.lats.size - 1)
                val hintCum = route.cum[skIdxHint]
                var bi = skIdxHint
                var bestCost = Double.MAX_VALUE
                for (i in lo..hi) {
                    val d = Geo.distance(skLat, skLon, route.lats[i], route.lons[i])
                    val jump = Math.abs(route.cum[i] - hintCum)
                    val penalty = if (jump > 150.0) (jump - 150.0) * 0.6 else 0.0
                    val cost = d + penalty
                    if (cost < bestCost) { bestCost = cost; bi = i; best = d }
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
            // Theo yêu cầu: dưới 50km/h phóng to 300% (maxAhead còn 1/3), 50-60km/h phóng to 200%
            // (còn 1/2), trên đó về mức bình thường — tốc độ thấp thường là đường nhỏ/khu đông dân
            // cư/vòng xoay, cần thấy rõ chi tiết gần hơn là nhìn xa.
            // Đứng yên hoặc đi lăng quăng quanh mốc 50/60km/h: tốc độ GPS hay nhiễu nhảy quanh 0 hoặc
            // quanh ngưỡng, nếu xét trực tiếp mỗi khung sẽ làm "zoom ra zoom vào" liên tục không để
            // làm gì (theo phản hồi thực tế). Dùng mức đã nhớ + biên trễ: chỉ đổi mức khi tốc độ vượt
            // hẳn qua ngưỡng (không chỉ chạm mép), và đứng yên (0km/h) giữ nguyên mức "gần" (300%)
            // như đang đi chậm, không nhảy về mức "xa" của khi không có skSpeedKmh hợp lệ.
            val v = skSpeedKmh
            if (v >= 0) {
                skSpdTier = when (skSpdTier) {
                    2 -> if (v > 52) (if (v > 62) 0 else 1) else 2
                    1 -> if (v > 62) 0 else if (v < 48) 2 else 1
                    else -> if (v < 58) (if (v < 48) 2 else 1) else 0
                }
            }
            val spdMul = when (skSpdTier) {
                2 -> 1.0 / 3.0
                1 -> 0.5
                else -> 1.0
            }
            val baseMaxAhead = (1800.0 * zoomMul * spdMul).coerceIn(400.0, 3600.0)
            // Luôn nhìn xa đủ qua khỏi chỗ rẽ sắp tới (+150m) và hé thêm một đoạn qua chỗ rẽ kế
            // tiếp nữa (+150m) nếu biết — theo phản hồi thực tế "gần tới chỗ rẽ mới thì đường xanh
            // hiện tại bị tắt": trước đây maxAhead chỉ theo tốc độ/zoom, có lúc ngắn hơn khoảng cách
            // tới chỗ rẽ (đặc biệt lúc đi chậm, maxAhead ~600m) nên đúng lúc sắp rẽ, đoạn đường sau
            // chỗ rẽ bị cắt mất khỏi khung hình, nhìn như đường xanh "biến mất". Không giới hạn trên
            // vì phép chiếu fisheye đã tự nén đoạn xa lại, không làm đường quá bé.
            val turnDist = skDistanceM.takeIf { it >= 0 }?.toDouble() ?: 0.0
            val afterTurnDist = if (skThenDistM > 0) turnDist + skThenDistM else turnDist
            val maxAhead = maxOf(baseMaxAhead, turnDist + 150.0, afterTurnDist * 0.3 + 150.0)
                .coerceIn(400.0, 3600.0)
            // Theo phản hồi thực tế nhiều lần "đường xanh chỉ tô khi chạy gần tới": mức zoom lúc đi
            // chậm (<50km/h → maxAhead còn 1/3, tức ~600m) trước đây bị dùng LUÔN làm luôn cả khoảng
            // vẽ tuyến (iEnd0) lẫn lưới nhánh đường — nghĩa là đoạn đường xa hơn 600m KHÔNG hề được
            // thu thập/vẽ chút nào (không phải vẽ nhỏ/nén, mà là không vẽ), nên nhìn như tuyến xanh
            // "dừng lại" và chỉ hiện tiếp khi xe chạy vào trong phạm vi đó. Tách riêng [drawAhead]
            // (khoảng cách THỰC SỰ thu thập điểm để vẽ, luôn giữ xa — không bị zoom tốc độ thấp làm
            // co lại) khỏi [maxAhead] (chỉ dùng để tính độ phóng to/nén phối cảnh gần xe cho rõ chỗ
            // rẽ). Phép nén fisheye (mũ 0.55, trần tỉ lệ 2.0 ở [toScreen]) đã tự ép đoạn xa về sát mép
            // khung hình chứ không văng ra ngoài, nên tăng khoảng vẽ lên không làm vỡ bố cục.
            val drawAhead = maxOf(1800.0 * zoomMul, maxAhead).coerceIn(400.0, 3600.0)
            val headingNow = if (skHeadingDeg >= 0) { skLastGoodHeadingDeg = skHeadingDeg; skHeadingDeg } else skLastGoodHeadingDeg
            val hRad = Math.toRadians(headingNow)
            val cosH = Math.cos(hRad); val sinH = Math.sin(hRad)
            // px trên "mét đã nén" — chỉnh theo [maxAhead] (mức zoom, không phải khoảng vẽ thật) để
            // chỗ rẽ gần vẫn to rõ kể cả khi [drawAhead] đã kéo xa hơn nhiều.
            val scale = (h * 0.68f) / maxAhead.toFloat()
            // rx/ry (mét thật, đã xoay theo hướng xe) -> toạ độ pixel, nén bán kính theo căn bậc hai
            // (mũ 0.55) quanh vị trí xe: gần xe gần như không nén (dễ thấy rẽ), xa xe nén mạnh hơn.
            fun flatToScreen(rx: Double, ry: Double): FloatArray {
                val dist = Math.hypot(rx, ry)
                if (dist < 1.0) return floatArrayOf(anchorX, h * 0.72f)
                val warped = maxAhead * Math.pow((dist / maxAhead).coerceAtMost(2.0), 0.55)
                val ux = rx / dist; val uy = ry / dist
                return floatArrayOf(
                    anchorX + (ux * warped * scale).toFloat(),
                    h * 0.72f - (uy * warped * scale).toFloat()
                )
            }
            // ---- Nghiêng camera 3D kiểu Waze theo tốc độ (yêu cầu thực tế: "đi nhanh zoom dần 2D,
            // chậm nghiêng dần 3D, chuyển mượt") — đã dò công thức + kiểm chứng trước trên mockup
            // artifact (TFT Phạm Văn Đồng) rồi mới đưa vào đây. Giữ nguyên [maxAhead] đã tính ở trên
            // (đã tự zoom theo skSpdTier) làm chuẩn cho cả 2 công thức, chỉ thêm độ "xoè ngang" kiểu
            // phối cảnh camera ảo (lùi sau xe 12m, cao 6m, cúp 50° ở mức nghiêng đầy) rồi TRỘN với
            // công thức phẳng cũ theo [skTiltT] — không chuyển thẳng, tránh giật khung khi đổi tốc độ.
            val tiltTarget = when (skSpdTier) { 2 -> 1.0; 1 -> 0.55; else -> 0.0 }
            val nowMs = System.currentTimeMillis()
            val dtMs = (if (skTiltLastMs == 0L) 400L else (nowMs - skTiltLastMs)).coerceIn(50L, 2000L)
            skTiltLastMs = nowMs
            // Trườn ~63%/650ms về mục tiêu bất kể nhịp khung thực tế đang giãn/co theo ACK — cùng
            // hằng số làm mượt (650ms) đã kiểm chứng mượt mắt trên mockup.
            val smoothK = 1.0 - Math.exp(-dtMs / 650.0)
            skTiltT += (tiltTarget - skTiltT) * smoothK
            val tiltT = skTiltT
            val tiltTheta = Math.toRadians(50.0)
            val tiltCamH = 6.0
            val tiltCamBack = 12.0
            val tiltFocal = 146.8
            fun tiltLateralFactor(ry: Double): Double {
                val vz = (ry + tiltCamBack) * Math.sin(tiltTheta) + tiltCamH * Math.cos(tiltTheta)
                return tiltFocal / Math.max(vz, 1.0)
            }
            fun tiltToScreen(rx: Double, ry: Double): FloatArray {
                val aY = h * 0.80f; val hzY = h * 0.16f
                val sgn = if (ry >= 0) 1.0 else -1.0
                val warped = Math.pow((Math.abs(ry) / maxAhead).coerceAtMost(2.0), 0.55)
                val sy = aY - (sgn * warped * (aY - hzY)).toFloat()
                val sx = anchorX + (rx * tiltLateralFactor(ry)).toFloat()
                return floatArrayOf(sx, sy)
            }
            // Theo phản hồi thực tế sau khi lên xe chạy thử: (1) mũi tên định vị + ETA bị đẩy xuống
            // thấp hơn trước vì anchor của khung nghiêng (0.80) thấp hơn khung phẳng cũ (0.72); (2)
            // mũi tên định vị hay nhìn như "văng ra ngoài đường" — vì độ xoè ngang của phối cảnh
            // nghiêng rất mạnh ngay sát xe (ry≈0, hệ số xoè lớn nhất), nên sai số match-vị-trí nhỏ bị
            // phóng đại thành lệch hẳn khỏi nét đường. SỬA cả hai cùng lúc: chỉ bắt đầu nghiêng dần từ
            // sau [tiltRampM] mét tính từ xe — một đoạn ngắn ngay trước mặt luôn vẽ gần-như-phẳng
            // (độ xoè nhỏ như 2D) để định vị luôn nằm đúng trên nét đường, xa hơn mới nghiêng dần vào
            // 3D đầy đủ theo tốc độ. Nhờ vậy tại đúng vị trí xe (ry=0) khung LUÔN là công thức phẳng
            // cũ (anchorY quay lại 0.72 như trước), không còn bị đẩy xuống nữa — không cần chỉnh riêng.
            // Theo phản hồi thực tế lần 2: định vị vẫn lệch nhẹ khỏi tâm đường dù đã nâng ngưỡng —
            // cần một đoạn "gần như phẳng" dài hơn nữa, rồi mới cong dần vào 3D thay vì dốc một lần.
            // Chọn hằng số sao cho ~80m vẫn gần như 2D (smooth01(80/300)≈0.18), ~150m là mức lưng
            // chừng "2.5D" (smooth01(150/300)=0.5 đúng — smoothstep đối xứng quanh điểm giữa), rồi
            // tiếp tục cong mượt (cùng công thức smoothstep, không phải bậc rời rạc) cho tới khi
            // chạm 3D đầy đủ quanh mốc 300m.
            val tiltRampM = 300.0
            fun smooth01(x: Double): Double { val cl = x.coerceIn(0.0, 1.0); return cl * cl * (3.0 - 2.0 * cl) }
            fun toScreen(rx: Double, ry: Double): FloatArray {
                val effT = tiltT * smooth01(ry / tiltRampM)
                if (effT <= 0.001) return flatToScreen(rx, ry)
                val f = flatToScreen(rx, ry)
                val g = tiltToScreen(rx, ry)
                return floatArrayOf(
                    f[0] + (g[0] - f[0]) * effT.toFloat(),
                    f[1] + (g[1] - f[1]) * effT.toFloat()
                )
            }
            anchorY = toScreen(0.0, 0.0)[1]
            // Đoạn ngay sau xe (ry<=0) luôn qua nhánh phẳng (effT=0, xem smooth01 ở trên) nên công
            // thức nghiêng không bao giờ được dùng ở đó nữa — không còn cần xiết riêng giới hạn phía
            // sau theo tiltT như bản trước, trả về đúng giới hạn rộng cũ.
            val behindLimit = -drawAhead * 0.3

            // Phạm vi tuyến chính sẽ vẽ (idx0..iEnd) — tính trước để dùng làm "lưới" phát hiện nhánh
            // đường THỰC SỰ gắn vào trục chính (xem bên dưới).
            var iEnd0 = idx0
            while (iEnd0 < route.lats.size && route.cum[iEnd0] - cum0 <= drawAhead) iEnd0++

            // Nhánh đường — PHƯƠNG ÁN THỬ NGHIỆM theo yêu cầu "đường chính và 2 bên đường chính": thay
            // vì dò way GẮN THẲNG vào tuyến chính rồi nối chuỗi theo đầu mút (cũ, hay bỏ sót đường gần
            // tuyến nhưng không có giao lộ nối trực tiếp), giờ vẽ MỌI way có ít nhất 1 điểm nằm trong
            // hành lang hai bên đoạn tuyến chính sắp hiện ra màn hình — kiểu "chụp khung" quanh tuyến
            // như bản đồ thật, không cần xét way đó có nối vào tuyến hay không. Lưới ô ~250m, mỗi điểm
            // mẫu trên tuyến tô thêm ô xung quanh (lưới vuông, không phải hình tròn chính xác — đủ
            // dùng cho mục đích hiển thị). Thử giảm từ 1km xuống 500m mỗi bên theo yêu cầu, phòng khi
            // bề rộng 1km làm máy phải xử lý/vẽ nặng quá.
            val ways = skWays
            if (ways != null) {
                // Theo yêu cầu "cái nào nhìn giống Google Map hơn": Google Maps không ẩn hẳn đường
                // cấp 3 (tertiary/residential) mà chỉ GIẢM PHẠM VI hiển thị quanh tuyến + phân cấp độ
                // dày/độ nổi bật — đã làm ở branchPaintFor bên dưới. Nên chọn giảm hành lang xuống
                // 300m mỗi bên (thu từ 400m, giữ đủ loại đường, giảm thêm dung lượng ảnh/reload)
                // thay vì cắt hẳn đường cấp 3, để không mất ngữ cảnh khu vực xung quanh mà vẫn đỡ rối.
                val cellDeg = 150.0 / 111320.0
                fun corridorKey(lat: Double, lon: Double): Long {
                    val la = Math.floor(lat / cellDeg).toLong()
                    val lo = Math.floor(lon / cellDeg).toLong()
                    return (la shl 24) xor (lo and 0xFFFFFFL)
                }
                val corridorGrid = HashSet<Long>()
                // Giữ lại các điểm mẫu trên tuyến chính (không phải vị trí xe) để sau này ưu tiên vẽ
                // way GẦN TUYẾN CHÍNH trước rồi lan dần ra 2 bên — theo yêu cầu "luôn ưu tiên load từ
                // trong đường chính ra 2 bên" (trước đo khoảng cách tới vị trí xe, lệch tâm khi tuyến
                // uốn cong phía trước, không đúng ý "từ chính tuyến lan ra").
                val routeSamples = ArrayList<DoubleArray>()
                val cellSpan = 2 // ~2*150m = 300m mỗi hướng quanh mỗi điểm mẫu trên tuyến
                var gi = idx0
                var lastCum = -1e9
                while (gi < iEnd0) {
                    if (route.cum[gi] - lastCum >= 150.0 || gi == idx0) {
                        lastCum = route.cum[gi]
                        routeSamples.add(doubleArrayOf(route.lats[gi], route.lons[gi]))
                        val baseLa = Math.floor(route.lats[gi] / cellDeg).toLong()
                        val baseLo = Math.floor(route.lons[gi] / cellDeg).toLong()
                        for (da in -cellSpan..cellSpan) for (db in -cellSpan..cellSpan) {
                            corridorGrid.add(((baseLa + da) shl 24) xor ((baseLo + db) and 0xFFFFFFL))
                        }
                    }
                    gi++
                }
                // Phân cấp đường như bản đồ thật: đường lớn (quốc lộ/trục chính) dày+trắng sáng rõ,
                // đường vừa (khu vực) dày vừa, đường nhỏ/hẻm/nội bộ mảnh+xám mờ hơn.
                // Theo yêu cầu: TẤT CẢ nhánh đường đều màu trắng (màu xám trước đây khó thấy giữa
                // ban ngày, trời nắng) — chỉ còn phân biệt bằng ĐỘ DÀY tương ứng với bề rộng thực tế
                // ngoài đời (quốc lộ/trục chính rộng nhất -> hẻm/đường nội bộ hẹp nhất).
                val branchPaintMajor = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3.4f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
                val branchPaintMid = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.4f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
                val branchPaintMinor = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.7f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
                val branchPaintTiny = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.1f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
                fun branchPaintFor(highway: String): Paint = when (highway) {
                    "motorway", "trunk", "primary", "motorway_link", "trunk_link", "primary_link" -> branchPaintMajor
                    "secondary", "tertiary", "secondary_link", "tertiary_link" -> branchPaintMid
                    "residential", "unclassified", "living_street" -> branchPaintMinor
                    else -> branchPaintTiny // service/track/alley/hẻm nhỏ/footway lẫn trong dữ liệu...
                }
                // BUG đã gặp trên máy thật: chỉ hiện nhánh đường bên TRÁI, bên phải trống trơn. Nguyên
                // nhân — trước đây duyệt `ways` ĐÚNG THỨ TỰ mảng trả về và dừng ngay khi đủ [wayCap]:
                // nếu dữ liệu OSM trả về theo khối/ô không gian (way ở một phía của tuyến nằm liền
                // nhau trong mảng), việc dừng sớm theo thứ tự mảng dễ "ăn hết" toàn bộ chỉ tiêu vào MỘT
                // phía trước khi kịp xét tới phía bên kia. SỬA: gom hết way nằm trong hành lang trước,
                // rồi vẽ theo thứ tự GẦN TUYẾN CHÍNH NHẤT trước, lan dần ra 2 bên tới khi đủ trần —
                // đảm bảo cả 2 bên đường được ưu tiên công bằng, không phụ thuộc thứ tự trả về của dữ
                // liệu, và luôn ưu tiên load sát tuyến chính trước thay vì đo theo vị trí xe (lệch tâm
                // khi tuyến uốn cong phía trước).
                fun distToRoute(lat: Double, lon: Double): Double {
                    var best = Double.MAX_VALUE
                    for (rp in routeSamples) {
                        val d = Geo.distance(lat, lon, rp[0], rp[1])
                        if (d < best) best = d
                    }
                    return best
                }
                val candidates = ArrayList<Pair<Way, Double>>()
                for (wy in ways) {
                    if (wy.lats.isEmpty()) continue
                    var inCorridor = false
                    var nearestD = Double.MAX_VALUE
                    val sampleStep = (wy.lats.size / 6).coerceAtLeast(1)
                    var si = 0
                    while (si < wy.lats.size) {
                        if (corridorGrid.contains(corridorKey(wy.lats[si], wy.lons[si]))) {
                            inCorridor = true
                            val d = distToRoute(wy.lats[si], wy.lons[si])
                            if (d < nearestD) nearestD = d
                        }
                        si += sampleStep
                    }
                    if (inCorridor) candidates.add(wy to nearestD)
                }
                candidates.sortBy { it.second }
                var drawnWays = 0
                val wayCap = 220 // hành lang bắt nhiều way hơn kiểu nối-chuỗi cũ, nới trần lên tương ứng
                for ((wy, _) in candidates) {
                    if (drawnWays >= wayCap) break
                    val bp = android.graphics.Path()
                    var bStarted = false
                    var any = false
                    val step = (wy.lats.size / 55).coerceAtLeast(1)
                    var j = 0
                    while (j < wy.lats.size) {
                        val dx = proj.x(wy.lons[j]); val dy = proj.y(wy.lats[j])
                        val rx = dx * cosH - dy * sinH; val ry = dx * sinH + dy * cosH
                        if (Math.abs(rx) <= drawAhead * 1.1 && ry >= behindLimit && ry <= drawAhead) {
                            val (sx, sy) = toScreen(rx, ry)
                            if (!bStarted) { bp.moveTo(sx, sy); bStarted = true } else bp.lineTo(sx, sy)
                            any = true
                        } else {
                            bStarted = false
                        }
                        j += step
                    }
                    if (any) { c.drawPath(bp, branchPaintFor(wy.highway)); drawnWays++ }
                }
            }

            // Với maxAhead lớn (tới 4km), tuyến có thể có hàng nghìn điểm trong đoạn này — nếu vẽ
            // hết sẽ làm khung JPEG phình to, chậm, có thể gây mất kết nối lại (đã sửa ở bản
            // trước). iEnd0 đã dò ở trên (dùng chung cho lưới phát hiện nhánh đường) — giãn bước vẽ
            // để tối đa ~260 điểm, vẫn giữ đủ hình dạng đường ở độ phân giải nhỏ của màn dash.
            val iEnd = iEnd0
            val ptCount = iEnd - idx0
            val pathStep = (ptCount / 260).coerceAtLeast(1)
            val path = android.graphics.Path()
            // Toạ độ màn hình của tuyến đỏ — để nhãn tên đường tự né, không đè lên tuyến.
            val routePx = ArrayList<FloatArray>()
            var started = false
            var i = idx0
            // Tuyến đỏ phải bắt đầu ĐÚNG tại mũi tên xe (phản hồi thực tế: "mất đường đỏ chỗ định
            // vị"). Trước đây nét đỏ bắt đầu từ điểm gấp khúc GẦN NHẤT của tuyến (idx0) — trên đoạn
            // đường thẳng dài, điểm này có thể cách xe hàng trăm mét (phía trước hoặc phía sau), nên
            // quanh mũi tên xe trống trơn. Nay chiếu vị trí xe lên đoạn tuyến kề idx0, bắt đầu vẽ từ
            // chính vị trí xe (nếu xe cách tuyến < 25 m) → điểm chiếu → các điểm tuyến phía trước.
            // Chỉ đổi điểm bắt đầu của nét đỏ, không đụng phép chiếu 3D/xoay.
            run {
                var bestD = Double.MAX_VALUE
                var bestPx = 0.0; var bestPy = 0.0; var bestNext = idx0
                for (a in intArrayOf(idx0 - 1, idx0)) {
                    if (a < 0 || a + 1 >= route.lats.size) continue
                    val ax = proj.x(route.lons[a]); val ay = proj.y(route.lats[a])
                    val bx = proj.x(route.lons[a + 1]); val by = proj.y(route.lats[a + 1])
                    val sdx = bx - ax; val sdy = by - ay
                    val len2 = sdx * sdx + sdy * sdy
                    val t = if (len2 < 0.01) 0.0 else (-(ax * sdx + ay * sdy) / len2).coerceIn(0.0, 1.0)
                    val px = ax + t * sdx; val py = ay + t * sdy
                    val d = Math.hypot(px, py)
                    if (d < bestD) { bestD = d; bestPx = px; bestPy = py; bestNext = a + 1 }
                }
                if (bestD < 25.0) {
                    path.moveTo(anchorX, toScreen(0.0, 0.0)[1])
                    routePx.add(floatArrayOf(anchorX, toScreen(0.0, 0.0)[1]))
                    val prx = bestPx * cosH - bestPy * sinH
                    val pry = bestPx * sinH + bestPy * cosH
                    val (psx, psy) = toScreen(prx, pry)
                    path.lineTo(psx, psy)
                    routePx.add(floatArrayOf(psx, psy))
                    started = true
                    i = bestNext
                }
            }
            while (i < iEnd) {
                val dx = proj.x(route.lons[i])
                val dy = proj.y(route.lats[i])
                val rx = dx * cosH - dy * sinH
                val ry = dx * sinH + dy * cosH
                val (sx, sy) = toScreen(rx, ry)
                if (!started) { path.moveTo(sx, sy); started = true } else path.lineTo(sx, sy)
                routePx.add(floatArrayOf(sx, sy))
                i += pathStep
            }
            // Đảm bảo luôn vẽ tới điểm cuối thực (iEnd-1) kể cả khi bước nhảy bỏ qua nó.
            if (started && (iEnd - 1) >= idx0 && (iEnd - 1 - idx0) % pathStep != 0) {
                val li = (iEnd - 1).coerceAtMost(route.lats.size - 1)
                val dx = proj.x(route.lons[li]); val dy = proj.y(route.lats[li])
                val rx = dx * cosH - dy * sinH; val ry = dx * sinH + dy * cosH
                val (lx, ly) = toScreen(rx, ry)
                path.lineTo(lx, ly)
                routePx.add(floatArrayOf(lx, ly))
            }
            // Nhãn tên-đường-sắp-rẽ gắn trên bản đồ (đường kẻ nối + nền đen) — giữ lại kiểu chỉ này
            // theo yêu cầu, nhưng bỏ chấm tròn ở đầu đường kẻ, và neo KHÔNG còn đúng tại điểm rẽ
            // (skManLat/skManLon) nữa — ở đó nhiều đường giao cắt chồng lên nhau trên hình, chấm/đường
            // kẻ trông mơ hồ không rõ đang chỉ con đường nào. SỬA LẠI theo yêu cầu: neo không lùi về
            // phía đường ĐANG ĐI nữa (hướng cũ, sai) mà tiến TỚI khoảng 50m vào ĐƯỜNG SẮP QUẸO (đoạn
            // tuyến sau điểm rẽ) — đúng là "cọc chỉ dẫn" cắm trên con đường sắp đi tới, không phải
            // con đường hiện tại.
            val nextSt = skNextStreet
            if (!nextSt.isNullOrBlank()) {
                val turnCumTarget = cum0 + (skDistanceM.takeIf { it >= 0 }?.toDouble() ?: 0.0)
                var mIdx = idx0
                while (mIdx < iEnd0 - 1 && route.cum[mIdx] < turnCumTarget) mIdx++
                var aIdx = mIdx
                while (aIdx < iEnd0 - 1 && route.cum[aIdx] - route.cum[mIdx] < 50.0) aIdx++
                val mdx = proj.x(route.lons[aIdx]); val mdy = proj.y(route.lats[aIdx])
                val mrx = mdx * cosH - mdy * sinH; val mry = mdx * sinH + mdy * cosH
                val (mx, my) = toScreen(mrx, mry)
                val labelPaint = Paint().apply { color = Color.WHITE; textSize = h * 0.09f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
                val bgPaint = Paint().apply { color = Color.argb(190, 0, 0, 0); style = Paint.Style.FILL }
                val leaderPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }
                val tw = labelPaint.measureText(nextSt)
                val padX = h * 0.022f; val padY = h * 0.02f
                // Ảnh thực tế (27/10): nền đen của nhãn nằm ĐÈ lên đúng đoạn tuyến đỏ quanh khúc rẽ
                // (nhãn vẽ sau tuyến, neo lệch lên-phải cố định) — nhìn như tuyến đỏ bị mất đoạn. Thử 4
                // hướng đặt nhãn quanh điểm neo, chọn hướng có ít điểm tuyến lọt vào khung nhãn nhất.
                // Tuyến đỏ vẫn được vẽ SAU nhãn (bên dưới) nên kể cả lúc buộc phải chồng lên vẫn thấy đỏ.
                val offs = arrayOf(floatArrayOf(0.16f, -0.1f), floatArrayOf(-0.16f, -0.1f), floatArrayOf(0.16f, 0.12f), floatArrayOf(-0.16f, 0.12f))
                var bestBox: FloatArray? = null
                var bestHits = Int.MAX_VALUE
                for (o in offs) {
                    val lx0 = (mx + h * o[0]).coerceIn(h * 0.02f, w - h * 0.02f)
                    val ly0 = (my + h * o[1]).coerceIn(h * 0.1f, h * 0.95f)
                    val left = (lx0 - tw / 2f - padX).coerceAtLeast(2f)
                    val right = (left + tw + padX * 2f).coerceAtMost(w - 2f)
                    val top = ly0 - h * 0.08f - padY
                    val bottom = ly0 + h * 0.026f + padY
                    var hits = 0
                    for (pt in routePx) if (pt[0] in (left - 6f)..(right + 6f) && pt[1] in (top - 6f)..(bottom + 6f)) hits++
                    if (hits < bestHits) { bestHits = hits; bestBox = floatArrayOf(lx0, ly0, left, right, top, bottom) }
                    if (hits == 0) break
                }
                val bx = bestBox!!
                c.drawLine(mx, my, bx[0], bx[1], leaderPaint)
                val cxText = (bx[2] + bx[3]) / 2f
                c.drawRect(bx[2], bx[4], bx[3], bx[5], bgPaint)
                c.drawText(nextSt, cxText, bx[1], labelPaint)
            }
            // Tuyến chính đỏ tươi — vẽ SAU CÙNG trong phần bản đồ (sau nhánh đường + nhãn) để không gì
            // che được. Thêm viền đen dày bên dưới: JPEG chất lượng thấp (4:2:0) làm nhạt màu đỏ ở
            // chỗ nét đỏ chạm sát nét trắng; nền đen đồng nhất quanh nét giữ màu đỏ rõ hơn.
            val casingPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 14f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            val linePaint = Paint().apply { color = Color.rgb(255, 0, 0); style = Paint.Style.STROKE; strokeWidth = 9.5f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            // Nét đỏ cong mềm (theo yêu cầu "uốn cong mềm mại, đừng xù xì"): dựng lại đường từ các
            // điểm màn hình — bỏ điểm sát nhau (< 3 px, gây răng cưa), rồi nối bằng đường cong bậc 2
            // qua trung điểm từng cặp điểm (góc gấp thành cung tròn mượt, vẫn đi đúng hình tuyến).
            val sp = ArrayList<FloatArray>()
            for (pt in routePx) {
                val last = sp.lastOrNull()
                if (last == null || Math.hypot((pt[0] - last[0]).toDouble(), (pt[1] - last[1]).toDouble()) >= 3.0) sp.add(pt)
            }
            val drawPath = if (sp.size >= 3) {
                android.graphics.Path().apply {
                    moveTo(sp[0][0], sp[0][1])
                    for (k in 1 until sp.size - 1) {
                        val mx2 = (sp[k][0] + sp[k + 1][0]) / 2f
                        val my2 = (sp[k][1] + sp[k + 1][1]) / 2f
                        quadTo(sp[k][0], sp[k][1], mx2, my2)
                    }
                    lineTo(sp[sp.size - 1][0], sp[sp.size - 1][1])
                }
            } else path
            c.drawPath(drawPath, casingPaint)
            c.drawPath(drawPath, linePaint)
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
        // ETA (giờ đến, bên TRÁI) và thời gian+quãng đường còn lại (bên PHẢI) — 2 khung giống hệt
        // nhau (trắng, viền đỏ, chữ đen) để đối xứng 2 bên, cùng ngang hàng với tam giác vị trí xe
        // (anchorY), theo yêu cầu mới nhất. Trước đây khung còn lại nằm dưới biển tốc độ, dạng chữ
        // trần không khung — nay gộp lại 1 dòng và đóng khung y hệt ETA.
        if (skActive && skRoute != null) {
            val remKm = skRemainingM / 1000.0
            val remTxt = if (remKm >= 10) "${remKm.toInt()} km" else String.format("%.1f km", remKm)
            val remMin = (skEtaMs / 60000L).toInt()
            val arriveCal = java.util.Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() + skEtaMs }
            val etaTxt = String.format("%02d:%02d", arriveCal.get(java.util.Calendar.HOUR_OF_DAY), arriveCal.get(java.util.Calendar.MINUTE))
            val remainTxt = "$remMin ph · $remTxt"
            // Chữ to hơn chút theo yêu cầu (0.11->0.12) nhưng khung giữ kiểu/đệm như cũ.
            val etaTextPaint = Paint().apply { color = Color.BLACK; textSize = h * 0.12f; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
            val padX2 = h * 0.035f; val padY2 = h * 0.028f
            val fmE = etaTextPaint.fontMetrics
            val textH = fmE.descent - fmE.ascent
            val boxH = textH + padY2 * 2f
            val boxTop = anchorY - h * 0.03f - boxH / 2f
            val cornerR = h * 0.025f
            val etaFillPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
            val etaBorderPaint = Paint().apply { color = Color.rgb(220, 30, 30); style = Paint.Style.STROKE; strokeWidth = h * 0.014f; isAntiAlias = true }
            // Khung ETA — bên trái
            val twEta = etaTextPaint.measureText(etaTxt)
            val boxWEta = twEta + padX2 * 2f
            val boxLeftEta = w * 0.035f
            val rectEta = android.graphics.RectF(boxLeftEta, boxTop, boxLeftEta + boxWEta, boxTop + boxH)
            c.drawRoundRect(rectEta, cornerR, cornerR, etaFillPaint)
            c.drawRoundRect(rectEta, cornerR, cornerR, etaBorderPaint)
            c.drawText(etaTxt, rectEta.centerX(), rectEta.centerY() - (fmE.ascent + fmE.descent) / 2f, etaTextPaint)
            // Khung thời gian+quãng đường còn lại — bên phải, cùng hàng, cùng kiểu, cùng chiều cao
            val twRem = etaTextPaint.measureText(remainTxt)
            val boxWRem = twRem + padX2 * 2f
            val boxRightRem = w - w * 0.035f
            val rectRem = android.graphics.RectF(boxRightRem - boxWRem, boxTop, boxRightRem, boxTop + boxH)
            c.drawRoundRect(rectRem, cornerR, cornerR, etaFillPaint)
            c.drawRoundRect(rectRem, cornerR, cornerR, etaBorderPaint)
            c.drawText(remainTxt, rectRem.centerX(), rectRem.centerY() - (fmE.ascent + fmE.descent) / 2f, etaTextPaint)
        }
        // Chỉ dẫn rẽ — icon mũi tên tự vẽ (KHÔNG ghi chữ "trái"/"phải"), khoảng cách đếm ngược và
        // tên đường sắp quẹo vào, cố định góc trên-trái, KHÔNG nền (theo yêu cầu). Trước đây icon rẽ
        // định gửi qua gói riêng cho xe tự vẽ (turnPacket/IconMap, cmd 4/1) nhưng mã icon đó CHƯA xác
        // nhận hiển thị đúng trên màn thật — vẽ thẳng vào khung JPEG ở đây để chắc chắn lên hình.
        // Bố cục theo kiểu Waze (ảnh người dùng gửi): icon bên TRÁI, chữ khoảng cách + tên đường
        // xếp dọc bên PHẢI icon (không còn xếp dọc dưới icon như trước) — chỉ khác Waze ở chỗ KHÔNG
        // có khung nền tối phía sau, theo đúng yêu cầu ban đầu "ko nền".
        if (skActive && skRoute != null && !skNextStreet.isNullOrBlank()) {
            // To hơn nữa theo yêu cầu "cho cái icon to to tí" (0.135->0.17).
            val arm = h * 0.17f
            // Đẩy cả bảng sát kịch góc trên-trái theo yêu cầu mới nhất.
            val iconCx = w * 0.075f
            val iconCy = arm * 0.78f
            // Bỏ icon tự vẽ (bezier/cung tròn) — quay lại tam giác đơn giản xoay theo góc rẽ, đúng
            // kiểu bản đầu tiên (v6.77) theo yêu cầu "chỉ tam giác như lúc đầu".
            val apexDeg = Maneuver.angle(skSign)
            val arrowR = arm * 0.62f
            c.save()
            c.rotate(apexDeg, iconCx, iconCy)
            val arrowPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
            val arrowPath = android.graphics.Path().apply {
                moveTo(iconCx, iconCy - arrowR)
                lineTo(iconCx - arrowR * 0.8f, iconCy + arrowR * 0.7f)
                lineTo(iconCx, iconCy + arrowR * 0.3f)
                lineTo(iconCx + arrowR * 0.8f, iconCy + arrowR * 0.7f)
                close()
            }
            c.drawPath(arrowPath, arrowPaint)
            c.restore()
            // Bỏ hẳn tên đường khỏi bảng này theo yêu cầu — chỉ còn icon + khoảng cách, căn giữa
            // icon theo chiều dọc (tên đường giờ chỉ còn hiện trên bản đồ, xem nhãn gắn tuyến bên
            // dưới, đã phóng to).
            val textX = iconCx + arm * 0.95f
            // Khoảng cách trên bảng dẫn giữ màu trắng theo yêu cầu (không đổi đỏ như ETA/tuyến chính).
            val infoPaint = Paint().apply { color = Color.WHITE; textSize = h * 0.13f; textAlign = Paint.Align.LEFT; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
            val dM = skDistanceM
            if (dM >= 0) {
                val distTxt = if (dM >= 1000) String.format(java.util.Locale.US, "%.1f km", dM / 1000f) else "$dM m"
                val fm = infoPaint.fontMetrics
                c.drawText(distTxt, textX, iconCy - (fm.ascent + fm.descent) / 2f, infoPaint)
            }
        }
        val out = ByteArrayOutputStream()
        // Hạ tiếp (17→14) theo log thực tế chuyến 115807 (0.205): vẫn ~20% khung timeout ACK dù đã
        // hạ xuống 17 trước đó, TB ~9,7KB. Nhưng đối chiếu kỹ: timeout KHÔNG tăng tuyến tính rõ theo
        // cỡ khung (có đoạn 9,0KB mà 0% timeout, có đoạn 9,9KB mà 41% timeout) — mà đi THEO CHUỖI dài
        // 4-12 khung timeout liên tiếp rồi lại hết hẳn một lúc, kiểu ngắt quãng BLE/khoảng cách chứ
        // không thuần "khung nặng". Hạ quality thêm vẫn nên làm (luôn giúp ít nhiều, không hại gì)
        // nhưng KHÔNG kỳ vọng hết hẳn "reload" — nếu vẫn còn, nhiều khả năng là giới hạn phần cứng/
        // khoảng cách Bluetooth phía xe chứ không phải thứ sửa thêm bằng code được nữa.
        bmp.compress(Bitmap.CompressFormat.JPEG, 14, out)
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
                    // Nhịp B: tránh gửi khung đúng lúc dự đoán xe sắp "đứng" (theo nhịp 10 s học từ lần
                    // đứng gần nhất) — chờ qua cửa sổ rủi ro rồi mới gửi khung mới nhất.
                    if (paceMode == 1) {
                        val wait = riskWaitMs(System.currentTimeMillis())
                        if (wait > 0) sleepQuiet(wait)
                    }
                    try {
                        sendFrame(buildSketchFrame())
                    } catch (_: Exception) {
                    }
                }
                // Tóm tắt lịch sử (v6.58→v6.69): gốc vấn đề không phải gửi dồn mà là KHOẢNG LẶNG —
                // xe có đồng hồ đếm im lặng riêng, im lặng 10–19s là tự hiện "Lỗi kết nối" (log
                // 20261024-213431), bất kể khung cũ có được ACK hay không. Công thức co giãn theo ACK
                // (dù gọi là "~500ms") vẫn cộng dồn được tới ~9s lúc mạng xấu vì sendFrame() đã tự
                // chờ ACK tới 6s bên trong rồi mới tới phần nghỉ này. Theo yêu cầu "khung liên tục,
                // gần như không bao giờ xoay, ngắn nhất có thể trong mức an toàn": bỏ hẳn phụ thuộc
                // ACK, dùng CỐ ĐỊNH 400ms — đây là mức sàn đã xác định an toàn qua nhiều bản trước
                // (thấp hơn từng gây dồn khung/xe xử lý không kịp), ngắn hơn 500ms vừa test (100/100
                // khung, 0 timeout). sendFrame() nay cũng chỉ còn chờ ACK tối đa 1,5s (trước 6s — xem
                // ACK_WAIT_MS), nên khoảng lặng xấu nhất giữa 2 khung giờ chỉ còn ~1,9s (1,5s chờ ACK
                // + 0,4s nghỉ), thay vì 6,4s trước đây.
                // Nhịp C: nghỉ dài hơn (900 ms) giữa 2 khung — ít khung hơn, thử xem có ít lần đứng hơn.
                sleepQuiet(if (paceMode == 2) 900 else 400)
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
        bmp.compress(Bitmap.CompressFormat.JPEG, 14, out) // cùng chất lượng 14 như khung thật
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
        bmp.compress(Bitmap.CompressFormat.JPEG, 14, out) // cùng chất lượng 14 như khung thật, để số liệu so sánh được
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

    @Volatile private var logoBmp: Bitmap? = null

    fun start(ctx: Context, deviceAddr: String?) {
        if (!running.compareAndSet(false, true)) return
        if (logoBmp == null) {
            logoBmp = try { android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.tft_logo) } catch (_: Throwable) { null }
        }
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
        waitingSeq = -1
        last42At = 0L
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
                    // Phương án B: ghi lại MỌI lệnh xe gửi tới (trừ ACK khung bình thường — quá
                    // nhiều) để đối chiếu với thời điểm mất ACK — trước đây không log nên không xác
                    // nhận được nhịp 10s đến từ đâu.
                    if (cmd != 0x50) {
                        try {
                            // Kèm nội dung gói ngắn (hex) — cmd 0x41 (3 byte) xe gửi ~1 lần/giây kể cả lúc
                            // đứng hình, chưa rõ ý nghĩa; xem có cờ "bận" báo trước nhịp đứng 5/10s không.
                            val hex = if (payload.size <= 16) payload.joinToString("") { String.format("%02x", it.toInt() and 0xFF) } else null
                            TripLog.ev("tftRx", "cmd" to cmd, "sub" to sub, "len" to payload.size, "authed" to authed, "hex" to hex)
                        } catch (_: Exception) {
                        }
                    }
                    if (cmd == 0x41 && payload.size >= 2) bikeSpeedKmh = payload[1].toInt() and 0xFF
                    when (cmd) {
                        0x42 -> { // 1/3: xe gửi serial → trả khả năng hỗ trợ
                            // Xe có thể gửi lại cmd này nhiều lần (loan báo định kỳ) kể cả sau khi đã
                            // qua bước 2/3, 3/3 — KHÔNG được coi là "bắt đầu lại từ đầu" và reset các
                            // bước đã qua, nếu không sẽ bị kẹt mãi dù xe đã xác nhận xong (lỗi đã gặp
                            // thực tế: xe báo đã tới bước 3/3 nhưng app vẫn chưa coi là bắt tay xong).
                            // Phương án B: ĐÃ bắt tay xong thì KHÔNG trả lời lại 0x51/0x21 cho lần
                            // "chào" định kỳ nữa — nghi xe coi đó là kết nối lại, khởi tạo lại màn dẫn
                            // đường và bỏ các khung đang tới (đúng kiểu "reload" + mất ACK theo nhịp
                            // 10s trong log). Ngoại lệ: xe gửi 0x42 dồn dập (2 lần trong < 3s) thì coi
                            // là xe THẬT SỰ cần trả lời (đang chờ bắt tay) → vẫn trả lời như cũ.
                            val now42 = System.currentTimeMillis()
                            val insist = last42At != 0L && now42 - last42At < 3000
                            last42At = now42
                            if (!authed || insist) {
                                if (handshakeStep < 1) handshakeStep = 1
                                send(pack(byteArrayOf(1, 0), 0x51, 0))
                                send(pack(byteArrayOf(0x7c, 6, 0, 1, 0, 0, 0, 0), 0x21, 1))
                                if (authed) try { TripLog.ev("tft42", "action" to "reply-insist") } catch (_: Exception) {}
                            } else {
                                try { TripLog.ev("tft42", "action" to "skip") } catch (_: Exception) {}
                            }
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
                            val want = waitingSeq
                            var ackVal = seq
                            if (want >= 0 && seq != want) {
                                // ACK lệch số thứ tự: ghi lại để biết là ACK TRỄ của khung trước (xe
                                // đang chậm, dồn khung) hay xe ĐẾM LẠI từ đầu (sau khi tự khởi tạo lại).
                                val ms = System.currentTimeMillis() - waitingSentAt
                                try { TripLog.ev("tftAckOdd", "got" to seq, "want" to want, "ms" to ms) } catch (_: Exception) {}
                                // Xe đếm lại từ đầu (số rất nhỏ trong khi app đã đếm xa) → coi như ACK
                                // khung đang chờ, để không mất oan hàng loạt khung tới khi số khớp lại.
                                if (seq <= 3 && want > 20) ackVal = want
                            }
                            synchronized(ackLock) {
                                ackedFrame = ackVal
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
    fun update(active: Boolean, sign: Int, roundabout: Boolean, distanceM: Int, road: String, currentStreet: String, etaMinutes: Int) {
        if (!authed) return
        // Nhãn nguồn dưới tam giác vị trí trên xe: đã đổi qua lại vài lần qua các bản (tên đường
        // đang đi → câu chỉ dẫn rẽ) — nay theo yêu cầu mới nhất quay về TÊN ĐƯỜNG ĐANG ĐI
        // (currentStreet), vì phần chỉ dẫn rẽ (icon/khoảng cách/tên đường sắp quẹo) đã có chỗ riêng,
        // tự vẽ cố định góc trên-trái khung ảnh (xem buildSketchFrame). Rơi về "MotoNav" khi chưa
        // dẫn đường hoặc chưa map-match ra tên đường. Tách khỏi "active" ở dưới để vẫn cập nhật được
        // kể cả lúc không dẫn đường, chỉ gửi khi thật sự đổi để không tốn băng thông.
        val wantName = if (currentStreet.isNotBlank()) currentStreet else "MotoNav"
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
