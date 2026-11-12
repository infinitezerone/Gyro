package dev.gyro.engine.state

import dev.gyro.engine.model.PlaybackDiagnostics
import dev.gyro.engine.model.PlaybackSnapshot
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
class PlaybackStore {

    private val _state = MutableStateFlow(PlaybackSnapshot())
    val state: StateFlow<PlaybackSnapshot> = _state.asStateFlow()

    /**
     * Per-second telemetry, intentionally outside [state].
     *
     * These values change on mpv's reporting cadence, not on state transitions. Keeping them in
     * the snapshot meant a dropped-frame counter invalidated every snapshot consumer; here they
     * invalidate only the readers that asked for them.
     */
    private val _diagnostics = MutableStateFlow(PlaybackDiagnostics())
    val diagnostics: StateFlow<PlaybackDiagnostics> = _diagnostics.asStateFlow()

    fun updateDiagnostics(transform: (PlaybackDiagnostics) -> PlaybackDiagnostics) {
        _diagnostics.update(transform)
    }

    private class BatchContext(
        val base: PlaybackSnapshot,
        var current: PlaybackSnapshot
    ) {
        val transforms = ArrayList<(PlaybackSnapshot) -> PlaybackSnapshot>(8)
        var depth = 0
    }

    private val batchContext = ThreadLocal<BatchContext?>()

    /**
     * Current snapshot visible to the calling thread (includes uncommitted writes inside [batch]).
     */
    val current: PlaybackSnapshot
        get() = batchContext.get()?.current ?: _state.value

    fun update(transform: (PlaybackSnapshot) -> PlaybackSnapshot) {
        val ctx = batchContext.get()
        if (ctx != null) {
            ctx.transforms.add(transform)
            ctx.current = transform(ctx.current)
        } else {
            _state.update(transform)
        }
    }

    /**
     * Coalesces all [update] and [StoreProperty] writes inside [block] on the current thread
     * into a single atomic [state] emission upon completion. Supports re-entrant nesting.
     */
    fun batch(block: () -> Unit) {
        val existing = batchContext.get()
        if (existing != null) {
            existing.depth += 1
            try {
                block()
            } finally {
                existing.depth -= 1
            }
            return
        }

        val initial = _state.value
        val ctx = BatchContext(base = initial, current = initial)
        ctx.depth = 1
        batchContext.set(ctx)
        try {
            block()
        } finally {
            batchContext.remove()
            if (ctx.transforms.isNotEmpty()) {
                _state.update { latest ->
                    if (latest === ctx.base) {
                        ctx.current
                    } else {
                        ctx.transforms.fold(latest) { acc, fn -> fn(acc) }
                    }
                }
            }
        }
    }

    /** A read-only, zero-coroutine synchronous view of one field of the state. */
    fun <T> select(selector: (PlaybackSnapshot) -> T): StateFlow<T> =
        DerivedStateFlow(this, _state, selector)

    /**
     * A field-like handle on one state value. Writes funnel into [update], so existing `.value =`
     * call sites keep working while the underlying container is shared.
     */
    fun <T> property(
        read: (PlaybackSnapshot) -> T,
        write: (PlaybackSnapshot, T) -> PlaybackSnapshot
    ): StoreProperty<T> = StoreProperty(this, read, write)

    class StoreProperty<T>(
        private val store: PlaybackStore,
        private val read: (PlaybackSnapshot) -> T,
        private val write: (PlaybackSnapshot, T) -> PlaybackSnapshot
    ) {
        var value: T
            get() = read(store.current)
            set(newValue) {
                store.update { write(it, newValue) }
            }

        private val flow: StateFlow<T> by lazy { store.select(read) }

        fun asStateFlow(): StateFlow<T> = flow
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class DerivedStateFlow<T>(
        private val store: PlaybackStore,
        private val parent: StateFlow<PlaybackSnapshot>,
        private val selector: (PlaybackSnapshot) -> T
    ) : StateFlow<T> {
        override val value: T
            get() = selector(store.current)

        override val replayCache: List<T>
            get() = listOf(value)

        override suspend fun collect(collector: FlowCollector<T>): Nothing {
            parent.map(selector).distinctUntilChanged().collect(collector)
            awaitCancellation()
        }
    }
}
