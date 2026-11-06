package dev.gyro.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking

class MpvEventBus {
    private val mutableEvents = MutableSharedFlow<MpvEvent>()
    val events: SharedFlow<MpvEvent> = mutableEvents.asSharedFlow()
    val subscriptionCount: StateFlow<Int> = mutableEvents.subscriptionCount

    fun publish(event: MpvEvent) {
        // JNI callbacks cannot suspend; backpressure preserves lifecycle events without replaying stale ones.
        runBlocking { mutableEvents.emit(event) }
    }
}
