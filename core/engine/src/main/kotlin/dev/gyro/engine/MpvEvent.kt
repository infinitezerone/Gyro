package dev.gyro.engine

sealed interface MpvEvent {
    data class Lifecycle(val id: Int) : MpvEvent
    data class PropertyChanged(val name: String) : MpvEvent
    data class LongProperty(val name: String, val value: Long) : MpvEvent
    data class DoubleProperty(val name: String, val value: Double) : MpvEvent
    data class FlagProperty(val name: String, val value: Boolean) : MpvEvent
    data class StringProperty(val name: String, val value: String) : MpvEvent
}
