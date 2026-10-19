package com.bkfamily.drivealert

import android.content.Context

/** 앱 설정 (폰 안의 앱 전용 공간에만 저장되며 코드·GitHub에는 들어가지 않음) */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var kakaoKey: String
        get() = sp.getString("kakaoKey", "") ?: ""
        set(v) = sp.edit().putString("kakaoKey", v.trim()).apply()

    var carAddress: String
        get() = sp.getString("carAddress", "") ?: ""
        set(v) = sp.edit().putString("carAddress", v.uppercase()).apply()

    var carName: String
        get() = sp.getString("carName", "") ?: ""
        set(v) = sp.edit().putString("carName", v).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keepScreenOn", false)
        set(v) = sp.edit().putBoolean("keepScreenOn", v).apply()

    var tripLog: Boolean
        get() = sp.getBoolean("tripLog", true)
        set(v) = sp.edit().putBoolean("tripLog", v).apply()

    var parking: Boolean
        get() = sp.getBoolean("parking", true)
        set(v) = sp.edit().putBoolean("parking", v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("autoStart", true)
        set(v) = sp.edit().putBoolean("autoStart", v).apply()

    var parkLat: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong("parkLat", 0))
        set(v) = sp.edit().putLong("parkLat", java.lang.Double.doubleToRawLongBits(v)).apply()

    var parkLon: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong("parkLon", 0))
        set(v) = sp.edit().putLong("parkLon", java.lang.Double.doubleToRawLongBits(v)).apply()

    var parkTime: Long
        get() = sp.getLong("parkTime", 0)
        set(v) = sp.edit().putLong("parkTime", v).apply()

    // ---- 경고 설정 ----
    var alertMode: Int
        get() = sp.getInt("alertMode", MODE_KAKAO)
        set(v) = sp.edit().putInt("alertMode", v).apply()

    var overMargin: Int
        get() = sp.getInt("overMargin", MARGIN_0)
        set(v) = sp.edit().putInt("overMargin", v).apply()

    var announceDistance: Int
        get() = sp.getInt("announceDistance", 500)
        set(v) = sp.edit().putInt("announceDistance", v).apply()

    /** 과속 경고 반복 간격(초). 0 = 한 번만 */
    var repeatSec: Int
        get() = sp.getInt("repeatSec", 5)
        set(v) = sp.edit().putInt("repeatSec", v).apply()

    var speakSpeed: Boolean
        get() = sp.getBoolean("speakSpeed", true)
        set(v) = sp.edit().putBoolean("speakSpeed", v).apply()

    var ttsRate: Float
        get() = sp.getFloat("ttsRate", 1.0f)
        set(v) = sp.edit().putFloat("ttsRate", v).apply()

    var ttsPitch: Float
        get() = sp.getFloat("ttsPitch", 1.0f)
        set(v) = sp.edit().putFloat("ttsPitch", v).apply()

    // ---- 테슬라 목적지 보내기 ----
    var kakaoRestKey: String
        get() = sp.getString("kakaoRestKey", "") ?: ""
        set(v) = sp.edit().putString("kakaoRestKey", v.trim()).apply()

    /** 일정 시작 1시간 전쯤 알림 */
    var notifyBefore: Boolean
        get() = sp.getBoolean("notifyBefore", false)
        set(v) = sp.edit().putBoolean("notifyBefore", v).apply()

    /** 차에 연결되면 다음 일정 장소 제안 */
    var suggestOnConnect: Boolean
        get() = sp.getBoolean("suggestOnConnect", true)
        set(v) = sp.edit().putBoolean("suggestOnConnect", v).apply()

    var favorites: List<Place>
        get() = try {
            val a = org.json.JSONArray(sp.getString("favorites", "[]"))
            (0 until a.length()).map { val o = a.getJSONObject(it); Place(o.getString("n"), o.getString("a")) }
        } catch (_: Exception) { emptyList() }
        set(v) {
            val a = org.json.JSONArray()
            v.forEach { a.put(org.json.JSONObject().put("n", it.name).put("a", it.address)) }
            sp.edit().putString("favorites", a.toString()).apply()
        }

    /** 일정을 볼 캘린더 ID 목록 (비어 있으면 전체) */
    var calendarIds: Set<String>
        get() = sp.getStringSet("calendarIds", emptySet())!!
        set(v) = sp.edit().putStringSet("calendarIds", v).apply()

    fun wasNotified(id: Long, begin: Long) = sp.getStringSet("notified", emptySet())!!.contains("$id@$begin")
    fun markNotified(id: Long, begin: Long) {
        val now = System.currentTimeMillis()
        val keep = sp.getStringSet("notified", emptySet())!!
            .filter { (it.substringAfter('@').toLongOrNull() ?: 0) > now - 86_400_000L }.toMutableSet()
        keep += "$id@$begin"
        sp.edit().putStringSet("notified", keep).apply()
    }

    /** 앱 사용자를 구분하는 임의 ID (카카오 SDK userKey) */
    val userKey: String
        get() {
            var k = sp.getString("userKey", null)
            if (k == null) {
                k = java.util.UUID.randomUUID().toString()
                sp.edit().putString("userKey", k).apply()
            }
            return k
        }

    companion object {
        const val MODE_KAKAO = 0
        const val MODE_TTS = 1
        const val MODE_BEEP = 2
        const val MODE_SILENT = 3
        const val MARGIN_0 = 0
        const val MARGIN_5 = 1
        const val MARGIN_10 = 2
        const val MARGIN_10P = 3
    }
}
