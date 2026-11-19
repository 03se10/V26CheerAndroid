package com.example.v26cheer

import android.Manifest
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
import android.view.WindowInsetsController
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    // ── 색상 ──
    private val cBg = Color.parseColor("#F4F6FA")
    private val cCard = Color.WHITE
    private val cPrimary = Color.parseColor("#1F3A8A")
    private val cAccent = Color.parseColor("#F97316")
    private val cText = Color.parseColor("#111827")
    private val cSub = Color.parseColor("#6B7280")
    private val cLine = Color.parseColor("#E5E7EB")
    private val cGreen = Color.parseColor("#16A34A")
    private val cRed = Color.parseColor("#DC2626")

    private lateinit var listBox: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var toggle: Button
    private lateinit var statusPill: TextView
    private var pendingKind = "cheer"   // "cheer" 또는 "walk"

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val name = nameInput.text.toString().trim()
        if (uri == null || name.isEmpty()) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val list = Store.players(this)
        val old = list.firstOrNull { it.name == name }
        list.removeAll { it.name == name }
        val s = uri.toString()
        list.add(
            if (pendingKind == "cheer") Player(name, s, old?.walk)
            else Player(name, old?.cheer, s)
        )
        Store.savePlayers(this, list)
        refreshList()
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
        window.statusBarColor = cBg
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(cBg); isFillViewport = true; addView(root) })

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
        btnRow.addView(btn("🎵 응원가", cPrimary, true).apply { setOnClickListener { pick("cheer") } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        btnRow.addView(btn("🎤 등장곡 (선택)", cPrimary, false).apply { setOnClickListener { pick("walk") } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        c2.addView(btnRow)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(8) }
        c2.addView(listBox)
        root.addView(c2)

        // 3. 재생 시간
        val c3 = card()
        c3.addView(title("3. 응원가 재생 시간"))
        c3.addView(slider("sec", 120, 25))
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

    private fun pick(kind: String) {
        if (nameInput.text.isBlank()) { toast("먼저 선수 이름을 입력하세요"); return }
        pendingKind = kind
        pickAudio.launch(arrayOf("audio/*"))
    }

    private fun slider(key: String, max: Int, def: Int): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(10) }
        val tv = TextView(this).apply { textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(cAccent) }
        fun fmt(v: Int) = if (v == 0) "끝까지 재생" else "${v}초"
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
                text = "삭제"; textSize = 14f; setTextColor(cRed)
                setPadding(dp(12), dp(8), dp(4), dp(8))
                setOnClickListener {
                    val l = Store.players(this@MainActivity); l.removeAll { it.name == p.name }
                    Store.savePlayers(this@MainActivity, l); refreshList()
                }
            })
            listBox.addView(row)
        }
    }

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
