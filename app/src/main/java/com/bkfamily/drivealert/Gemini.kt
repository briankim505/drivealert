package com.bkfamily.drivealert

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 제미나이 API (무료 키). 키가 없거나 실패하면 null → 호출하는 쪽에서 기본 방식으로 처리 */
object Gemini {
    @Volatile var lastError: String? = null

    fun enabled(ctx: Context) = Prefs(ctx).geminiKey.isNotBlank()

    /** JSON으로 답하도록 요청. 백그라운드 스레드에서 호출 */
    fun askJson(ctx: Context, prompt: String): JSONObject? {
        val p = Prefs(ctx)
        if (p.geminiKey.isBlank()) return null
        val models = listOf(p.geminiModel, "gemini-flash-latest", "gemini-2.5-flash").filter { it.isNotBlank() }.distinct()
        for (m in models) {
            val r = call(p.geminiKey, m, prompt) ?: continue
            return try {
                JSONObject(r.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
            } catch (e: Exception) { lastError = "제미나이 응답 해석 실패"; null }
        }
        return null
    }

    private fun call(key: String, model: String, prompt: String): String? = try {
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 10000; c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2))
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode != 200) {
            lastError = "제미나이 오류 ${c.responseCode} ($model)"
            null
        } else {
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            j.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0).getString("text")
        }
    } catch (e: Exception) {
        lastError = "제미나이 연결 실패: ${e.message}"; null
    }

    // ------------------------------------------------------------------ 용도별

    /** 일정 정보로 '어디로 가야 하는지' 카카오 검색어를 만든다 */
    fun placeQuery(ctx: Context, e: CalEvent): String? {
        val useNotes = Prefs(ctx).geminiUseNotes
        val prompt = """
            너는 한국에서 운전하는 사람의 비서다. 아래 캘린더 일정에 가려면 내비에 어떤 장소를 찍어야 할지 판단해라.
            카카오맵에서 검색할 짧은 검색어 하나를 만든다 (예: "OO회사 본사", "스타벅스 강남R점", "서울 성동구 성수이로 113").
            회사 이름이 보이면 그 회사의 사무실을 검색어로 쓴다. 이메일 도메인은 회사를 추정하는 힌트다.
            온라인 회의이거나 장소를 알 수 없으면 query를 빈 문자열로.
            JSON만 출력: {"query": "...", "reason": "한 줄"}

            제목: ${e.title}
            장소 칸: ${e.location}
            참석자 회사 도메인: ${e.domains.joinToString(", ")}
            ${if (useNotes) "메모: " + e.description.take(400) else ""}
        """.trimIndent()
        return askJson(ctx, prompt)?.optString("query")?.trim()?.takeIf { it.isNotBlank() }
    }

    /** 운행기록부 사용목적 한 줄 */
    fun tripPurpose(ctx: Context, eventTitle: String): String? {
        val prompt = """
            법인차 운행기록부의 '사용목적' 칸에 쓸 문구를 만들어라. 15자 이내, 업무 목적이 드러나게.
            예: "투자사 미팅(OO)", "포트폴리오사 방문", "IR 참석", "업무 미팅".
            JSON만 출력: {"purpose": "..."}
            일정 제목: $eventTitle
        """.trimIndent()
        return askJson(ctx, prompt)?.optString("purpose")?.trim()?.takeIf { it.isNotBlank() }
    }

    /** 음성 명령 해석: 자주 가는 곳 이름 / 검색어 / 주변 검색 여부 */
    fun voiceCommand(ctx: Context, said: String, favorites: List<String>): JSONObject? {
        val prompt = """
            운전자가 내비 목적지를 말했다. 해석해라.
            자주 가는 곳 목록에 해당하면 favorite에 그 이름을 그대로 넣는다.
            아니면 카카오맵 검색어(query)를 만든다. "근처/가까운" 같은 말이 있으면 nearby=true.
            "슈퍼차저"·"충전소"는 "테슬라 슈퍼차저"처럼 검색어를 구체화한다.
            JSON만 출력: {"favorite": "이름 또는 빈 문자열", "query": "...", "nearby": true/false}
            자주 가는 곳: ${favorites.joinToString(", ")}
            말한 내용: $said
        """.trimIndent()
        return askJson(ctx, prompt)
    }
}
