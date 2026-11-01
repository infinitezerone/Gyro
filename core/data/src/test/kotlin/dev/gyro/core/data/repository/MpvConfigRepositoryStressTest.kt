package dev.gyro.core.data.repository

import dev.gyro.core.model.MpvConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val configFile = File(tempFolder.root, "mpv.conf")
        // Use a multi-threaded dispatcher with 8 threads to simulate intense concurrent IO
        val pool = Executors.newFixedThreadPool(8)
        val multiThreadDispatcher = pool.asCoroutineDispatcher()

        try {
            val repository = MpvConfigRepository(configFile, multiThreadDispatcher)

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
            }

            // Empirical assertion: In-memory StateFlow MUST strictly match the persisted file on disk
            val fileContent = configFile.readText(Charsets.UTF_8)
            val stateFlowValue = repository.configText.value

            assertEquals(
                "DESYNCHRONIZATION DETECTED: In-memory configText StateFlow does not match file on disk!",
                fileContent,
                stateFlowValue
            )
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun testHeavyConcurrentSavesConsistency() {
        val configFile = File(tempFolder.root, "mpv_heavy.conf")
        val pool = Executors.newFixedThreadPool(10)
        val multiThreadDispatcher = pool.asCoroutineDispatcher()

        try {
            val repository = MpvConfigRepository(configFile, multiThreadDispatcher)

            runBlocking(multiThreadDispatcher) {
                val iterations = 150
                val jobs = (1..iterations).map { index ->
                    async {
                        val content = (1..20).joinToString("\n") { line -> "option_$line=value_${index}_$line" }
                        repository.saveConfig(content)
                    }
                }
                jobs.awaitAll()
            }

            val fileContent = configFile.readText(Charsets.UTF_8)
            val stateFlowValue = repository.configText.value

            assertEquals(
                "DESYNCHRONIZATION DETECTED under heavy concurrent saves!",
                fileContent,
                stateFlowValue
            )
        } finally {
            pool.shutdown()
        }
    }
}
