package com.bkfamily.drivealert

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** 차량 연결/해제 시 주행 모드를 켜고 끄는 단일 진입점 */
object DriveControl {
    const val ACTION_STOP = "com.bkfamily.drivealert.STOP_DRIVE"

    fun onCarConnected(context: Context, address: String?) {
        val prefs = Prefs(context)
        if (!prefs.autoStart) return
        if (prefs.carAddress.isEmpty()) return
        if (address != null && !address.equals(prefs.carAddress, ignoreCase = true)) return
        start(context)
    }

    fun onCarDisconnected(context: Context, address: String?) {
        val prefs = Prefs(context)
        if (prefs.carAddress.isEmpty()) return
        if (address != null && !address.equals(prefs.carAddress, ignoreCase = true)) return
        stop(context)
    }

    fun start(context: Context): Boolean {
        if (DriveService.running) return true
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        return try {
            ContextCompat.startForegroundService(context, Intent(context, DriveService::class.java)); true
        } catch (e: Exception) {
            CrashLog.save(context, e); false
        }
    }

    fun stop(context: Context) {
        context.sendBroadcast(Intent(ACTION_STOP).setPackage(context.packageName))
        context.stopService(Intent(context, DriveService::class.java))
    }
}
