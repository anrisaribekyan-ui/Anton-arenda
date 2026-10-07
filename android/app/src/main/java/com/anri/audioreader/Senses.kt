package com.anri.audioreader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.max
import kotlin.random.Random

/** Короткие вибрации: верный ответ, ошибка, лайк, конец серии. */
object Haptics {
    private var vibrator: Vibrator? = null

    fun init(ctx: Context) {
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun wave(timings: LongArray, amps: IntArray) {
        if (!Engine.settings.haptics) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(VibrationEffect.createWaveform(timings, amps, -1)) }
    }

    fun tick() = wave(longArrayOf(0, 18), intArrayOf(0, 120))
    fun like() = wave(longArrayOf(0, 25, 70, 25), intArrayOf(0, 160, 0, 220))
    fun success() = wave(longArrayOf(0, 30, 60, 45), intArrayOf(0, 140, 0, 255))
    fun fail() = wave(longArrayOf(0, 90), intArrayOf(0, 90))
    fun episode() = wave(longArrayOf(0, 40, 80, 40, 80, 120), intArrayOf(0, 120, 0, 180, 0, 255))
}

/**
 * Фоновый звук без файлов: шум генерируется на лету.
 * brown — ровный «коричневый» шум, rain — шум с каплями, fire — низкий гул с потрескиванием.
 */
object Ambient {
    private const val RATE = 22050
    @Volatile private var kind = "off"
    @Volatile private var active = false
    @Volatile private var volume = 0.3f
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    /** Какой звук играть: настройка или, в режиме «авто», по настроению серии. */
    fun resolve(setting: String, mood: String?): String = when (setting) {
        "auto" -> when (mood) {
            "tense", "dark", "sad" -> "rain"
            "warm", "calm", "joy" -> "fire"
            else -> "brown"
        }
        else -> setting
    }

    fun update(kindNow: String, playing: Boolean, vol: Float) {
        kind = kindNow
        volume = vol
        active = playing && kindNow != "off"
        track?.setVolume(volume)
        if (active && thread == null && !failed) {
            // фоновый звук — дополнение: если не запустился, чтение идёт дальше без него
            runCatching { start() }.onFailure { failed = true; track = null }
        }
    }

    @Volatile private var failed = false

    private fun start() {
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, RATE))   // байты; для 16 бит размер обязан быть чётным
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.setVolume(volume)
        t.play()
        thread = Thread({
            try {
                loop(t)
            } catch (_: Throwable) {
                failed = true
                runCatching { t.release() }
            }
        }, "ambient").apply { isDaemon = true; start() }
    }

    private fun loop(t: AudioTrack) {
        val buf = ShortArray(1024)
        val rnd = Random(System.nanoTime())
        var brown = 0f
        var low = 0f
        var drop = 0f
        var crack = 0f
        var gain = 0f   // плавное появление и затухание, без щелчков
        while (true) {
            val target = if (active) 1f else 0f
            for (n in buf.indices) {
                gain += (target - gain) * 0.0004f
                val white = rnd.nextFloat() * 2f - 1f
                brown = (brown + 0.02f * white) / 1.02f
                val sample = when (kind) {
                    "rain" -> {
                        low += (white - low) * 0.35f
                        if (rnd.nextFloat() < 0.0009f) drop = 0.5f + rnd.nextFloat() * 0.5f
                        drop *= 0.992f
                        low * 0.35f + white * drop * 0.5f + brown * 1.5f
                    }
                    "fire" -> {
                        if (rnd.nextFloat() < 0.00035f) crack = (rnd.nextFloat() * 2f - 1f)
                        crack *= 0.93f
                        brown * 3.2f + crack * 0.9f
                    }
                    else -> brown * 3.5f
                }
                buf[n] = (sample.coerceIn(-1f, 1f) * gain * 26000f).toInt().toShort()
            }
            t.write(buf, 0, buf.size)
            if (!active && gain < 0.001f) {
                // тишина: стоим на паузе, пока снова не понадобится звук
                t.pause(); t.flush()
                while (!active) Thread.sleep(150)
                t.play()
            }
        }
    }
}
