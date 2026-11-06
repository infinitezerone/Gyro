package dev.gyro.core.data.repository

import dev.gyro.core.testing.fakes.FakeContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors

class MpvConfigRepositoryStressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testRapidConcurrentSaveAndResetConsistency() {
        val pool = Executors.newFixedThreadPool(8)
        val multiThreadDispatcher = pool.asCoroutineDispatcher()

        try {
            val repository = DefaultMpvConfigRepository(FakeContext(tempFolder.root), multiThreadDispatcher)
            val configFile = File(tempFolder.root, "mpv.conf")

            runBlocking(multiThreadDispatcher) {
                val iterations = 100
                val jobs = (1..iterations).map { index ->
                    async {
                        if (index % 3 == 0) {
                            repository.resetToDefault()
                        } else {
                            repository.saveConfig("vo=gpu\nhwdec=mediacodec\nkey_$index=val_$index\n")
                        }
                    }
                }
                jobs.awaitAll()

                val fileContent = configFile.readText(Charsets.UTF_8)
                val loadedContent = repository.loadConfig()

                assertEquals(
                    "DESYNCHRONIZATION DETECTED: Loaded config does not match file on disk!",
                    fileContent,
                    loadedContent
                )
            }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun testHeavyConcurrentSavesConsistency() {
        val subDir = tempFolder.newFolder("heavy")
        val pool = Executors.newFixedThreadPool(10)
        val multiThreadDispatcher = pool.asCoroutineDispatcher()

        try {
            val repository = DefaultMpvConfigRepository(FakeContext(subDir), multiThreadDispatcher)
            val configFile = File(subDir, "mpv.conf")

            runBlocking(multiThreadDispatcher) {
                val iterations = 150
                val jobs = (1..iterations).map { index ->
                    async {
                        val content = (1..20).joinToString("\n") { line -> "option_$line=value_${index}_$line" }
                        repository.saveConfig(content)
                    }
                }
                jobs.awaitAll()

                val fileContent = configFile.readText(Charsets.UTF_8)
                val loadedContent = repository.loadConfig()

                assertEquals(
                    "DESYNCHRONIZATION DETECTED under heavy concurrent saves!",
                    fileContent,
                    loadedContent
                )
            }
        } finally {
            pool.shutdown()
        }
    }
}
