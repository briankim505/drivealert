package com.bkfamily.drivealert

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * 티맵·네이버지도·카카오내비가 길안내 중인지 확인한다.
 * 이 앱들은 길안내 중에 '진행 중' 알림을 띄우므로, 알림 접근 권한으로 그 알림이 있는지 본다.
 */
class OtherNav : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    companion object {
        @Volatile private var instance: OtherNav? = null

        val apps = linkedMapOf(
            "com.skt.tmap.ku" to "티맵",
            "com.nhn.android.nmap" to "네이버지도",
            "com.locnall.KimGiSa" to "카카오내비"
        )

        fun permitted(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(ComponentName(ctx, OtherNav::class.java).flattenToString())
        }

        /** 길안내 중인 다른 내비 앱 이름 (없으면 null) */
        fun activeName(ctx: Context): String? {
            if (!Prefs(ctx).muteWithOtherNav) return null
            val s = instance ?: return null
            return try {
                s.activeNotifications
                    ?.firstOrNull { it.packageName in apps && it.isOngoing }
                    ?.let { apps[it.packageName] }
            } catch (_: Exception) { null }
        }

        fun active(ctx: Context) = activeName(ctx) != null
    }
}
