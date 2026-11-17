package dev.gyro.core.common

import android.net.Uri

/**
 * Standard platform conversion from String to [Uri].
 */
fun String.toUri(): Uri = Uri.parse(this)
