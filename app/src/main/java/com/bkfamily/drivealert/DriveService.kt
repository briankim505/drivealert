package com.bkfamily.drivealert

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 주행 중 계속 살아 있는 서비스.
 * - 화면이 꺼져도 앱이 종료되지 않게 유지
 * - 운행기록(출발·도착·거리)과 주차 위치를 기록
 * - 주행 화면(카카오 안전운행 모드)을 띄움
 */
class DriveService : Service(), LocationListener {

    private var wakeLock: PowerManager.WakeLock? = null
    private var startTime = 0L
    private var startLoc: Location? = null
    private var lastLoc: Location? = null
    private var distanceM = 0.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startTime = System.currentTimeMillis()

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, DriveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, App.CHANNEL_DRIVE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("단속카메라 알림 작동 중")
            .setContentText("차량 연결이 끊기면 자동으로 종료됩니다")
            .setOngoing(true)
            .setContentIntent(open)
            .build()

        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
        } catch (e: Exception) {
            CrashLog.save(this, e)
            running = false
            stopSelf()
            return
        }

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "drivealert:drive").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L) // 최대 6시간
        }

        startLocation()
        openDriveScreen()
    }

    private fun openDriveScreen() {
        try {
            startActivity(
                Intent(this, DriveActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: Exception) {
            // 백그라운드 실행 제한으로 못 띄우면 알림을 눌러 열 수 있음
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val lm = getSystemService(LocationManager::class.java)
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 10f, this, Looper.getMainLooper())
        } catch (_: Exception) {
        }
        try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { if (startLoc == null) startLoc = it }
        } catch (_: Exception) {
        }
    }

    override fun onLocationChanged(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > 50f) return
        if (startLoc == null) startLoc = loc
        lastLoc?.let { prev ->
            val d = prev.distanceTo(loc)
            if (d < 500f) distanceM += d // 순간 튀는 값 제외
        }
        lastLoc = loc
    }

    override fun onDestroy() {
        running = false
        try {
            getSystemService(LocationManager::class.java).removeUpdates(this)
        } catch (_: Exception) {
        }
        val prefs = Prefs(this)
        val end = lastLoc
        if (prefs.parking && end != null) {
            prefs.parkLat = end.latitude
            prefs.parkLon = end.longitude
            prefs.parkTime = System.currentTimeMillis()
        }
        if (prefs.tripLog && distanceM > 200) {
            TripLog.add(this, startTime, System.currentTimeMillis(), startLoc, end, distanceM / 1000.0)
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 7
        @Volatile var running = false
    }
}
