package dev.gyro.engine.state

import dev.gyro.engine.model.PlaybackSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single state container for the whole playback engine.
 *
 * Every controller writes through [update]; nothing else owns a `MutableStateFlow` of playback
 * state. What controllers expose as `StateFlow<T>` is a projection produced by [select], so there
 * is exactly one source of truth and every read is a view of it.
 *
 * `timePosMs`/`bufferedPosMs` deliberately stay outside this object: they change at report
 * frequency and must not make the UI recompose the whole surface on every tick.
 */
@Singleton
class PlaybackStore @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val _state = MutableStateFlow(PlaybackSnapshot())
    val state: StateFlow<PlaybackSnapshot> = _state.asStateFlow()

    fun update(transform: (PlaybackSnapshot) -> PlaybackSnapshot) {
        _state.update(transform)
    }

    /** A read-only, synchronous view of one field of the state. */
    fun <T> select(selector: (PlaybackSnapshot) -> T): StateFlow<T> =
        _state.map(selector)
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, selector(_state.value))

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
            get() = read(store.state.value)
            set(newValue) {
                store.update { write(it, newValue) }
            }

        private val flow: StateFlow<T> by lazy { store.select(read) }

        fun asStateFlow(): StateFlow<T> = flow
    }
}
