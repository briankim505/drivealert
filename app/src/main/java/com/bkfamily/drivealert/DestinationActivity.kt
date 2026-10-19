package com.bkfamily.drivealert

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** 테슬라로 목적지 보내기: 다음 일정 / 장소 검색 / 자주 가는 곳 */
class DestinationActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var eventsBox: LinearLayout
    private lateinit var resultsBox: LinearLayout
    private lateinit var favBox: LinearLayout
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad * 2) }

        col.addView(text("테슬라로 목적지 보내기", 22f, bold = true))
        if (!Tesla.installed(this)) col.addView(text("⚠️ 테슬라 앱이 설치되어 있지 않습니다", 14f))
        if (prefs.kakaoRestKey.isBlank()) col.addView(text("⚠️ 설정에서 '카카오 REST API 키'를 넣어야 장소 검색이 됩니다", 14f))

        col.addView(text("다음 일정 (24시간 이내)", 17f, bold = true, top = true))
        eventsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(eventsBox)

        col.addView(text("장소 검색", 17f, bold = true, top = true))
        val input = EditText(this).apply {
            hint = "예: 강남역 스타벅스, 판교 테크노밸리"; isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        col.addView(input)
        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val go = { runSearch(input.text.toString()) }
        input.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEARCH) { go(); true } else false }
        col.addView(Button(this).apply { text = "검색"; setOnClickListener { go() } })
        col.addView(resultsBox)

        col.addView(text("자주 가는 곳", 17f, bold = true, top = true))
        col.addView(text("검색 결과를 길게 누르면 여기에 추가됩니다. 길게 누르면 삭제.", 13f))
        favBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(favBox)

        setContentView(ScrollView(this).apply { addView(col) })
        loadEvents()
        showFavorites()
    }

    private fun text(t: String, size: Float, bold: Boolean = false, top: Boolean = false) = TextView(this).apply {
        text = t; textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, if (top) pad else pad / 4, 0, pad / 4)
    }

    private fun placeButton(p: Place, onLong: (() -> Unit)? = null) = Button(this).apply {
        isAllCaps = false
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        text = "${p.name}\n${p.address}"
        setOnClickListener { Tesla.send(this@DestinationActivity, p) }
        onLong?.let { f -> setOnLongClickListener { f(); true } }
    }

    private fun loadEvents() {
        if (!Schedule.hasPermission(this)) {
            eventsBox.addView(text("캘린더 권한이 없습니다. 설정 화면의 '필수 권한 한 번에 설정'을 눌러주세요.", 14f))
            return
        }
        eventsBox.addView(text("불러오는 중…", 14f))
        Thread {
            val evs = Schedule.upcoming(this, 24).filter { !Schedule.isOnline(it) }.take(5)
            val guesses = evs.map { it to Schedule.guessPlaces(this, it) }
            runOnUiThread {
                eventsBox.removeAllViews()
                if (guesses.isEmpty()) eventsBox.addView(text("장소가 있는 일정이 없습니다", 14f))
                for ((e, places) in guesses) {
                    val how = if (e.location.isBlank()) " · 제목으로 추정" else ""
                    eventsBox.addView(text("${Schedule.timeText(e.begin)}  ${e.title}$how", 15f, bold = true, top = true))
                    if (places.isEmpty()) {
                        eventsBox.addView(text("장소를 찾지 못했습니다", 13f)); continue
                    }
                    eventsBox.addView(placeButton(places.first()) { addFavorite(places.first()) })
                    if (places.size > 1) eventsBox.addView(Button(this).apply {
                        text = "다른 후보 ${places.size - 1}곳 보기"; isAllCaps = false
                        setOnClickListener { pick(places.drop(1)) }
                    })
                }
                Places.lastError?.let { eventsBox.addView(text("($it)", 12f)) }
            }
        }.start()
    }

    private fun pick(list: List<Place>) {
        AlertDialog.Builder(this).setTitle("어디로 보낼까요?")
            .setItems(list.map { "${it.name}\n${it.address}" }.toTypedArray()) { _, i -> Tesla.send(this, list[i]) }
            .show()
    }

    private fun runSearch(q: String) {
        resultsBox.removeAllViews()
        resultsBox.addView(text("검색 중…", 14f))
        Thread {
            Places.lastError = null
            val r = Places.search(this, q)
            runOnUiThread {
                resultsBox.removeAllViews()
                if (r.isEmpty()) resultsBox.addView(text(Places.lastError ?: "결과가 없습니다", 14f))
                r.forEach { p -> resultsBox.addView(placeButton(p) { addFavorite(p) }) }
            }
        }.start()
    }

    private fun addFavorite(p: Place) {
        val label = EditText(this).apply { setText(p.name) }
        AlertDialog.Builder(this).setTitle("자주 가는 곳에 추가").setView(label)
            .setPositiveButton("추가") { _, _ ->
                prefs.favorites = prefs.favorites + p.copy(name = label.text.toString().ifBlank { p.name })
                showFavorites()
            }
            .setNegativeButton("취소", null).show()
    }

    private fun showFavorites() {
        favBox.removeAllViews()
        val favs = prefs.favorites
        if (favs.isEmpty()) favBox.addView(text("아직 없습니다", 14f))
        favs.forEachIndexed { i, p ->
            favBox.addView(placeButton(p) {
                AlertDialog.Builder(this).setMessage("'${p.name}'을(를) 삭제할까요?")
                    .setPositiveButton("삭제") { _, _ ->
                        prefs.favorites = favs.filterIndexed { j, _ -> j != i }; showFavorites()
                    }.setNegativeButton("취소", null).show()
            })
        }
    }
}
