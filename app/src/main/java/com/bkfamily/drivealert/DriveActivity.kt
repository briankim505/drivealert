package com.bkfamily.drivealert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kakaomobility.knsdk.KNRouteAvoidOption
import com.kakaomobility.knsdk.KNRoutePriority
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.common.objects.KNError
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance
import com.kakaomobility.knsdk.guidance.knguidance.KNGuideRouteChangeReason
import com.kakaomobility.knsdk.guidance.knguidance.KNGuideState
import com.kakaomobility.knsdk.guidance.knguidance.common.KNLocation
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_GuideStateDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_LocationGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_RouteGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_SafetyGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_VoiceGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.KNGuide_Route
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNMultiRouteInfo
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.KNGuide_Safety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety
import com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice
import com.kakaomobility.knsdk.trip.kntrip.knroute.KNRoute
import com.kakaomobility.knsdk.ui.view.KNNaviView
import com.kakaomobility.knsdk.ui.view.KNNaviView_GuideStateDelegate

/**
 * 주행 화면 = 카카오내비 '안전운행 모드'(목적지 없이 단속카메라·제한속도·과속 경고).
 * 음성 안내는 카카오내비 SDK가 직접 재생하며, 블루투스로 차량 스피커에서 나온다.
 */
class DriveActivity : AppCompatActivity(),
    KNGuidance_GuideStateDelegate,
    KNGuidance_LocationGuideDelegate,
    KNGuidance_RouteGuideDelegate,
    KNGuidance_SafetyGuideDelegate,
    KNGuidance_VoiceGuideDelegate,
    KNNaviView_GuideStateDelegate {

    private lateinit var naviView: KNNaviView
    private lateinit var status: TextView
    private var started = false
    private var engine: AlertEngine? = null

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = finishDrive()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContentView(R.layout.activity_drive)
        status = findViewById(R.id.status)
        findViewById<android.view.View>(R.id.btn_close).setOnClickListener { DriveControl.stop(this) }
        findViewById<android.view.View>(R.id.btn_voice).setOnClickListener { listen() }

        if (Prefs(this).keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ContextCompat.registerReceiver(
            this, stopReceiver, IntentFilter(DriveControl.ACTION_STOP), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        status.text = "시작 중…"
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            locPerm.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        } else afterPermission()
    }

    private val locPerm = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { afterPermission() }

    private fun afterPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            status.text = "위치 권한이 없어 시작할 수 없습니다. 설정 화면에서 '필수 권한 한 번에 설정'을 눌러주세요."
            return
        }
        // 화면 꺼짐 대비 서비스를 같이 켠다
        if (!DriveService.running) DriveControl.start(this)
        try {
            initKakao()
        } catch (e: Throwable) {
            CrashLog.save(this, e)
            status.text = "카카오 시작 오류: ${e.javaClass.simpleName} ${e.message}"
        }
    }

    private fun initKakao() {
        val prefs = Prefs(this)
        if (prefs.kakaoKey.isEmpty()) {
            status.text = "설정에서 카카오 앱 키를 먼저 입력해 주세요"
            return
        }
        status.text = "카카오내비 연결 중… (계속 이 문구면 키·키해시 확인 필요)"
        KNSDK.initializeWithAppKey(prefs.kakaoKey, BuildConfig.VERSION_NAME, prefs.userKey) { error: KNError? ->
            runOnUiThread {
                if (error != null) {
                    status.text = "카카오 인증 실패: ${error.code} ${error.msg}"
                    Toast.makeText(this, "카카오 인증 실패 (${error.code})", Toast.LENGTH_LONG).show()
                } else {
                    try {
                        startSafetyMode()
                    } catch (e: Throwable) {
                        CrashLog.save(this, e)
                        status.text = "안전운행 시작 오류: ${e.javaClass.simpleName} ${e.message}"
                    }
                }
            }
        }
    }

    private fun startSafetyMode() {
        // 카카오 SDK 인증이 끝난 뒤에 주행 화면을 만들어야 함
        naviView = KNNaviView(this)
        findViewById<android.widget.FrameLayout>(R.id.navi_container).addView(
            naviView,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val guidance = KNSDK.sharedGuidance() ?: run {
            status.text = "안내 기능을 시작하지 못했습니다"
            return
        }
        guidance.guideStateDelegate = this
        guidance.locationGuideDelegate = this
        guidance.routeGuideDelegate = this
        guidance.safetyGuideDelegate = this
        guidance.voiceGuideDelegate = this
        guidance.useBackgroundUpdate = true
        val eng = AlertEngine(this).also { engine = it }
        // 단속 지점 접근 시 SDK가 호출 → 사용자 설정대로 안내 (반환값: 카카오 경고음 사용 여부)
        guidance.judgeOverSpeedAlert = { code, limit, cur, remain, highway ->
            val r = eng.onCamera(code.name, limit, cur, remain, highway)
            runOnUiThread { status.text = eng.lastInfo }
            r
        }
        guidance.judgeOverSpeedViolation = { _, limit, cur -> limit > 0 && cur > eng.threshold(limit) }
        naviView.guideStateDelegate = this
        // 경로(trip) 없이 시작 = 안전운행 모드
        naviView.initWithGuidance(
            guidance, null,
            KNRoutePriority.KNRoutePriority_Recommand,
            KNRouteAvoidOption.KNRouteAvoidOption_None.value
        )
        started = true
        status.text = "단속카메라 알림 작동 중"
    }

    // ---- 음성으로 목적지 바꾸기 ----
    private val speech = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val said = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            ?: return@registerForActivityResult
        status.text = "\"$said\" 찾는 중…"
        Thread {
            val place = try { VoiceDest.resolve(this, said) } catch (e: Exception) { null }
            runOnUiThread {
                val voice = engine ?: AlertEngine(this).also { engine = it }
                if (place == null) {
                    status.text = "'$said' 장소를 찾지 못했습니다"
                    voice.speak("장소를 찾지 못했어요")
                } else {
                    status.text = "테슬라로 보냄: ${place.name}"
                    voice.speak("${place.name}, 테슬라로 보냈어요")
                    Tesla.send(this, place)
                }
            }
        }.start()
    }

    private fun listen() {
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "어디로 갈까요? (예: 집, 근처 슈퍼차저)")
        try { speech.launch(i) } catch (_: Exception) { Toast.makeText(this, "음성 인식을 쓸 수 없습니다", Toast.LENGTH_SHORT).show() }
    }

    private fun finishDrive() {
        if (started) {
            try { KNSDK.sharedGuidance()?.stop() } catch (_: Exception) {}
            started = false
        }
        finish()
    }

    // ---- 앱 상태를 SDK에 전달 (화면 꺼짐/켜짐 대응) ----
    override fun onResume() { super.onResume(); KNSDK.handleDidBecomeActive() }
    override fun onPause() { KNSDK.handleWillResignActive(); super.onPause() }
    override fun onStart() { super.onStart(); KNSDK.handleWillEnterForeground() }
    override fun onStop() { KNSDK.handleDidEnterBackground(); super.onStop() }

    override fun onDestroy() {
        engine?.release()
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---- 카카오 SDK 콜백: 그대로 주행 화면(KNNaviView)에 전달 ----
    override fun guidanceGuideStarted(aGuidance: KNGuidance) = naviView.guidanceGuideStarted(aGuidance)
    override fun guidanceCheckingRouteChange(aGuidance: KNGuidance) = naviView.guidanceCheckingRouteChange(aGuidance)
    override fun guidanceRouteUnchanged(aGuidance: KNGuidance) = naviView.guidanceRouteUnchanged(aGuidance)
    override fun guidanceRouteUnchangedWithError(aGuidnace: KNGuidance, aError: KNError) =
        naviView.guidanceRouteUnchangedWithError(aGuidnace, aError)
    override fun guidanceOutOfRoute(aGuidance: KNGuidance) = naviView.guidanceOutOfRoute(aGuidance)
    override fun guidanceRouteChanged(
        aGuidance: KNGuidance, aFromRoute: KNRoute, aFromLocation: KNLocation,
        aToRoute: KNRoute, aToLocation: KNLocation, aChangeReason: KNGuideRouteChangeReason
    ) = naviView.guidanceRouteChanged(aGuidance)
    override fun guidanceDidUpdateIndoorRoute(aGuidance: KNGuidance, aRoute: KNRoute?) {}

    // 주행 화면에서 사용자가 '안내 종료'를 누른 경우
    override fun naviViewGuideState(state: KNGuideState) {}
    override fun naviViewGuideEnded() = DriveControl.stop(this)
    override fun guidanceGuideEnded(aGuidance: KNGuidance) = naviView.guidanceGuideEnded(aGuidance, false)
    override fun guidanceDidUpdateRoutes(aGuidance: KNGuidance, aRoutes: List<KNRoute>, aMultiRouteInfo: KNMultiRouteInfo?) =
        naviView.guidanceDidUpdateRoutes(aGuidance, aRoutes, aMultiRouteInfo)

    override fun guidanceDidUpdateLocation(aGuidance: KNGuidance, aLocationGuide: KNGuide_Location) =
        naviView.guidanceDidUpdateLocation(aGuidance, aLocationGuide)

    override fun guidanceDidUpdateRouteGuide(aGuidance: KNGuidance, aRouteGuide: KNGuide_Route) =
        naviView.guidanceDidUpdateRouteGuide(aGuidance, aRouteGuide)

    override fun guidanceDidUpdateSafetyGuide(aGuidance: KNGuidance, aSafetyGuide: KNGuide_Safety?) =
        naviView.guidanceDidUpdateSafetyGuide(aGuidance, aSafetyGuide)

    override fun guidanceDidUpdateAroundSafeties(aGuidance: KNGuidance, aSafeties: List<KNSafety>?) =
        naviView.guidanceDidUpdateAroundSafeties(aGuidance, aSafeties)

    // 카카오 기본 음성을 쓰지 않는 설정이면 SDK 음성을 막고 앱이 직접 안내
    override fun shouldPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice, aNewData: MutableList<ByteArray>): Boolean =
        if (engine?.useKakaoVoice == false || OtherNav.active(this)) false
        else naviView.shouldPlayVoiceGuide(aGuidance, aVoiceGuide, aNewData)

    override fun willPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) =
        naviView.willPlayVoiceGuide(aGuidance, aVoiceGuide)

    override fun didFinishPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) =
        naviView.didFinishPlayVoiceGuide(aGuidance, aVoiceGuide)

}
