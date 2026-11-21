package com.example.v26cheer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.tanh

/**
 * 음악 파일을 직접 디코딩 → 구장 울림(StadiumDsp) 입히기 → 페이드 → 재생.
 * 안드로이드 기기의 오디오 효과 지원 여부와 상관없이 같은 소리가 납니다.
 */
class StadiumPlayer(
    private val ctx: Context,
    private val uri: Uri,
    private val strengthPct: Int,
    private val onEnd: (StadiumPlayer) -> Unit = {}
) {
    @Volatile private var hardStop = false
    @Volatile private var fadeReqMs = 0
    /** 재생이 완전히 끝났는지(페이드 중에는 false) */
    @Volatile var finished = false
        private set
    private var thread: Thread? = null

    private var sr = 44100
    private var ch = 2
    private var pcmFloat = false
    private var ready = false
    private var track: AudioTrack? = null
    private var dsp: StadiumDsp? = null
    private var l = FloatArray(4096)
    private var r = FloatArray(4096)
    private var out = ShortArray(8192)
    private var fadeTotal = 0L
    private var fadeLeft = 0L
    private var fadeDone = false

    fun start() {
        thread = Thread({ runSafely() }, "stadium-player").also { it.start() }
    }

    /** ms 동안 서서히 소리를 줄이며 끈다. 0이면 즉시 정지. */
    fun fadeOutAndStop(ms: Int) {
        if (ms <= 0) hardStop = true else fadeReqMs = ms
    }

    fun stopNow() { hardStop = true }

    private fun runSafely() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (e: Exception) {}
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(ctx, uri, null)
            var idx = -1
            var mime = ""
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("audio/")) { idx = i; mime = m; break }
            }
            if (idx < 0) return
            extractor.selectTrack(idx)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(extractor.getTrackFormat(idx), null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone && !hardStop && !fadeDone) {
                if (!inputDone) {
                    val ib = codec.dequeueInputBuffer(5000)
                    if (ib >= 0) {
                        val buf = codec.getInputBuffer(ib)
                        if (buf != null) {
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(ib, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                val ob = codec.dequeueOutputBuffer(info, 5000)
                if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    setup(codec.outputFormat)
                } else if (ob >= 0) {
                    if (!ready) setup(codec.outputFormat)
                    if (info.size > 0) {
                        val pcm = codec.getOutputBuffer(ob)
                        if (pcm != null) {
                            pcm.position(info.offset)
                            pcm.limit(info.offset + info.size)
                            handlePcm(pcm)
                        }
                    }
                    codec.releaseOutputBuffer(ob, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }

            // 곡이 끝까지 재생됐다면 울림의 꼬리가 자연스럽게 사라질 때까지 잠시 더 내보낸다
            if (outputDone && !hardStop && !fadeDone && dsp != null) {
                var left = StadiumDsp.latencyFrames() + (sr * 1.3).toInt()
                ensure(2048)
                while (left > 0 && !hardStop && !fadeDone) {
                    val n = minOf(left, 2048)
                    java.util.Arrays.fill(l, 0, n, 0f)
                    java.util.Arrays.fill(r, 0, n, 0f)
                    emit(n)
                    left -= n
                }
            }
        } catch (e: Exception) {
            // 재생할 수 없는 파일이면 조용히 종료
        } finally {
            try { codec?.stop() } catch (e: Exception) {}
            try { codec?.release() } catch (e: Exception) {}
            try { extractor?.release() } catch (e: Exception) {}
            val t = track
            if (t != null) {
                try {
                    if (hardStop) { t.pause(); t.flush() }
                    else { t.stop(); Thread.sleep(250) }   // 이미 넣은 소리가 끝까지 나오도록 잠시 대기
                } catch (e: Exception) {}
                try { t.release() } catch (e: Exception) {}
            }
            finished = true
            try { onEnd(this) } catch (e: Exception) {}
        }
    }

    private fun setup(fmt: MediaFormat) {
        val newSr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        ch = maxOf(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        pcmFloat = fmt.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
        if (ready && newSr == sr) return
        try { track?.release() } catch (e: Exception) {}
        sr = newSr
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = maxOf(minBuf, sr * 4 * 150 / 1000)   // 약 0.15초 버퍼: 페이드/정지 반응이 빠름
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        dsp = if (strengthPct > 0) StadiumDsp(sr, strengthPct / 100f) else null
        ready = true
    }

    private fun ensure(frames: Int) {
        if (l.size < frames) { l = FloatArray(frames); r = FloatArray(frames) }
        if (out.size < frames * 2) out = ShortArray(frames * 2)
    }

    private fun handlePcm(buf: ByteBuffer) {
        buf.order(ByteOrder.nativeOrder())
        val frames: Int
        if (pcmFloat) {
            val fb = buf.asFloatBuffer()
            frames = fb.remaining() / ch
            ensure(frames)
            for (i in 0 until frames) {
                val a = fb.get(i * ch)
                l[i] = a
                r[i] = if (ch >= 2) fb.get(i * ch + 1) else a
            }
        } else {
            val sb = buf.asShortBuffer()
            frames = sb.remaining() / ch
            ensure(frames)
            for (i in 0 until frames) {
                val a = sb.get(i * ch) / 32768f
                l[i] = a
                r[i] = if (ch >= 2) sb.get(i * ch + 1) / 32768f else a
            }
        }
        emit(frames)
    }

    /** l, r의 앞 frames개를 울림 처리 → 페이드 → 한도 제한 → 재생 */
    private fun emit(frames: Int) {
        if (frames <= 0) return
        if (fadeReqMs > 0 && fadeTotal == 0L) {
            fadeTotal = fadeReqMs.toLong() * sr / 1000
            fadeLeft = fadeTotal
        }
        dsp?.process(l, r, frames)
        ensure(frames)
        for (i in 0 until frames) {
            var g = 1f
            if (fadeTotal > 0) {
                val x = fadeLeft.toFloat() / fadeTotal
                g = x * x   // 제곱 곡선: 귀에 더 자연스럽게 줄어듦
                if (fadeLeft > 0) fadeLeft--
            }
            out[2 * i] = toShort(soft(l[i] * g))
            out[2 * i + 1] = toShort(soft(r[i] * g))
        }
        if (fadeTotal > 0 && fadeLeft <= 0L) fadeDone = true

        val t = track ?: return
        val len = frames * 2
        var off = 0
        while (off < len && !hardStop) {
            val w = t.write(out, off, len - off)
            if (w < 0) { hardStop = true; return }
            off += w
        }
    }

    private fun soft(x: Float): Float {
        val a = abs(x)
        if (a <= 0.8f) return x
        val s = 0.8f + 0.2f * tanh((a - 0.8f) / 0.2f)
        return if (x > 0) s else -s
    }

    private fun toShort(x: Float): Short = (x * 32767f).toInt().coerceIn(-32768, 32767).toShort()
}
