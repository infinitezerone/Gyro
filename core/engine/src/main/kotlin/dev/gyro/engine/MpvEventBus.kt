package dev.gyro.engine

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Non-blocking inbound event pipeline dispatching [MpvEvent]s from JNI callbacks to Kotlin.
 *
 * Uses a buffered [MutableSharedFlow] with lock-free atomic [tryEmit] so that native JNI threads
 * never block or invoke [kotlinx.coroutines.runBlocking], while safely buffering rapid lifecycle
 * and property events without deadlocks.
 */
class MpvEventPipeline(bufferCapacity: Int = 4096) {
    private val mutableEvents = MutableSharedFlow<MpvEvent>(
        replay = 0,
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<MpvEvent> = mutableEvents.asSharedFlow()
    val subscriptionCount: StateFlow<Int> = mutableEvents.subscriptionCount

    fun publish(event: MpvEvent) {
        mutableEvents.tryEmit(event)
    }
}

typealias MpvEventBus = MpvEventPipeline
