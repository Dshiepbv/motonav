package vn.hiep.motonav

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cập nhật phần mềm từ GitHub Releases ngay trong app.
 * Bản cài được truyền thẳng vào trình cài đặt của Android (không lưu file .apk nào trong máy).
 */
object Updater {
    private const val REPO = "Dshiepbv/motonav"
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Gọi một endpoint GitHub API. Nếu đang kèm mã mà bị 401/403 (mã sai/hết hạn/sai quyền —
     *  ĐÂY LÀ NGUYÊN NHÂN GÂY LỖI "401" KHI TẢI GÓI OFFLINE) thì tự thử lại KHÔNG mã một lần:
     *  kho motonav hiện là kho CÔNG KHAI nên không cần mã vẫn đọc được release. Ném
     *  RuntimeException với thông báo đã bản địa hoá nếu cả hai lần đều thất bại. */
    private fun <T> gh(url: String, token0: String, accept: String, parse: (okhttp3.Response) -> T): T {
        var token = token0
        while (true) {
            http.newCall(req(url, token, accept)).execute().use { r ->
                if (r.isSuccessful) return parse(r)
                val code = r.code
                if (token.isNotBlank() && (code == 401 || code == 403)) {
                    token = "" // mã bị từ chối — kho công khai không cần mã, thử lại lần cuối
                    return@use
                }
                throw RuntimeException(mapGhError(code))
            }
        }
    }

    private fun mapGhError(code: Int) = when (code) {
        401 -> "Mã GitHub sai hoặc đã hết hạn — kho motonav hiện CÔNG KHAI, hãy XOÁ mã trong Cài đặt để tải trực tiếp"
        403 -> "GitHub chặn truy cập (hết lượt) — thử lại sau ít phút, hoặc đổi mã GitHub khác"
        404 -> "Không thấy dữ liệu trên GitHub (nếu kho chuyển riêng tư thì cần mã có quyền đọc)"
        else -> "GitHub trả lỗi $code"
    }

