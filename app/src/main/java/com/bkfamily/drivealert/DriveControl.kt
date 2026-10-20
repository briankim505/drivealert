package com.bkfamily.drivealert

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** 차량 연결/해제 시 주행 모드를 켜고 끄는 단일 진입점 */
object DriveControl {
    const val ACTION_STOP = "com.bkfamily.drivealert.STOP_DRIVE"

    fun onCarConnected(context: Context, address: String?) {
        val prefs = Prefs(context)
        val mine = address == null || address.equals(prefs.carAddress, ignoreCase = true)
        if (mine || prefs.carAddress.isEmpty()) DriveLog.add(context, "블루투스 연결 감지 ($address)")
        if (!prefs.autoStart) { DriveLog.add(context, "→ 자동 시작 꺼져 있음"); return }
        if (prefs.carAddress.isEmpty()) { DriveLog.add(context, "→ 등록된 차량이 없어 시작 안 함"); return }
        if (!mine) return
        start(context)
    }

    fun onCarDisconnected(context: Context, address: String?) {
        val prefs = Prefs(context)
        if (prefs.carAddress.isEmpty()) return
        if (address != null && !address.equals(prefs.carAddress, ignoreCase = true)) return
        DriveLog.add(context, "차량 연결 해제 → 주행 종료")
        stop(context)
    }

    fun start(context: Context): Boolean {
        if (DriveService.running) return true
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) { DriveLog.add(context, "→ 위치 권한이 없어 시작 못 함"); return false }
        return try {
            ContextCompat.startForegroundService(context, Intent(context, DriveService::class.java)); true
        } catch (e: Exception) {
            DriveLog.add(context, "→ 주행 서비스 시작 실패: ${e.javaClass.simpleName}")
            CrashLog.save(context, e); false
        }
    }

    fun stop(context: Context) {
        context.sendBroadcast(Intent(ACTION_STOP).setPackage(context.packageName))
        context.stopService(Intent(context, DriveService::class.java))
    }
}
