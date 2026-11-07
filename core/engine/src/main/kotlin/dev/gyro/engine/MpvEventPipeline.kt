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
 *   (default 64, far beyond anything mpv can produce between pump passes) and are never
 *   dropped. Overflowing it means engine internals are broken, so it is logged loudly
 *   instead of silently swallowing a transition.
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

    private val pumpScope: CoroutineScope =
        parentScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val pumpJobs: List<Job> = listOf(
        pumpScope.launch { for (event in lifecycleChannel) _events.emit(event) },
        pumpScope.launch {
            for (wake in wakeUp) {
                for ((key, event) in propertyLatest) {
                    // Remove only what was actually emitted: a newer snapshot published
                    // while this drain suspends stays in the map for the next pass.
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
        if (event is MpvEvent.Lifecycle || event is MpvEvent.EndFile) {
            val result = lifecycleChannel.trySend(event)
            if (result.isFailure) {
                EngineLog.e(
                    TAG,
                    "Lifecycle channel overflow (capacity=$lifecycleCapacity), dropping $event - " +
                        "a collector is stalled far beyond any legitimate mpv burst"
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
