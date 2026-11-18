package dev.gyro.feature.player.surface

import android.view.PixelCopy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SurfaceCaptureTest {

    private class FakeBitmap {
        var recycled = false
    }

    @Test
    fun successBeforeTimeoutReturnsBitmapWithoutRecycling() = runTest {
        val bitmap = FakeBitmap()
        var finishCallback: ((Int) -> Unit)? = null

        val deferred = async {
            awaitSurfaceCopy(
                bitmap = bitmap,
                timeoutMs = 2_000L,
                recycle = { it.recycled = true },
                requestCopy = { _, onFinished -> finishCallback = onFinished },
            )
        }
        runCurrent()
        assertFalse(bitmap.recycled)

        finishCallback!!.invoke(PixelCopy.SUCCESS)
        val result = deferred.await()

        assertSame(bitmap, result)
        assertFalse(bitmap.recycled)
    }

    @Test
    fun failureCodeRecyclesBitmapAndReturnsNull() = runTest {
        val bitmap = FakeBitmap()
        var finishCallback: ((Int) -> Unit)? = null

        val deferred = async {
            awaitSurfaceCopy(
                bitmap = bitmap,
                timeoutMs = 2_000L,
                recycle = { it.recycled = true },
                requestCopy = { _, onFinished -> finishCallback = onFinished },
            )
        }
        runCurrent()

        finishCallback!!.invoke(PixelCopy.ERROR_SOURCE_NO_DATA)
        val result = deferred.await()

        assertNull(result)
        assertTrue(bitmap.recycled)
    }

    @Test
    fun timeoutDoesNotRecycleWhileCopyInFlightAndRecyclesOnLateCallback() = runTest {
        val bitmap = FakeBitmap()
        var recycleCount = 0
        var finishCallback: ((Int) -> Unit)? = null

        val deferred = async {
            awaitSurfaceCopy(
                bitmap = bitmap,
                timeoutMs = 2_000L,
                recycle = {
                    recycleCount++
                    it.recycled = true
                },
                requestCopy = { _, onFinished -> finishCallback = onFinished },
            )
        }
        runCurrent()
        assertFalse(bitmap.recycled)

        advanceTimeBy(2_000L)
        val result = deferred.await()

        // Must return null without recycling while PixelCopy still holds the bitmap.
        assertNull(result)
        assertFalse("Bitmap must NOT be recycled before PixelCopy callback fires", bitmap.recycled)
        assertEquals(0, recycleCount)

        // Late PixelCopy callback arrives on Main Looper after timeout: must recycle and not throw.
        finishCallback!!.invoke(PixelCopy.SUCCESS)
        assertTrue("Bitmap must be recycled once late PixelCopy callback fires", bitmap.recycled)
        assertEquals(1, recycleCount)
    }

    @Test
    fun cancellationDoesNotRecycleWhileCopyInFlightAndRecyclesOnLateCallback() = runTest {
        val bitmap = FakeBitmap()
        var recycleCount = 0
        var finishCallback: ((Int) -> Unit)? = null

        val job = launch {
            awaitSurfaceCopy(
                bitmap = bitmap,
                timeoutMs = 2_000L,
                recycle = {
                    recycleCount++
                    it.recycled = true
                },
                requestCopy = { _, onFinished -> finishCallback = onFinished },
            )
        }
        runCurrent()
        assertFalse(bitmap.recycled)

        job.cancelAndJoin()
        assertFalse("Bitmap must NOT be recycled while PixelCopy is still in flight", bitmap.recycled)
        assertEquals(0, recycleCount)

        finishCallback!!.invoke(PixelCopy.ERROR_UNKNOWN)
        assertTrue(bitmap.recycled)
        assertEquals(1, recycleCount)
    }

    @Test
    fun synchronousRequestExceptionRecyclesBitmapAndReturnsNull() = runTest {
        val bitmap = FakeBitmap()
        var recycleCount = 0

        val result = awaitSurfaceCopy(
            bitmap = bitmap,
            timeoutMs = 2_000L,
            recycle = {
                recycleCount++
                it.recycled = true
            },
            requestCopy = { _, _ -> throw IllegalArgumentException("Surface invalidated") },
        )

        assertNull(result)
        assertTrue(bitmap.recycled)
        assertEquals(1, recycleCount)
    }

    @Test
    fun duplicateCallbackAfterSuccessIsIgnoredWithoutRecyclingReturnedBitmap() = runTest {
        val bitmap = FakeBitmap()
        var recycleCount = 0
        var finishCallback: ((Int) -> Unit)? = null

        val deferred = async {
            awaitSurfaceCopy(
                bitmap = bitmap,
                timeoutMs = 2_000L,
                recycle = {
                    recycleCount++
                    it.recycled = true
                },
                requestCopy = { _, onFinished -> finishCallback = onFinished },
            )
        }
        runCurrent()

        finishCallback!!.invoke(PixelCopy.SUCCESS)
        val result = deferred.await()
        finishCallback.invoke(PixelCopy.ERROR_UNKNOWN)

        assertSame(bitmap, result)
        assertFalse(bitmap.recycled)
        assertEquals(0, recycleCount)
    }
}
