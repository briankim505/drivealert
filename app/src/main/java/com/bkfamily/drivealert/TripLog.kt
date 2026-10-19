package com.bkfamily.drivealert

import android.content.Context
import android.location.Geocoder
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 운행기록: 주행 1회 = 1줄. 월별 CSV(엑셀에서 열림)로 내보내기 */
object TripLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "trips.json")

    @Synchronized
    fun all(ctx: Context): JSONArray =
        try { JSONArray(file(ctx).readText()) } catch (_: Exception) { JSONArray() }

    @Synchronized
    fun add(ctx: Context, start: Long, end: Long, from: Location?, to: Location?, km: Double) {
        val arr = all(ctx)
        arr.put(JSONObject().apply {
            put("start", start)
            put("end", end)
            put("km", Math.round(km * 10) / 10.0)
            from?.let { put("fromLat", it.latitude); put("fromLon", it.longitude) }
            to?.let { put("toLat", it.latitude); put("toLon", it.longitude) }
        })
        file(ctx).writeText(arr.toString())
    }

    private fun address(ctx: Context, lat: Double?, lon: Double?): String {
        if (lat == null || lon == null) return ""
        return try {
            @Suppress("DEPRECATION")
            val list = Geocoder(ctx, Locale.KOREA).getFromLocation(lat, lon, 1)
            val a = list?.firstOrNull() ?: return "%.5f, %.5f".format(lat, lon)
            listOfNotNull(a.adminArea, a.locality ?: a.subAdminArea, a.subLocality, a.thoroughfare)
                .distinct().joinToString(" ")
        } catch (_: Exception) {
            "%.5f, %.5f".format(lat, lon)
        }
    }

    /** 도착 시각 전후 일정으로 사용목적을 정한다 (제미나이 있으면 운행기록부 문구로 다듬음) */
    private fun purpose(ctx: Context, start: Long, end: Long): String {
        val ev = Schedule.between(ctx, end - 60 * 60_000L, end + 90 * 60_000L)
            .filter { !Schedule.isOnline(it) }
            .minByOrNull { kotlin.math.abs(it.begin - end) }
            ?: Schedule.between(ctx, start - 30 * 60_000L, end).lastOrNull { !Schedule.isOnline(it) }
            ?: return ""
        return (if (Gemini.enabled(ctx)) Gemini.tripPurpose(ctx, ev.title) else null) ?: ev.title
    }

    /** monthOffset 0 = 이번 달, -1 = 지난 달. 백그라운드 스레드에서 호출할 것 */
    fun exportCsv(ctx: Context, monthOffset: Int): File {
        val cal = Calendar.getInstance().apply { add(Calendar.MONTH, monthOffset) }
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH)
        val dFmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val tFmt = SimpleDateFormat("HH:mm", Locale.KOREA)
        val sb = StringBuilder("﻿") // 엑셀 한글 깨짐 방지
        sb.append("날짜,출발시각,도착시각,출발지,도착지,주행거리(km),사용목적\n")
        var total = 0.0
        val arr = all(ctx)
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val c = Calendar.getInstance().apply { timeInMillis = o.getLong("start") }
            if (c.get(Calendar.YEAR) != y || c.get(Calendar.MONTH) != m) continue
            val from = address(ctx, o.optDoubleOrNull("fromLat"), o.optDoubleOrNull("fromLon"))
            val to = address(ctx, o.optDoubleOrNull("toLat"), o.optDoubleOrNull("toLon"))
            val km = o.getDouble("km")
            if (!o.has("purpose")) {
                val pp = purpose(ctx, o.getLong("start"), o.getLong("end"))
                if (pp.isNotEmpty()) { o.put("purpose", pp); changed = true }
            }
            val why = o.optString("purpose").replace("\"", "'")
            total += km
            sb.append(dFmt.format(o.getLong("start"))).append(',')
                .append(tFmt.format(o.getLong("start"))).append(',')
                .append(tFmt.format(o.getLong("end"))).append(',')
                .append('"').append(from).append("\",\"").append(to).append("\",")
                .append(km).append(",\"").append(why).append("\"\n")
        }
        if (changed) synchronized(this) { file(ctx).writeText(arr.toString()) }
        sb.append(",,,,합계,").append(Math.round(total * 10) / 10.0).append(",\n")
        val out = File(ctx.cacheDir, "운행기록_%d-%02d.csv".format(y, m + 1))
        out.writeText(sb.toString())
        return out
    }

    private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k)) getDouble(k) else null
}
