package com.example.v26cheer

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {

    // ── 색상 ──
    private val cBg = Color.parseColor("#F4F6FA")
    private val cCard = Color.WHITE
    private val cPrimary = Color.parseColor("#1F3A8A")
    private val cAccent = Color.parseColor("#F97316")
    private val cText = Color.parseColor("#111827")
    private val cSub = Color.parseColor("#6B7280")
    private val cLine = Color.parseColor("#E5E7EB")
    private val cRed = Color.parseColor("#DC2626")

    private lateinit var listBox: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var toggle: Button
    private lateinit var statusPill: TextView
    private var pendingKind = "cheer"     // "cheer" 또는 "walk"
    private var pendingName: String? = null  // 기존 선수 수정 중이면 그 선수 이름

    private var previewPlayer: StadiumPlayer? = null

    private class Act(val label: String, val run: () -> Unit)

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val name = pendingName ?: nameInput.text.toString().trim()
        val editing = pendingName != null
        pendingName = null
        if (uri == null || name.isEmpty()) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val list = Store.players(this)
        val idx = list.indexOfFirst { it.name == name }
        val old = if (idx >= 0) list[idx] else null
        val s = uri.toString()
        val np = if (pendingKind == "cheer") Player(name, s, old?.walk) else Player(name, old?.cheer, s)
        if (idx >= 0) list[idx] = np else list.add(np)
        Store.savePlayers(this, list)
        refreshList()
        if (!editing) nameInput.setText("")
        toast(if (pendingKind == "cheer") "응원가가 등록됐어요" else "등장곡이 등록됐어요")
    }

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK && r.data != null) {
            val i = Intent(this, CheerService::class.java)
                .setAction(CheerService.ACTION_START)
                .putExtra("code", r.resultCode)
                .putExtra("data", r.data)
            startForegroundService(i)
            toggle.postDelayed({ updateToggle() }, 500)
        }
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startCapture() }

    // ── 스타일 도우미 ──
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(cCard, 16)
        elevation = dp(2).toFloat()
        setPadding(dp(18), dp(16), dp(18), dp(18))
        layoutParams = lp(14)
    }

    private fun title(text: String, sub: String? = null): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply {
            this.text = text; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(cText)
        })
        if (sub != null) addView(TextView(this@MainActivity).apply {
            this.text = sub; textSize = 12.5f; setTextColor(cSub); setPadding(0, dp(3), 0, 0)
        })
        layoutParams = lp()
    }

    private fun edit(hintText: String): EditText = EditText(this).apply {
        hint = hintText; isSingleLine = true; textSize = 15f
        setTextColor(cText); setHintTextColor(Color.parseColor("#9CA3AF"))
        background = round(Color.parseColor("#F9FAFB"), 12, cLine)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = lp(12)
    }

    private fun btn(text: String, color: Int, filled: Boolean): Button = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        stateListAnimator = null
        minHeight = dp(48); minimumHeight = dp(48)
        if (filled) { background = round(color, 12); setTextColor(Color.WHITE) }
        else { background = round(Color.WHITE, 12, color); setTextColor(color) }
    }

    private fun chip(text: String, on: Boolean) = TextView(this).apply {
        this.text = text; textSize = 12f
        setTextColor(if (on) Color.parseColor("#15803D") else Color.parseColor("#9CA3AF"))
        background = round(if (on) Color.parseColor("#DCFCE7") else Color.parseColor("#F3F4F6"), 20)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    // ── 화면 ──
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(cBg); isFillViewport = true; addView(root) })

        runCatching {
            window.statusBarColor = cBg
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        }

        // 헤더
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "⚾ V26 응원가"; textSize = 26f
            setTypeface(typeface, Typeface.BOLD); setTextColor(cPrimary)
        })
        titles.addView(TextView(this).apply {
            text = "타석에 들어서면 자동으로 응원가가 나와요"; textSize = 13f; setTextColor(cSub)
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusPill = TextView(this).apply { textSize = 12f; setPadding(dp(12), dp(5), dp(12), dp(5)) }
        header.addView(statusPill)
        root.addView(header, lp())

        // 1. 내 팀
        val c1 = card()
        c1.addView(title("1. 내 팀", "공격팀이 내 팀일 때만 동작해요. 비워두면 양쪽 모두 동작해요."))
        c1.addView(edit("예: 한화").apply {
            setText(Store.str(this@MainActivity, "team"))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { Store.putStr(this@MainActivity, "team", s.toString()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
        root.addView(c1)

        // 2. 선수 등록
        val c2 = card()
        c2.addView(title("2. 선수 등록", "이름은 '26 같은 표시 없이 선수 이름만 적어주세요."))
        nameInput = edit("선수 이름 (예: 허인서)")
        c2.addView(nameInput)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(12) }
        btnRow.addView(btn("🎵 응원가", cPrimary, true).apply { setOnClickListener { pickNew("cheer") } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        btnRow.addView(btn("🎤 등장곡 (선택)", cPrimary, false).apply { setOnClickListener { pickNew("walk") } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        c2.addView(btnRow)
        c2.addView(TextView(this).apply {
            text = "등록한 선수를 누르면 응원가·등장곡을 바꾸거나 미리 들어볼 수 있어요."
            textSize = 12f; setTextColor(cSub); setPadding(0, dp(12), 0, 0)
        })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(4) }
        c2.addView(listBox)
        root.addView(c2)

        // 3. 소리 설정
        val c3 = card()
        c3.addView(title("3. 소리 설정"))
        c3.addView(label("응원가 재생 시간"))
        c3.addView(slider("sec", 120, 25) { if (it == 0) "끝까지 재생" else "${it}초" })
        c3.addView(label("구장 울림 효과", 16))
        c3.addView(slider("reverb", 100, 50) { if (it == 0) "끄기" else "$it%" })
        c3.addView(label("끝날 때 페이드아웃", 16))
        c3.addView(slider("fade", 5, 2) { if (it == 0) "끄기" else "${it}초" })
        c3.addView(TextView(this).apply {
            text = "컴프야 구장 응원처럼 소리가 넓은 야구장에서 울려요. 기기와 상관없이 동작하고, 클수록 더 크게 울려요."
            textSize = 12f; setTextColor(cSub); setPadding(0, dp(8), 0, 0)
        })
        root.addView(c3)

        // 시작 버튼
        toggle = btn("", cPrimary, true).apply {
            textSize = 17f; minHeight = dp(58); minimumHeight = dp(58)
            setOnClickListener { onToggle() }
            layoutParams = lp(20)
        }
        root.addView(toggle)
        root.addView(TextView(this).apply {
            text = "시작 후 게임을 켜세요. 알림창에 PB(●보임 ○안보임) · 공격 · 타자 · 상태가 표시돼요."
            textSize = 12f; setTextColor(cSub); gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), 0)
        }, lp())

        refreshList()
        updateToggle()
    }

    override fun onResume() { super.onResume(); updateToggle() }
    override fun onPause() { stopPreview(); super.onPause() }

    private fun label(t: String, top: Int = 12) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(cSub)
        layoutParams = lp(top)
    }

    private fun slider(key: String, max: Int, def: Int, fmt: (Int) -> String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(4) }
        val tv = TextView(this).apply { textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(cAccent) }
        val cur = Store.int(this, key, def)
        tv.text = fmt(cur)
        val sb = SeekBar(this).apply {
            this.max = max; progress = cur
            progressTintList = ColorStateList.valueOf(cPrimary)
            thumbTintList = ColorStateList.valueOf(cPrimary)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    tv.text = fmt(p); Store.putInt(this@MainActivity, key, p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(tv); box.addView(sb)
        return box
    }

    // ── 선수 추가 / 수정 ──
    private fun pickNew(kind: String) {
        if (nameInput.text.isBlank()) { toast("먼저 선수 이름을 입력하세요"); return }
        pendingName = null
        pendingKind = kind
        pickAudio.launch(arrayOf("audio/*"))
    }

    private fun pickFor(p: Player, kind: String) {
        pendingName = p.name
        pendingKind = kind
        pickAudio.launch(arrayOf("audio/*"))
    }

    private fun updatePlayer(name: String, f: (Player) -> Player) {
        val l = Store.players(this)
        val i = l.indexOfFirst { it.name == name }
        if (i >= 0) { l[i] = f(l[i]); Store.savePlayers(this, l); refreshList() }
    }

    private fun showPlayerDialog(p: Player) {
        val acts = ArrayList<Act>()
        acts.add(Act("🎵 응원가 변경") { pickFor(p, "cheer") })
        acts.add(Act("🎤 등장곡 변경") { pickFor(p, "walk") })
        if (p.cheer != null) acts.add(Act("▶ 응원가 미리듣기") { preview(p.cheer) })
        if (p.walk != null) acts.add(Act("▶ 등장곡 미리듣기") { preview(p.walk) })
        if (p.walk != null) acts.add(Act("등장곡 지우기") { updatePlayer(p.name) { it.copy(walk = null) } })
        acts.add(Act("✏️ 이름 바꾸기") { rename(p) })
        acts.add(Act("🗑 선수 삭제") {
            AlertDialog.Builder(this).setTitle("${p.name} 삭제")
                .setMessage("이 선수의 응원가·등장곡 설정이 지워져요.")
                .setPositiveButton("삭제") { _, _ ->
                    val l = Store.players(this); l.removeAll { it.name == p.name }
                    Store.savePlayers(this, l); refreshList()
                }
                .setNegativeButton("취소", null).show()
        })
        AlertDialog.Builder(this).setTitle(p.name)
            .setItems(acts.map { it.label }.toTypedArray()) { _, i -> acts[i].run() }
            .setNegativeButton("닫기", null).show()
    }

    private fun rename(p: Player) {
        val et = edit("새 이름").apply { setText(p.name) }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(et)
        }
        AlertDialog.Builder(this).setTitle("이름 바꾸기").setView(wrap)
            .setPositiveButton("확인") { _, _ ->
                val n = et.text.toString().trim()
                val l = Store.players(this)
                when {
                    n.isEmpty() -> {}
                    l.any { it.name == n && it.name != p.name } -> toast("이미 있는 이름이에요")
                    else -> { updatePlayer(p.name) { it.copy(name = n) } }
                }
            }
            .setNegativeButton("취소", null).show()
    }

    // ── 미리듣기 ──
    private fun preview(uri: String) {
        stopPreview()
        val p = StadiumPlayer(this, Uri.parse(uri), Store.int(this, "reverb", 50))
        previewPlayer = p
        p.start()
        toast("미리듣기 (20초 뒤 자동으로 줄어들며 멈춰요)")
        listBox.postDelayed({
            if (previewPlayer === p) { p.fadeOutAndStop(1500); previewPlayer = null }
        }, 20000)
    }

    private fun stopPreview() {
        previewPlayer?.fadeOutAndStop(300)
        previewPlayer = null
    }

    // ── 목록 ──
    private fun refreshList() {
        listBox.removeAllViews()
        val players = Store.players(this)
        if (players.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "아직 등록된 선수가 없어요"; textSize = 13f; setTextColor(cSub)
                gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(6))
            })
            return
        }
        players.forEachIndexed { idx, p ->
            if (idx > 0) listBox.addView(View(this).apply { setBackgroundColor(cLine) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                setOnClickListener { showPlayerDialog(p) }
            }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(TextView(this).apply {
                text = p.name; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(cText)
            })
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            chips.addView(chip("응원가 " + if (p.cheer != null) "✓" else "없음", p.cheer != null))
            chips.addView(chip("등장곡 " + if (p.walk != null) "✓" else "없음", p.walk != null),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { leftMargin = dp(6) })
            left.addView(chips)
            row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "수정 ›"; textSize = 14f; setTextColor(cPrimary)
                setPadding(dp(12), dp(8), dp(4), dp(8))
            })
            listBox.addView(row)
        }
    }

    // ── 실행 ──
    private fun onToggle() {
        if (CheerService.running) {
            startService(Intent(this, CheerService::class.java).setAction(CheerService.ACTION_STOP))
            toggle.postDelayed({ updateToggle() }, 500)
        } else if (Store.players(this).isEmpty()) {
            toast("선수를 한 명 이상 등록하세요")
        } else if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startCapture()
    }

    private fun startCapture() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capture.launch(mgr.createScreenCaptureIntent())
    }

    private fun updateToggle() {
        val on = CheerService.running
        toggle.text = if (on) "■  중지" else "▶  시작"
        toggle.background = round(if (on) cRed else cPrimary, 14)
        statusPill.text = if (on) "● 실행 중" else "○ 대기"
        statusPill.setTextColor(if (on) Color.parseColor("#15803D") else cSub)
        statusPill.background = round(if (on) Color.parseColor("#DCFCE7") else Color.parseColor("#E5E7EB"), 20)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
