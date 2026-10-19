package com.bkfamily.drivealert

import android.content.Context

/** 음성으로 말한 목적지를 장소로 바꾼다 (백그라운드 스레드) */
object VoiceDest {
    fun resolve(ctx: Context, said: String): Place? {
        val favs = Prefs(ctx).favorites
        // 1) 자주 가는 곳 이름이 그대로 들어 있으면 바로
        favs.firstOrNull { said.replace(" ", "").contains(it.name.replace(" ", "")) }?.let { return it }
        var query = said.replace(Regex("(으로|로)?\\s*(가자|가줘|안내해줘|안내|찍어줘|보내줘|목적지)"), "").trim()
        if (Gemini.enabled(ctx)) {
            Gemini.voiceCommand(ctx, said, favs.map { it.name })?.let { j ->
                val f = j.optString("favorite")
                favs.firstOrNull { f.isNotBlank() && it.name == f }?.let { return it }
                j.optString("query").takeIf { it.isNotBlank() }?.let { query = it }
            }
        }
        return Places.search(ctx, query).firstOrNull()
    }
}
