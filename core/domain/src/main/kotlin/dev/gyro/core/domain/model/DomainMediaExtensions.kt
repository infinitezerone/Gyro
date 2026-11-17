package dev.gyro.core.domain.model

import android.net.Uri
import dev.gyro.core.common.toUri
import dev.gyro.core.model.VideoMediaItem

val VideoMediaItem.contentUri: Uri
    get() = uriString.toUri()
