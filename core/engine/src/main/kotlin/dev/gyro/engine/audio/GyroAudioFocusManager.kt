package dev.gyro.engine.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Owns the Android audio focus contract on behalf of the player.
 *
 * The engine itself has no notion of focus; it only exposes [Listener.onPlayRequested] and
 * [Listener.onPauseRequested], which the owner maps to mpv's `pause` property. Keeping the state
 * machine here means mpv stays a dumb decoder and every policy decision (resume or not, duck or
 * pause, restore or not) is auditable in one place.
 *
 * Policy summary:
 *  - Transient loss (call, assistant, another app briefly taking over) pauses and resumes on gain.
 *  - Permanent loss (another player took over) pauses and never self-resumes.
 *  - Duckable loss (navigation prompt, notification chime) lowers the system stream instead of
 *    pausing, and restores the exact previous step afterwards.
 *  - Becoming-noisy (headset unplugged, Bluetooth dropped) pauses and never auto-resumes, because
 *    suddenly playing out of the phone speaker is worse than staying silent.
 *
 * Focus is treated as an enhancement, never a precondition: when the platform cannot supply an
 * [AudioManager] (bare unit-test contexts, headless harnesses) playback proceeds unfocused instead
 * of failing, so a broken audio stack can never make the player unusable.
 */
