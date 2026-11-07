package dev.gyro.engine

/**
 * Common lifecycle contract for internal domain controllers managed by [GyroPlayerEngine].
 */
interface EngineSubsystem {
    fun registerObservers() {}
    fun onEvent(event: MpvEvent) {}
    fun reset() {}
}
