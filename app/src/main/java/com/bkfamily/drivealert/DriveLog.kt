package com.bkfamily.drivealert

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** 주행 중 일어난 일을 시간순으로 남긴다 (설정 화면 '최근 기록 보기'에서 확인) */
object DriveLog {
    private fun file(c: Context) = File(c.filesDir, "drive_log.txt")
    private val fmt = SimpleDateFormat("MM/dd HH:mm:ss", Locale.KOREA)

    @Synchronized
    fun add(c: Context, msg: String) {
        try {
            val f = file(c)
            val lines = if (f.exists()) f.readLines().takeLast(400) else emptyList()
            f.writeText((lines + "${fmt.format(System.currentTimeMillis())}  $msg").joinToString("\n"))
        } catch (_: Exception) {
        }
    }

    fun read(c: Context): String = try {
        file(c).takeIf { it.exists() }?.readLines()?.takeLast(150)?.reversed()?.joinToString("\n") ?: ""
    } catch (_: Exception) { "" }

    fun clear(c: Context) { file(c).delete() }
}
