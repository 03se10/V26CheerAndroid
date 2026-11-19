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
import android.media.AudioAttributes
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
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
    private var player: MediaPlayer? = null
    private var limitRunnable: Runnable? = null
    private var fx: Fx.Handle? = null

    // 상태
    private var pbStable = false      // PLAY BALL 버튼이 보이는 상태
    private var flipCount = 0
    private var identified = false    // 이번 PLAY BALL 구간에서 타자 인식 완료
    private var idFails = 0
    private var busy = false
    private var current: Player? = null
    private var phase = Phase.IDLE

    // 알림 표시용
    private var dbgOff = "-"
    private var dbgBatter = "-"
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
    }

    private fun onPlayBallChanged(visible: Boolean) {
        if (visible) {
            identified = false; idFails = 0
            dbgMsg = "PLAY BALL 감지"
        } else {
            // PLAY BALL 사라짐 → 응원가
            val p = current
            if (p != null && phase != Phase.CHEER) {
                phase = Phase.CHEER
                if (p.cheer != null) {
                    play(p.cheer, Store.int(this, "sec", 25))
                    dbgMsg = "▶ ${p.name} 응원가"
                } else {
                    stopPlayer(); dbgMsg = "${p.name}: 응원가 없음"
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
            if (current != null) { stopPlayer(); current = null; phase = Phase.IDLE }
            dbgMsg = "상대팀 공격 – 대기"
            refresh(); return
        }
        dbgBatter = text.replace("\n", " ").trim().take(20).ifEmpty { "-" }
        if (p != null) {
            identified = true; idFails = 0
            if (p.name != current?.name) {
                stopPlayer()
                current = p
                if (p.walk != null) {
                    phase = Phase.WALKUP
                    play(p.walk, 60)
                    dbgMsg = "♬ ${p.name} 등장곡"
                } else {
                    phase = Phase.IDLE
                    dbgMsg = "${p.name} 인식 (등장곡 없음)"
                }
            } else dbgMsg = "${p.name} 인식"
        } else if (++idFails >= 3) {
            identified = true; idFails = 0
            stopPlayer(); current = null; phase = Phase.IDLE
            dbgMsg = "등록되지 않은 타자"
        }
        refresh()
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

    /** 흰 글자만 남기고(검정 글씨/흰 배경) 확대 — 복잡한 배경 위 이름 인식용 */
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
        stopPlayer()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            mp.setDataSource(this, Uri.parse(uri))
            mp.prepare()
            fx = Fx.attach(mp, Store.int(this, "reverb", 50))
            mp.start()
        } catch (e: Exception) { fx?.release(); fx = null; mp.release(); return }
        player = mp
        if (limitSec > 0) {
            val r = Runnable { if (player === mp) stopPlayer() }
            limitRunnable = r
            handler.postDelayed(r, limitSec * 1000L)
        }
    }

    private fun stopPlayer() {
        limitRunnable?.let { handler.removeCallbacks(it) }
        limitRunnable = null
        player?.runCatching { stop(); release() }
        player = null
        fx?.release(); fx = null
    }

    // ───────────────────────── 알림 ─────────────────────────

    private fun refresh() {
        val msg = "PB ${if (pbStable) "●" else "○"} | 공격:$dbgOff | 타자:$dbgBatter | $dbgMsg"
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
        if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
        stopPlayer()
        vd?.release(); reader?.close(); projection?.stop()
        if (::thread.isInitialized) thread.quitSafely()
        super.onDestroy()
    }
}
