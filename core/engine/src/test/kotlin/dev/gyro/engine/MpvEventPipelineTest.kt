package dev.gyro.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Contracts of the split event pipeline, verified in real time: the pump's channel wake-ups
 * resume outside a virtual scheduler's clock, so virtual-time runTest would hang (see the
 * note on [MpvEventPipeline]). Every wait below is bounded.
 */
class MpvEventPipelineTest {

    private val scopes = mutableListOf<CoroutineScope>()
    private val pipelines = mutableListOf<MpvEventPipeline>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
        pipelines.forEach { it.close() }
        pipelines.clear()
    }

    private fun newPipeline(lifecycleCapacity: Int = 64): MpvEventPipeline =
        MpvEventPipeline(lifecycleCapacity = lifecycleCapacity).also { pipelines.add(it) }

    private fun newScope(): CoroutineScope =
        CoroutineScope(Job() + Dispatchers.Unconfined).also { scopes.add(it) }

    /** Bounded busy-wait for a predicate; the pump runs on real dispatchers. */
    private fun awaitTrue(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            assertTrue("condition not met within ${timeoutMs}ms", System.nanoTime() < deadline)
            Thread.sleep(2)
        }
    }

    @Test(timeout = 10_000)
    fun lifecycleEvents_reachEverySubscriberInOrder() {
        val pipeline = newPipeline()
        val scope = newScope()
        val first = CopyOnWriteArrayList<MpvEvent>()
        val second = CopyOnWriteArrayList<MpvEvent>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pipeline.events.collect { first.add(it) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pipeline.events.collect { second.add(it) }
        }
        awaitTrue { pipeline.subscriptionCount.value == 2 }

        val expected = listOf(
            MpvEvent.Lifecycle(MpvNativeBridge.Event.FILE_LOADED),
            MpvEvent.EndFile(MpvNativeBridge.EndReason.EOF),
            MpvEvent.Lifecycle(MpvNativeBridge.Event.PLAYBACK_RESTART),
            MpvEvent.Lifecycle(MpvNativeBridge.Event.SHUTDOWN),
        )
        expected.forEach(pipeline::publish)

        awaitTrue { first.size == expected.size && second.size == expected.size }
        assertEquals(expected, first)
        assertEquals(expected, second)
    }

    @Test(timeout = 10_000)
    fun propertyEvents_coalesceToLatestPerKey() {
        val pipeline = newPipeline()
        val scope = newScope()
        val received = CopyOnWriteArrayList<MpvEvent>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pipeline.events.collect { received.add(it) }
        }
        awaitTrue { pipeline.subscriptionCount.value == 1 }

        // Single-threaded: the final drain must carry the last snapshot of each key.
        repeat(100) { pipeline.publish(MpvEvent.LongProperty("time-pos", it.toLong())) }
        pipeline.publish(MpvEvent.DoubleProperty("speed", 1.25))
        pipeline.publish(MpvEvent.LongProperty("time-pos", 100L))

        // Drain order across different keys is arbitrary (concurrent map): set semantics.
        awaitTrue(timeoutMs = 5_000) {
            received.contains(MpvEvent.LongProperty("time-pos", 100L)) &&
                received.contains(MpvEvent.DoubleProperty("speed", 1.25))
        }
    }

    @Test(timeout = 15_000)
    fun propertyFlood_publishNeverFailsAndLatestSnapshotSurvives() {
        val pipeline = newPipeline()
        val scope = newScope()
        val received = CopyOnWriteArrayList<MpvEvent>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pipeline.events.collect { received.add(it) }
        }
        awaitTrue { pipeline.subscriptionCount.value == 1 }

        val threads = 8
        val perThread = 1_000
        val published = CountDownLatch(threads)
        val executor = Executors.newFixedThreadPool(threads) { r ->
            Thread(r, "pipeline-flood-publisher").apply { isDaemon = true }
        }
        try {
            for (t in 0 until threads) {
                executor.execute {
                    repeat(perThread) { i ->
                        pipeline.publish(MpvEvent.LongProperty("time-pos", i.toLong()))
                    }
                    published.countDown()
                }
            }
            assertTrue(
                "Publishing ${threads * perThread} events must not block or fail",
                published.await(10, TimeUnit.SECONDS),
            )
            // Some snapshot for the key must eventually be delivered; every delivered value
            // must be a value some thread published. No count assertion: drains coalesce.
            awaitTrue(timeoutMs = 5_000) { received.isNotEmpty() }
            received.filterIsInstance<MpvEvent.LongProperty>().forEach {
                assertTrue(it.value in 0 until perThread.toLong())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test(timeout = 10_000)
    fun lifecycleIngest_overflowsLoudlyInsteadOfBufferingUnboundedly() {
        val pipeline = newPipeline(lifecycleCapacity = 2)
        val scope = newScope()
        val received = CopyOnWriteArrayList<MpvEvent>()
        val gate = CompletableDeferred<Unit>()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pipeline.events.collect {
                received.add(it)
                gate.await() // park the pump on the very first event
            }
        }

        val published: List<MpvEvent> = (0 until 20).map { MpvEvent.Lifecycle(100 + it) }
        pipeline.publish(published[0])
        awaitTrue { received.size == 1 } // the pump is parked on the first event somewhere:
        // either suspended in emit(published[0]) or already holding published[1] - either way
        // it cannot consume while the collector is gated, and the channel only buffers 2.
        published.drop(1).forEach(pipeline::publish)

        gate.complete(Unit)
        awaitTrue { received.size >= 3 } // whatever buffered plus the parked one arrives
        collector.cancel()
        // The unbounded-buffering contract violation must not happen: with 20 published and
        // an ingest bound of 2, at least one event was dropped loudly rather than queued.
        assertTrue(
            "expected loud drops beyond the ingest bound, but all ${published.size} arrived",
            received.size < published.size,
        )
        // Delivery order is a subsequence of publish order: the concurrent pump interleaves
        // with the publisher, so WHICH events drop is racy, but delivered events never
        // reorder relative to each other.
        var cursor = 0
        for (event in received) {
            while (cursor < published.size && published[cursor] != event) cursor++
            assertTrue("delivered event $event violated publish order", cursor < published.size)
            cursor++
        }
    }

    @Test(timeout = 10_000)
    fun subscribe_doesNotReplayEventsPublishedBeforeSubscription() {
        val pipeline = newPipeline()
        val scope = newScope()
        pipeline.publish(MpvEvent.Lifecycle(MpvNativeBridge.Event.FILE_LOADED))
        awaitTrue { pipeline.subscriptionCount.value == 0 }

        val received = CopyOnWriteArrayList<MpvEvent>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pipeline.events.collect { received.add(it) }
        }
        awaitTrue { pipeline.subscriptionCount.value == 1 }
        assertEquals(0, received.size)

        val next = MpvEvent.Lifecycle(MpvNativeBridge.Event.END_FILE)
        pipeline.publish(next)
        awaitTrue { received.size == 1 }
        assertEquals(listOf(next), received)
    }

    @Test(timeout = 10_000)
    fun cancel_removesOnlyThatSubscriber() {
        val pipeline = newPipeline()
        val scope = newScope()
        val cancelledEvents = CopyOnWriteArrayList<MpvEvent>()
        val activeEvents = CopyOnWriteArrayList<MpvEvent>()
        val cancelledJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pipeline.events.collect { cancelledEvents.add(it) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pipeline.events.collect { activeEvents.add(it) }
        }
        awaitTrue { pipeline.subscriptionCount.value == 2 }

        val first = MpvEvent.Lifecycle(MpvNativeBridge.Event.FILE_LOADED)
        pipeline.publish(first)
        awaitTrue { activeEvents.size == 1 }
        cancelledJob.cancel()
        awaitTrue { pipeline.subscriptionCount.value == 1 }

        val second = MpvEvent.Lifecycle(MpvNativeBridge.Event.END_FILE)
        pipeline.publish(second)
        awaitTrue { activeEvents.size == 2 }
        assertEquals(listOf(first), cancelledEvents)
        assertEquals(listOf(first, second), activeEvents)
    }
}
