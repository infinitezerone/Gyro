package dev.gyro.engine.session

import android.net.Uri
import android.view.Surface
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.audio.AudioEffectsController
import dev.gyro.engine.media.MediaItem
import dev.gyro.engine.media.MediaResourceResolver
import dev.gyro.engine.media.ResolvedMediaSource
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.SessionPhase
import dev.gyro.engine.state.PlaybackStore
import dev.gyro.engine.trace.EngineLog
import dev.gyro.engine.trace.SwitchTrace
import dev.gyro.engine.tuning.SyncTuningController
import dev.gyro.engine.video.VideoGeometryCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Coordinates media resolution, asynchronous queueing while awaiting Surface attachment,
 * and dispatching the `loadfile` command to mpv with appropriate resume timestamps.
 */
class MediaLoadCoordinator(
    private val bridge: MpvNativeBridge,
    private val store: PlaybackStore,
    private val resolver: MediaResourceResolver,
    private val timeline: PlaybackTimelineController,
    private val geometry: VideoGeometryCoordinator,
    private val audio: AudioEffectsController,
    private val tuning: SyncTuningController,
    private val playbackController: PlaybackLifecycleController,
    private val coroutineScope: () -> CoroutineScope,
    private val ensureInitialized: () -> Unit
) {

    companion object {
        private const val TAG = "MediaLoadCoordinator"
    }

    @Volatile
    var isSurfaceAttached: Boolean = false
        private set

    @Volatile
    var requestedTitle: String = ""
        private set

    private var attachedSurfaceRef: Any? = null

    @Volatile
    private var pendingSource: ResolvedMediaSource? = null

    @Volatile
    private var pendingStartMs: Long = 0L

    @Volatile
    private var pendingLabel: String = ""

    @Volatile
    private var currentLoadJob: Job? = null

    private fun discardPending() {
        pendingSource?.close()
        pendingSource = null
        pendingLabel = ""
    }

    fun load(item: MediaItem) {
        val target = item.target.ifBlank { item.uri?.toString().orEmpty() }
        beginLoad(
            target = target,
            title = item.title,
            startPositionMs = item.startPositionMs,
            uri = item.uri,
            initialDurationMs = item.initialDurationMs,
            initialWidth = item.initialWidth,
            initialHeight = item.initialHeight
        )

        currentLoadJob?.cancel()
        discardPending()

        if (item.uri != null && (item.uri.scheme == "content" || target.startsWith("content://"))) {
            currentLoadJob = coroutineScope().launch {
                var owned: ResolvedMediaSource? = null
                try {
                    val resolved = resolver.resolve(item)
                    owned = resolved
                    SwitchTrace.log("RESOLVE_PATH_DONE", "target=${resolved.targetDescription}")
                    withContext(Dispatchers.Main) {
                        submitOrQueue(resolved, item.startPositionMs, target)
                    }
                    owned = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    EngineLog.e(TAG, "load: cannot resolve $target", t)
                    store.update {
                        it.copy(
                            session = SessionPhase.Failed(target, t),
                            error = t,
                            playbackState = PlaybackState.ERROR
                        )
                    }
                } finally {
                    owned?.close()
                }
            }
        } else {
            submitOrQueue(ResolvedMediaSource(target), item.startPositionMs, target)
        }
    }

    private fun beginLoad(
        target: String,
        title: String?,
        startPositionMs: Long,
        uri: Uri?,
        initialDurationMs: Long = 0L,
        initialWidth: Int = 0,
        initialHeight: Int = 0
    ) {
        SwitchTrace.start("LOAD_URI", "uri=$target, startPos=$startPositionMs")
        ensureInitialized()

        store.batch {
            store.update {
                it.copy(
                    session = SessionPhase.Resolving(target, startPositionMs),
                    error = null,
                    currentUri = uri ?: it.currentUri
                )
            }
            geometry.onSwitchStarted(target, startPositionMs, initialWidth, initialHeight)
            audio.reset()
            tuning.reset()
            store.update { it.copy(playbackState = PlaybackState.BUFFERING) }
            timeline.onLoadStarted(startPositionMs, initialDurationMs)

            requestedTitle = title?.trim().orEmpty()
            val resolvedTitle = requestedTitle.ifBlank {
                (uri?.lastPathSegment ?: target.substringAfterLast('/').substringAfterLast('\\'))
                    .takeIf { candidate -> candidate.isNotBlank() && !candidate.startsWith("fdclose://") }
                    .orEmpty()
            }
            store.update { it.copy(mediaTitle = resolvedTitle) }
            bridge.setPropertyString("force-media-title", resolvedTitle)
        }
        SwitchTrace.log("SHUTTER_ARMED", "firstFrame=false, geometryReady=false")
    }

    @Volatile
    var isHeadlessMode: Boolean = false

    private fun submitOrQueue(source: ResolvedMediaSource, startPositionMs: Long, label: String) {
        if (!isSurfaceAttached && !isHeadlessMode) {
            EngineLog.i(TAG, "load: surface not attached yet, queueing $label")
            discardPending()
            pendingSource = source
            pendingStartMs = startPositionMs
            pendingLabel = label
            store.batch {
                store.update {
                    it.copy(
                        playbackState = PlaybackState.BUFFERING,
                        session = SessionPhase.AwaitingSurface(label, startPositionMs)
                    )
                }
            }
            return
        }
        submit(source, startPositionMs, label)
    }

    private fun submit(source: ResolvedMediaSource, startPositionMs: Long, label: String) {
        val target = source.takeTargetUrl()
        SwitchTrace.log("LOAD_COMMAND_ISSUED", "url=$target, isSurfaceAttached=$isSurfaceAttached")
        EngineLog.i(TAG, "submit: url=$target, startPositionMs=$startPositionMs")
        store.batch {
            timeline.onLoadCommandIssued()
            store.update {
                it.copy(
                    playbackState = PlaybackState.BUFFERING,
                    session = SessionPhase.Submitting(label, startPositionMs)
                )
            }
        }
        val startSeconds = startPositionMs / 1000.0
        val loadArgs = if (startPositionMs > 0L) {
            arrayOf("loadfile", target, "replace", "0", "start=+${String.format(Locale.US, "%.3f", startSeconds)}")
        } else {
            arrayOf("loadfile", target, "replace")
        }
        bridge.command(loadArgs)
        playbackController.play()
    }

    fun attachSurface(surface: Surface) {
        attachedSurfaceRef = surface
        isSurfaceAttached = true
        isHeadlessMode = false
        bridge.attachSurface(surface)
        flushPendingSourceOnSurfaceReady()
    }

    fun attachSurface(surfaceToken: Any) {
        if (surfaceToken is Surface) {
            attachSurface(surfaceToken)
            return
        }
        attachedSurfaceRef = surfaceToken
        isSurfaceAttached = true
        isHeadlessMode = false
        flushPendingSourceOnSurfaceReady()
    }

    private fun flushPendingSourceOnSurfaceReady() {
        pendingSource?.let { source ->
            val startMs = pendingStartMs
            val label = pendingLabel
            pendingSource = null
            pendingLabel = ""
            EngineLog.i(TAG, "attachSurface: surface ready, flushing queued $label")
            submit(source, startMs, label)
        }
    }

    fun detachSurface(surface: Any?) {
        if (!isSurfaceAttached) return
        if (surface != null && attachedSurfaceRef !== surface) {
            EngineLog.i(TAG, "detachSurface ignored: engine holds a different surface")
            return
        }
        isSurfaceAttached = false
        attachedSurfaceRef = null
        bridge.detachSurface()
    }

    fun redraw() {
        SwitchTrace.log("REDRAW_REQUESTED", "surfaceAttached=$isSurfaceAttached")
        if (isSurfaceAttached) {
            bridge.redraw()
        }
    }

    fun release() {
        currentLoadJob?.cancel()
        discardPending()
    }
}