class GyroAudioFocusManager(
    context: Context,
    private val listener: Listener,
    private val audioManager: AudioManager? = resolveAudioManager(context)
) {

    interface Listener {
        /** Playback may start or continue: first grant, delayed grant landing, or recovery from a transient loss. */
        fun onPlayRequested()

        /** Playback must stop producing sound right now. Never calls back into focus handling. */
        fun onPauseRequested()

        /** The system stream was lowered; software gain is untouched. Optional UI hook. */
        fun onDucked() {}

        /** The system stream was restored to the step captured before ducking. */
        fun onUnducked() {}
    }

    private companion object {
        const val TAG = "GyroAudioFocus"

        /** Why we stopped, so a later focus gain knows whether resuming is allowed. */
        const val PAUSED_BY_NONE = 0
        const val PAUSED_BY_TRANSIENT = 1

        fun resolveAudioManager(context: Context): AudioManager? = runCatching {
            val target = context.applicationContext ?: context
            target.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull()
    }

    private val appContext: Context = runCatching { context.applicationContext }.getOrNull() ?: context

    private var hasFocus = false
    private var pausedBy = PAUSED_BY_NONE

    /** Set when the system accepted the request but deferred the grant (for example mid-call). */
    private var pendingPlay = false

    private var duckedFromVolume: Int = -1
    private var duckedToVolume: Int = -1

    private var noisyReceiver: BroadcastReceiver? = null
    private var released = false
    private var focusUnavailableLogged = false

    /**
     * Built lazily because [AudioFocusRequest.Builder.build] has no meaning without a real platform
     * and returns null under stubbed unit-test runtimes.
     */
    private val focusRequest: AudioFocusRequest? by lazy { buildFocusRequest() }

    private fun buildFocusRequest(): AudioFocusRequest? = runCatching {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(::handleFocusChange)
            .build()
    }.getOrNull()

    /**
     * Requests focus before starting or resuming playback.
     *
     * @return true when playback may start immediately. false means the request was deferred or
     * denied; in the deferred case [Listener.onPlayRequested] fires once the grant arrives, so the
     * caller must not start playback on its own.
     */
    fun requestPlay(): Boolean {
        if (released) return false
        val manager = audioManager
        val request = focusRequest
        if (manager == null || request == null) {
            if (!focusUnavailableLogged) {
                focusUnavailableLogged = true
                Log.w(TAG, "Audio focus unavailable on this runtime, playing without focus")
            }
            return true
        }
        registerNoisyReceiver()
        return when (manager.requestAudioFocus(request)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                hasFocus = true
                pausedBy = PAUSED_BY_NONE
                pendingPlay = false
                true
            }

            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                hasFocus = false
                pendingPlay = true
                Log.i(TAG, "Audio focus deferred, waiting for gain before starting playback")
                false
            }

            else -> {
                hasFocus = false
                pendingPlay = false
                Log.w(TAG, "Audio focus request denied, playback suppressed")
                false
            }
        }
    }

    /**
     * Called when the user (or a media button) pauses deliberately.
     *
     * Focus is retained so resuming stays instant, but any pending auto-resume is cancelled and a
     * duck in progress is lifted: the user paused, so the volume they set should come back.
     */
    fun notifyUserPaused() {
        pendingPlay = false
        pausedBy = PAUSED_BY_NONE
        unduck()
    }

    /** Releases focus, unregisters the receiver and restores a ducked volume. Safe to call twice. */
    fun abandon() {
        if (released) return
        released = true
        pendingPlay = false
        pausedBy = PAUSED_BY_NONE
        unduck()
        unregisterNoisyReceiver()
        val manager = audioManager
        val request = focusRequest
        if (hasFocus && manager != null && request != null) {
            runCatching { manager.abandonAudioFocusRequest(request) }
                .onFailure { Log.w(TAG, "abandonAudioFocusRequest failed: ${it.message}") }
        }
        hasFocus = false
    }

    /** Resets the abandoned state so the manager can accept requests upon engine re-initialization. */
    fun reset() {
        released = false
        hasFocus = false
        pendingPlay = false
        pausedBy = PAUSED_BY_NONE
        focusUnavailableLogged = false
    }

    private fun handleFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                pendingPlay = false
                pausedBy = PAUSED_BY_NONE
                unduck()
                Log.i(TAG, "Focus lost permanently, pausing without auto-resume")
                listener.onPauseRequested()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                pendingPlay = false
                pausedBy = PAUSED_BY_TRANSIENT
                unduck()
                Log.i(TAG, "Focus lost transiently, pausing and arming auto-resume")
                listener.onPauseRequested()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "Focus duckable, lowering system stream")
                duck()
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                unduck()
                when {
                    pendingPlay -> {
                        pendingPlay = false
                        pausedBy = PAUSED_BY_NONE
                        Log.i(TAG, "Deferred focus granted, starting playback")
                        listener.onPlayRequested()
                    }

                    pausedBy == PAUSED_BY_TRANSIENT -> {
                        pausedBy = PAUSED_BY_NONE
                        Log.i(TAG, "Focus regained after transient loss, resuming")
                        listener.onPlayRequested()
                    }

                    else -> Log.i(TAG, "Focus regained, staying paused")
                }
            }
        }
    }

    private fun duck() {
        val manager = audioManager ?: return
        if (duckedFromVolume >= 0) return
        val current = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }
            .getOrDefault(0)
        val max = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
            .getOrDefault(0)
        val target = AudioDuckPolicy.duckTarget(current, max)
        if (target >= current) {
            Log.d(TAG, "Duck skipped: current=$current max=$max has no headroom")
            return
        }
        val applied = runCatching {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }.isSuccess
        if (!applied) {
            Log.w(TAG, "setStreamVolume failed, duck not applied")
            return
        }
        duckedFromVolume = current
        duckedToVolume = target
        listener.onDucked()
    }

    private fun unduck() {
        val from = duckedFromVolume
        val to = duckedToVolume
        duckedFromVolume = -1
        duckedToVolume = -1
        if (from < 0 || to < 0) return
        val manager = audioManager ?: return
        val current = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }
            .getOrDefault(-1)
        if (!AudioDuckPolicy.shouldRestoreVolume(current, to)) {
            Log.d(TAG, "Duck restore skipped: volume changed externally (current=$current, ducked=$to)")
            return
        }
        runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, from, 0) }
            .onSuccess { listener.onUnducked() }
            .onFailure { Log.w(TAG, "Restoring ducked volume failed: ${it.message}") }
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiver != null || released) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
                Log.i(TAG, "Audio becoming noisy, pausing without auto-resume")
                pendingPlay = false
                pausedBy = PAUSED_BY_NONE
                listener.onPauseRequested()
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onSuccess {
            noisyReceiver = receiver
        }.onFailure {
            Log.w(TAG, "Failed to register becoming-noisy receiver: ${it.message}")
        }
    }

    private fun unregisterNoisyReceiver() {
        noisyReceiver?.let { receiver ->
            runCatching { appContext.unregisterReceiver(receiver) }
                .onFailure { Log.w(TAG, "Failed to unregister noisy receiver: ${it.message}") }
        }
        noisyReceiver = null
    }
}
