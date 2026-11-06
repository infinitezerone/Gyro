package dev.gyro.core.common.dispatcher

import javax.inject.Qualifier

/**
 * Qualifier annotation for injecting application-level CoroutineScope, aligned with Now in Android.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
