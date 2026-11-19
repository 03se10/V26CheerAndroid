package com.example.v26cheer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private lateinit var listBox: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var toggle: Button
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
        toast(if (pendingKind == "cheer") "응원가 등록됨" else "등장곡 등록됨")
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(24), dp(16), dp(16)) }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply { text = "V26 응원가"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "PLAY BALL이 보이면 타자를 인식해 등장곡을, PLAY BALL이 사라지면 응원가를 재생합니다. (세로 화면 기준)"
            setPadding(0, dp(4), 0, dp(12))
        })

        // 1) 내 팀
        root.addView(section("1. 내 팀 이름"))
        root.addView(EditText(this).apply {
            hint = "예: 한화 (비우면 양쪽 공격 모두 동작)"
            isSingleLine = true
            setText(Store.str(this@MainActivity, "team"))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { Store.putStr(this@MainActivity, "team", s.toString()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })

        // 2) 선수 등록
        root.addView(section("2. 선수 등록 (이름 입력 후 음원 선택)"))
        nameInput = EditText(this).apply { hint = "선수 이름 (예: 허인서)"; isSingleLine = true }
        root.addView(nameInput)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Button(this).apply {
            text = "응원가 선택"
            setOnClickListener { pick("cheer") }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(Button(this).apply {
            text = "등장곡 선택(선택)"
            setOnClickListener { pick("walk") }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btnRow)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        // 3) 재생 시간
        root.addView(section("3. 응원가 재생 시간"))
        root.addView(slider("재생 시간(초, 0=끝까지)", "sec", 120, 25))

        // 4) 실행
        root.addView(section("4. 실행"))
        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        root.addView(toggle)
        root.addView(TextView(this).apply {
            text = "시작 후 알림창에 PB(●보임/○안보임) | 공격 | 타자 | 상태가 표시됩니다."
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        })

        refreshList()
        updateToggle()
    }

    override fun onResume() { super.onResume(); updateToggle() }

    private fun pick(kind: String) {
        if (nameInput.text.isBlank()) { toast("먼저 선수 이름을 입력하세요"); return }
        pendingKind = kind
        pickAudio.launch(arrayOf("audio/*"))
    }

    private fun section(t: String) = TextView(this).apply {
        text = t; textSize = 16f; setPadding(0, dp(20), 0, dp(6))
    }

    private fun slider(label: String, key: String, max: Int, def: Int): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tv = TextView(this)
        val cur = Store.int(this, key, def)
        tv.text = "$label: $cur"
        val sb = SeekBar(this).apply {
            this.max = max; progress = cur
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    tv.text = "$label: $p"; Store.putInt(this@MainActivity, key, p)
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
        Store.players(this).forEach { p ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val info = "♪ ${p.name}\n   응원가 ${if (p.cheer != null) "✓" else "✗"}   등장곡 ${if (p.walk != null) "✓" else "✗"}"
            row.addView(TextView(this).apply { text = info },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "삭제"
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

    private fun updateToggle() { toggle.text = if (CheerService.running) "중지" else "시작 (화면 캡처 허용 필요)" }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
