package com.bkfamily.drivealert

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.kakaomobility.knsdk.KNSDK

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            CrashLog.save(this, e)
            prev?.uncaughtException(t, e)
        }
        // 카카오내비 SDK 등록 (지도·음성 데이터 저장 경로)
        // 카카오 SDK는 앱 시작 때 켜지 않는다 (켜 두면 GPS를 계속 씀) → 주행 화면에서만 켜고 끝나면 해제

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DRIVE, "주행 알림", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_START, "주행 시작", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        @Volatile private var knInstalled = false

        /** 주행 화면 진입 시에만 카카오 SDK를 켠다 */
        fun knStart(app: android.app.Application) {
            if (knInstalled) return
            try { KNSDK.install(app, "${app.filesDir}/knsdk"); knInstalled = true } catch (e: Throwable) { CrashLog.save(app, e) }
        }

        /** 주행이 끝나면 카카오 SDK를 완전히 해제 (GPS·네트워크 중단) */
        fun knStop(ctx: android.content.Context) {
            if (!knInstalled) return
            try { KNSDK.sharedGuidance()?.stop() } catch (_: Throwable) {}
            try { KNSDK.KNSDKRelease() } catch (_: Throwable) {}
            knInstalled = false
            DriveLog.add(ctx, "카카오 안내 해제 (GPS 끔)")
        }

        const val CHANNEL_DRIVE = "drive"
        const val CHANNEL_START = "start"
        lateinit var instance: App
            private set
    }
}
