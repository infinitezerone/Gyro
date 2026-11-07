package dev.gyro.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MpvEventPipelineTest {

    private lateinit var pipeline: MpvEventPipeline
    private lateinit var eventScope: CoroutineScope

    @Before
    fun setUp() {
        pipeline = MpvEventPipeline()
        eventScope = CoroutineScope(Job() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        eventScope.cancel()
        assertEquals(0, pipeline.subscriptionCount.value)
    }

    private fun subscribe(events: MutableList<MpvEvent>): Job =
        eventScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            pipeline.events.collect { events.add(it) }
        }

    private fun allEventTypes(): List<MpvEvent> = listOf(
        MpvEvent.Lifecycle(MpvNativeBridge.Event.FILE_LOADED),
        MpvEvent.PropertyChanged("track-list"),
        MpvEvent.LongProperty("time-pos", 42L),
        MpvEvent.DoubleProperty("speed", 1.25),
        MpvEvent.FlagProperty("pause", true),
        MpvEvent.StringProperty("media-title", "Test Video")
    )

    @Test(timeout = 5_000)
    fun publish_deliversAllSixEventTypesToEverySubscriberInOrder() {
        val first = CopyOnWriteArrayList<MpvEvent>()
        val second = CopyOnWriteArrayList<MpvEvent>()
        subscribe(first)
        subscribe(second)
        assertEquals(2, pipeline.subscriptionCount.value)

        val expected = allEventTypes() + allEventTypes().reversed()
        expected.forEach(pipeline::publish)

        assertEquals(expected, first)
        assertEquals(expected, second)
    }

    @Test(timeout = 5_000)
    fun publish_withoutSubscribersReturnsWithoutBlocking() {
        assertEquals(0, pipeline.subscriptionCount.value)

        repeat(10) { allEventTypes().forEach(pipeline::publish) }

        assertEquals(0, pipeline.subscriptionCount.value)
        assertTrue(pipeline.events.replayCache.isEmpty())
    }

    @Test(timeout = 5_000)
    fun subscribe_doesNotReplayEventsPublishedBeforeSubscription() {
        allEventTypes().forEach(pipeline::publish)

        val first = CopyOnWriteArrayList<MpvEvent>()
        subscribe(first)
        assertTrue(first.isEmpty())

        val current = MpvEvent.Lifecycle(MpvNativeBridge.Event.PLAYBACK_RESTART)
        pipeline.publish(current)
        assertEquals(listOf(current), first)

        // Also exclude events published while another collector was active.
        val late = CopyOnWriteArrayList<MpvEvent>()
        subscribe(late)
        assertEquals(2, pipeline.subscriptionCount.value)
        assertTrue(late.isEmpty())
        assertTrue(pipeline.events.replayCache.isEmpty())

        val next = MpvEvent.Lifecycle(MpvNativeBridge.Event.END_FILE)
        pipeline.publish(next)
        assertEquals(listOf(current, next), first)
        assertEquals(listOf(next), late)
    }

    @Test(timeout = 5_000)
    fun cancel_removesOnlyThatSubscriberAndStopsItsNextEvent() {
        val cancelledEvents = CopyOnWriteArrayList<MpvEvent>()
        val activeEvents = CopyOnWriteArrayList<MpvEvent>()
        val cancelled = subscribe(cancelledEvents)
        val active = subscribe(activeEvents)
        assertEquals(2, pipeline.subscriptionCount.value)

        val first = MpvEvent.Lifecycle(MpvNativeBridge.Event.FILE_LOADED)
        pipeline.publish(first)
        cancelled.cancel()
        assertTrue(cancelled.isCompleted)
        assertEquals(1, pipeline.subscriptionCount.value)

        val next = MpvEvent.Lifecycle(MpvNativeBridge.Event.END_FILE)
        pipeline.publish(next)
        assertEquals(listOf(first), cancelledEvents)
        assertEquals(listOf(first, next), activeEvents)

        active.cancel()
        assertEquals(0, pipeline.subscriptionCount.value)
        pipeline.publish(MpvEvent.Lifecycle(MpvNativeBridge.Event.SHUTDOWN))
        assertEquals(listOf(first), cancelledEvents)
        assertEquals(listOf(first, next), activeEvents)
    }

    @Test(timeout = 15_000)
    fun publish_isNonBlockingAndBuffersEventsForConsumer() {
        val lifecycleIds = listOf(
            MpvNativeBridge.Event.FILE_LOADED,
            MpvNativeBridge.Event.TRACKS_CHANGED,
            MpvNativeBridge.Event.END_FILE,
            MpvNativeBridge.Event.SHUTDOWN
        )
        val expected = List(32) { MpvEvent.Lifecycle(lifecycleIds[it % lifecycleIds.size]) }
        val fastEvents = CopyOnWriteArrayList<MpvEvent>()
        val slowEvents = CopyOnWriteArrayList<MpvEvent>()
        val received = List(expected.size) { CountDownLatch(1) }
        val allowNext = List(expected.size) { CompletableDeferred<Unit>() }
        subscribe(fastEvents)
        val slowCollector = eventScope.launch(
            Dispatchers.Unconfined,
            start = CoroutineStart.UNDISPATCHED
        ) {
            pipeline.events.collect { event ->
                val index = slowEvents.size
                slowEvents.add(event)
                received[index].countDown()
                withTimeout(5_000) { allowNext[index].await() }
            }
        }
        assertEquals(2, pipeline.subscriptionCount.value)

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mpv-event-pipeline-publisher").apply { isDaemon = true }
        }
        val secondPublishFinished = CountDownLatch(1)
        val allPublished = CountDownLatch(1)
        val publisher = executor.submit {
            expected.forEachIndexed { index, event ->
                pipeline.publish(event)
                if (index == 1) secondPublishFinished.countDown()
            }
            allPublished.countDown()
        }

        try {
            assertTrue(
                "Publish must be non-blocking and advance immediately",
                secondPublishFinished.await(500, TimeUnit.MILLISECONDS)
            )
            assertTrue(
                "All events should publish rapidly without waiting for consumer",
                allPublished.await(1, TimeUnit.SECONDS)
            )

            expected.indices.forEach { index ->
                assertTrue("Missing lifecycle event $index", received[index].await(5, TimeUnit.SECONDS))
                assertEquals(expected[index], slowEvents[index])
                allowNext[index].complete(Unit)
            }
            publisher.get(5, TimeUnit.SECONDS)

            assertEquals(expected, slowEvents)
            assertEquals(expected, fastEvents)
        } finally {
            slowCollector.cancel()
            publisher.cancel(true)
            executor.shutdownNow()
            assertTrue("Publisher thread did not stop", executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
