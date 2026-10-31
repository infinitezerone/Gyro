package dev.gyro.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.VideoDimensions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

class GyroPlayerEngine(
    private val context: Context
) {
    companion object {
        private const val TAG = "GyroPlayerEngine"
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isInitialized = AtomicBoolean(false)

    // Flow states
    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _timePosMs = MutableStateFlow(0L)
    val timePosMs: StateFlow<Long> = _timePosMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _bufferedPosMs = MutableStateFlow(0L)
    val bufferedPosMs: StateFlow<Long> = _bufferedPosMs.asStateFlow()

    private val _videoDimensions = MutableStateFlow(VideoDimensions())
    val videoDimensions: StateFlow<VideoDimensions> = _videoDimensions.asStateFlow()

    private val _hwdecCurrent = MutableStateFlow("")
    val hwdecCurrent: StateFlow<String> = _hwdecCurrent.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private var pendingSeekMs: Long = -1L
    private var fileLoaded: Boolean = false

    private val nativeListener = object : GyroNative.EventListener {
        override fun onEvent(eventId: Int) {
            when (eventId) {
                GyroNative.Event.FILE_LOADED -> {
                    fileLoaded = true
                    val hwdec = GyroNative.getPropertyString("hwdec-current") ?: "none"
                    _hwdecCurrent.value = hwdec
                    _playbackState.value = PlaybackState.READY
                    Log.i(TAG, "File loaded, active hwdec=$hwdec")

                    if (pendingSeekMs >= 0L) {
                        val target = pendingSeekMs
                        pendingSeekMs = -1L
                        seekTo(target)
                    }
                }
                GyroNative.Event.END_FILE -> {
                    fileLoaded = false
                    _isPlaying.value = false
                    _playbackState.value = PlaybackState.ENDED
                }
                GyroNative.Event.SHUTDOWN -> {
                    fileLoaded = false
                    _isPlaying.value = false
                    _playbackState.value = PlaybackState.IDLE
                }
                GyroNative.Event.PLAYBACK_RESTART -> {
                    _playbackState.value = PlaybackState.READY
                }
                GyroNative.Event.WATCHDOG_FALLBACK -> {
                    _hwdecCurrent.value = "mediacodec-copy (fallback)"
                    Log.w(TAG, "Watchdog triggered: GPU hang/timeout detected, safely fell back to mediacodec-copy")
                }
            }
        }

        override fun onProperty(name: String) {}

        override fun onPropertyLong(name: String, value: Long) {
            when (name) {
                "width" -> {
                    val h = GyroNative.getLong("height")?.toInt() ?: 0
                    if (value > 0 && h > 0) {
                        _videoDimensions.value = VideoDimensions(value.toInt(), h)
                    }
                }
                "height" -> {
                    val w = GyroNative.getLong("width")?.toInt() ?: 0
                    if (w > 0 && value > 0) {
                        _videoDimensions.value = VideoDimensions(w, value.toInt())
                    }
                }
            }
        }

        override fun onPropertyDouble(name: String, value: Double) {
            when (name) {
                "time-pos" -> {
                    _timePosMs.value = (value * 1000.0).toLong().coerceAtLeast(0L)
                }
                "duration" -> {
                    _durationMs.value = (value * 1000.0).toLong().coerceAtLeast(0L)
                }
                "demuxer-cache-time" -> {
                    _bufferedPosMs.value = (value * 1000.0).toLong().coerceAtLeast(_timePosMs.value)
                }
                "speed" -> {
                    _speed.value = value.toFloat()
                }
            }
        }

        override fun onPropertyFlag(name: String, value: Boolean) {
            when (name) {
                "pause" -> {
                    _isPlaying.value = !value
                    if (!value && _playbackState.value != PlaybackState.BUFFERING) {
                        _playbackState.value = PlaybackState.READY
                    }
                }
                "paused-for-cache" -> {
                    if (value) {
                        _playbackState.value = PlaybackState.BUFFERING
                    } else if (_isPlaying.value) {
                        _playbackState.value = PlaybackState.READY
                    }
                }
            }
        }

        override fun onPropertyString(name: String, value: String) {
            when (name) {
                "hwdec-current" -> _hwdecCurrent.value = value
            }
        }
    }

    fun ensureInitialized() {
        if (isInitialized.compareAndSet(false, true)) {
            try {
                GyroNative.create(appContext)

                // Query static environment probe
                val isZeroCopySafe = GyroNative.isZeroCopySafe()
                val gpuRenderer = GyroNative.getGpuRenderer()
                val hwdecConfig = if (isZeroCopySafe) "mediacodec,mediacodec-copy" else "mediacodec-copy"
                Log.i(TAG, "EnvProbe: GPU='$gpuRenderer', ZeroCopySafe=$isZeroCopySafe, configuring hwdec='$hwdecConfig'")

                // Standard modern MPV reference config
                GyroNative.setOptionString("vo", "libmpv")
                GyroNative.setOptionString("idle", "yes")
                GyroNative.setOptionString("hwdec", hwdecConfig)
                GyroNative.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
                GyroNative.setOptionString("ao", "audiotrack,opensles")
                GyroNative.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
                GyroNative.setOptionString("demuxer-max-back-bytes", "${32 * 1024 * 1024}")
                GyroNative.setOptionString("sub-auto", "all")
                GyroNative.setOptionString("sub-font-provider", "none")
                GyroNative.setOptionString("sub-ass-override", "no")
                GyroNative.setOptionString("blend-subtitles", "no")
                GyroNative.setOptionString("keep-open", "yes")

                GyroNative.init()

                GyroNative.eventListener = nativeListener

                // Observe properties
                GyroNative.observeProperty("time-pos", GyroNative.Format.DOUBLE)
                GyroNative.observeProperty("duration", GyroNative.Format.DOUBLE)
                GyroNative.observeProperty("pause", GyroNative.Format.FLAG)
                GyroNative.observeProperty("paused-for-cache", GyroNative.Format.FLAG)
                GyroNative.observeProperty("demuxer-cache-time", GyroNative.Format.DOUBLE)
                GyroNative.observeProperty("width", GyroNative.Format.INT64)
                GyroNative.observeProperty("height", GyroNative.Format.INT64)
                GyroNative.observeProperty("hwdec-current", GyroNative.Format.STRING)
                GyroNative.observeProperty("speed", GyroNative.Format.DOUBLE)

                Log.i(TAG, "GyroPlayerEngine initialized successfully")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize GyroPlayerEngine", t)
                isInitialized.set(false)
            }
        }
    }

    fun loadUri(uri: android.net.Uri, startPositionMs: Long = 0L) {
        val targetPath = try {
            val pfd = appContext.contentResolver.openFileDescriptor(uri, "r")
            val fd = pfd?.detachFd() ?: -1
            if (fd >= 0) {
                "fdclose://$fd"
            } else {
                uri.path ?: uri.toString()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "openFileDescriptor fallback for uri=$uri: ${e.message}")
            if (uri.scheme == "file") {
                uri.path ?: uri.toString()
            } else {
                uri.toString()
            }
        }
        Log.i(TAG, "loadUri: uri=$uri -> targetPath=$targetPath")
        load(targetPath, startPositionMs)
    }

    @Volatile
    private var isSurfaceAttached = false
    private var pendingLoadUrl: String? = null
    private var pendingLoadStartMs: Long = 0L

    fun load(url: String, startPositionMs: Long = 0L) {
        Log.i(TAG, "load: url=$url, startPositionMs=$startPositionMs, surfaceAttached=$isSurfaceAttached")
        ensureInitialized()
        if (!isSurfaceAttached) {
            Log.i(TAG, "load: SurfaceView 尚未挂载，先进入等待队列: $url")
            pendingLoadUrl = url
            pendingLoadStartMs = startPositionMs
            _playbackState.value = PlaybackState.BUFFERING
            return
        }
        pendingLoadUrl = null
        fileLoaded = false
        pendingSeekMs = if (startPositionMs > 0L) startPositionMs else -1L
        _playbackState.value = PlaybackState.BUFFERING
        GyroNative.command(arrayOf("loadfile", url, "replace"))
        play()
    }

    fun play() {
        GyroNative.setPropertyBoolean("pause", false)
    }

    fun pause() {
        GyroNative.setPropertyBoolean("pause", true)
    }

    fun togglePlayPause() {
        if (_isPlaying.value) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(positionMs: Long) {
        if (!fileLoaded) {
            pendingSeekMs = positionMs
            return
        }
        val seconds = positionMs / 1000.0
        GyroNative.command(arrayOf("seek", "$seconds", "absolute"))
    }

    fun seekRelative(offsetMs: Long) {
        if (!fileLoaded) return
        val seconds = offsetMs / 1000.0
        GyroNative.command(arrayOf("seek", "$seconds", "relative"))
    }

    fun setSpeed(speed: Float) {
        GyroNative.setPropertyDouble("speed", speed.toDouble().coerceIn(0.25, 4.0))
    }

    fun attachSurface(surface: Surface) {
        isSurfaceAttached = true
        GyroNative.attachSurface(surface)
        pendingLoadUrl?.let { url ->
            Log.i(TAG, "attachSurface: Surface 已就绪，触发排队的待播视频: $url")
            load(url, pendingLoadStartMs)
        }
    }

    fun detachSurface() {
        isSurfaceAttached = false
        GyroNative.detachSurface()
    }

    fun release() {
        isInitialized.set(false)
        GyroNative.eventListener = null
        GyroNative.destroy()
        _playbackState.value = PlaybackState.IDLE
        _isPlaying.value = false
    }
}
