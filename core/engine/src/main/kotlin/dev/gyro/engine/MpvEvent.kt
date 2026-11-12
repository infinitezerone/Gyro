package dev.gyro.engine

sealed interface MpvEvent {
    val epoch: Long
        get() = 0L

    data class Lifecycle(val id: Int, override val epoch: Long = 0L) : MpvEvent
    data class EndFile(val reason: Int, override val epoch: Long = 0L) : MpvEvent
    data class PropertyChanged(val name: String, override val epoch: Long = 0L) : MpvEvent
    data class LongProperty(val name: String, val value: Long, override val epoch: Long = 0L) : MpvEvent
    data class DoubleProperty(val name: String, val value: Double, override val epoch: Long = 0L) : MpvEvent
    data class FlagProperty(val name: String, val value: Boolean, override val epoch: Long = 0L) : MpvEvent
    data class StringProperty(val name: String, val value: String, override val epoch: Long = 0L) : MpvEvent

    /**
     * At least one lifecycle transition was dropped because the ingest channel was full.
     *
     * The event itself carries no replayable state: it is a signal to re-read the authoritative
     * values from mpv, because the state machine can no longer be trusted to have seen every
     * transition. Delivered after the lifecycle backlog drains, so it never reorders past the
     * events that did survive.
     */
    data class Desync(val droppedEvents: Int = 1, override val epoch: Long = 0L) : MpvEvent
}
