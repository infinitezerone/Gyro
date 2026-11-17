package dev.gyro.core.testing.rules

import android.net.TestUri
import android.net.Uri
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit TestRule that stubs Android's static [Uri.parse] on the JVM to return [TestUri],
 * completely eliminating test-induced architectural pollution in production code.
 */
class MockUriRule : TestWatcher() {
    override fun starting(description: Description) {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { TestUri(firstArg()) }
    }

    override fun finished(description: Description) {
        unmockkStatic(Uri::class)
    }
}
