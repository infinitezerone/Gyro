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

class MpvEventBusTest {

    private lateinit var bus: MpvEventBus
    private lateinit var eventScope: CoroutineScope

    @Before
    fun setUp() {
        bus = MpvEventBus()
        eventScope = CoroutineScope(Job() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        eventScope.cancel()
        assertEquals(0, bus.subscriptionCount.value)
    }

    private fun subscribe(events: MutableList<MpvEvent>): Job =
        eventScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            bus.events.collect { events.add(it) }
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
        assertEquals(2, bus.subscriptionCount.value)

        val expected = allEventTypes() + allEventTypes().reversed()
        expected.forEach(bus::publish)

        assertEquals(expected, first)
        assertEquals(expected, second)
    }

    @Test(timeout = 5_000)
    fun publish_withoutSubscribersReturnsWithoutBlocking() {
        assertEquals(0, bus.subscriptionCount.value)

        repeat(10) { allEventTypes().forEach(bus::publish) }

        assertEquals(0, bus.subscriptionCount.value)
        assertTrue(bus.events.replayCache.isEmpty())
    }

    @Test(timeout = 5_000)
    fun subscribe_doesNotReplayEventsPublishedBeforeSubscription() {
        allEventTypes().forEach(bus::publish)

        val first = CopyOnWriteArrayList<MpvEvent>()
        subscribe(first)
        assertTrue(first.isEmpty())

        val current = MpvEvent.Lifecycle(MpvNativeBridge.Event.PLAYBACK_RESTART)
        bus.publish(current)
        assertEquals(listOf(current), first)

        // Also exclude events published while another collector was active.
        val late = CopyOnWriteArrayList<MpvEvent>()
        subscribe(late)
        assertEquals(2, bus.subscriptionCount.value)
        assertTrue(late.isEmpty())
        assertTrue(bus.events.replayCache.isEmpty())

        val next = MpvEvent.Lifecycle(MpvNativeBridge.Event.END_FILE)
        bus.publish(next)
        assertEquals(listOf(current, next), first)
        assertEquals(listOf(next), late)
    }

    @Test(timeout = 5_000)
    fun cancel_removesOnlyThatSubscriberAndStopsItsNextEvent() {
        val cancelledEvents = CopyOnWriteArrayList<MpvEvent>()
        val activeEvents = CopyOnWriteArrayList<MpvEvent>()
        val cancelled = subscribe(cancelledEvents)
        val active = subscribe(activeEvents)
        assertEquals(2, bus.subscriptionCount.value)

        val first = MpvEvent.Lifecycle(MpvNativeBridge.Event.FILE_LOADED)
        bus.publish(first)
        cancelled.cancel()
        assertTrue(cancelled.isCompleted)
        assertEquals(1, bus.subscriptionCount.value)

        val next = MpvEvent.Lifecycle(MpvNativeBridge.Event.END_FILE)
        bus.publish(next)
        assertEquals(listOf(first), cancelledEvents)
        assertEquals(listOf(first, next), activeEvents)

        active.cancel()
        assertEquals(0, bus.subscriptionCount.value)
        bus.publish(MpvEvent.Lifecycle(MpvNativeBridge.Event.SHUTDOWN))
        assertEquals(listOf(first), cancelledEvents)
        assertEquals(listOf(first, next), activeEvents)
    }

    @Test(timeout = 15_000)
    fun publish_slowConsumerAppliesBackpressureWithoutLosingLifecycleEvents() {
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
            bus.events.collect { event ->
                val index = slowEvents.size
                slowEvents.add(event)
                received[index].countDown()
                // Hold each handler until the test explicitly allows the next event.
                withTimeout(5_000) { allowNext[index].await() }
            }
        }
        assertEquals(2, bus.subscriptionCount.value)

        // A blocked test scheduler cannot resume the suspended collector.
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mpv-event-bus-publisher").apply { isDaemon = true }
        }
        val secondPublishStarted = CountDownLatch(1)
        val secondPublishFinished = CountDownLatch(1)
        val publisher = executor.submit {
            expected.forEachIndexed { index, event ->
                if (index == 1) secondPublishStarted.countDown()
                bus.publish(event)
                if (index == 1) secondPublishFinished.countDown()
            }
        }

        try {
            assertTrue("First event was not received", received[0].await(5, TimeUnit.SECONDS))
            assertTrue("Publisher did not advance", secondPublishStarted.await(5, TimeUnit.SECONDS))
            assertFalse(
                "Publish must wait while the slow subscriber is still handling the first event",
                secondPublishFinished.await(100, TimeUnit.MILLISECONDS)
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
