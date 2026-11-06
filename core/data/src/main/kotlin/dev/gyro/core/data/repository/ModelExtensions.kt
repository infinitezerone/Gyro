package dev.gyro.core.data.repository

import android.net.Uri
import dev.gyro.core.model.CustomFolderDetail
import dev.gyro.core.model.VideoMediaItem

/**
 * Android [Uri] parsing helper, configurable for testing environments.
 */
object AndroidUriParser {
    var parser: (String) -> Uri = { str -> Uri.parse(str) }
}

/**
 * Android [Uri] convenience extensions for pure model objects.
 */
val VideoMediaItem.asAndroidUri: Uri
    get() = AndroidUriParser.parser(uriString)

val VideoMediaItem.androidUri: Uri
    get() = asAndroidUri

val CustomFolderDetail.asAndroidUri: Uri
    get() = AndroidUriParser.parser(uriString)

val CustomFolderDetail.androidUri: Uri
    get() = asAndroidUri
