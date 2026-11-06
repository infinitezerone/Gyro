package dev.gyro.core.data.repository

import android.net.Uri
import javax.inject.Inject

/**
 * Converts a persisted URI string back into a platform [Uri].
 *
 * Injected rather than reached through a global: `android.net.Uri.parse` is stubbed out on the JVM,
 * so tests substitute an implementation while production binds [AndroidUriParser].
 */
fun interface UriParser {
    fun parse(uri: String): Uri
}

class AndroidUriParser @Inject constructor() : UriParser {
    override fun parse(uri: String): Uri = Uri.parse(uri)
}
