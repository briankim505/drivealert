package com.bkfamily.drivealert

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.kakaomobility.knsdk.KNSDK

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // 카카오내비 SDK 등록 (지도·음성 데이터 저장 경로)
        KNSDK.install(this, "$filesDir/knsdk")

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DRIVE, "주행 알림", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_START, "주행 시작", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        const val CHANNEL_DRIVE = "drive"
        const val CHANNEL_START = "start"
        lateinit var instance: App
            private set
    }
}
