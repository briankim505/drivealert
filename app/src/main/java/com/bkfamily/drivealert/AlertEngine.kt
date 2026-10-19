package com.bkfamily.drivealert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 사용자 설정대로 단속 안내·과속 경고를 낸다.
 * 카카오 SDK가 단속 지점에 가까워질 때마다 (종류, 제한속도, 현재속도, 남은거리)를 알려주면
 * 여기서 '언제·얼마나 자주·어떤 소리로' 낼지 결정한다.
 */
class AlertEngine(private val ctx: Context) : TextToSpeech.OnInitListener {

    private val prefs = Prefs(ctx)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).build()

    // 단속 지점별 안내 상태
    private var currentKey: String? = null
    private var announced = false
    private var lastWarnAt = 0L
    private var lastRemain = Int.MAX_VALUE

    /** 화면 표시용 마지막 상태 */
    var lastInfo: String = ""
        private set

    init {
        initTts()
    }

    private fun initTts() {
        if (tts == null) tts = TextToSpeech(ctx, this)
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status == TextToSpeech.SUCCESS) {
            t.language = Locale.KOREAN
            t.setAudioAttributes(attrs)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { am.abandonAudioFocusRequest(focus) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { am.abandonAudioFocusRequest(focus) }
            })
            ttsReady = true
        }
    }

    /** 경고 기준 속도(이 속도를 넘으면 과속 경고) */
    fun threshold(limit: Int): Int = when (prefs.overMargin) {
        Prefs.MARGIN_5 -> limit + 5
        Prefs.MARGIN_10 -> limit + 10
        Prefs.MARGIN_10P -> (limit * 1.1).toInt()
        else -> limit
    }

    /** 카카오 기본 음성을 그대로 쓸지 */
    val useKakaoVoice get() = prefs.alertMode == Prefs.MODE_KAKAO

    /**
     * 카카오 SDK의 과속 판단 콜백에서 호출.
     * @return 카카오 기본 경고음을 울릴지 여부
     */
    fun onCamera(codeName: String, limit: Int, cur: Int, remain: Int, highway: Boolean): Boolean {
        val over = limit > 0 && cur > threshold(limit)
        lastInfo = "${label(codeName)} ${remain}m · 제한 ${limit} · 현재 ${cur}km/h"

        if (useKakaoVoice) return over

        // 새 단속 지점인지 판단 (종류·제한속도가 바뀌었거나, 남은 거리가 크게 늘어남)
        val key = "$codeName/$limit"
        if (key != currentKey || remain > lastRemain + 150) {
            currentKey = key
            announced = false
            lastWarnAt = 0L
        }
        lastRemain = remain

        val firstDist = prefs.announceDistance
        if (!announced && remain <= firstDist) {
            announced = true
            val dist = if (remain >= 100) "${(remain / 100) * 100}미터 앞" else "곧"
            val lim = if (limit > 0) ", 제한속도 $limit" else ""
            say("$dist ${label(codeName)}$lim", beep = 1)
        }

        if (over && remain <= firstDist) {
            val now = SystemClock.elapsedRealtime()
            val interval = prefs.repeatSec * 1000L
            val canRepeat = interval > 0 && now - lastWarnAt >= interval
            if (lastWarnAt == 0L || canRepeat) {
                lastWarnAt = now
                say(if (prefs.speakSpeed) "감속하세요, 현재 $cur" else "감속하세요", beep = 3)
            }
        }
        return false
    }

    private fun say(text: String, beep: Int) {
        when (prefs.alertMode) {
            Prefs.MODE_TTS -> speak(text)
            Prefs.MODE_BEEP -> tone(beep)
            else -> {} // 화면만
        }
    }

    fun speak(text: String) {
        initTts()
        val t = tts ?: return
        if (!ttsReady) return
        t.setSpeechRate(prefs.ttsRate)
        t.setPitch(prefs.ttsPitch)
        am.requestAudioFocus(focus)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alert${SystemClock.elapsedRealtime()}")
    }

    private fun tone(times: Int) {
        Thread {
            try {
                val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                repeat(times) { tg.startTone(ToneGenerator.TONE_PROP_BEEP, 180); Thread.sleep(300) }
                tg.release()
            } catch (_: Exception) {
            }
        }.start()
    }

    /** 설정 화면 미리 듣기 */
    fun preview() {
        when (prefs.alertMode) {
            Prefs.MODE_TTS -> speak("300미터 앞 과속 단속, 제한속도 60. 감속하세요, 현재 72")
            Prefs.MODE_BEEP -> tone(3)
            Prefs.MODE_KAKAO -> speak("카카오내비 기본 음성은 주행 화면에서 나옵니다")
            else -> {}
        }
    }

    fun release() {
        tts?.stop(); tts?.shutdown(); tts = null
    }

    companion object {
        /** 카카오 단속 코드 이름 → 한국어 안내 문구 */
        fun label(code: String): String = when {
            code.contains("SectionIn") -> "구간단속 시작"
            code.contains("SectionOut") -> "구간단속 끝"
            code.contains("SpeedViolationSection") -> "구간단속 중"
            code.contains("Movable") -> "이동식 단속"
            code.contains("SignalAndSpeed") -> "신호 과속 단속"
            code.contains("Signal") -> "신호 단속"
            code.contains("Buslane") -> "버스전용차로 단속"
            code.contains("Backward") -> "후면 단속"
            code.contains("Speed") -> "과속 단속"
            code.contains("Parking") -> "주정차 단속"
            code.contains("Camera") -> "단속 카메라"
            code.contains("Children") -> "어린이 보호구역"
            else -> "안전 운전 구간"
        }
    }
}
