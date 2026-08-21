package com.yahtzee.online.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.yahtzee.online.game.AppSettings
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Game sounds, synthesised at runtime rather than shipped as audio files.
 *
 * Everything here is a few hundred milliseconds of PCM generated from noise and sine waves, so
 * the app carries no audio assets, no licensing questions, and a handful of kilobytes of code
 * instead of megabytes of samples. The trade is that these are recognisably synthetic — clearly
 * dice-like, but not studio recordings. Swapping in real samples later would only mean changing
 * how the buffers are filled; nothing else here would move.
 *
 * Buffers are built once and each keeps a static [AudioTrack], rewound and replayed on demand,
 * so repeated rolls do not allocate.
 */
class SoundEngine(private val context: Context) {

    enum class Sound { ROLL, LAND, SCORE, WIN }

    private val tracks = mutableMapOf<Sound, AudioTrack>()

    /**
     * Plays [sound] and, where it makes sense, the matching buzz. Haptics live here rather than
     * at the call sites because they fire at exactly the same moments; splitting them would mean
     * every caller remembering to do both.
     */
    fun play(sound: Sound) {
        vibrateFor(sound)
        if (!AppSettings.soundEnabled(context)) return
        val track = tracks.getOrPut(sound) { buildTrack(pcmFor(sound)) }
        try {
            // Rewinding a static track is what lets it replay without reallocating. stop() first,
            // or the head position cannot be moved while it is playing.
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
            track.reloadStaticData()
            track.play()
        } catch (_: IllegalStateException) {
            // A track can be left in a bad state if the device took the audio path away; losing
            // a sound effect is not worth interrupting a game over.
        }
    }

    /**
     * A short buzz for the events worth feeling. The rattle is deliberately left silent in the
     * hand: it runs most of a second, and a vibration that long is irritating rather than
     * informative.
     */
    private fun vibrateFor(sound: Sound) {
        if (!AppSettings.hapticsEnabled(context)) return
        val millis = when (sound) {
            Sound.LAND -> 22L
            Sound.SCORE -> 14L
            Sound.WIN -> 60L
            Sound.ROLL -> return
        }
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as? android.os.VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            vibrator.vibrate(
                android.os.VibrationEffect.createOneShot(
                    millis,
                    android.os.VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        }
    }

    fun release() {
        tracks.values.forEach { runCatching { it.release() } }
        tracks.clear()
    }

    private fun buildTrack(pcm: ShortArray): AudioTrack {
        val bytes = pcm.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    private fun pcmFor(sound: Sound): ShortArray = when (sound) {
        Sound.ROLL -> rattle()
        Sound.LAND -> thud()
        Sound.SCORE -> click()
        Sound.WIN -> fanfare()
    }

    /** Dice tumbling: a scatter of woody clacks thinning out as the throw loses energy. */
    private fun rattle(): ShortArray {
        val buffer = ShortArray((SAMPLE_RATE * 0.85f).toInt())
        val random = Random(7)
        var position = 0.04
        var amplitude = 0.9
        while (position < 0.78) {
            addClack(buffer, (position * SAMPLE_RATE).toInt(), amplitude, random)
            // Clacks crowd together early and spread out as the dice settle, which is what makes
            // it read as a throw rather than an even rattle.
            position += 0.045 + random.nextDouble() * 0.055
            amplitude *= 0.88
        }
        return buffer
    }

    /** A single die coming to rest: a short low knock with a touch of surface noise. */
    private fun thud(): ShortArray {
        val length = (SAMPLE_RATE * 0.22f).toInt()
        val buffer = ShortArray(length)
        val random = Random(11)
        for (i in 0 until length) {
            val t = i.toDouble() / SAMPLE_RATE
            val body = sin(2 * PI * 96 * t) * exp(-t / 0.05)
            val grit = (random.nextDouble() * 2 - 1) * exp(-t / 0.012) * 0.35
            buffer[i] = ((body * 0.7 + grit) * Short.MAX_VALUE * 0.55).toInt().toShort()
        }
        return buffer
    }

    /** Scoring a category: a brief two-step blip, rising so it reads as confirmation. */
    private fun click(): ShortArray {
        val length = (SAMPLE_RATE * 0.14f).toInt()
        val buffer = ShortArray(length)
        for (i in 0 until length) {
            val t = i.toDouble() / SAMPLE_RATE
            val frequency = if (t < 0.05) 720.0 else 1080.0
            val envelope = exp(-((t % 0.05)) / 0.02) * exp(-t / 0.12)
            buffer[i] = (sin(2 * PI * frequency * t) * envelope * Short.MAX_VALUE * 0.4)
                .toInt().toShort()
        }
        return buffer
    }

    /** End of game: a short rising arpeggio. */
    private fun fanfare(): ShortArray {
        val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.50)
        val noteLength = (SAMPLE_RATE * 0.16f).toInt()
        val buffer = ShortArray(noteLength * notes.size)
        notes.forEachIndexed { index, frequency ->
            for (i in 0 until noteLength) {
                val t = i.toDouble() / SAMPLE_RATE
                // A little second harmonic keeps it from sounding like a test tone.
                val tone = sin(2 * PI * frequency * t) + 0.3 * sin(4 * PI * frequency * t)
                val envelope = (1 - exp(-t / 0.006)) * exp(-t / 0.13)
                val value = tone * envelope * Short.MAX_VALUE * 0.3
                buffer[index * noteLength + i] = value.toInt().toShort()
            }
        }
        return buffer
    }

    /**
     * One die knock: filtered noise under a fast decay. The one-pole lowpass takes the hiss off
     * raw white noise and leaves something closer to plastic on wood.
     */
    private fun addClack(buffer: ShortArray, startIndex: Int, amplitude: Double, random: Random) {
        val length = (SAMPLE_RATE * 0.055f).toInt()
        var lowpass = 0.0
        for (i in 0 until length) {
            val index = startIndex + i
            if (index >= buffer.size) return
            val t = i.toDouble() / SAMPLE_RATE
            val noise = random.nextDouble() * 2 - 1
            lowpass += (noise - lowpass) * 0.33
            val value = lowpass * exp(-t / 0.009) * amplitude
            val mixed = buffer[index] + (value * Short.MAX_VALUE * 0.5)
            buffer[index] = mixed.coerceIn(MIN_SAMPLE, MAX_SAMPLE).toInt().toShort()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 22050
        const val MIN_SAMPLE = -32768.0
        const val MAX_SAMPLE = 32767.0
    }
}
