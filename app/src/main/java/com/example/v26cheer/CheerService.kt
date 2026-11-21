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
import android.media.ImageReader
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
        if (running) return START_NOT_STICKY

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

        try { process(frame) } finally { frame.recycle() }
    }

    private fun process(f: Bitmap) {
        val pbNow = purpleFraction(f) >= 0.35f
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
            stopPlayer(transitionFade()); current = null; phase = Phase.IDLE
            dbgMsg = "등록되지 않은 타자"
        }
        refresh()
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
            top >= 0.6f && top >= bot -> 1
            bot >= 0.6f -> 2
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
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("V26 응원가 실행 중")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
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
