package com.example.v26cheer

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
        const val SCAN_MS = 700L
        var running = false
    }

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var curW = 0
    private var curH = 0
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var player: MediaPlayer? = null
    private var lastBatter: String? = null
    private var missCount = 0
    private var busy = false
    private var lastShown = ""
    private val recognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY

        createChannel()
        startForeground(1, notif("화면에서 타자 이름을 찾는 중…"))

        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        thread = HandlerThread("cheer-scan").also { it.start() }
        handler = Handler(thread.looper)
        // Android 14+: createVirtualDisplay 전에 콜백 등록 필수
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

    private fun scan() {
        ensureDisplay()
        if (busy) return
        val img = reader?.acquireLatestImage() ?: return
        val crop: Bitmap
        try {
            val plane = img.planes[0]
            val pad = (plane.rowStride - plane.pixelStride * img.width) / plane.pixelStride
            val full = Bitmap.createBitmap(img.width + pad, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            val l = Store.int(this, "l", 0); val t = Store.int(this, "t", 60)
            val w = Store.int(this, "w", 50); val h = Store.int(this, "h", 40)
            val x = (img.width * l / 100).coerceIn(0, img.width - 2)
            val y = (img.height * t / 100).coerceIn(0, img.height - 2)
            val cw = (img.width * w / 100).coerceIn(2, img.width - x)
            val ch = (img.height * h / 100).coerceIn(2, img.height - y)
            crop = Bitmap.createBitmap(full, x, y, cw, ch)
            full.recycle()
        } finally { img.close() }

        busy = true
        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { r -> handler.post { onText(r.text) } }
            .addOnFailureListener { busy = false }
    }

    private fun onText(text: String) {
        busy = false
        val song = Matcher.find(text, Store.songs(this))
        if (song == null) {
            if (++missCount >= 8) lastBatter = null   // 타석이 끝났다고 보고 다음 타석에 대비
            show("인식: " + text.replace("\n", " ").take(40))
            return
        }
        missCount = 0
        if (song.name != lastBatter) {
            lastBatter = song.name
            play(song)
            show("▶ ${song.name} 응원가")
        }
    }

    private fun play(s: Song) {
        player?.runCatching { stop(); release() }
        val sec = Store.int(this, "sec", 25)
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            try {
                setDataSource(this@CheerService, Uri.parse(s.uri))
                prepare(); start()
            } catch (e: Exception) { release(); player = null; return }
        }
        val mine = player
        if (sec > 0) handler.postDelayed({ if (player === mine) mine?.runCatching { stop() } }, sec * 1000L)
    }

    private fun show(msg: String) {
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
            .setContentIntent(open)
            .addAction(0, "중지", stop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
        player?.runCatching { stop(); release() }
        vd?.release(); reader?.close(); projection?.stop()
        if (::thread.isInitialized) thread.quitSafely()
        super.onDestroy()
    }
}
