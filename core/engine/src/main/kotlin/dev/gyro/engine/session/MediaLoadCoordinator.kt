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
 *
 * Session identity: the epoch is owned by the native bridge (Rust `G_SESSION_EPOCH` is the
 * single authority). This coordinator never caches it — every freshness check re-reads
 * `bridge.currentEpoch()` — so an in-flight resolve/submit can never mistake a stale epoch
 * for the current session even if another caller (e.g. the background service) advanced the
 * session concurrently.
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
    private var pendingEpoch: Long = 0L

    @Volatile
    private var currentLoadJob: Job? = null

    private fun discardPending() {
        pendingSource?.close()
        pendingSource = null
        pendingLabel = ""
        pendingEpoch = 0L
    }

    fun load(item: MediaItem) {
        val target = item.target.ifBlank { item.uri?.toString().orEmpty() }
        // Ensure the native engine exists BEFORE this load captures its epoch. Lazy init runs
        // reset_engine_lifecycle_state, which advances the native epoch counter; bumping first
        // would make the very first load after create trail the native counter (observed on
        // device: LOAD_URI epoch=1 while the first-frame signal fired with epoch=2), leaving the
        // Kotlin mirror and Rust authority out of sync for that session. Idempotent (CAS).
        ensureInitialized()
        val epoch = bridge.nextEpoch()
        beginLoad(
            target = target,
            title = item.title,
            startPositionMs = item.startPositionMs,
            uri = item.uri,
            initialDurationMs = item.initialDurationMs,
            initialWidth = item.initialWidth,
            initialHeight = item.initialHeight,
            epoch = epoch
        )

        currentLoadJob?.cancel()
        discardPending()

        val uri = item.uri
        if (uri != null && (uri.scheme == "content" || target.startsWith("content://"))) {
            val jobEpoch = epoch
            currentLoadJob = coroutineScope().launch {
                var owned: ResolvedMediaSource? = null
                try {
                    val resolved = resolver.resolve(item)
                    owned = resolved
                    SwitchTrace.log("RESOLVE_PATH_DONE", "target=${resolved.targetDescription}, epoch=$jobEpoch")
                    withContext(Dispatchers.Main) {
                        if (jobEpoch == bridge.currentEpoch()) {
                            submitOrQueue(resolved, item.startPositionMs, target, epoch = jobEpoch)
                        } else {
                            EngineLog.i(TAG, "load: resolved target for expired epoch $jobEpoch (current=${bridge.currentEpoch()}), discarding")
                            owned.close()
                        }
                    }
                    owned = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    if (jobEpoch == bridge.currentEpoch()) {
                        EngineLog.e(TAG, "load: cannot resolve $target", t)
                        store.update {
                            it.copy(
                                session = SessionPhase.Failed(target, t),
                                error = t,
                                playbackState = PlaybackState.ERROR
                            )
                        }
                    }
                } finally {
                    owned?.close()
                }
            }
        } else {
            submitOrQueue(ResolvedMediaSource(target), item.startPositionMs, target, epoch = epoch)
        }
    }

    private fun beginLoad(
        target: String,
        title: String?,
        startPositionMs: Long,
        uri: Uri?,
        initialDurationMs: Long = 0L,
        initialWidth: Int = 0,
        initialHeight: Int = 0,
        epoch: Long = bridge.currentEpoch()
    ) {
        SwitchTrace.start("LOAD_URI", "uri=$target, startPos=$startPositionMs, epoch=$epoch")
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
        set(value) {
            field = value
            // Entering headless (background) mode must submit a load queued while awaiting a surface,
            // mirroring attachSurface — otherwise a load queued in the detach->service-start window
            // would stall until the user returns to the foreground.
            if (value) flushPendingSourceOnSurfaceReady()
        }

    private fun submitOrQueue(
        source: ResolvedMediaSource,
        startPositionMs: Long,
        label: String,
        epoch: Long = bridge.currentEpoch()
    ) {
        if (epoch != bridge.currentEpoch()) {
            EngineLog.i(TAG, "submitOrQueue: dropping obsolete load for epoch $epoch (current=${bridge.currentEpoch()})")
            source.close()
            return
        }
        if (!isSurfaceAttached && !isHeadlessMode) {
            EngineLog.i(TAG, "load: surface not attached yet, queueing $label (epoch=$epoch)")
            discardPending()
            pendingSource = source
            pendingStartMs = startPositionMs
            pendingLabel = label
            pendingEpoch = epoch
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
        submit(source, startPositionMs, label, epoch = epoch)
    }

    private fun submit(
        source: ResolvedMediaSource,
        startPositionMs: Long,
        label: String,
        epoch: Long = bridge.currentEpoch()
    ) {
        if (epoch != bridge.currentEpoch()) {
            EngineLog.i(TAG, "submit: dropping obsolete load for epoch $epoch (current=${bridge.currentEpoch()})")
            source.close()
            return
        }
        val target = source.takeTargetUrl()
        SwitchTrace.log("LOAD_COMMAND_ISSUED", "url=$target, isSurfaceAttached=$isSurfaceAttached, epoch=$epoch")
        EngineLog.i(TAG, "submit: url=$target, startPositionMs=$startPositionMs, epoch=$epoch")
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
            val epoch = pendingEpoch
            pendingSource = null
            pendingLabel = ""
            pendingEpoch = 0L
            if (epoch == bridge.currentEpoch()) {
                EngineLog.i(TAG, "attachSurface: surface ready, flushing queued $label (epoch=$epoch)")
                submit(source, startMs, label, epoch = epoch)
            } else {
                EngineLog.i(TAG, "attachSurface: discarding stale queued source for epoch $epoch (current=${bridge.currentEpoch()})")
                source.close()
            }
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
        bridge.nextEpoch()
        currentLoadJob?.cancel()
        discardPending()
    }
}
