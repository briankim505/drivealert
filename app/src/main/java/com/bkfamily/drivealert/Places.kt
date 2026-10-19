package com.bkfamily.drivealert

import android.content.Context
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Place(val name: String, val address: String, val category: String = "", val lat: Double = 0.0, val lon: Double = 0.0) {
    /** 테슬라로 보낼 문자열 (이름 + 주소) */
    val shareText get() = if (name.isNotBlank() && name != address) "$name\n$address" else address
}

/** 카카오 장소 검색 (REST API 키 필요). 백그라운드 스레드에서 호출할 것 */
object Places {

    fun search(ctx: Context, query: String, size: Int = 8): List<Place> {
        val key = Prefs(ctx).kakaoRestKey
        if (key.isBlank() || query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        var url = "https://dapi.kakao.com/v2/local/search/keyword.json?query=$q&size=$size"
        lastLocation(ctx)?.let { (lat, lon) -> url += "&x=$lon&y=$lat&sort=accuracy" }
        val list = request(url, key)?.let { parseKeyword(it) }.orEmpty()
        if (list.isNotEmpty()) return list
        // 장소명으로 안 나오면 주소로 검색
        return request("https://dapi.kakao.com/v2/local/search/address.json?query=$q&size=$size", key)
            ?.let { parseAddress(it) }.orEmpty()
    }

    private fun request(url: String, key: String): JSONObject? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "KakaoAK $key")
        c.connectTimeout = 8000; c.readTimeout = 8000
        if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText())
        else {
            val body = try { c.errorStream?.bufferedReader()?.readText().orEmpty() } catch (_: Exception) { "" }
            val msg = try { JSONObject(body).optString("message") } catch (_: Exception) { body.take(120) }
            lastError = if (c.responseCode == 403 && msg.contains("OPEN_MAP_AND_LOCAL", true))
                "카카오 403: 카카오디벨로퍼스에서 '카카오맵' 사용 설정을 켜야 합니다"
            else "카카오 검색 오류 ${c.responseCode}: $msg"
            null
        }
    } catch (e: Exception) {
        lastError = "카카오 검색 실패: ${e.message}"; null
    }

    @Volatile var lastError: String? = null

    private fun parseKeyword(j: JSONObject): List<Place> {
        val arr = j.optJSONArray("documents") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            Place(
                d.optString("place_name"),
                d.optString("road_address_name").ifBlank { d.optString("address_name") },
                d.optString("category_name").substringAfterLast("> "),
                d.optString("y").toDoubleOrNull() ?: 0.0, d.optString("x").toDoubleOrNull() ?: 0.0
            )
        }
    }

    private fun parseAddress(j: JSONObject): List<Place> {
        val arr = j.optJSONArray("documents") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            val road = d.optJSONObject("road_address")?.optString("address_name").orEmpty()
            val addr = road.ifBlank { d.optString("address_name") }
            Place(addr, addr, "", d.optString("y").toDoubleOrNull() ?: 0.0, d.optString("x").toDoubleOrNull() ?: 0.0)
        }
    }

    @Suppress("MissingPermission")
    fun lastLocation(ctx: Context): Pair<Double, Double>? = try {
        val lm = ctx.getSystemService(LocationManager::class.java)
        (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.let { it.latitude to it.longitude }
    } catch (_: Exception) { null }
}
