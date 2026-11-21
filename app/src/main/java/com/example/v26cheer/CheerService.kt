package com.example.v26cheer

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.ImageReader
import android.media.ToneGenerator
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

class CheerService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_SHOT = "shot"   // 영역 지정용 화면 저장
        const val ACTION_NEXT = "next"   // 다음 타자로 수동 넘기기
        const val ORDER_DELAY = 2500L    // 아웃 후 다음 타자 등장곡을 시작하기까지 (공수교대 확인)
        const val CH = "cheer"
        const val SCAN_MS = 500L
        const val INNING_MS = 1200L
        var running = false
    }

    private enum class Phase { IDLE, WALKUP, CHEER }

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var curW = 0
    private var curH = 0
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    // 재생기
    private var player: StadiumPlayer? = null        // 선수 응원가/등장곡
    private var teamPlayer: StadiumPlayer? = null    // 팀 응원가
    private val all = java.util.concurrent.CopyOnWriteArrayList<StadiumPlayer>()
    private var limitRunnable: Runnable? = null

    // 선수 응원가 상태
    private var pbStable = false      // PLAY BALL 버튼이 보이는 상태
    private var flipCount = 0
    private var identified = false    // 이번 PLAY BALL 구간에서 타자 인식 완료
    private var idFails = 0
    private var busy = false
    private var current: Player? = null
    private var phase = Phase.IDLE

    // 이닝 / 팀 응원가 상태
    private var lastInningAt = 0L
    private var inningBusy = false
    private var introBusy = false          // 선수 소개바 인식 중
    private var lastIntroAt = 0L
    private var introName: String? = null  // 지금 보이는 소개바의 선수 (없으면 null)
    private var introMiss = 0

    // 타순 / 아웃 인식
    private var resultBusy = false
    private var lastResultAt = 0L
    private var resultMiss = 0
    private var resultVisible = false      // 아웃 배너가 떠 있는 동안 true (한 번만 반응)
    private var lastAdvanceAt = 0L
    private var lastOffRow = 0             // 마지막으로 확인한 공격 줄 (1 위, 2 아래)
    private var offCand = 0
    private var offCandCount = 0
    private var mineBusy = false
    private var walkRunnable: Runnable? = null
    @Volatile private var shotAt = 0L          // 이 시각이 되면 화면을 저장
    private var regionsVer = -1
    private var wantComment = 0       // 해설 바("1회 말")를 읽어볼 남은 횟수
    private var candKey = 0
    private var candCount = 0
    private var stableKey = 0
    private var inning = 0
    private var half = 0              // 1=초, 2=말
    private val rowMine = arrayOfNulls<Boolean>(3)   // [1]=위 줄, [2]=아래 줄 팀이 내 팀인지
    private val rowMineAt = LongArray(3)

    // 알림/홈 화면 표시용
    private var dbgOff = "-"
    private var dbgBatter = "-"
    private var dbgInning = "-"
    private var dbgMsg = "대기 중"
    private var lastShown = ""

    private val recognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_NEXT) {
            if (running && ::handler.isInitialized) handler.post { manualNext() }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SHOT) {
            if (running) {
                val delay = intent?.getIntExtra("delay", 4000) ?: 4000
                shotAt = SystemClock.elapsedRealtime() + delay
                dbgMsg = "📸 ${delay / 1000}초 뒤 화면 저장 – 알림창을 닫고 게임 화면을 보여주세요"
                refresh()
            }
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        Regions.load(this)
        createChannel()
        startForeground(1, notif("PLAY BALL 화면을 기다리는 중…"))

        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        thread = HandlerThread("cheer-scan").also { it.start() }
        handler = Handler(thread.looper)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        running = true
        handler.post(loop)
        return START_NOT_STICKY
    }

    private val loop = object : Runnable {
        override fun run() {
            try { scan() } catch (e: Exception) { busy = false }
            handler.postDelayed(this, SCAN_MS)
        }
    }

    @Suppress("DEPRECATION")
    private fun ensureDisplay() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics(); d.getRealMetrics(m)
        if (m.widthPixels == curW && m.heightPixels == curH && vd != null) return
        curW = m.widthPixels; curH = m.heightPixels
        reader?.close()
        reader = ImageReader.newInstance(curW, curH, PixelFormat.RGBA_8888, 2)
        if (vd == null) {
            vd = projection?.createVirtualDisplay(
                "cheer", curW, curH, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler
            )
        } else {
            vd?.resize(curW, curH, m.densityDpi)
            vd?.surface = reader!!.surface
        }
    }

    // ───────────────────────── 스캔 ─────────────────────────

    private fun scan() {
        ensureDisplay()
        if (busy) return
        val img = reader?.acquireLatestImage() ?: return
        val frame: Bitmap
        try {
            val plane = img.planes[0]
            val pad = (plane.rowStride - plane.pixelStride * img.width) / plane.pixelStride
            val raw = Bitmap.createBitmap(img.width + pad, img.height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(plane.buffer)
            frame = if (pad > 0) {
                Bitmap.createBitmap(raw, 0, 0, img.width, img.height).also { raw.recycle() }
            } else raw
        } finally { img.close() }

        try {
            val ver = Store.int(this, "calibVer", 0)
            if (ver != regionsVer) { Regions.load(this); regionsVer = ver }
            if (shotAt != 0L && SystemClock.elapsedRealtime() >= shotAt) { shotAt = 0L; saveShot(frame) }
            process(frame)
        } finally { frame.recycle() }
    }

    /** 영역 지정용으로 지금 게임 화면을 저장 */
    private fun saveShot(f: Bitmap) {
        try {
            java.io.File(filesDir, "calib.png").outputStream().use { f.compress(Bitmap.CompressFormat.PNG, 100, it) }
            runCatching {
                val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                handler.postDelayed({ tg.release() }, 600)
            }
            dbgMsg = "📸 화면 저장됨 (${f.width}×${f.height}) – 앱에서 영역을 지정하세요"
        } catch (e: Exception) {
            dbgMsg = "화면 저장 실패"
        }
        refresh()
    }

    private fun process(f: Bitmap) {
        val pbNow = purpleFraction(f) >= Regions.pbThreshold
        val off = offenseRow(f)  // 0=없음, 1=위, 2=아래
        dbgOff = when (off) { 1 -> "위"; 2 -> "아래"; else -> "-" }

        // PLAY BALL 보임/사라짐 (깜빡임 방지: 2회 연속 같은 값일 때 확정)
        if (pbNow == pbStable) flipCount = 0
        else if (++flipCount >= 2) { pbStable = pbNow; flipCount = 0; onPlayBallChanged(pbNow) }

        // PLAY BALL이 보이는 동안 타자 인식 (구간당 1회 성공할 때까지)
        if (pbStable && !identified) {
            val myTeam = Store.str(this, "team").trim()
            val teamBmp = if (off != 0 && myTeam.isNotEmpty())
                binarize(crop(f, if (off == 1) Regions.TEAM_TOP else Regions.TEAM_BOT), 5) else null
            val batterBw = binarize(crop(f, Regions.BATTER), 3)
            val batterRaw = upscale(crop(f, Regions.BATTER), 3)
            busy = true
            identify(myTeam, teamBmp, batterBw, batterRaw)
        } else refresh()

        // 이닝 인식 (팀 응원가용)
        maybeSampleInning(f, off)

        // 선수 소개바 인식 (등장곡용)
        maybeSampleIntro(f, off)

        // 타순: 공격팀 변화 / 내 팀 확인 / 아웃 배너 인식
        trackOffense(off)
        updateMine(f, off)
        maybeSampleResult(f)
    }

    // ───────────────────────── PLAY BALL / 선수 응원가 ─────────────────────────

    private fun teamActive(): Boolean = teamPlayer?.finished == false

    private fun onPlayBallChanged(visible: Boolean) {
        if (visible) {
            identified = false; idFails = 0
            wantComment = 3
            dbgMsg = "PLAY BALL 감지"
        } else {
            // PLAY BALL 사라짐 → 응원가
            val p = current
            if (p != null && phase != Phase.CHEER) {
                phase = Phase.CHEER
                if (teamActive()) {
                    stopPlayer(transitionFade())
                    dbgMsg = "팀응원가 재생 중 – ${p.name} 응원가 건너뜀"
                } else if (p.cheer != null) {
                    play(p.cheer, Store.int(this, "sec", 25))
                    dbgMsg = "▶ ${p.name} 응원가"
                } else {
                    stopPlayer(transitionFade()); dbgMsg = "${p.name}: 응원가 없음"
                }
            }
        }
        refresh()
    }

    private fun identify(myTeam: String, team: Bitmap?, bw: Bitmap, raw: Bitmap) {
        fun batterStep(teamOk: Boolean) {
            if (!teamOk) { finishId(null, "", false); return }
            ocr(bw) { t1 ->
                val p1 = Matcher.find(t1, Store.players(this))
                if (p1 != null) finishId(p1, t1, true)
                else ocr(raw) { t2 -> finishId(Matcher.find(t2, Store.players(this)), t1 + " / " + t2, true) }
            }
        }
        if (team == null) { batterStep(true); return }
        ocr(team) { tt ->
            val t = Matcher.norm(tt)
            // 팀 이름을 못 읽으면(빈 값) 막지 않고 진행, 읽었는데 내 팀이 아니면 상대 공격으로 판단
            val ok = t.isEmpty() || t.contains(Matcher.norm(myTeam))
            dbgOff += "(${tt.trim().take(6)})"
            batterStep(ok)
        }
    }

    private fun finishId(p: Player?, text: String, teamOk: Boolean) {
        busy = false
        if (!teamOk) {
            identified = true
            if (current != null) { stopPlayer(transitionFade()); current = null; phase = Phase.IDLE }
            dbgMsg = "상대팀 공격 – 대기"
            refresh(); return
        }
        dbgBatter = text.replace("\n", " ").trim().take(20).ifEmpty { "-" }
        if (p != null) {
            identified = true; idFails = 0
            syncOrder(p)
            if (p.name != current?.name) {
                stopPlayer(transitionFade())
                current = p
                if (p.walk != null && !teamActive()) {
                    phase = Phase.WALKUP
                    play(p.walk, 60)
                    dbgMsg = "♬ ${p.name} 등장곡"
                } else {
                    phase = Phase.IDLE
                    dbgMsg = if (teamActive()) "팀응원가 재생 중 – ${p.name} 대기" else "${p.name} 인식 (등장곡 없음)"
                }
            } else dbgMsg = "${p.name} 인식"
        } else if (++idFails >= 3) {
            identified = true; idFails = 0
            // 소개바에서 이미 타자를 확인했다면 유지, 아니면 등록되지 않은 타자로 보고 정리
            if (introName == null) {
                stopPlayer(transitionFade()); current = null; phase = Phase.IDLE
                dbgMsg = "등록되지 않은 타자"
            }
        }
        refresh()
    }

    // ───────────────────────── 선수 소개바 → 등장곡 ─────────────────────────

    private fun maybeSampleIntro(f: Bitmap, off: Int) {
        if (introBusy) return
        if (orderOn()) return   // 타순 기능을 쓰면 소개바 인식은 필요 없음
        val now = SystemClock.elapsedRealtime()
        if (now - lastIntroAt < 700L) return
        lastIntroAt = now
        if (Store.players(this).none { it.walk != null }) return   // 등장곡이 하나도 없으면 읽을 필요 없음

        val myTeam = Store.str(this, "team").trim()
        val nameBmp = binarize(crop(f, Regions.INTRO), 2)
        val teamBmp = if (off != 0 && myTeam.isNotEmpty())
            binarize(crop(f, if (off == 1) Regions.TEAM_TOP else Regions.TEAM_BOT), 5) else null
        introBusy = true
        ocr(nameBmp) { t -> onIntroText(t, myTeam, teamBmp) }
    }

    private fun onIntroText(t: String, myTeam: String, teamBmp: Bitmap?) {
        val p = Matcher.find(t, Store.players(this))
        if (p == null) {
            if (++introMiss >= 4) introName = null   // 소개바가 사라진 것으로 봄
            introBusy = false
            return
        }
        introMiss = 0
        if (p.name == introName) { introBusy = false; return }   // 같은 소개바가 계속 보이는 중
        if (teamBmp == null) { finishIntro(p, true); return }
        ocr(teamBmp) { tt ->
            val tn = Matcher.norm(tt)
            // 팀 이름을 못 읽으면 막지 않고 진행, 읽었는데 내 팀이 아니면 상대 공격
            finishIntro(p, tn.isEmpty() || tn.contains(Matcher.norm(myTeam)))
        }
    }

    private fun finishIntro(p: Player, teamOk: Boolean) {
        introBusy = false
        introName = p.name
        dbgBatter = p.name
        syncOrder(p)
        if (!teamOk) {
            if (current != null) { stopPlayer(transitionFade()); current = null; phase = Phase.IDLE }
            dbgMsg = "상대팀 공격 – 대기"
            refresh(); return
        }
        if (current?.name == p.name) { refresh(); return }       // 이미 이 타자로 진행 중
        stopPlayer(transitionFade())                             // 이전 타자 곡은 서서히 정리
        current = p
        if (p.walk != null && !teamActive()) {
            phase = Phase.WALKUP
            play(p.walk, 60)
            dbgMsg = "♬ ${p.name} 등장곡 (선수 소개바)"
        } else {
            phase = Phase.IDLE
            dbgMsg = if (teamActive()) "팀응원가 재생 중 – ${p.name} 대기" else "${p.name} 소개바 인식 (등장곡 없음)"
        }
        refresh()
    }

    // ───────────────────────── 타순: 아웃 → 다음 타자 등장곡 ─────────────────────────

    private fun orderOn(): Boolean = Store.bool(this, "orderOn", false)

    /** 타순 포인터가 가리키는 타자 (등록 안 한 선수면 null) */
    private fun pointerPlayer(): Player? {
        val order = Store.order(this)
        val name = order[Store.int(this, "orderIdx", 0).coerceIn(0, order.size - 1)]
        if (name.isEmpty()) return null
        return Store.players(this).firstOrNull { it.name == name }
    }

    /** 타자 이름을 읽었을 때 타순 위치를 그 선수에 맞춤 */
    private fun syncOrder(p: Player) {
        if (!orderOn()) return
        val i = Store.order(this).indexOf(p.name)
        if (i >= 0) Store.putInt(this, "orderIdx", i)
    }

    private fun cancelWalk() {
        walkRunnable?.let { handler.removeCallbacks(it) }
        walkRunnable = null
    }

    /** 이전 곡은 서서히 줄이고, 해당 타자의 등장곡을 시작 */
    private fun startWalk(p: Player?, why: String) {
        stopPlayer(transitionFade())
        current = p
        phase = Phase.IDLE
        if (p == null) { dbgMsg = "$why: 등록 안 한 선수 차례 – 대기"; refresh(); return }
        dbgBatter = p.name
        if (p.walk != null && !teamActive()) {
            phase = Phase.WALKUP
            play(p.walk, 60)
            dbgMsg = "♬ ${p.name} 등장곡 ($why)"
        } else {
            dbgMsg = if (teamActive()) "팀응원가 재생 중 – ${p.name} 대기" else "${p.name} 차례 (등장곡 없음)"
        }
        refresh()
    }

    /** 알림의 '다음 타자' 버튼 */
    private fun manualNext() {
        val n = Store.order(this).size
        val idx = (Store.int(this, "orderIdx", 0) + 1) % n
        Store.putInt(this, "orderIdx", idx)
        cancelWalk()
        startWalk(pointerPlayer(), "수동 ${idx + 1}번")
    }

    /** 노란 막대(공격 줄)가 바뀌면 공수교대로 보고 처리 (2번 연속 같은 줄일 때 확정) */
    private fun trackOffense(off: Int) {
        if (off == 0) return
        if (off == lastOffRow) { offCand = 0; offCandCount = 0; return }
        if (off == offCand) offCandCount++ else { offCand = off; offCandCount = 1 }
        if (offCandCount >= 2) {
            val prev = lastOffRow
            lastOffRow = off; offCand = 0; offCandCount = 0
            if (prev != 0) onOffenseChanged(off)
        }
    }

    private fun onOffenseChanged(row: Int) {
        if (!orderOn()) return
        cancelWalk()
        val my = Store.str(this, "team").trim()
        val mine = rowMine[row] ?: my.isEmpty()
        if (mine) {
            startWalk(pointerPlayer(), "공격 시작")      // 우리 공격 시작: 타순 포인터의 타자
        } else {
            stopPlayer(transitionFade()); current = null; phase = Phase.IDLE
            dbgMsg = "상대팀 공격 – 대기"; refresh()
        }
    }

    /** 공격 줄의 팀 이름으로 어느 줄이 내 팀인지 파악 (한 줄을 알면 다른 줄은 반대) */
    private fun updateMine(f: Bitmap, off: Int) {
        if (!orderOn() || off == 0 || mineBusy) return
        val my = Store.str(this, "team").trim()
        if (my.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (rowMine[off] != null && now - rowMineAt[off] <= 20000L) return
        mineBusy = true
        val bmp = binarize(crop(f, if (off == 1) Regions.TEAM_TOP else Regions.TEAM_BOT), 5)
        ocr(bmp) { tt ->
            val t = Matcher.norm(tt)
            val m = Matcher.norm(my)
            if (t.isNotEmpty() && m.isNotEmpty()) {
                val mine = t.contains(m)
                val at = SystemClock.elapsedRealtime()
                rowMine[off] = mine; rowMineAt[off] = at
                rowMine[3 - off] = !mine; rowMineAt[3 - off] = at
            }
            mineBusy = false
        }
    }

    /** 결과 배너(OUT / STRIKE OUT)는 어두운 빨강 바탕이라 색으로 먼저 찾고, 글자를 읽어 확인 */
    private fun maybeSampleResult(f: Bitmap) {
        if (!orderOn() || resultBusy) return
        val now = SystemClock.elapsedRealtime()
        if (now < lastResultAt) return
        lastResultAt = now + 350L
        val box = when {
            redFraction(f, Regions.RESULT_TOP) >= 0.2f -> Regions.RESULT_TOP
            redFraction(f, Regions.RESULT_MID) >= 0.2f -> Regions.RESULT_MID
            else -> null
        }
        if (box == null) {
            if (++resultMiss >= 4) resultVisible = false   // 배너가 사라짐
            return
        }
        resultMiss = 0
        if (resultVisible) return                          // 이미 처리한 배너
        resultBusy = true
        ocr(binarize(crop(f, box), 3)) { t -> onResultText(t) }
    }

    private fun onResultText(t: String) {
        resultBusy = false
        if (isOutText(t)) {
            resultVisible = true
            val my = Store.str(this, "team").trim()
            // 배너가 스코어보드를 가리므로 마지막으로 확인한 공격 줄 기준
            val mine = if (my.isEmpty()) true else (rowMine[lastOffRow] ?: true)
            if (mine) onOutRecognized()
            else { dbgMsg = "상대팀 아웃 – 무시"; refresh() }
        } else {
            lastResultAt = SystemClock.elapsedRealtime() + 1200L   // 다른 빨간 배너면 잠시 쉼
        }
    }

    private fun isOutText(t: String): Boolean {
        val u = t.uppercase().replace("0UT", "OUT")
        return Regex("(?<![A-Z])OUT(?![A-Z])").containsMatchIn(u) ||
            u.contains("STRIKEOUT") || u.contains("삼진") || u.contains("아웃")
    }

    private fun onOutRecognized() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAdvanceAt < 4000L) return
        lastAdvanceAt = now
        val order = Store.order(this)
        val idx = (Store.int(this, "orderIdx", 0) + 1) % order.size
        Store.putInt(this, "orderIdx", idx)
        val startRow = lastOffRow
        cancelWalk()
        stopPlayer(transitionFade())      // 아웃된 타자의 응원가는 서서히 정리
        current = null; phase = Phase.IDLE
        dbgMsg = "아웃! 다음 타자: ${idx + 1}번"
        refresh()
        val r = Runnable {
            walkRunnable = null
            // 3아웃으로 공수교대가 됐다면 시작하지 않음 (우리 공격이 다시 시작될 때 재생)
            if (lastOffRow == startRow) startWalk(pointerPlayer(), "아웃 후 ${idx + 1}번")
        }
        walkRunnable = r
        handler.postDelayed(r, ORDER_DELAY)
    }

    private fun redFraction(b: Bitmap, r: FloatArray): Float {
        val q = rect(b, r)
        var tot = 0; var cnt = 0
        var y = q[1]
        while (y < q[1] + q[3]) {
            var x = q[0]
            while (x < q[0] + q[2]) {
                val c = b.getPixel(x, y)
                val rr = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
                tot++
                if (rr in 60..170 && rr > g + 30 && rr > bl + 30 && rr + g + bl < 330) cnt++
                x += 3
            }
            y += 3
        }
        return if (tot == 0) 0f else cnt.toFloat() / tot
    }

    // ───────────────────────── 이닝 인식 / 팀 응원가 ─────────────────────────

    private fun teamFeatureOn(): Boolean =
        Store.bool(this, "teamOn", true) && Store.teamSongs(this).any { it.enabled && it.uri != null }

    private fun maybeSampleInning(f: Bitmap, off: Int) {
        if (inningBusy) return
        val now = SystemClock.elapsedRealtime()
        val wantC = wantComment > 0 && pbStable
        if (now - lastInningAt < (if (wantC) 600L else INNING_MS)) return
        lastInningAt = now
        if (!teamFeatureOn()) return

        val numBmp = binarize(crop(f, Regions.INNING_NUM), 6)
        val cBw = if (wantC) binarize(crop(f, Regions.COMMENT), 3) else null
        val cRaw = if (wantC) upscale(crop(f, Regions.COMMENT), 2) else null
        val myTeam = Store.str(this, "team").trim()
        val needTeam = off != 0 && myTeam.isNotEmpty() &&
            (rowMine[off] == null || now - rowMineAt[off] > 20000L)
        val teamBmp = if (needTeam)
            binarize(crop(f, if (off == 1) Regions.TEAM_TOP else Regions.TEAM_BOT), 5) else null
        val halfNow = if (off != 0) off else triangleHalf(f)

        inningBusy = true
        sampleInning(numBmp, cBw, cRaw, teamBmp, halfNow, off)
    }

    private fun sampleInning(numBmp: Bitmap, cBw: Bitmap?, cRaw: Bitmap?, teamBmp: Bitmap?, halfNow: Int, row: Int) {
        fun finish() { inningBusy = false; refresh() }

        fun readInning() {
            if (cBw == null || cRaw == null) {
                ocr(numBmp) { t -> feed(parseInning(t), halfNow, false); finish() }
                return
            }
            ocr(cBw) { t1 ->
                val c1 = parseComment(t1)
                if (c1 != null) { wantComment = 0; feed(c1.first, c1.second, true); finish() }
                else ocr(cRaw) { t2 ->
                    val c2 = parseComment(t2)
                    if (c2 != null) { wantComment = 0; feed(c2.first, c2.second, true) }
                    else {
                        wantComment--
                        ocr(numBmp) { t3 -> feed(parseInning(t3), halfNow, false); finish() }
                        return@ocr
                    }
                    finish()
                }
            }
        }

        if (teamBmp != null && row in 1..2) {
            ocr(teamBmp) { tt ->
                val my = Matcher.norm(Store.str(this, "team"))
                val t = Matcher.norm(tt)
                if (t.isNotEmpty() && my.isNotEmpty()) {
                    rowMine[row] = t.contains(my)
                    rowMineAt[row] = SystemClock.elapsedRealtime()
                }
                readInning()
            }
        } else readInning()
    }

    /** 읽은 이닝을 반영. 같은 값이 2번 연속 읽히면 확정(해설 바에서 읽은 값은 바로 확정). */
    private fun feed(inn: Int, h: Int, strong: Boolean) {
        if (inn <= 0 || inn > 15 || h == 0) return
        val key = inn * 10 + h
        if (strong) { candKey = key; candCount = 2 }
        else if (key == candKey) candCount++
        else { candKey = key; candCount = 1 }
        if (candCount >= 2 && key != stableKey) {
            val first = stableKey == 0
            stableKey = key; inning = inn; half = h
            dbgInning = "${inn}회${if (h == 1) "초" else "말"}"
            // 앱을 이닝 중간에 켠 경우(첫 인식)에는 해설 바로 확인된 때만 재생
            if (!first || strong) onInningChanged(inn, h)
        }
    }

    private fun onInningChanged(inn: Int, h: Int) {
        if (!Store.bool(this, "teamOn", true)) return
        val mine = rowMine[h]
        val match = Store.teamSongs(this).firstOrNull { ts ->
            ts.enabled && ts.uri != null &&
                inningOk(ts, inn) &&
                (ts.half == 0 || ts.half == h) &&
                sideOk(ts.side, mine)
        }
        if (match != null) playTeam(match)
        else dbgMsg = "${inn}회${if (h == 1) "초" else "말"} 시작 – 해당하는 팀응원가 없음"
    }

    private fun inningOk(ts: TeamSong, inn: Int): Boolean = when {
        ts.inning == 0 -> true
        ts.orMore -> inn >= ts.inning
        else -> inn == ts.inning
    }

    /** side: 0 상관없음, 1 내 팀 공격 때, 2 내 팀 수비 때. mine = 지금 공격팀이 내 팀인지(모르면 null) */
    private fun sideOk(side: Int, mine: Boolean?): Boolean = when (side) {
        1 -> mine == true
        2 -> mine == false
        else -> true
    }

    private fun playTeam(ts: TeamSong) {
        val uri = ts.uri ?: return
        // 팀 응원가가 나오는 동안 선수 응원가/등장곡은 멈추고 나오지 않는다
        stopPlayer(transitionFade())
        current = null
        phase = Phase.IDLE
        teamPlayer?.let { if (!it.finished) it.fadeOutAndStop(transitionFade()) }

        val p = StadiumPlayer(this, Uri.parse(uri), Store.int(this, "reverb", 50)) { done -> all.remove(done) }
        all.add(p)
        teamPlayer = p
        p.start()
        if (ts.sec > 0) {
            val total = ts.sec * 1000L
            val fadeLen = minOf(Store.int(this, "fade", 2) * 1000L, total / 2).toInt()
            handler.postDelayed({ if (teamPlayer === p) p.fadeOutAndStop(fadeLen) }, total - fadeLen)
        }
        dbgMsg = "📣 팀응원가: ${ts.name}"
        refresh()
    }

    private fun parseComment(t: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2})\\s*회\\s*(초|말)").find(t) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        return Pair(n, if (m.groupValues[2] == "초") 1 else 2)
    }

    /** 이닝 숫자 OCR 결과 보정 (숫자로 오인되기 쉬운 글자를 바로잡음) */
    private fun parseInning(t: String): Int {
        val sb = StringBuilder()
        for (c in t) {
            when (c) {
                in '0'..'9' -> sb.append(c)
                'l', 'I', '|', 'i', '!' -> sb.append('1')
                'O', 'o', 'D' -> sb.append('0')
                'S', 's' -> sb.append('5')
                'B' -> sb.append('8')
                'g', 'q' -> sb.append('9')
                'Z', 'z' -> sb.append('2')
            }
        }
        return sb.toString().take(2).toIntOrNull() ?: 0
    }

    /** ▲(초)=1, ▼(말)=2, 모르면 0 */
    private fun triangleHalf(b: Bitmap): Int {
        val q = rect(b, Regions.INNING_TRI)
        val rows = IntArray(q[3])
        for (y in 0 until q[3]) {
            for (x in 0 until q[2]) {
                val c = b.getPixel(q[0] + x, q[1] + y)
                if (minOf(Color.red(c), Color.green(c), Color.blue(c)) > 120) rows[y]++
            }
        }
        var first = -1; var last = -1
        for (y in rows.indices) if (rows[y] > 0) { if (first < 0) first = y; last = y }
        if (first < 0 || last - first < 3) return 0
        val mid = (first + last) / 2
        var top = 0; var bottom = 0
        for (y in first..last) if (y <= mid) top += rows[y] else bottom += rows[y]
        return when {
            top > bottom * 1.3 -> 2      // ▼ = 말
            bottom > top * 1.3 -> 1      // ▲ = 초
            else -> 0
        }
    }

    // ───────────────────────── 이미지 처리 ─────────────────────────

    private fun rect(b: Bitmap, r: FloatArray): IntArray {
        val x = (b.width * r[0]).toInt().coerceIn(0, b.width - 2)
        val y = (b.height * r[1]).toInt().coerceIn(0, b.height - 2)
        val w = (b.width * r[2]).toInt().coerceIn(2, b.width - x)
        val h = (b.height * r[3]).toInt().coerceIn(2, b.height - y)
        return intArrayOf(x, y, w, h)
    }

    private fun crop(b: Bitmap, r: FloatArray): Bitmap {
        val q = rect(b, r)
        return Bitmap.createBitmap(b, q[0], q[1], q[2], q[3])
    }

    /** PLAY BALL 버튼의 보라색 비율 */
    private fun purpleFraction(b: Bitmap): Float {
        val q = rect(b, Regions.PLAYBALL)
        var tot = 0; var cnt = 0
        var y = q[1]
        while (y < q[1] + q[3]) {
            var x = q[0]
            while (x < q[0] + q[2]) {
                val c = b.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
                tot++
                if (bl > 150 && r in 71..189 && g < 80 && bl - g > 120) cnt++
                x += 2
            }
            y += 2
        }
        return if (tot == 0) 0f else cnt.toFloat() / tot
    }

    /** 스코어보드 팀 옆 노란 막대가 있는 줄: 1=위, 2=아래, 0=없음 */
    private fun offenseRow(b: Bitmap): Int {
        val top = yellowRows(b, Regions.YEL_TOP)
        val bot = yellowRows(b, Regions.YEL_BOT)
        return when {
            top >= 0.5f && top >= bot -> 1
            bot >= 0.5f -> 2
            else -> 0
        }
    }

    private fun yellowRows(b: Bitmap, r: FloatArray): Float {
        val q = rect(b, r)
        var hit = 0
        for (y in q[1] until q[1] + q[3]) {
            for (x in q[0] until q[0] + q[2]) {
                val c = b.getPixel(x, y)
                if (Color.red(c) > 200 && Color.green(c) > 170 && Color.blue(c) < 110) { hit++; break }
            }
        }
        return hit.toFloat() / q[3]
    }

    /** 흰 글자만 남기고(검정 글씨/흰 배경) 확대 — 복잡한 배경 위 글자 인식용 */
    private fun binarize(src: Bitmap, scale: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val m = minOf(Color.red(c), Color.green(c), Color.blue(c))
            px[i] = if (m > 185) Color.BLACK else Color.WHITE
        }
        val bw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bw.setPixels(px, 0, w, 0, 0, w, h)
        return upscale(bw, scale)
    }

    private fun upscale(src: Bitmap, scale: Int): Bitmap {
        val s = Bitmap.createScaledBitmap(src, src.width * scale, src.height * scale, true)
        val out = Bitmap.createBitmap(s.width + 40, s.height + 40, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(s, 20f, 20f, null) }
        return out
    }

    private fun ocr(b: Bitmap, cb: (String) -> Unit) {
        recognizer.process(InputImage.fromBitmap(b, 0))
            .addOnSuccessListener { r -> handler.post { cb(r.text) } }
            .addOnFailureListener { handler.post { cb("") } }
    }

    // ───────────────────────── 재생 ─────────────────────────

    private fun play(uri: String, limitSec: Int) {
        stopPlayer(transitionFade())   // 이전 곡은 서서히 줄이고, 새 곡은 바로 시작 → 자연스럽게 이어짐
        val p = StadiumPlayer(this, Uri.parse(uri), Store.int(this, "reverb", 50)) { done ->
            all.remove(done)
            handler.post { if (player === done) player = null }
        }
        all.add(p)
        player = p
        p.start()
        if (limitSec > 0) {
            val total = limitSec * 1000L
            val fadeLen = minOf(Store.int(this, "fade", 2) * 1000L, total / 2).toInt()
            val r = Runnable { if (player === p) stopPlayer(fadeLen) }
            limitRunnable = r
            handler.postDelayed(r, total - fadeLen)   // 전체 길이는 그대로, 끝부분에서 서서히 줄임
        }
    }

    /** 곡이 바뀔 때 쓰는 짧은 페이드. 페이드를 끄면 즉시 정지 */
    private fun transitionFade(): Int = if (Store.int(this, "fade", 2) > 0) 700 else 0

    private fun stopPlayer(fadeMs: Int = 0) {
        limitRunnable?.let { handler.removeCallbacks(it) }
        limitRunnable = null
        val p = player ?: return
        player = null
        if (fadeMs > 0) p.fadeOutAndStop(fadeMs) else p.stopNow()
    }

    // ───────────────────────── 알림 ─────────────────────────

    private fun refresh() {
        val msg = "PB ${if (pbStable) "●" else "○"} | 이닝:$dbgInning | 공격:$dbgOff | 타자:$dbgBatter | $dbgMsg"
        Live.text = msg
        if (msg == lastShown) return
        lastShown = msg
        getSystemService(NotificationManager::class.java).notify(1, notif(msg))
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "응원가", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notif(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CheerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val next = PendingIntent.getService(
            this, 2, Intent(this, CheerService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_IMMUTABLE
        )
        val shot = PendingIntent.getService(
            this, 1, Intent(this, CheerService::class.java).setAction(ACTION_SHOT),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("V26 응원가 실행 중")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, "⏭ 다음 타자", next)
            .addAction(0, "📸 화면 저장", shot)
            .addAction(0, "중지", stop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        Live.text = ""
        if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
        stopPlayer(0)
        all.forEach { it.stopNow() }
        vd?.release(); reader?.close(); projection?.stop()
        if (::thread.isInitialized) thread.quitSafely()
        super.onDestroy()
    }
}
