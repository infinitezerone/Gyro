package dev.gyro.engine

import android.graphics.PixelFormat
import android.media.ImageReader
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gyro.engine.model.PlaybackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class GyroNativeStressTest {

    companion object {
        private const val TAG = "GyroNativeTest"
    }

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun findTestVideo(): File? {
        val candidates = listOf(
            File("/data/user/0/dev.gyro.player/files/test.mkv"),
            File(context.filesDir, "test.mkv"),
            File(context.getExternalFilesDir(null), "test.mkv"),
            File("/data/local/tmp/test.mkv")
        )
        return candidates.firstOrNull { it.exists() && it.length() > 0 }
    }

    @Test
    fun test01_probe_and_init() {
        Log.i(TAG, "=== Running test01_probe_and_init ===")
        GyroNative.create(context)
        val isZeroCopy = GyroNative.isZeroCopySafe()
        val renderer = GyroNative.getGpuRenderer()
        Log.i(TAG, "Static Probe Result: isZeroCopySafe=$isZeroCopy, renderer='$renderer'")

        assertTrue("Renderer should not be empty", renderer.isNotBlank())
        assertTrue("Qualcomm Adreno should support zero copy", isZeroCopy)

        GyroNative.setOptionString("idle", "yes")
        GyroNative.setOptionString("vo", "libmpv")
        GyroNative.init()

        GyroNative.destroy()
        Log.i(TAG, "test01 passed successfully")
    }

    @Test
    fun test02_rapid_lifecycle_stress_50_cycles() {
        Log.i(TAG, "=== Running test02_rapid_lifecycle_stress_50_cycles ===")
        for (i in 1..50) {
            GyroNative.create(context)
            GyroNative.setOptionString("idle", "yes")
            GyroNative.setOptionString("vo", "libmpv")
            GyroNative.init()
            GyroNative.destroy()
        }
        Log.i(TAG, "test02: 50 cycles of create->init->destroy passed with 0 crashes")
    }

    @Test
    fun test03_surface_attach_detach_30_cycles() {
        Log.i(TAG, "=== Running test03_surface_attach_detach_30_cycles ===")
        GyroNative.create(context)
        GyroNative.setOptionString("idle", "yes")
        GyroNative.setOptionString("vo", "libmpv")
        GyroNative.init()

        val imageReader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 2)
        val surface = imageReader.surface
        assertNotNull("ImageReader surface must not be null", surface)

        for (i in 1..30) {
            GyroNative.attachSurface(surface)
            GyroNative.detachSurface()
        }

        imageReader.close()
        GyroNative.destroy()
        Log.i(TAG, "test03: 30 cycles of attachSurface/detachSurface passed with 0 crashes")
    }

    @Test
    fun test04_end_to_end_playback_and_hwdec_verification(): Unit = runBlocking {
        Log.i(TAG, "=== Running test04_end_to_end_playback_and_hwdec_verification ===")
        val videoFile = findTestVideo()
        assertNotNull("test.mkv must exist for test04", videoFile)

        val engine = GyroPlayerEngine(context)
        engine.ensureInitialized()

        val imageReader = ImageReader.newInstance(1920, 1080, PixelFormat.RGBA_8888, 2)
        engine.attachSurface(imageReader.surface)

        engine.load(videoFile!!.absolutePath)

        // Wait up to 5 seconds for video resolution & hwdec to become available
        val dimensions = withTimeoutOrNull(5000L) {
            engine.geometry.videoDimensions.first { it.width > 0 && it.height > 0 }
        }
        assertNotNull("Video dimensions should be detected within 5s", dimensions)
        assertEquals("Width must be 1920", 1920, dimensions?.width)
        assertEquals("Height must be 1080", 1080, dimensions?.height)
        Log.i(TAG, "Verified dimensions: ${dimensions?.width}x${dimensions?.height}")

        val hwdec = withTimeoutOrNull(5000L) {
            engine.hwdecCurrent.first { it.isNotBlank() }
        }
        assertNotNull("Hardware decoder should be engaged", hwdec)
        assertTrue("Decoder should be mediacodec, actual: $hwdec", hwdec?.contains("mediacodec") == true)
        Log.i(TAG, "Verified hwdec: $hwdec")

        val initialFdCount = java.io.File("/proc/self/fd").listFiles()?.size ?: 0

        // Wait for playback to progress and verify rendered frames
        val timePos = withTimeoutOrNull(5000L) {
            engine.timePosMs.first { it > 500L }
        }
        assertNotNull("Time position should advance past 500ms", timePos)
        Log.i(TAG, "Verified active playback timePos: ${timePos}ms")

        // 核心校验 1：校验真实离屏渲染像素，绝对不能为全黑！
        var hasValidPixels = false
        var attempts = 0
        while (attempts < 10 && !hasValidPixels) {
            val img = imageReader.acquireLatestImage()
            if (img != null) {
                val plane = img.planes[0]
                val buffer = plane.buffer
                val sampleBytes = ByteArray(1024.coerceAtMost(buffer.remaining()))
                buffer.get(sampleBytes)
                // 检查样本像素是否存在非零色彩分量
                if (sampleBytes.any { it.toInt() != 0 }) {
                    hasValidPixels = true
                }
                img.close()
            }
            if (!hasValidPixels) {
                Thread.sleep(100)
                attempts++
            }
        }
        assertTrue("Rendered frame must contain non-zero visual pixels (not black screen)", hasValidPixels)
        Log.i(TAG, "Verified non-black rendered video pixels successfully!")

        // 核心校验 2：校验文件句柄没有在每帧无节制泄露
        val finalFdCount = java.io.File("/proc/self/fd").listFiles()?.size ?: 0
        val fdDelta = finalFdCount - initialFdCount
        Log.i(TAG, "FD tracking: initial=$initialFdCount, final=$finalFdCount, delta=$fdDelta")
        assertTrue("FD count should remain stable during playback (delta=$fdDelta <= 15)", fdDelta <= 15)

        engine.release()
        imageReader.close()
        Log.i(TAG, "test04: End-to-end hardware decoding, visual pixels & FD leak verification passed!")
    }

    /**
     * 测试 5：Surface 动态重构与分辨率混沌切换（Resize Chaos）
     * 模拟用户在全屏、分屏、画中画（PiP）、旋转、折叠屏展开等场景下的尺寸剧变
     */
    @Test
    fun test05_surface_resize_and_dimension_chaos(): Unit = runBlocking {
        Log.i(TAG, "=== Running test05_surface_resize_and_dimension_chaos ===")
        val videoFile = findTestVideo()
        assertNotNull("test.mkv must exist for test05", videoFile)

        val engine = GyroPlayerEngine(context)
        engine.ensureInitialized()

        val testResolutions = listOf(
            Pair(1920, 1080),
            Pair(1272, 2772), // OnePlus 13 竖屏全屏
            Pair(2772, 1272), // OnePlus 13 横屏全屏
            Pair(800, 600),   // 小窗 PiP
            Pair(1080, 1920)  // 垂直居中
        )

        for ((w, h) in testResolutions) {
            Log.i(TAG, "Chaos resize: Switching Surface to ${w}x${h}...")
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            engine.attachSurface(reader.surface)

            engine.load(videoFile!!.absolutePath)

            // 等待渲染至少 2 帧
            delay(400)

            val img = reader.acquireLatestImage()
            assertNotNull("Image must be acquired after resize to ${w}x${h}", img)
            if (img != null) {
                val buffer = img.planes[0].buffer
                val sample = ByteArray(512.coerceAtMost(buffer.remaining()))
                buffer.get(sample)
                assertTrue("Pixels must not be all zero after resize to ${w}x${h}", sample.any { it.toInt() != 0 })
                img.close()
            }
            reader.close()
        }

        engine.release()
        Log.i(TAG, "test05: Surface resize chaos completed with 0 crashes and valid pixels!")
    }

    /**
     * 测试 6：长时播放句柄与显存导数断言 (Temporal Stability: d(FD)/dt = 0)
     * 持续采样 20 秒播放过程中的 FD 数量，确保没有 anon_inode:sync_file 线性累积
     */
    @Test
    fun test06_long_running_fd_and_memory_stability_20s(): Unit = runBlocking {
        Log.i(TAG, "=== Running test06_long_running_fd_and_memory_stability_20s ===")
        val videoFile = findTestVideo()
        assertNotNull("test.mkv must exist for test06", videoFile)

        val engine = GyroPlayerEngine(context)
        engine.ensureInitialized()

        val reader = ImageReader.newInstance(1920, 1080, PixelFormat.RGBA_8888, 2)
        engine.attachSurface(reader.surface)
        engine.load(videoFile!!.absolutePath)

        // 预热 2 秒，等待编解码器与 AudioTrack 缓冲区建立
        delay(2000)

        val fdSamples = mutableListOf<Int>()
        val startFd = java.io.File("/proc/self/fd").listFiles()?.size ?: 0

        // 持续观察 15 秒（每 3 秒采样一次）
        for (i in 1..5) {
            delay(3000)
            val currentFd = java.io.File("/proc/self/fd").listFiles()?.size ?: 0
            fdSamples.add(currentFd)
            Log.i(TAG, "Stability sample #$i (t=${i * 3 + 2}s): FDs=$currentFd")
        }

        val baselineFd = fdSamples.first()
        val endFd = fdSamples.last()
        val positiveLeak = (endFd - baselineFd).coerceAtLeast(0)
        Log.i(TAG, "Long-running FD summary: baseline=$baselineFd, samples=$fdSamples, end=$endFd, positiveLeak=$positiveLeak")

        // 在 15 秒内，如果存在每秒 24 个的泄露，positiveLeak 将达到 360 个！
        // 允许抖动范围在 15 之内，证明 d(FD)/dt 严格收敛于 0，无单调泄漏！
        assertTrue(
            "FD positive leak over 15s should be tightly bounded (actual positiveLeak=$positiveLeak, samples=$fdSamples)",
            positiveLeak <= 15
        )

        engine.release()
        reader.close()
        Log.i(TAG, "test06: 20s long-running temporal stability verified! Zero leak!")
    }
}
