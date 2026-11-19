package com.example.v26cheer

import android.media.MediaPlayer
import android.media.audiofx.EnvironmentalReverb

/** 구장에서 울리는 듯한 잔향(리버브) 효과. 기기가 지원하지 않으면 조용히 건너뜁니다. */
object Fx {
    class Handle(private val reverb: EnvironmentalReverb) {
        fun release() { runCatching { reverb.setEnabled(false) }; runCatching { reverb.release() } }
    }

    /** strength: 0(끔) ~ 100(가장 강하게 울림) */
    fun attach(mp: MediaPlayer, strength: Int): Handle? {
        val s = strength.coerceIn(0, 100)
        if (s == 0) return null
        var r: EnvironmentalReverb? = null
        return try {
            r = EnvironmentalReverb(0, 0)
            r.setDecayTime(1200 + s * 35)                      // 잔향 길이 1.2~4.7초 (넓은 구장)
            r.setDecayHFRatio(600.toShort())                   // 고음은 빨리 줄어 먹먹하게 울림
            r.setReverbLevel((-2500 + s * 25).toShort())       // 잔향 크기
            r.setReverbDelay(60)
            r.setReflectionsLevel((-1500 + s * 10).toShort())
            r.setReflectionsDelay(40)
            r.setRoomLevel(0.toShort())
            r.setDiffusion(1000.toShort())
            r.setDensity(1000.toShort())
            r.setEnabled(true)
            mp.attachAuxEffect(r.id)
            mp.setAuxEffectSendLevel(1.0f)
            Handle(r)
        } catch (e: Exception) {
            runCatching { r?.release() }
            null
        }
    }
}
