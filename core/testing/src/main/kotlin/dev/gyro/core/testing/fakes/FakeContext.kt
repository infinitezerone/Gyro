package dev.gyro.core.testing.fakes

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/**
 * Minimal [Context] for JVM tests.
 *
 * Only the members production code actually reaches are overridden; everything else falls through
 * to the stubbed platform implementation, so an accidental call still fails loudly instead of
 * silently returning a fake value.
 */
class FakeContext(
    private val filesDir: File,
    private val packageName: String = "dev.gyro.test",
) : ContextWrapper(null) {

    override fun getApplicationContext(): Context = this

    override fun getFilesDir(): File = filesDir

    override fun getPackageName(): String = packageName
}