    private fun packInfo(token: String): Triple<Long, Long, String> {
        val o = gh("https://api.github.com/repos/$REPO/releases/tags/osm-data", token, "application/vnd.github+json") { r ->
            JSONObject(r.body!!.string())
        }
        val assets = o.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val x = assets.getJSONObject(i)
            if (x.getString("name") == "osm-hcm.zip")
                return Triple(x.getLong("id"), x.optLong("size"), x.optString("updated_at"))
        }
        throw RuntimeException("Chưa có file osm-hcm.zip trong release osm-data")
    }

    class Release(val version: String, val build: Int, val assetId: Long, val size: Long, val notes: String)

    /** Trạng thái cho hàng "Cập nhật phần mềm" trong Cài đặt — hiện trực tiếp dưới dòng đó (thanh
     * tiến độ + chữ nhỏ), không mở bảng hỏi riêng. */
    sealed class UpdateState {
        object Checking : UpdateState()
        class UpToDate(val ver: String) : UpdateState()
        class Downloading(val pct: Int, val doneMB: Long, val totalMB: Long) : UpdateState()
        object Installing : UpdateState()
        class Error(val msg: String) : UpdateState()
    }

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** Số build (versionCode) của bản đang cài — dùng để so "mới hơn" thay vì so chuỗi tên bản,
     * vì tên bản (versionName) không còn tăng dần đều kiểu "6.95 → 6.96" (đổi sang "0.<build>"). */
    fun currentBuild(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (_: Exception) {
        0
    }

    private fun req(url: String, token: String, accept: String) = Request.Builder().url(url)
        .header("Accept", accept)
        .header("User-Agent", "MotoNav")
        .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
        .build()

    private fun latest(token: String): Release {
        val o = gh("https://api.github.com/repos/$REPO/releases/latest", token, "application/vnd.github+json") { r ->
            JSONObject(r.body!!.string())
        }
        val assets = o.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name")
            val m = Regex("""MotoNav-([\d.]+)\.apk""").find(name) ?: continue
            val build = Regex("""(\d+)""").find(o.optString("tag_name"))?.value?.toIntOrNull() ?: 0
            return Release(m.groupValues[1], build, a.getLong("id"), a.optLong("size"), o.optString("body"))
        }
        throw RuntimeException("Bản phát hành mới nhất không có file APK")
    }

    /** build-437: thông tin file bản đồ nền offline cả VN (release "vn-map", asset vietnam.mbtiles). */
    private fun mapInfo(token: String): Triple<Long, Long, String> {
        val o = gh("https://api.github.com/repos/$REPO/releases/tags/vn-map", token, "application/vnd.github+json") { r ->
            JSONObject(r.body!!.string())
        }
        val assets = o.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val x = assets.getJSONObject(i)
            if (x.getString("name") == "vietnam.mbtiles")
                return Triple(x.getLong("id"), x.optLong("size"), x.optString("updated_at"))
        }
        throw RuntimeException("Chưa có file bản đồ Việt Nam (đang dựng trên GitHub, thử lại sau)")
    }

    /** build-437 (Sếp): tải nền bản đồ offline cả Việt Nam (vài trăm MB) — chạy nền, hiện % trong hộp thoại. */
    fun downloadVnMap(a: Activity, prefs: Prefs, done: () -> Unit) {
        val token = prefs.githubToken
        val progress = AlertDialog.Builder(a).setTitle("Đang tải bản đồ Việt Nam").setMessage("Đang kiểm tra…").setCancelable(true).show()
        val ctx = a.applicationContext
        fun ui(f: () -> Unit) = main.post { try { f() } catch (_: Throwable) {} }
        Thread {
            try {
                val (id, size, updated) = mapInfo(token)
                val dst = MapSnap.offlineFile(ctx)
                val tmp = java.io.File(dst.parentFile, "vietnam.tmp")
                gh("https://api.github.com/repos/$REPO/releases/assets/$id", token, "application/octet-stream") { r ->
                    val body = r.body!!
                    val total = body.contentLength().takeIf { it > 0 } ?: size
                    tmp.outputStream().use { out ->
                        body.byteStream().use { inp ->
                            val buf = ByteArray(256 * 1024)
                            var got = 0L; var last = -1
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                val pct = if (total > 0) (got * 100 / total).toInt() else 0
                                if (pct != last) { last = pct; ui { progress.setMessage("$pct%  (${got / 1_000_000}/${total / 1_000_000} MB)\nCó thể đóng hộp thoại, vẫn tải tiếp.") } }
                            }
                        }
                    }
                }
                if (size > 0 && tmp.length() < size) throw RuntimeException("tải chưa đủ (${tmp.length() / 1_000_000}/${size / 1_000_000} MB)")
                if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) }
                ctx.getSharedPreferences("motonav", Context.MODE_PRIVATE).edit().putString("vnMapUpdated", updated).apply()
                TripLog.ev("vnMap", "ok" to true, "bytes" to dst.length())
                ui {
                    progress.dismiss()
                    Toast.makeText(ctx, "Đã có bản đồ offline Việt Nam (${dst.length() / 1_000_000} MB)", Toast.LENGTH_LONG).show()
                    done()
                }
            } catch (e: Exception) {
                ui {
                    progress.dismiss()
                    Toast.makeText(ctx, "Không tải được bản đồ: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Tải gói dữ liệu OSM ngoại tuyến (release "osm-data") vào bộ nhớ riêng của app. */
    fun downloadPack(a: Activity, prefs: Prefs, done: () -> Unit) {
        // Kho motonav hiện CÔNG KHAI: tải không cần mã; mã chỉ dùng dự phòng nếu kho đổi riêng tư.
        val token = prefs.githubToken
        val progress = AlertDialog.Builder(a).setTitle("Đang tải dữ liệu ngoại tuyến").setMessage("Đang kiểm tra…").setCancelable(false).show()
        val ctx = a.applicationContext
        Thread {
            try {
                val (id, size, updated) = try {
                    packInfo(token)
                } catch (e: Exception) {
                    if (e.message?.contains("riêng tư") == true) {
                        main.post { progress.dismiss(); askToken(a, prefs) }
                        return@Thread
                    }
                    throw e
                }
                val dst = OfflinePack.file(ctx)
                val tmp = java.io.File(dst.parentFile, "pack.tmp")
                gh("https://api.github.com/repos/$REPO/releases/assets/$id", token, "application/octet-stream") { r ->
                    val body = r.body!!
                    val total = body.contentLength().takeIf { it > 0 } ?: size
                    tmp.outputStream().use { out ->
                        body.byteStream().use { inp ->
                            val buf = ByteArray(64 * 1024)
                            var got = 0L
                            var last = -1
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                val pct = if (total > 0) (got * 100 / total).toInt() else 0
                                if (pct != last) { last = pct; main.post { progress.setMessage("$pct%  (${got / 1_000_000}/${total / 1_000_000} MB)") } }
                            }
                        }
                    }
                }
                OfflinePack.close()
                if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) }
                val ok = OfflinePack.open(ctx)
                if (ok) ctx.getSharedPreferences("motonav", Context.MODE_PRIVATE).edit().putString("packUpdated", updated).apply()
                main.post {
                    progress.dismiss()
                    Toast.makeText(ctx, if (ok) "Đã có dữ liệu ngoại tuyến (cập nhật ${updated.take(10)})" else "File tải về bị lỗi", Toast.LENGTH_LONG).show()
                    done()
                }
            } catch (e: Exception) {
                main.post {
                    progress.dismiss()
                    Toast.makeText(ctx, "Không tải được: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * Tự cập nhật gói OSM ngầm: mỗi ngày kiểm tra một lần, chỉ khi đang dùng Wi-Fi; có bản mới (hoặc chưa có gói)
     * thì tải về thay thế. Gọi từ luồng nền; không hiện hộp thoại.
     */
    fun autoPack(ctx: Context, prefs: Prefs) {
        try {
            val sp = ctx.getSharedPreferences("motonav", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - sp.getLong("packCheckAt", 0L) < 20L * 3600_000) return
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return
            if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return
            val token = prefs.githubToken
            val (id, size, updated) = try { packInfo(token) } catch (_: Exception) { return }
            sp.edit().putLong("packCheckAt", now).apply()
            val dst = OfflinePack.file(ctx)
            if (dst.exists() && sp.getString("packUpdated", "") == updated) return
            val tmp = java.io.File(dst.parentFile, "pack.tmp")
            try {
                gh("https://api.github.com/repos/$REPO/releases/assets/$id", token, "application/octet-stream") { r ->
                    tmp.outputStream().use { out -> r.body!!.byteStream().use { it.copyTo(out) } }
                }
            } catch (_: Exception) { return }
            if (size > 0 && tmp.length() < size / 2) { tmp.delete(); return }
            OfflinePack.close()
            if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) }
            val ok = OfflinePack.open(ctx)
            if (ok) sp.edit().putString("packUpdated", updated).apply()
            TripLog.ev("packAuto", "ok" to ok, "updated" to updated.take(10), "bytes" to dst.length())
        } catch (_: Throwable) {
        }
    }

    /** Nút "Cập nhật phần mềm" trong Cài đặt. */
    fun check(a: Activity, prefs: Prefs) {
        val token = prefs.githubToken
        Toast.makeText(a, "Đang kiểm tra bản mới…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val rel = latest(token)
                val cur = currentVersion(a)
                val curBuild = currentBuild(a)
                main.post {
                    if (a.isFinishing) return@post
                    if (rel.build <= curBuild) {
                        AlertDialog.Builder(a)
                            .setTitle("Đã là bản mới nhất")
                            .setMessage("MotoNav $cur – không có bản cập nhật.")
                            .setPositiveButton("OK", null)
                            .setNeutralButton("Cài lại bản ${rel.version}") { _, _ -> install(a, prefs, rel) }
                            .show()
                    } else {
                        AlertDialog.Builder(a)
                            .setTitle("Có bản mới: ${rel.version}")
                            .setMessage("Đang dùng: $cur\nBản mới: ${rel.version} (${rel.size / 1_000_000} MB)\n\nTải và cài ngay? Không lưu file nào trong máy.")
                            .setPositiveButton("Cập nhật") { _, _ -> install(a, prefs, rel) }
                            .setNegativeButton("Để sau", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (a.isFinishing) return@post
                    AlertDialog.Builder(a)
                        .setTitle("Không kiểm tra được")
                        .setMessage(e.message ?: "Lỗi mạng")
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Đổi mã GitHub") { _, _ -> askToken(a, prefs) }
                        .show()
                }
            }
        }.start()
    }

    /** Dùng cho hàng "Cập nhật phần mềm" trong Cài đặt: bấm là tự kiểm tra, có bản mới thì tải+cài
     * ngay (không hỏi lại "có muốn cập nhật không") — mọi trạng thái báo qua [onState] để vẽ thanh
     * tiến độ ngay dưới dòng đó thay vì mở bảng riêng. Bảng hỏi duy nhất còn lại là xin quyền
     * "Cài ứng dụng không rõ nguồn" lúc đầu (chỉ 1 lần, bắt buộc phải có, Android không cho bỏ qua). */
    fun checkInline(a: Activity, prefs: Prefs, onState: (UpdateState) -> Unit) {
        val token = prefs.githubToken
        onState(UpdateState.Checking)
        Thread {
            try {
                val rel = latest(token)
                val cur = currentVersion(a)
                val curBuild = currentBuild(a)
                main.post {
                    if (a.isFinishing) return@post
                    if (rel.build <= curBuild) {
                        onState(UpdateState.UpToDate(cur))
                    } else {
                        installInline(a, prefs, rel, onState)
                    }
                }
            } catch (e: Exception) {
                main.post { if (!a.isFinishing) onState(UpdateState.Error(e.message ?: "Lỗi mạng")) }
            }
        }.start()
    }

    private fun installInline(a: Activity, prefs: Prefs, rel: Release, onState: (UpdateState) -> Unit) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(a)
                .setTitle("Cho phép NMAX Navi tự cập nhật")
                .setMessage("Bấm \"Mở cài đặt\" → bật \"Cho phép từ nguồn này\". Chỉ cần làm 1 lần. Xong quay lại bấm Cập nhật phần mềm lần nữa.")
                .setPositiveButton("Mở cài đặt") { _, _ ->
                    a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
                }
                .setNegativeButton("Huỷ", null)
                .show()
            return
        }
        val ctx = a.applicationContext
        Thread {
            var session: PackageInstaller.Session? = null
            try {
                val pi = ctx.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(ctx.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = pi.createSession(params)
                session = pi.openSession(id)
                gh("https://api.github.com/repos/$REPO/releases/assets/${rel.assetId}", prefs.githubToken, "application/octet-stream") { r ->
                    val body = r.body!!
                    val total = body.contentLength().takeIf { it > 0 } ?: rel.size
                    session.openWrite("motonav.apk", 0, total).use { out ->
                        body.byteStream().use { inp ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                if (pct != lastPct) {
                                    lastPct = pct
                                    main.post { onState(UpdateState.Downloading(pct, done / 1_000_000, total / 1_000_000)) }
                                }
                            }
                        }
                        session.fsync(out)
                    }
                }
                main.post { onState(UpdateState.Installing) }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val intent = Intent(ctx, InstallReceiver::class.java)
                val pint = PendingIntent.getBroadcast(ctx, id, intent, flags)
                session.commit(pint.intentSender)
                session.close()
            } catch (e: Exception) {
                try { session?.abandon() } catch (_: Exception) {}
                main.post { onState(UpdateState.Error(e.message ?: "Lỗi cài đặt")) }
            }
        }.start()
    }

    fun askToken(a: Activity, prefs: Prefs) {
        val pad = (16 * a.resources.displayMetrics.density).toInt()
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val ed = EditText(a).apply { setText(prefs.githubToken); hint = "github_pat_…"; setSingleLine() }
        box.addView(ed)
        AlertDialog.Builder(a)
            .setTitle("Mã GitHub để tải bản cập nhật")
            .setMessage(
                "Kho MotoNav hiện CÔNG KHAI — có thể để trống mã và tải trực tiếp.\n\n" +
                "Nếu kho đổi riêng tư, tạo mã chỉ-đọc:\n" +
                    "GitHub → Settings → Developer settings → Fine-grained tokens → Generate\n" +
                    "• Repository access: Only select → motonav\n" +
                    "• Permissions → Contents: Read-only\n" +
                    "Dán mã vào đây (chỉ lưu trong máy).",
            )
            .setView(box)
            .setPositiveButton("Lưu & kiểm tra") { _, _ ->
                prefs.githubToken = ed.text.toString()
                if (prefs.githubToken.isNotBlank()) check(a, prefs)
            }
            .setNeutralButton("Mở trang tạo mã") { _, _ ->
                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/personal-access-tokens/new")))
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun install(a: Activity, prefs: Prefs, rel: Release) {
        // Android 8+: cần cho phép MotoNav cài ứng dụng
        if (!a.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(a)
                .setTitle("Cho phép MotoNav tự cập nhật")
                .setMessage("Bấm \"Mở cài đặt\" → bật \"Cho phép từ nguồn này\". Xong quay lại bấm Cập nhật phần mềm lần nữa.")
                .setPositiveButton("Mở cài đặt") { _, _ ->
                    a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
                }
                .setNegativeButton("Huỷ", null)
                .show()
            return
        }
        val progress = AlertDialog.Builder(a)
            .setTitle("Đang tải MotoNav ${rel.version}")
            .setMessage("0%")
            .setCancelable(false)
            .show()
        val ctx = a.applicationContext
        Thread {
            var session: PackageInstaller.Session? = null
            try {
                val pi = ctx.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(ctx.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = pi.createSession(params)
                session = pi.openSession(id)
                gh("https://api.github.com/repos/$REPO/releases/assets/${rel.assetId}", prefs.githubToken, "application/octet-stream") { r ->
                    val body = r.body!!
                    val total = body.contentLength().takeIf { it > 0 } ?: rel.size
                    // Ghi thẳng vào phiên cài đặt của Android – không tạo file trong bộ nhớ máy
                    session.openWrite("motonav.apk", 0, total).use { out ->
                        body.byteStream().use { inp ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                if (pct != lastPct) {
                                    lastPct = pct
                                    main.post { progress.setMessage("$pct%  (${done / 1_000_000}/${total / 1_000_000} MB)") }
                                }
                            }
                        }
                        session.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val intent = Intent(ctx, InstallReceiver::class.java)
                val pint = PendingIntent.getBroadcast(ctx, id, intent, flags)
                session.commit(pint.intentSender)
                session.close()
                main.post {
                    progress.dismiss()
                    Toast.makeText(ctx, "Đang cài MotoNav ${rel.version}…", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                try { session?.abandon() } catch (_: Exception) {}
                main.post {
                    progress.dismiss()
                    Toast.makeText(ctx, "Cập nhật lỗi: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}

/** Nhận kết quả cài đặt; nếu Android cần người dùng xác nhận thì mở màn hình xác nhận. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(confirm) } catch (_: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(ctx, "Đã cập nhật MotoNav — đang mở lại", Toast.LENGTH_LONG).show()
                // Tự mở lại app sau khi cài xong (giống NConnect: cài xong tự "reload"), khỏi phải
                // tìm icon mở tay — bản mới cài đè lên nên khởi động lại là vào đúng bản mới.
                try {
                    val open = Intent(ctx, NavActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    ctx.startActivity(open)
                } catch (_: Exception) {
                }
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "không rõ"
                Toast.makeText(ctx, "Cài đặt không thành công: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
