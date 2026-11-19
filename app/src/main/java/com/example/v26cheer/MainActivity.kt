package com.example.v26cheer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private lateinit var listBox: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var toggle: Button

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val name = nameInput.text.toString().trim()
        if (uri == null || name.isEmpty()) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val list = Store.songs(this)
        list.removeAll { it.name == name }
        list.add(Song(name, uri.toString()))
        Store.saveSongs(this, list)
        nameInput.setText("")
        refreshList()
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
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(TextView(this).apply { text = "V26 응원가"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "게임 화면에서 타자 이름을 읽어, 등록한 선수의 응원가를 자동 재생합니다."
            setPadding(0, dp(4), 0, dp(12))
        })

        // 1) 선수 등록
        root.addView(section("1. 선수 / 응원가 등록"))
        nameInput = EditText(this).apply { hint = "선수 이름 (게임에 표시되는 그대로)"; isSingleLine = true }
        root.addView(nameInput)
        root.addView(Button(this).apply {
            text = "응원가 파일 선택해서 추가"
            setOnClickListener {
                if (nameInput.text.isBlank()) toast("먼저 선수 이름을 입력하세요")
                else pickAudio.launch(arrayOf("audio/*"))
            }
        })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        // 2) 인식 영역
        root.addView(section("2. 이름이 표시되는 화면 영역 (가로 화면 기준, %)"))
        root.addView(slider("왼쪽 위치", "l", 100, 0))
        root.addView(slider("위쪽 위치", "t", 100, 60))
        root.addView(slider("너비", "w", 100, 50))
        root.addView(slider("높이", "h", 100, 40))
        root.addView(slider("재생 시간(초, 0=끝까지)", "sec", 120, 25))
        root.addView(TextView(this).apply {
            text = "시작 후 알림창에 '인식: …'으로 읽은 글자가 표시됩니다. 타자 이름이 거기 나오도록 영역을 조절하세요."
            textSize = 12f
        })

        // 3) 시작
        root.addView(section("3. 실행"))
        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        root.addView(toggle)

        refreshList()
        updateToggle()
    }

    override fun onResume() { super.onResume(); updateToggle() }

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
        Store.songs(this).forEach { s ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply { text = "♪ ${s.name}" },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "삭제"
                setOnClickListener {
                    val l = Store.songs(this@MainActivity); l.removeAll { it.name == s.name }
                    Store.saveSongs(this@MainActivity, l); refreshList()
                }
            })
            listBox.addView(row)
        }
    }

    private fun onToggle() {
        if (CheerService.running) {
            startService(Intent(this, CheerService::class.java).setAction(CheerService.ACTION_STOP))
            toggle.postDelayed({ updateToggle() }, 500)
        } else if (Store.songs(this).isEmpty()) {
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
