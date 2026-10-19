package com.bkfamily.drivealert

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor

/** 설정 화면: 카카오 키, 차량 등록, 권한, 기능 켜기/끄기, 운행기록, 업데이트 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var keyInput: EditText
    private lateinit var carView: TextView
    private var previewEngine: AlertEngine? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { nextPermissionStep() }

    private val associateLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            val data = res.data ?: return@registerForActivityResult
            if (Build.VERSION.SDK_INT >= 33) {
                val info = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                info?.let { saveCar(it.deviceMacAddress?.toString(), it.displayName?.toString()) }
            } else {
                @Suppress("DEPRECATION")
                val dev: BluetoothDevice? = data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
                dev?.let { saveCar(it.address, safeName(it)) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        Updater.check(this, silent = true)
        PlannerWorker.schedule(this)
        CrashLog.take(this)?.let { log ->
            AlertDialog.Builder(this)
                .setTitle("지난번 오류 기록")
                .setMessage(log.take(1500))
                .setPositiveButton("확인", null).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------------ UI
    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 2)
        }
        fun title(t: String) = TextView(this).apply {
            text = t; textSize = 17f; setPadding(0, pad, 0, pad / 3)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }.also { col.addView(it) }
        fun note(t: String) = TextView(this).apply { text = t; textSize = 13f; alpha = 0.7f }.also { col.addView(it) }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
        }.also { col.addView(it) }
        fun switch(t: String, get: () -> Boolean, set: (Boolean) -> Unit) = SwitchCompat(this).apply {
            text = t; textSize = 15f; isChecked = get(); setPadding(0, pad / 3, 0, pad / 3)
            setOnCheckedChangeListener { _, v -> set(v) }
        }.also { col.addView(it) }

        TextView(this).apply {
            text = "드라이브 알리미"; textSize = 24f; gravity = Gravity.START
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }.also { col.addView(it) }
        statusView = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, 0) }.also { col.addView(it) }

        title("1. 카카오 앱 키")
        note("카카오디벨로퍼스에서 발급한 '네이티브 앱 키'를 붙여넣으세요.")
        keyInput = EditText(this).apply {
            hint = "네이티브 앱 키"; setText(prefs.kakaoKey); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }.also { col.addView(it) }
        button("키 저장") {
            prefs.kakaoKey = keyInput.text.toString()
            toast("저장했습니다"); refresh()
        }

        title("2. 차량 등록")
        carView = TextView(this).apply { textSize = 14f }.also { col.addView(it) }
        note("목록에서 차량(예: Tesla Model Y)을 고르면, 이후 블루투스가 연결될 때 자동으로 켜집니다.")
        button("차량 선택하기") { associateCar() }

        title("3. 권한 설정")
        note("위치는 '항상 허용'을 골라야 화면이 꺼져도 작동합니다.")
        button("필수 권한 한 번에 설정") { permStep = 0; nextPermissionStep() }

        title("4. 기능")
        switch("블루투스 연결 시 자동 시작", { prefs.autoStart }) { prefs.autoStart = it }
        switch("주행 중 화면 켜짐 유지", { prefs.keepScreenOn }) { prefs.keepScreenOn = it }
        note("화면이 꺼졌을 때 안내가 끊기면 켜 두세요(충전 중 권장).")
        switch("운행기록 자동 작성", { prefs.tripLog }) { prefs.tripLog = it }
        switch("주차 위치 자동 저장", { prefs.parking }) { prefs.parking = it }

        title("5. 단속 안내 방식")
        fun choice(label: String, names: List<String>, values: List<Any>, get: () -> Any, set: (Any) -> Unit) {
            val b = Button(this).apply { isAllCaps = false }
            fun upd() { b.text = "$label: " + names[values.indexOf(get()).coerceAtLeast(0)] }
            upd()
            b.setOnClickListener {
                AlertDialog.Builder(this).setTitle(label)
                    .setSingleChoiceItems(names.toTypedArray(), values.indexOf(get())) { d, i -> set(values[i]); upd(); d.dismiss() }
                    .show()
            }
            col.addView(b)
        }
        choice("소리", listOf("카카오내비 기본 음성", "휴대폰 음성(직접 설정)", "효과음만", "화면만(무음)"),
            listOf(Prefs.MODE_KAKAO, Prefs.MODE_TTS, Prefs.MODE_BEEP, Prefs.MODE_SILENT),
            { prefs.alertMode }) { prefs.alertMode = it as Int }
        note("아래 설정은 '휴대폰 음성'·'효과음'일 때 적용됩니다. 경고 기준은 모든 방식에 적용됩니다.")
        choice("과속 경고 기준", listOf("제한속도 넘으면 바로", "제한속도 +5km", "제한속도 +10km", "제한속도 +10%"),
            listOf(Prefs.MARGIN_0, Prefs.MARGIN_5, Prefs.MARGIN_10, Prefs.MARGIN_10P),
            { prefs.overMargin }) { prefs.overMargin = it as Int }
        choice("첫 안내 거리", listOf("700m", "500m", "300m", "200m"), listOf(700, 500, 300, 200),
            { prefs.announceDistance }) { prefs.announceDistance = it as Int }
        choice("과속 중 반복 경고", listOf("한 번만", "10초마다", "5초마다", "3초마다"), listOf(0, 10, 5, 3),
            { prefs.repeatSec }) { prefs.repeatSec = it as Int }
        choice("말 빠르기", listOf("느리게", "보통", "빠르게", "아주 빠르게"), listOf(0.8f, 1.0f, 1.3f, 1.6f),
            { prefs.ttsRate }) { prefs.ttsRate = it as Float }
        choice("목소리 높낮이", listOf("낮게", "보통", "높게"), listOf(0.8f, 1.0f, 1.25f),
            { prefs.ttsPitch }) { prefs.ttsPitch = it as Float }
        switch("경고할 때 현재 속도도 말하기", { prefs.speakSpeed }) { prefs.speakSpeed = it }
        button("목소리 종류 바꾸기 (휴대폰 음성 설정)") {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) { toast("음성 설정을 열 수 없습니다") }
        }
        button("미리 듣기") {
            val e = previewEngine ?: AlertEngine(this).also { previewEngine = it }
            col.postDelayed({ e.preview() }, 700)
        }

        title("6. 테슬라로 목적지 보내기")
        note("차에 연결되면 3시간 이내 다음 일정 장소(없으면 제목으로 추정)를 자동으로 테슬라 내비에 보냅니다. 다른 곳으로 가려면 알림을 눌러 바꾸면 됩니다.")
        button("목적지 보내기 화면 열기 (직접 선택·자주 가는 곳)") { startActivity(Intent(this, DestinationActivity::class.java)) }
        button("자동 전송 지금 시험해 보기") {
            Thread {
                val ok = Schedule.autoSend(this, withinHours = 24)
                runOnUiThread { if (!ok) toast("보낼 일정이 없거나 이미 보냈습니다 (24시간 이내, 장소 추정 가능한 일정)") }
            }.start()
        }
        note("장소 검색용 '카카오 REST API 키' (같은 카카오 앱의 플랫폼 키 화면에 있음)")
        val restInput = EditText(this).apply {
            hint = "REST API 키"; setText(prefs.kakaoRestKey); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }.also { col.addView(it) }
        button("REST 키 저장") { prefs.kakaoRestKey = restInput.text.toString(); toast("저장했습니다") }
        button("일정을 볼 캘린더 선택") { chooseCalendars() }
        switch("차에 연결되면 다음 일정 목적지 자동 전송", { prefs.suggestOnConnect }) { prefs.suggestOnConnect = it }
        switch("(선택) 일정 1시간 전 미리 알림", { prefs.notifyBefore }) {
            prefs.notifyBefore = it; PlannerWorker.schedule(this)
        }

        title("7. 주행")
        button("지금 주행 화면 열기 (테스트)") { startActivity(Intent(this, DriveActivity::class.java)) }
        button("주차 위치 보기") { showParking() }
        button("이번 달 운행기록 내보내기") { exportTrips(0) }
        button("지난 달 운행기록 내보내기") { exportTrips(-1) }

        title("8. 앱 정보")
        note("버전 ${BuildConfig.VERSION_NAME}")
        button("업데이트 확인") { Updater.check(this, silent = false) }

        return ScrollView(this).apply { addView(col) }
    }

    private fun refresh() {
        carView.text = if (prefs.carAddress.isEmpty()) "등록된 차량 없음"
        else "등록된 차량: ${prefs.carName.ifEmpty { "이름 없음" }} (${prefs.carAddress})"
        val issues = mutableListOf<String>()
        if (prefs.kakaoKey.isEmpty()) issues += "카카오 키"
        if (prefs.carAddress.isEmpty()) issues += "차량 등록"
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) issues += "위치 권한"
        else if (Build.VERSION.SDK_INT >= 29 && !has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) issues += "위치 '항상 허용'"
        if (!batteryExempt()) issues += "배터리 최적화 제외"
        statusView.text = if (issues.isEmpty()) "✅ 준비 완료 — 차량에 연결되면 자동으로 작동합니다"
        else "⚠️ 남은 설정: " + issues.joinToString(", ")
    }

    // ------------------------------------------------------------ 차량 등록
    @SuppressLint("MissingPermission")
    private fun associateCar() {
        val cdm = getSystemService(CompanionDeviceManager::class.java)
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(false)
            .build()
        if (Build.VERSION.SDK_INT >= 33) {
            val exec = Executor { it.run() }
            cdm.associate(request, exec, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    associateLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    runOnUiThread {
                        saveCar(associationInfo.deviceMacAddress?.toString(), associationInfo.displayName?.toString())
                    }
                }
                override fun onFailure(error: CharSequence?) {
                    runOnUiThread { toast("차량 선택 실패: $error") }
                }
            })
        } else {
            @Suppress("DEPRECATION")
            cdm.associate(request, object : CompanionDeviceManager.Callback() {
                @Deprecated("Deprecated in Java")
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    associateLauncher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
                }
                override fun onFailure(error: CharSequence?) { toast("차량 선택 실패: $error") }
            }, null)
        }
    }

    private fun saveCar(address: String?, name: String?) {
        if (address.isNullOrEmpty()) return
        prefs.carAddress = address
        prefs.carName = name ?: ""
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                @Suppress("DEPRECATION")
                getSystemService(CompanionDeviceManager::class.java).startObservingDevicePresence(address)
            } catch (_: Exception) {
            }
        }
        toast("차량을 등록했습니다")
        refresh()
    }

    @SuppressLint("MissingPermission")
    private fun safeName(d: BluetoothDevice): String = try { d.name ?: "" } catch (_: SecurityException) { "" }

    // ---------------------------------------------------------------- 권한
    private var permStep = 0

    private fun nextPermissionStep() {
        when (permStep++) {
            0 -> {
                val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.READ_CALENDAR)
                if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
                if (Build.VERSION.SDK_INT >= 31) list += Manifest.permission.BLUETOOTH_CONNECT
                val missing = list.filter { !has(it) }
                if (missing.isEmpty()) nextPermissionStep() else permLauncher.launch(missing.toTypedArray())
            }
            1 -> {
                if (Build.VERSION.SDK_INT >= 29 && has(Manifest.permission.ACCESS_FINE_LOCATION) &&
                    !has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                ) {
                    AlertDialog.Builder(this)
                        .setTitle("위치 '항상 허용'")
                        .setMessage("다음 화면에서 '항상 허용'을 골라 주세요. 화면이 꺼져도 단속 알림이 나오게 됩니다.")
                        .setPositiveButton("확인") { _, _ ->
                            permLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                        }
                        .setCancelable(false).show()
                } else nextPermissionStep()
            }
            2 -> {
                if (!batteryExempt()) {
                    @SuppressLint("BatteryLife")
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    try { startActivity(i) } catch (_: Exception) {}
                } else nextPermissionStep()
            }
            3 -> {
                if (!Settings.canDrawOverlays(this)) {
                    AlertDialog.Builder(this)
                        .setTitle("다른 앱 위에 표시")
                        .setMessage("휴대폰이 잠겨 있어도 주행 화면이 자동으로 뜨도록, 다음 화면에서 이 앱을 허용해 주세요.")
                        .setPositiveButton("확인") { _, _ ->
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        }
                        .setNegativeButton("건너뛰기", null).show()
                }
                refresh()
            }
        }
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun batteryExempt(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    // ------------------------------------------------------------ 주차·기록
    private fun showParking() {
        if (prefs.parkTime == 0L) { toast("저장된 주차 위치가 없습니다"); return }
        val t = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(prefs.parkTime)
        val lat = prefs.parkLat; val lon = prefs.parkLon
        AlertDialog.Builder(this)
            .setTitle("주차 위치")
            .setMessage("$t 에 저장된 위치입니다.")
            .setPositiveButton("지도에서 보기") { _, _ ->
                val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(주차 위치)")
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { toast("지도 앱이 없습니다") }
            }
            .setNegativeButton("닫기", null).show()
    }

    private fun exportTrips(offset: Int) {
        toast("운행기록을 정리하는 중…")
        Thread {
            val f = TripLog.exportCsv(this, offset)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            runOnUiThread {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/csv")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        "운행기록 보내기"
                    )
                )
            }
        }.start()
    }

    private fun chooseCalendars() {
        val cals = Schedule.calendars(this)
        if (cals.isEmpty()) { toast("캘린더 권한을 먼저 허용해 주세요 ('필수 권한 한 번에 설정')"); return }
        val sel = prefs.calendarIds
        val checked = BooleanArray(cals.size) { sel.isEmpty() || cals[it].id.toString() in sel }
        AlertDialog.Builder(this)
            .setTitle("목적지를 찾을 캘린더")
            .setMultiChoiceItems(cals.map { "${it.name}\n${it.account}" }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("저장") { _, _ ->
                val ids = cals.filterIndexed { i, _ -> checked[i] }.map { it.id.toString() }.toSet()
                prefs.calendarIds = if (ids.size == cals.size) emptySet() else ids
                toast("${ids.size}개 캘린더를 봅니다")
            }
            .setNegativeButton("취소", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
