package com.bkfamily.drivealert

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 제미나이 음성(TTS). 주행 중엔 인터넷을 기다리지 않도록, 안내 문장을 '조각'으로 나눠 미리 만들어 폰에 저장해 두고
 * 저장된 조각을 이어서 재생한다. 아직 없는 조각이 있으면 그때는 휴대폰 음성으로 말하고, 뒤에서 조각을 만들어 둔다.
 */
object GeminiVoice {
    /** 제미나이 기본 목소리 (이름 → 설명) */
    val voices = linkedMapOf(
        "Kore" to "단단하고 또렷한 목소리",
        "Aoede" to "산뜻하고 가벼운 목소리",
        "Leda" to "젊고 밝은 목소리",
        "Zephyr" to "명랑한 목소리",
        "Charon" to "차분하고 설명하는 목소리",
        "Orus" to "굵고 단단한 목소리",
        "Puck" to "경쾌한 목소리"
    )

    private val models = listOf("gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts")
    @Volatile var lastError: String? = null
    @Volatile private var working = false

    private fun dir(ctx: Context) = File(ctx.filesDir, "gemini_voice").apply { mkdirs() }

    fun fileFor(ctx: Context, phrase: String): File {
        val p = Prefs(ctx)
        val key = "${p.geminiVoice}|${phrase.trim()}".hashCode().toString(16)
        return File(dir(ctx), "$key.wav")
    }

    fun has(ctx: Context, phrase: String) = fileFor(ctx, phrase).let { it.exists() && it.length() > 1000 }

    /** 안내 문장을 쉼표·마침표 기준 조각으로 */
    fun segments(text: String): List<String> =
        text.split(Regex("[,.]")).map { it.trim() }.filter { it.isNotEmpty() }

    /** 미리 만들어 둘 기본 조각들 */
    fun commonPhrases(): List<String> {
        val labels = listOf("과속 단속", "구간단속 시작", "구간단속 끝", "구간단속 중", "이동식 단속",
            "신호 과속 단속", "후면 단속", "버스전용차로 단속")
        val limits = (30..110 step 10).map { "제한속도 $it" }
        val dists = (100..700 step 100).map { "${it}미터 앞" } + "곧"
        return labels + limits + dists + listOf("감속하세요", "테슬라로 보냈어요", "장소를 찾지 못했어요")
    }

    /** 한 조각을 제미나이로 만들어 저장 (블로킹, 백그라운드에서 호출) */
    fun synth(ctx: Context, phrase: String): Boolean {
        val p = Prefs(ctx)
        if (p.geminiKey.isBlank()) { lastError = "제미나이 키가 없습니다"; return false }
        val prompt = "Say in a calm, clear Korean car-navigation voice: ${phrase.trim()}"
        for (m in models) {
            repeat(2) { attempt ->
                try {
                    val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                        .openConnection() as HttpURLConnection
                    c.requestMethod = "POST"; c.doOutput = true
                    c.connectTimeout = 10000; c.readTimeout = 30000
                    c.setRequestProperty("Content-Type", "application/json")
                    c.setRequestProperty("x-goog-api-key", p.geminiKey)
                    val body = JSONObject()
                        .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                        .put("generationConfig", JSONObject()
                            .put("responseModalities", JSONArray().put("AUDIO"))
                            .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                                .put("prebuiltVoiceConfig", JSONObject().put("voiceName", p.geminiVoice)))))
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = c.responseCode
                    if (code == 429 && attempt == 0) { Thread.sleep(30_000); return@repeat }
                    if (code != 200) { lastError = "제미나이 음성 오류 $code ($m)"; return@repeat }
                    val part = JSONObject(c.inputStream.bufferedReader().readText())
                        .getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                        .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData")
                    val pcm = Base64.decode(part.getString("data"), Base64.DEFAULT)
                    val rate = Regex("rate=(\\d+)").find(part.optString("mimeType"))?.groupValues?.get(1)?.toInt() ?: 24000
                    writeWav(fileFor(ctx, phrase), pcm, rate)
                    lastError = null
                    return true
                } catch (e: Exception) {
                    lastError = "제미나이 음성 실패: ${e.javaClass.simpleName}"
                }
            }
        }
        return false
    }

    private fun writeWav(f: File, pcm: ByteArray, rate: Int) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { it.write(h.array()); it.write(pcm) }
        tmp.renameTo(f)
    }

    /** 없는 조각들을 뒤에서 하나씩 만든다 (무료 한도 보호를 위해 간격을 둠) */
    fun fillMissing(ctx: Context, phrases: List<String>, onProgress: ((Int, Int) -> Unit)? = null) {
        if (working || Prefs(ctx).geminiKey.isBlank()) return
        val todo = phrases.map { it.trim() }.distinct().filter { it.isNotEmpty() && !has(ctx, it) }
        if (todo.isEmpty()) { onProgress?.invoke(0, 0); return }
        working = true
        Thread {
            var done = 0
            try {
                for (ph in todo) {
                    if (synth(ctx, ph)) done++
                    onProgress?.invoke(done, todo.size)
                    Thread.sleep(6000)
                }
                DriveLog.add(ctx, "제미나이 음성 조각 ${done}/${todo.size}개 만듦" + (lastError?.let { " ($it)" } ?: ""))
            } finally { working = false }
        }.start()
    }
}
