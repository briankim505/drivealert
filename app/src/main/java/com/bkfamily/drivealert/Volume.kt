package com.bkfamily.drivealert

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** 주행 중 안내 음성이 잘 들리도록 미디어 음량을 설정값까지 자동으로 올린다 */
object Volume {
    private fun am(c: Context) = c.getSystemService(AudioManager::class.java)

    /** 주행 시작 시 호출. 블루투스 오디오 연결이 늦게 잡히는 경우를 대비해 몇 번 다시 맞춘다 */
    fun onDriveStart(c: Context) {
        val p = Prefs(c)
        if (!p.volumeAuto) return
        val am = am(c)
        p.savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val h = Handler(Looper.getMainLooper())
        listOf(0L, 4000L, 12000L).forEach { d -> h.postDelayed({ apply(c) }, d) }
    }

    fun apply(c: Context) {
        val p = Prefs(c)
        val am = am(c)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * p.volumeLevel / 100f).toInt().coerceIn(1, max)
        try {
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) < target)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (_: Exception) {
        }
    }

    fun onDriveEnd(c: Context) {
        val p = Prefs(c)
        if (!p.volumeAuto || !p.volumeRestore || p.savedVolume < 0) return
        try { am(c).setStreamVolume(AudioManager.STREAM_MUSIC, p.savedVolume, 0) } catch (_: Exception) {}
        p.savedVolume = -1
    }
}
