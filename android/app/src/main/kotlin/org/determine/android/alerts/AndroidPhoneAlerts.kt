package org.determine.android.alerts

import org.determine.app.audio.PhoneSounds
import org.determine.app.runtime.PlatformAlerts
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Phone-side speech and failure outputs (Arch §4.1, §8.2 spoken layer):
 *  - [speak]: TextToSpeech at a sped-up rate, so short words act as spearcons ("left", "person"). Newest
 *    utterance flushes older ones: a stale spoken hint must never delay a fresh one.
 *  - [vibrate]: phone vibration (non-directional), Android waveform convention (off, on, off, on … ms).
 *  - [systemOff]: the loud failure path (Arch §4.2 UNAVAILABLE, invariant "failure is loud"): a three-note
 *    descending tone on the alarm stream and the words "system off". The engine follows every systemOff()
 *    with vibrate(long-short-short) and repeats both every 5 s while UNAVAILABLE, so neither is done here.
 *
 * Callbacks arrive on the engine thread inside the engine lock; every call here is non-blocking.
 */
class AndroidPhoneAlerts(context: Context) : PlatformAlerts {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val alarmAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    @Volatile private var ttsReady = false
    /** Utterances requested before the TTS engine finished binding; replayed once (newest last). */
    private val pending = ArrayDeque<Pair<String, Boolean>>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        onTtsInit(status)
    }

    private val offTone: AudioTrack? = buildSystemOffTone()

    private fun onTtsInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TextToSpeech init failed ($status); spoken layer unavailable, tones and vibration remain")
            return
        }
        tts.language = Locale.getDefault()
        val queued = synchronized(pending) {
            ttsReady = true
            pending.toList().also { pending.clear() }
        }
        queued.forEach { (text, alarm) -> say(text, alarm) }
    }

    override fun speak(text: String) {
        say(text, alarm = false)
    }

    override fun vibrate(pattern: LongArray) {
        if (pattern.isEmpty() || !vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(pattern, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, alarmAttributes)
        }
    }

    override fun systemOff() {
        playOffTone()
        say(PhoneSounds.SYSTEM_OFF_TEXT, alarm = true)
    }

    /**
     * Describe answers (Arch §7.3) are spoken at normal rate on the speech stream. Any hazard utterance uses
     * QUEUE_FLUSH and so replaces a running description; [stopDescription] stops one only if it is still the
     * current utterance, so it never cuts off a spearcon.
     */
    @Volatile private var describing = false

    override fun speakDescription(text: String) {
        say(text, alarm = false, rate = 1.0f)
        describing = true
    }

    override fun stopDescription() {
        if (describing) {
            describing = false
            tts.stop()
        }
    }

    /** Short Describe status words ("Description unavailable."), normal rate. */
    override fun say(text: String) = say(text, alarm = false, rate = 1.0f)

    private fun say(text: String, alarm: Boolean, rate: Float? = null) {
        describing = false
        if (!ttsReady) {
            synchronized(pending) {
                if (!ttsReady) {
                    if (pending.size >= MAX_PENDING) pending.removeFirst()
                    pending.addLast(text to alarm)
                    return
                }
            }
        }
        tts.setAudioAttributes(if (alarm) alarmAttributes else speechAttributes)
        // System-off is spoken at normal rate so it is unmistakable; hazard words are spearcons.
        tts.setSpeechRate(rate ?: if (alarm) 1.0f else PhoneSounds.SPEARCON_RATE)
        val params = Bundle()
        if (alarm) params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, text)
    }

    private fun playOffTone() {
        val track = offTone ?: return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.reloadStaticData()
            track.play()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "system-off tone failed", e)
        }
    }

    /** [PhoneSounds.systemOffTone] in a static AudioTrack on the alarm stream (phone speaker / current route). */
    private fun buildSystemOffTone(): AudioTrack? = try {
        val rate = PhoneSounds.OFF_TONE_RATE
        val pcm = PhoneSounds.systemOffTone(rate)
        AudioTrack.Builder()
            .setAudioAttributes(alarmAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .also { it.write(pcm, 0, pcm.size) }
    } catch (e: Exception) {
        Log.e(TAG, "cannot build system-off tone; speech and vibration remain", e)
        null
    }

    override fun close() {
        tts.stop()
        tts.shutdown()
        offTone?.release()
    }

    companion object {
        private const val TAG = "PhoneAlerts"
        private const val MAX_PENDING = 3
    }
}
