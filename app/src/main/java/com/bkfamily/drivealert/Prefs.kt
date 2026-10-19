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
}
