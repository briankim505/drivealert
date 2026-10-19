package com.bkfamily.drivealert

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** GitHub 최신 버전 확인 → 새 버전이면 내려받아 설치 화면 띄우기 */
object Updater {

    fun check(act: Activity, silent: Boolean) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) {
            if (!silent) toast(act, "업데이트 주소가 설정되지 않았습니다")
            return
        }
        Thread {
            try {
                val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 10000; c.readTimeout = 10000
                if (c.responseCode != 200) {
                    if (!silent) toast(act, "업데이트 정보를 가져오지 못했습니다 (${c.responseCode})")
                    return@Thread
                }
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val latest = j.getString("tag_name").removePrefix("v").substringAfterLast('.').toIntOrNull() ?: 0
                val assets = j.getJSONArray("assets")
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) apkUrl = a.getString("browser_download_url")
                }
                if (latest <= BuildConfig.VERSION_CODE || apkUrl == null) {
                    if (!silent) toast(act, "최신 버전입니다 (${BuildConfig.VERSION_NAME})")
                    return@Thread
                }
                act.runOnUiThread {
                    AlertDialog.Builder(act)
                        .setTitle("새 버전이 있습니다")
                        .setMessage("1.0.$latest 버전으로 업데이트할까요?")
                        .setPositiveButton("업데이트") { _, _ -> download(act, apkUrl) }
                        .setNegativeButton("나중에", null)
                        .show()
                }
            } catch (e: Exception) {
                if (!silent) toast(act, "업데이트 확인 실패: ${e.message}")
            }
        }.start()
    }

    private fun download(act: Activity, url: String) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            toast(act, "'이 출처 허용'을 켠 뒤 다시 눌러주세요")
            act.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))
            )
            return
        }
        toast(act, "내려받는 중…")
        Thread {
            try {
                val out = File(act.cacheDir, "update.apk")
                var link = url
                var conn: HttpURLConnection
                var hops = 0
                while (true) {
                    conn = URL(link).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    val code = conn.responseCode
                    if (code in 300..399 && hops < 5) { link = conn.getHeaderField("Location"); hops++; continue }
                    break
                }
                conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", out)
                act.runOnUiThread {
                    act.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (e: Exception) {
                toast(act, "내려받기 실패: ${e.message}")
            }
        }.start()
    }

    private fun toast(act: Activity, msg: String) = act.runOnUiThread {
        android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
