package dev.gyro.core.common.dispatcher

import javax.inject.Qualifier

/**
 * Qualifier annotation for injecting CoroutineDispatchers, aligned with Now in Android architecture.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val dispatcher: GyroDispatchers)

enum class GyroDispatchers {
    Default,
    IO,
    Main,
}
