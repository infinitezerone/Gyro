package dev.gyro.engine

import dev.gyro.engine.trace.EngineLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Inbound event pipeline dispatching [MpvEvent]s from JNI callbacks to Kotlin collectors.
 *
 * Events take one of two paths chosen by their semantics, which removes the need for any
 * tuned catch-up buffer (the previous design carried every event through a 4096-slot
 * DROP_OLDEST queue - a stress-test-derived magic number that could still silently lose
 * events):
 *
 * - Lifecycle events ([MpvEvent.Lifecycle], [MpvEvent.EndFile]) are state transitions whose
 *   volume is bounded by mpv's serial playback model. They flow through a small FIFO channel
 *   (default 64, far beyond anything mpv can produce between pump passes). If the channel is
 *   full the transition is lost, which is why a drop is not merely logged: it arms a
 *   [MpvEvent.Desync] marker that the pump emits once the backlog drains, telling consumers to
 *   re-read mpv instead of trusting a state machine that missed a step.
 *
 * - Property events (everything else: snapshots carrying a property `name`) are merged per
 *   key into a concurrent map - every publish succeeds instantly on the JNI thread - and
 *   the pump drains the map when woken. Coalescing discards only stale intermediate
 *   snapshots of the same property, so no information is lost and no backpressure exists.
 *
 * Ordering contract: lifecycle order is preserved. Property snapshots carry no ordering
 * guarantees against each other or against lifecycle events - consumers must treat them as
 * refresh signals and re-read current values through the bridge when exact timing matters
 * (the domain subsystems already do this).
 *
 * Outbound delivery is a plain SharedFlow with no extra buffer: the pump suspends on slow
 * collectors instead of dropping, so nothing is lost after the ingest point.
 *
 * Note for tests: the pump's channel wake-ups resume in real time even under a virtual
 * scheduler, so pipeline tests must drive it with real time and bounded latches rather
 * than `runTest`'s virtual clock.
 */
class MpvEventPipeline(
    parentScope: CoroutineScope? = null,
    private val lifecycleCapacity: Int = 64,
) {
    private val _events = MutableSharedFlow<MpvEvent>(replay = 0, extraBufferCapacity = 0)
    val events: SharedFlow<MpvEvent> = _events.asSharedFlow()
    val subscriptionCount: StateFlow<Int> = _events.subscriptionCount

    private val lifecycleChannel = Channel<MpvEvent>(lifecycleCapacity)
    private val propertyLatest = ConcurrentHashMap<String, MpvEvent>()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    /** Set when a lifecycle transition could not be queued; cleared when its marker is emitted. */
    private val desyncPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val droppedLifecycleEvents = java.util.concurrent.atomic.AtomicInteger(0)

    // Epoch ownership: the native bridge (Rust `G_SESSION_EPOCH`) is the single authority. This
    // AtomicLong is the Kotlin mirror, only advanced through `GyroNative.nextEpoch()` (native
    // call, or its JVM-test fallback) and merged monotonically via `updateEpoch` — never reset
    // backwards. Downstream consumers (`MediaLoadCoordinator`, `VideoGeometryCoordinator`) must
    // re-read the bridge instead of caching a copy, so a background load advancing the session
    // is visible to every check immediately.
    private val _currentEpoch = java.util.concurrent.atomic.AtomicLong(0L)
    var currentEpoch: Long
        get() = _currentEpoch.get()
        set(value) { _currentEpoch.set(value) }

    fun nextEpoch(): Long = _currentEpoch.incrementAndGet()

    fun updateEpoch(epoch: Long) {
        _currentEpoch.updateAndGet { current -> maxOf(current, epoch) }
        propertyLatest.clear()
    }

    fun resetEpoch(epoch: Long = 0L) {
        _currentEpoch.set(epoch)
        propertyLatest.clear()
    }

    private val pumpScope: CoroutineScope =
        parentScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val pumpJobs: List<Job> = listOf(
        pumpScope.launch {
            for (event in lifecycleChannel) {
                if (event.epoch >= _currentEpoch.get()) {
                    _events.emit(event)
                }
                // A gap is reported only once the surviving backlog is out, so consumers see a
                // consistent prefix before being told to re-read mpv.
                if (desyncPending.get() && lifecycleChannel.isEmpty) {
                    if (desyncPending.compareAndSet(true, false)) {
                        val dropped = droppedLifecycleEvents.getAndSet(0)
                        _events.emit(MpvEvent.Desync(dropped, _currentEpoch.get()))
                    }
                }
            }
        },
        pumpScope.launch {
            for (wake in wakeUp) {
                for ((key, event) in propertyLatest) {
                    // Remove only what was actually emitted: a newer snapshot published
                    // while this drain suspends stays in the map for the next pass.
                    if (event.epoch < _currentEpoch.get()) {
                        propertyLatest.remove(key, event)
                        continue
                    }
                    if (propertyLatest.remove(key, event)) {
                        _events.emit(event)
                    }
                }
            }
        },
    )

    /**
     * Ingestion point for JNI callback threads. Never blocks and never fails: lifecycle
     * events try the FIFO (overflow is logged, not thrown), property snapshots always land
     * in the merge map.
     */
    fun publish(event: MpvEvent) {
        if (event.epoch < _currentEpoch.get()) {
            return
        }
        if (event is MpvEvent.Lifecycle || event is MpvEvent.EndFile) {
            val result = lifecycleChannel.trySend(event)
            if (result.isFailure) {
                droppedLifecycleEvents.incrementAndGet()
                desyncPending.set(true)
                EngineLog.e(
                    TAG,
                    "Lifecycle channel overflow (capacity=$lifecycleCapacity), dropping $event - " +
                        "a collector is stalled far beyond any legitimate mpv burst; a Desync " +
                        "marker will be delivered once the backlog drains"
                )
            }
        } else {
            propertyLatest[propertyKey(event)] = event
            wakeUp.trySend(Unit)
        }
    }

    /** Stops the pump coroutines; the pipeline must not be published to afterwards. */
    fun close() {
        pumpJobs.forEach { it.cancel() }
        lifecycleChannel.close()
        wakeUp.close()
    }

    private fun propertyKey(event: MpvEvent): String = when (event) {
        is MpvEvent.PropertyChanged -> event.name
        is MpvEvent.LongProperty -> event.name
        is MpvEvent.DoubleProperty -> event.name
        is MpvEvent.FlagProperty -> event.name
        is MpvEvent.StringProperty -> event.name
        else -> throw IllegalArgumentException("non-property event reached the merge path: $event")
    }

    private companion object {
        private const val TAG = "MpvEventPipeline"
    }
}
