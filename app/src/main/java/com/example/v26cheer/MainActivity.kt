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
import android.os.Handler
import android.os.Looper
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

    // ── 화면 요소 ──
    private lateinit var content: FrameLayout
    private val tabViews = ArrayList<View>()
    private val navItems = ArrayList<TextView>()
    private lateinit var listBox: LinearLayout
    private lateinit var teamListBox: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var toggle: Button
    private lateinit var statusPill: TextView
    private lateinit var liveText: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            liveText.text = Live.text.ifEmpty { if (CheerService.running) "준비 중…" else "실행 전이에요. ▶ 시작을 눌러주세요." }
            updateToggle()
            ui.postDelayed(this, 1000)
        }
    }

    // ── 음원 선택 상태 ──
    private val MODE_PLAYER = 0
    private val MODE_TEAM_NEW = 1
    private val MODE_TEAM_CHANGE = 2
    private var pickMode = MODE_PLAYER
    private var pendingKind = "cheer"          // 선수: "cheer" 또는 "walk"
    private var pendingName: String? = null    // 기존 선수 수정 중이면 그 선수 이름
    private var pendingTeamName = ""
    private var pendingTeamId = 0L

    private var previewPlayer: StadiumPlayer? = null

    private class Act(val label: String, val run: () -> Unit)

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val mode = pickMode
        pickMode = MODE_PLAYER
        if (uri == null) { pendingName = null; return@registerForActivityResult }
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val s = uri.toString()
        when (mode) {
            MODE_TEAM_NEW -> {
                val ts = TeamSong(System.currentTimeMillis(), pendingTeamName.ifBlank { "팀 응원가" }, s, true, 8, false, 0, 0, 40)
                val l = Store.teamSongs(this); l.add(ts); Store.saveTeamSongs(this, l)
                refreshTeamList()
                toast("등록됐어요. 재생 조건을 확인해 주세요")
                showConditionDialog(ts)
            }
            MODE_TEAM_CHANGE -> {
                updateTeam(pendingTeamId) { it.copy(uri = s) }
                toast("음원이 바뀌었어요")
            }
            else -> {
                val name = pendingName ?: nameInput.text.toString().trim()
                val editing = pendingName != null
                pendingName = null
                if (name.isEmpty()) return@registerForActivityResult
                val list = Store.players(this)
                val idx = list.indexOfFirst { it.name == name }
                val old = if (idx >= 0) list[idx] else null
                val np = if (pendingKind == "cheer") Player(name, s, old?.walk) else Player(name, old?.cheer, s)
                if (idx >= 0) list[idx] = np else list.add(np)
                Store.savePlayers(this, list)
                refreshList()
                if (!editing) nameInput.setText("")
                toast(if (pendingKind == "cheer") "응원가가 등록됐어요" else "등장곡이 등록됐어요")
            }
        }
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

    private fun note(t: String, top: Int = 8) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(cSub); layoutParams = lp(top)
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
        minHeight = dp(46); minimumHeight = dp(46)
        if (filled) { background = round(color, 12); setTextColor(Color.WHITE) }
        else { background = round(Color.WHITE, 12, color); setTextColor(color) }
    }

    private fun chip(text: String, on: Boolean) = TextView(this).apply {
        this.text = text; textSize = 12f
        setTextColor(if (on) Color.parseColor("#15803D") else Color.parseColor("#9CA3AF"))
        background = round(if (on) Color.parseColor("#DCFCE7") else Color.parseColor("#F3F4F6"), 20)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    private fun pageTitle(t: String, sub: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = t; textSize = 26f; setTypeface(typeface, Typeface.BOLD); setTextColor(cPrimary)
        })
        addView(TextView(this@MainActivity).apply {
            text = sub; textSize = 13f; setTextColor(cSub); setPadding(0, dp(2), 0, 0)
        })
        layoutParams = lp()
    }

    private fun page(build: (LinearLayout) -> Unit): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }
        build(col)
        return ScrollView(this).apply { setBackgroundColor(cBg); isFillViewport = true; addView(col) }
    }

    // ── 전체 화면 ──
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        content = FrameLayout(this)
        tabViews.add(page { buildHome(it) })
        tabViews.add(page { buildLineup(it) })
        tabViews.add(page { buildTeamTab(it) })
        tabViews.add(page { buildSound(it) })
        tabViews.forEach { content.addView(it, FrameLayout.LayoutParams(-1, -1)) }

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
        }
        listOf("🏠" to "홈", "👥" to "라인업", "📣" to "팀응원가", "🔊" to "소리").forEachIndexed { i, (icon, label) ->
            val tv = TextView(this).apply {
                text = "$icon\n$label"; textSize = 12f; gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { showTab(i) }
            }
            nav.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            navItems.add(tv)
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg) }
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(nav, lp())
        setContentView(root)

        runCatching {
            window.statusBarColor = cBg
            window.navigationBarColor = Color.WHITE
            val c = WindowCompat.getInsetsController(window, window.decorView)
            c.isAppearanceLightStatusBars = true
            c.isAppearanceLightNavigationBars = true
        }

        refreshList()
        refreshTeamList()
        showTab(0)
        updateToggle()
    }

    override fun onResume() { super.onResume(); ui.post(poll) }
    override fun onPause() { ui.removeCallbacks(poll); stopPreview(); super.onPause() }

    private fun showTab(i: Int) {
        tabViews.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        navItems.forEachIndexed { idx, tv ->
            tv.setTextColor(if (idx == i) cPrimary else Color.parseColor("#9CA3AF"))
            tv.setTypeface(null, if (idx == i) Typeface.BOLD else Typeface.NORMAL)
        }
        if (i == 1) refreshList()
        if (i == 2) refreshTeamList()
    }

    // ═════════════════════ 홈 ═════════════════════
    private fun buildHome(col: LinearLayout) {
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
        col.addView(header, lp())

        val c1 = card()
        c1.addView(title("내 팀", "공격팀이 내 팀일 때만 선수 응원가가 나와요. 비워두면 양쪽 모두 동작해요."))
        c1.addView(edit("예: 한화").apply {
            setText(Store.str(this@MainActivity, "team"))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { Store.putStr(this@MainActivity, "team", s.toString()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
        col.addView(c1)

        val c2 = card()
        c2.addView(title("실행"))
        toggle = btn("", cPrimary, true).apply {
            textSize = 17f; minHeight = dp(56); minimumHeight = dp(56)
            setOnClickListener { onToggle() }
            layoutParams = lp(12)
        }
        c2.addView(toggle)
        c2.addView(note("시작을 누르고 화면 캡처를 허용한 뒤 컴프야를 켜세요."))
        col.addView(c2)

        val c3 = card()
        c3.addView(title("실시간 상태", "PB(●PLAY BALL 보임 ○안 보임) · 이닝 · 공격 · 타자 · 동작"))
        liveText = TextView(this).apply {
            textSize = 13f; setTextColor(cText)
            background = round(Color.parseColor("#F9FAFB"), 12, cLine)
            setPadding(dp(14), dp(12), dp(14), dp(12)); layoutParams = lp(12)
        }
        c3.addView(liveText)
        col.addView(c3)

        val c4 = card()
        c4.addView(title("사용 순서"))
        c4.addView(note("1. 라인업 탭에서 선수 이름과 응원가·등장곡을 등록해요.", 10))
        c4.addView(note("2. 팀응원가 탭에서 이닝별 팀 응원가를 등록하고 조건을 정해요.", 4))
        c4.addView(note("3. 소리 탭에서 구장 울림과 페이드를 맞춰요.", 4))
        c4.addView(note("4. 홈에서 시작을 누르고 컴프야를 켜세요.", 4))
        col.addView(c4)
    }

    // ═════════════════════ 라인업 ═════════════════════
    private fun buildLineup(col: LinearLayout) {
        col.addView(pageTitle("👥 라인업", "선수별 응원가와 등장곡을 등록해요"))

        val c2 = card()
        c2.addView(title("선수 등록", "이름은 '26 같은 표시 없이 선수 이름만 적어주세요."))
        nameInput = edit("선수 이름 (예: 허인서)")
        c2.addView(nameInput)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(12) }
        btnRow.addView(btn("🎵 응원가", cPrimary, true).apply { setOnClickListener { pickNew("cheer") } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        btnRow.addView(btn("🎤 등장곡 (선택)", cPrimary, false).apply { setOnClickListener { pickNew("walk") } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        c2.addView(btnRow)
        c2.addView(note("등록한 선수를 누르면 응원가·등장곡을 바꾸거나 미리 들어볼 수 있어요.", 12))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(4) }
        c2.addView(listBox)
        col.addView(c2)
    }

    private fun pickNew(kind: String) {
        if (nameInput.text.isBlank()) { toast("먼저 선수 이름을 입력하세요"); return }
        pendingName = null
        pendingKind = kind
        pickMode = MODE_PLAYER
        pickAudio.launch(arrayOf("audio/*"))
    }

    private fun pickFor(p: Player, kind: String) {
        pendingName = p.name
        pendingKind = kind
        pickMode = MODE_PLAYER
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
                    else -> updatePlayer(p.name) { it.copy(name = n) }
                }
            }
            .setNegativeButton("취소", null).show()
    }

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

    // ═════════════════════ 팀응원가 ═════════════════════
    private fun buildTeamTab(col: LinearLayout) {
        col.addView(pageTitle("📣 팀응원가", "이닝에 맞춰 팀 응원가를 자동으로 틀어요"))

        val c1 = card()
        c1.addView(title("팀응원가 사용"))
        val sw = Switch(this).apply {
            text = "팀응원가 자동 재생"; textSize = 15f
            isChecked = Store.bool(this@MainActivity, "teamOn", true)
            setOnCheckedChangeListener { _, on -> Store.putBool(this@MainActivity, "teamOn", on) }
            layoutParams = lp(10)
        }
        c1.addView(sw)
        c1.addView(note("팀 응원가가 나오는 동안에는 선수 응원가·등장곡이 나오지 않아요. 팀 응원가가 끝난 뒤 다음 타석부터 다시 나와요."))
        c1.addView(note("이닝 인식은 화면 왼쪽 위 스코어보드의 이닝 숫자와, PLAY BALL 때 나오는 해설(예: \"8회 초\")을 읽어서 해요."))
        col.addView(c1)

        val c2 = card()
        c2.addView(title("팀응원가 목록"))
        c2.addView(btn("＋ 팀응원가 추가", cPrimary, true).apply {
            layoutParams = lp(12)
            setOnClickListener { askTeamName() }
        })
        teamListBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(4) }
        c2.addView(teamListBox)
        col.addView(c2)
    }

    private fun askTeamName() {
        val et = edit("예: 8회 팀 응원가")
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(et)
        }
        AlertDialog.Builder(this).setTitle("팀응원가 이름").setView(wrap)
            .setPositiveButton("음원 선택") { _, _ ->
                pendingTeamName = et.text.toString().trim()
                pickMode = MODE_TEAM_NEW
                pickAudio.launch(arrayOf("audio/*"))
            }
            .setNegativeButton("취소", null).show()
    }

    private fun updateTeam(id: Long, f: (TeamSong) -> TeamSong) {
        val l = Store.teamSongs(this)
        val i = l.indexOfFirst { it.id == id }
        if (i >= 0) { l[i] = f(l[i]); Store.saveTeamSongs(this, l); refreshTeamList() }
    }

    private fun summary(ts: TeamSong): String {
        val inn = when {
            ts.inning == 0 -> "아무 이닝"
            ts.orMore -> "${ts.inning}회 이후 매 이닝"
            else -> "${ts.inning}회"
        }
        val half = when (ts.half) { 1 -> "초(원정 공격)"; 2 -> "말(홈 공격)"; else -> "초/말 상관없음" }
        val side = when (ts.side) { 1 -> "내 팀 공격 때"; 2 -> "내 팀 수비 때"; else -> "공격/수비 상관없음" }
        val len = if (ts.sec == 0) "끝까지 재생" else "${ts.sec}초"
        return "$inn · $half · $side · $len"
    }

    private fun refreshTeamList() {
        teamListBox.removeAllViews()
        val songs = Store.teamSongs(this)
        if (songs.isEmpty()) {
            teamListBox.addView(TextView(this).apply {
                text = "아직 등록된 팀응원가가 없어요"; textSize = 13f; setTextColor(cSub)
                gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(6))
            })
            return
        }
        songs.forEach { ts ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = round(Color.parseColor("#F9FAFB"), 14, cLine)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = lp(12)
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(this).apply {
                text = "📣 ${ts.name}"; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(cText)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(Switch(this).apply {
                isChecked = ts.enabled
                setOnCheckedChangeListener { _, on ->
                    val l = Store.teamSongs(this@MainActivity)
                    val i = l.indexOfFirst { it.id == ts.id }
                    if (i >= 0) { l[i] = l[i].copy(enabled = on); Store.saveTeamSongs(this@MainActivity, l) }
                }
            })
            box.addView(top)
            box.addView(TextView(this).apply {
                text = summary(ts); textSize = 12.5f; setTextColor(cSub); setPadding(0, dp(6), 0, dp(10))
            })
            val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r1.addView(btn("조건 설정", cPrimary, true).apply { setOnClickListener { showConditionDialog(ts) } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            r1.addView(btn("음원 변경", cPrimary, false).apply {
                setOnClickListener {
                    pendingTeamId = ts.id; pickMode = MODE_TEAM_CHANGE
                    pickAudio.launch(arrayOf("audio/*"))
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
            box.addView(r1)
            val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(8) }
            r2.addView(btn("▶ 미리듣기", cPrimary, false).apply {
                setOnClickListener { ts.uri?.let { preview(it) } ?: toast("음원이 없어요") }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            r2.addView(btn("삭제", cRed, false).apply {
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("${ts.name} 삭제")
                        .setPositiveButton("삭제") { _, _ ->
                            val l = Store.teamSongs(this@MainActivity); l.removeAll { it.id == ts.id }
                            Store.saveTeamSongs(this@MainActivity, l); refreshTeamList()
                        }
                        .setNegativeButton("취소", null).show()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
            box.addView(r2)
            teamListBox.addView(box)
        }
    }

    private fun spinner(items: List<String>, selected: Int): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(selected.coerceIn(0, items.size - 1))
        layoutParams = lp(4)
    }

    private fun showConditionDialog(ts: TeamSong) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun label(t: String, top: Int = 14) = TextView(this).apply {
            text = t; textSize = 13f; setTextColor(cSub); layoutParams = lp(top)
        }
        box.addView(label("이닝", 0))
        val innSp = spinner(listOf("아무 이닝") + (1..12).map { "${it}회" }, ts.inning)
        box.addView(innSp)
        val more = CheckBox(this).apply { text = "그 이닝 이후 매 이닝마다 재생"; isChecked = ts.orMore; textSize = 14f }
        box.addView(more)
        box.addView(label("초 / 말"))
        val halfSp = spinner(listOf("초/말 상관없음", "초 (원정팀 공격)", "말 (홈팀 공격)"), ts.half)
        box.addView(halfSp)
        box.addView(label("공격 / 수비"))
        val sideSp = spinner(listOf("공격/수비 상관없음", "내 팀 공격 때", "내 팀 수비 때"), ts.side)
        box.addView(sideSp)
        box.addView(TextView(this).apply {
            text = "공격/수비 조건은 홈 탭에 내 팀 이름을 입력해야 동작해요."
            textSize = 11.5f; setTextColor(cSub); setPadding(0, dp(4), 0, 0)
        })
        val secTv = label("재생 시간")
        box.addView(secTv)
        fun fmt(v: Int) = if (v == 0) "재생 시간: 끝까지" else "재생 시간: ${v}초"
        secTv.text = fmt(ts.sec)
        val secSb = SeekBar(this).apply {
            max = 120; progress = ts.sec
            progressTintList = ColorStateList.valueOf(cPrimary)
            thumbTintList = ColorStateList.valueOf(cPrimary)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { secTv.text = fmt(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(secSb)
        AlertDialog.Builder(this).setTitle("${ts.name} – 재생 조건")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("저장") { _, _ ->
                updateTeam(ts.id) {
                    it.copy(
                        inning = innSp.selectedItemPosition,
                        orMore = more.isChecked,
                        half = halfSp.selectedItemPosition,
                        side = sideSp.selectedItemPosition,
                        sec = secSb.progress
                    )
                }
            }
            .setNegativeButton("취소", null).show()
    }

    // ═════════════════════ 소리 ═════════════════════
    private fun buildSound(col: LinearLayout) {
        col.addView(pageTitle("🔊 소리", "울림과 페이드를 맞춰요"))

        val c = card()
        c.addView(title("선수 응원가 재생 시간"))
        c.addView(slider("sec", 120, 25) { if (it == 0) "끝까지 재생" else "${it}초" })
        c.addView(note("PLAY BALL이 사라진 뒤 선수 응원가가 나오는 길이예요."))
        col.addView(c)

        val c2 = card()
        c2.addView(title("구장 울림 효과"))
        c2.addView(slider("reverb", 100, 50) { if (it == 0) "끄기" else "$it%" })
        c2.addView(note("컴프야 구장 응원처럼 소리가 넓은 야구장에서 울려요. 기기와 상관없이 동작하고, 클수록 더 크게 울려요. 선수·팀 응원가 모두에 적용돼요."))
        col.addView(c2)

        val c3 = card()
        c3.addView(title("끝날 때 페이드아웃"))
        c3.addView(slider("fade", 5, 2) { if (it == 0) "끄기" else "${it}초" })
        c3.addView(note("재생 시간이 끝나기 전에 소리가 서서히 줄어들어요. 곡이 바뀔 때도 앞 곡이 부드럽게 줄어들며 이어져요."))
        col.addView(c3)
    }

    private fun slider(key: String, max: Int, def: Int, fmt: (Int) -> String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(10) }
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

    // ── 미리듣기 ──
    private fun preview(uri: String) {
        stopPreview()
        val p = StadiumPlayer(this, Uri.parse(uri), Store.int(this, "reverb", 50))
        previewPlayer = p
        p.start()
        toast("미리듣기 (20초 뒤 자동으로 줄어들며 멈춰요)")
        content.postDelayed({
            if (previewPlayer === p) { p.fadeOutAndStop(1500); previewPlayer = null }
        }, 20000)
    }

    private fun stopPreview() {
        previewPlayer?.fadeOutAndStop(300)
        previewPlayer = null
    }

    // ── 실행 ──
    private fun onToggle() {
        if (CheerService.running) {
            startService(Intent(this, CheerService::class.java).setAction(CheerService.ACTION_STOP))
            toggle.postDelayed({ updateToggle() }, 500)
        } else if (Store.players(this).isEmpty() && Store.teamSongs(this).none { it.enabled && it.uri != null }) {
            toast("선수 또는 팀응원가를 하나 이상 등록하세요")
        } else if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startCapture()
    }

    private fun startCapture() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capture.launch(mgr.createScreenCaptureIntent())
    }

    private fun updateToggle() {
        if (!::toggle.isInitialized) return
        val on = CheerService.running
        toggle.text = if (on) "■  중지" else "▶  시작"
        toggle.background = round(if (on) cRed else cPrimary, 14)
        statusPill.text = if (on) "● 실행 중" else "○ 대기"
        statusPill.setTextColor(if (on) Color.parseColor("#15803D") else cSub)
        statusPill.background = round(if (on) Color.parseColor("#DCFCE7") else Color.parseColor("#E5E7EB"), 20)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
