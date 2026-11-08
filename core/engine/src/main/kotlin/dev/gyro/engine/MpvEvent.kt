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
}
