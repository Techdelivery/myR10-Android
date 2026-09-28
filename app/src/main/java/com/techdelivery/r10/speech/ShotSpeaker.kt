package com.techdelivery.r10.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.techdelivery.r10.protocol.shot.Shot
import java.util.Locale

/**
 * Speaks an arriving shot's ball speed (ROADMAP R10).
 *
 * Android's own [TextToSpeech]: no permission, no dependency, and nothing to keep
 * alive beyond the engine. Three behaviours matter more than the API call:
 *
 * - **Latest wins, never a queue.** `QUEUE_FLUSH` drops whatever is still pending.
 *   A burst of practice swings would otherwise leave the phone talking about a shot
 *   from ten seconds ago, which is worse than not speaking.
 * - **Audio focus, and give it up.** Request transient focus per utterance, stop on
 *   loss. Talking over a phone call is the failure that would end a session.
 * - **Silence is a valid answer.** An empty phrase is not spoken at all, so a shot
 *   with no ball metrics costs nothing.
 *
 * Not unit-tested, deliberately: the engine is the platform's, and a test double
 * would assert that we call the framework rather than that a number is right. The
 * phrasing — [SpokenShot] — is the part with a contract, and that is covered.
 */
class ShotSpeaker(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var tts: TextToSpeech? = null

    /**
     * True once the engine reports ready. A shot arriving before that is dropped
     * rather than queued: speaking it seconds late is worse than not speaking it.
     */
    @Volatile
    private var ready = false

    @Volatile
    private var released = false

    /**
     * The focus request currently held, so it can be abandoned with the same object
     * it was asked with. Abandoning one the platform no longer knows about does
     * nothing but warn.
     */
    private var focusRequest: AudioFocusRequest? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            if (released) return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS init failed: $status; shot speed will stay silent")
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            engine.language = Locale.getDefault()
            // Navigation-ish rate: fast enough not to bloat the gap between shots,
            // slow enough to parse a three-digit number the first time.
            engine.setSpeechRate(SPEECH_RATE)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = abandonFocus()

                @Deprecated("required by the platform interface")
                override fun onError(utteranceId: String?) = abandonFocus()
            })
            ready = true
            Log.i(TAG, "TTS ready (${Locale.getDefault()})")
        }
    }

    /**
     * Speak [shot]'s speed, if the setting is on and there is anything to say.
     *
     * Call from the shot-arrival path only. History loaded on relaunch and a
     * replayed shot must both stay silent.
     */
    fun speak(shot: Shot) {
        if (!ready || released) return
        val phrase = SpokenShot.speed(shot)
        if (phrase.isEmpty()) return
        val engine = tts ?: return
        if (!requestFocus()) return
        val result = engine.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (result != TextToSpeech.SUCCESS) {
            abandonFocus()
            Log.w(TAG, "speak() rejected for '$phrase'")
        }
    }

    /** Stop now. Used when the setting is switched off mid-session. */
    fun stop() {
        tts?.stop()
        abandonFocus()
    }

    /** Release the engine. Idempotent, because a service teardown can race a stop. */
    fun shutdown() {
        if (released) return
        released = true
        ready = false
        tts?.stop()
        tts?.shutdown()
        tts = null
        abandonFocus()
    }

    private fun requestFocus(): Boolean {
        val manager = audioManager ?: return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                // Transient focus is ours to give back. Anything that is not a gain
                // means something else wanted the audio - a call, another app - and
                // the right response is to stop, not to push.
                if (change != AudioManager.AUDIOFOCUS_GAIN) {
                    tts?.stop()
                }
            }
            .build()
        // Kept so focus is abandoned with the same request it was asked with:
        // abandoning one the platform no longer knows about does nothing but warn.
        focusRequest = request
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        audioManager?.abandonAudioFocusRequest(request)
        focusRequest = null
    }

    private companion object {
        const val TAG = "R10SPEECH"
        const val UTTERANCE_ID = "r10-shot-speed"

        /**
         * A little faster than default: the number is short, and at the default rate
         * the tail of the utterance is still going when the next swing lands.
         */
        const val SPEECH_RATE = 1.15f
    }
}
