package com.bkfamily.drivealert

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

data class CalEvent(val id: Long, val title: String, val location: String, val begin: Long, val calendar: String)

/** 휴대폰에 동기화된 구글 캘린더(개인·회사 모두)에서 다가오는 일정과 장소를 찾는다 */
object Schedule {

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** 지금부터 hours 시간 안의 일정 (종일 일정 제외, 시간순) */
    fun upcoming(ctx: Context, hours: Int): List<CalEvent> {
        if (!hasPermission(ctx)) return emptyList()
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 10 * 60_000)
            ContentUris.appendId(it, now + hours * 3_600_000L)
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS
        )
        val out = mutableListOf<CalEvent>()
        try {
            ctx.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(4) == 1) continue // 종일 일정
                    if (c.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    if (c.getLong(3) < now - 10 * 60_000) continue
                    out += CalEvent(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3), c.getString(5) ?: "")
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private val online = Regex("(?i)zoom|meet\\.google|teams|webex|https?://|온라인|화상|전화|콜")
    private val noise = Regex("(?i)미팅|회의|면담|점심|저녁|식사|커피|티타임|ir|미팅\\s*w/?|with|w/|약속|방문|\\(.*?\\)|\\[.*?]")

    /** 온라인 회의로 보이면 장소 추정을 하지 않는다 */
    fun isOnline(e: CalEvent) = online.containsMatchIn(e.location) || (e.location.isBlank() && online.containsMatchIn(e.title))

    /**
     * 일정의 장소를 추정한다.
     * 1) 장소 칸이 있으면 그것으로 검색  2) 없으면 제목에서 회사·장소 이름을 뽑아 검색
     * @return 후보 목록 (첫 번째가 가장 유력)
     */
    fun guessPlaces(ctx: Context, e: CalEvent): List<Place> {
        if (isOnline(e)) return emptyList()
        if (e.location.isNotBlank()) {
            val loc = e.location.lines().first().trim()
            val r = Places.search(ctx, loc)
            if (r.isNotEmpty()) return r
            return listOf(Place(loc, loc)) // 검색이 안 되면 적힌 그대로
        }
        val q = noise.replace(e.title, " ").replace(Regex("\\s+"), " ").trim()
        if (q.length < 2) return emptyList()
        return Places.search(ctx, q)
    }

    fun timeText(ms: Long): String = SimpleDateFormat("a h:mm", Locale.KOREA).format(ms)

    /** 다음 일정 장소를 찾아 '테슬라로 보내기' 알림을 띄운다 (백그라운드 스레드) */
    fun notifyNext(ctx: Context, withinHours: Int, onlyOnce: Boolean): Boolean {
        val prefs = Prefs(ctx)
        val ev = upcoming(ctx, withinHours).firstOrNull { !isOnline(it) } ?: return false
        if (onlyOnce && prefs.wasNotified(ev.id, ev.begin)) return false
        val place = guessPlaces(ctx, ev).firstOrNull() ?: return false
        prefs.markNotified(ev.id, ev.begin)

        val req = (ev.id % 100000).toInt()
        val other = PendingIntent.getActivity(
            ctx, req + 1, Intent(ctx, DestinationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val guessed = if (ev.location.isBlank()) " (제목으로 추정)" else ""
        val n = NotificationCompat.Builder(ctx, App.CHANNEL_START)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("${timeText(ev.begin)} ${ev.title}")
            .setContentText("눌러서 테슬라로 보내기 → ${place.name}$guessed")
            .setStyle(NotificationCompat.BigTextStyle().bigText("눌러서 테슬라로 보내기\n${place.name}$guessed\n${place.address}"))
            .setContentIntent(Tesla.pending(ctx, place, req))
            .addAction(0, "다른 장소 고르기", other)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(1000 + req, n)
        return true
    }
}
