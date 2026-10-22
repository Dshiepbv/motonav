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

    class Release(val version: String, val build: Int, val assetId: Long, val size: Long, val notes: String)

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** So sánh "5.10" > "5.9". */
    private fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    private fun req(url: String, token: String, accept: String) = Request.Builder().url(url)
        .header("Accept", accept)
        .header("User-Agent", "MotoNav")
        .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
        .build()

    private fun latest(token: String): Release {
        http.newCall(req("https://api.github.com/repos/$REPO/releases/latest", token, "application/vnd.github+json")).execute().use { r ->
            if (r.code == 401) throw RuntimeException("Mã GitHub sai hoặc đã hết hạn")
            if (r.code == 404) throw RuntimeException("Không thấy bản phát hành (kho riêng tư cần mã GitHub có quyền đọc kho motonav)")
            if (!r.isSuccessful) throw RuntimeException("GitHub trả lỗi ${r.code}")
            val o = JSONObject(r.body!!.string())
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
    }

    /** Tải gói dữ liệu OSM ngoại tuyến (release "osm-data") vào bộ nhớ riêng của app. */
    fun downloadPack(a: Activity, prefs: Prefs, done: () -> Unit) {
        val token = prefs.githubToken
        if (token.isBlank()) { askToken(a, prefs); return }
        val progress = AlertDialog.Builder(a).setTitle("Đang tải dữ liệu ngoại tuyến").setMessage("Đang kiểm tra…").setCancelable(false).show()
        val ctx = a.applicationContext
        Thread {
            try {
                val (id, size, updated) = http.newCall(req("https://api.github.com/repos/$REPO/releases/tags/osm-data", token, "application/vnd.github+json")).execute().use { r ->
                    if (r.code == 404) throw RuntimeException("Gói dữ liệu chưa được tạo trên GitHub (đợi bản chạy hằng tuần)")
                    if (!r.isSuccessful) throw RuntimeException("GitHub trả lỗi ${r.code}")
                    val assets = JSONObject(r.body!!.string()).getJSONArray("assets")
                    var res: Triple<Long, Long, String>? = null
                    for (i in 0 until assets.length()) {
                        val x = assets.getJSONObject(i)
                        if (x.getString("name") == "osm-hcm.zip") res = Triple(x.getLong("id"), x.optLong("size"), x.optString("updated_at"))
                    }
                    res ?: throw RuntimeException("Chưa có file osm-hcm.zip")
                }
                val dst = OfflinePack.file(ctx)
                val tmp = java.io.File(dst.parentFile, "pack.tmp")
                http.newCall(req("https://api.github.com/repos/$REPO/releases/assets/$id", token, "application/octet-stream")).execute().use { r ->
                    if (!r.isSuccessful) throw RuntimeException("Tải lỗi ${r.code}")
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

    /** Nút "Cập nhật phần mềm" trong Cài đặt. */
    fun check(a: Activity, prefs: Prefs) {
        val token = prefs.githubToken
        if (token.isBlank()) {
            askToken(a, prefs)
            return
        }
        Toast.makeText(a, "Đang kiểm tra bản mới…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val rel = latest(token)
                val cur = currentVersion(a)
                main.post {
                    if (a.isFinishing) return@post
                    if (!newer(rel.version, cur)) {
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

    fun askToken(a: Activity, prefs: Prefs) {
        val pad = (16 * a.resources.displayMetrics.density).toInt()
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val ed = EditText(a).apply { setText(prefs.githubToken); hint = "github_pat_…"; setSingleLine() }
        box.addView(ed)
        AlertDialog.Builder(a)
            .setTitle("Mã GitHub để tải bản cập nhật")
            .setMessage(
                "Kho MotoNav đang để riêng tư nên cần một mã chỉ-đọc:\n" +
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
                val url = "https://api.github.com/repos/$REPO/releases/assets/${rel.assetId}"
                http.newCall(req(url, prefs.githubToken, "application/octet-stream")).execute().use { r ->
                    if (!r.isSuccessful) throw RuntimeException("Tải lỗi ${r.code}")
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
                Toast.makeText(ctx, "Đã cập nhật MotoNav", Toast.LENGTH_LONG).show()
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "không rõ"
                Toast.makeText(ctx, "Cài đặt không thành công: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
