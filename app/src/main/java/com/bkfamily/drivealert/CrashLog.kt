package com.bkfamily.drivealert

import android.content.Context
import java.io.File

/** 오류가 나면 내용을 저장해 두었다가 다음에 설정 화면을 열 때 보여준다 (캡처해서 전달용) */
object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "last_error.txt")

    fun save(c: Context, e: Throwable) {
        try {
            val text = "버전 ${BuildConfig.VERSION_NAME}\n" + e.stackTraceToString().lines().take(25).joinToString("\n")
            file(c).writeText(text)
        } catch (_: Exception) {
        }
    }

    fun take(c: Context): String? {
        val f = file(c)
        if (!f.exists()) return null
        val t = f.readText()
        f.delete()
        return t
    }
}
