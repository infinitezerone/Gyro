package dev.gyro.core.testing.fakes

import dev.gyro.engine.media.MediaItem
import dev.gyro.engine.media.MediaResourceResolver
import dev.gyro.engine.media.ResolvedMediaSource
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Answers load requests without touching a provider, which the JVM suite cannot do: `Uri.parse`
 * returns null there and no content resolver exists.
 *
 * Defaults to passing the URI through as a target, matching what mpv would receive for a path.
 */
class FakeMediaResourceResolver(
    private val failure: Throwable? = null
) : MediaResourceResolver {

    val resolved: CopyOnWriteArrayList<MediaItem> = CopyOnWriteArrayList()

    var targetFor: (MediaItem) -> String = { it.uri.toString() }

    override suspend fun resolve(item: MediaItem): ResolvedMediaSource {
        resolved += item
        failure?.let { throw it }
        return ResolvedMediaSource(targetFor(item))
    }
}
