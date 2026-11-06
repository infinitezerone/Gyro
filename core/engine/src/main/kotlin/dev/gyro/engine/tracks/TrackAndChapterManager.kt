package dev.gyro.engine.tracks

import android.util.Log
import dev.gyro.core.model.ChapterInfo
import dev.gyro.engine.MpvEvent
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.model.TrackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages audio, video, and subtitle tracks as well as chapter parsing and selection.
 */
interface TrackAndChapterManager {
    val tracks: StateFlow<List<TrackInfo>>
    val chapters: StateFlow<List<ChapterInfo>>
    val currentChapter: StateFlow<Int>

    fun observeEvents(scope: CoroutineScope): Job
    fun refreshTracks()
    fun selectTrack(type: String, id: Int)
    fun addSubtitle(path: String)
    fun refreshChapters()
    fun onChapterPropertyChanged(index: Int)
    fun clearChapters()
}

@Singleton
class RealTrackAndChapterManager @Inject constructor(
    private val bridge: MpvNativeBridge
) : TrackAndChapterManager {

    companion object {
        private const val TAG = "TrackAndChapterManager"
    }

    private val _tracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    override val tracks: StateFlow<List<TrackInfo>> = _tracks.asStateFlow()

    private val _chapters = MutableStateFlow<List<ChapterInfo>>(emptyList())
    override val chapters: StateFlow<List<ChapterInfo>> = _chapters.asStateFlow()

    private val _currentChapter = MutableStateFlow(-1)
    override val currentChapter: StateFlow<Int> = _currentChapter.asStateFlow()

    override fun observeEvents(scope: CoroutineScope): Job =
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            bridge.events.collect { event ->
                when (event) {
                    is MpvEvent.Lifecycle -> when (event.id) {
                        MpvNativeBridge.Event.FILE_LOADED -> {
                            refreshTracks()
                            refreshChapters()
                        }
                        MpvNativeBridge.Event.TRACKS_CHANGED -> refreshTracks()
                        MpvNativeBridge.Event.END_FILE, MpvNativeBridge.Event.SHUTDOWN -> clearChapters()
                    }
                    is MpvEvent.PropertyChanged -> when (event.name) {
                        "track-list" -> refreshTracks()
                        "chapter-list" -> refreshChapters()
                    }
                    is MpvEvent.LongProperty -> {
                        if (event.name == "chapter") onChapterPropertyChanged(event.value.toInt())
                    }
                    else -> Unit
                }
            }
        }

    override fun refreshTracks() {
        try {
            val count = bridge.getLong("track-list/count") ?: 0L
            val trackList = mutableListOf<TrackInfo>()
            for (i in 0 until count) {
                val id = bridge.getLong("track-list/$i/id")?.toInt() ?: (i.toInt() + 1)
                val type = bridge.getPropertyString("track-list/$i/type") ?: ""
                val title = bridge.getPropertyString("track-list/$i/title")
                val lang = bridge.getPropertyString("track-list/$i/lang")
                val selected = bridge.getBoolean("track-list/$i/selected") ?: false
                trackList.add(TrackInfo(id, type, title, lang, selected))
            }
            _tracks.value = trackList
            Log.i(TAG, "Refreshed tracks: count=$count, tracks=$trackList")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to refresh tracks: ${t.message}")
        }
    }

    override fun selectTrack(type: String, id: Int) {
        when (type) {
            "audio" -> {
                if (id <= 0) {
                    bridge.setPropertyString("aid", "no")
                } else {
                    bridge.setPropertyString("aid", "$id")
                }
            }
            "sub" -> {
                if (id <= 0) {
                    bridge.setPropertyString("sid", "no")
                } else {
                    bridge.setPropertyString("sid", "$id")
                }
            }
        }
        refreshTracks()
    }

    override fun addSubtitle(path: String) {
        bridge.command(arrayOf("sub-add", path, "select"))
        refreshTracks()
    }

    override fun refreshChapters() {
        try {
            val count = bridge.getLong("chapter-list/count") ?: 0L
            if (count <= 0L) {
                _chapters.value = emptyList()
                _currentChapter.value = -1
                return
            }

            val chapterList = ArrayList<ChapterInfo>(count.toInt())
            for (i in 0 until count) {
                val rawTitle = bridge.getPropertyString("chapter-list/$i/title")?.trim()
                val timeSec = bridge.getDouble("chapter-list/$i/time") ?: 0.0
                val timeMs = (timeSec * 1000.0).toLong().coerceAtLeast(0L)
                val title = if (!rawTitle.isNullOrBlank()) rawTitle else "Chapter ${i + 1}"
                chapterList.add(ChapterInfo(index = i.toInt(), title = title, timeMs = timeMs))
            }
            _chapters.value = chapterList

            val activeChapter = bridge.getLong("chapter")?.toInt() ?: -1
            _currentChapter.value = activeChapter
            Log.i(TAG, "Refreshed chapters: count=$count, activeChapter=$activeChapter")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to refresh chapters: ${t.message}")
        }
    }

    override fun onChapterPropertyChanged(index: Int) {
        _currentChapter.value = index
    }

    override fun clearChapters() {
        _chapters.value = emptyList()
        _currentChapter.value = -1
    }
}
