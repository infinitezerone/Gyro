package dev.gyro.engine.tracks

import dev.gyro.core.model.ChapterInfo
import dev.gyro.engine.EngineSubsystem
import dev.gyro.engine.MpvEvent
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.model.TrackInfo
import dev.gyro.engine.state.PlaybackStore
import dev.gyro.engine.trace.EngineLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Manages audio, video, and subtitle tracks as well as chapter parsing and selection.
 */
class TrackAndChapterManager(
    private val bridge: MpvNativeBridge,
    private val store: PlaybackStore = PlaybackStore()
) : EngineSubsystem, dev.gyro.engine.api.controller.TrackController {

    companion object {
        private const val TAG = "TrackAndChapterManager"
    }

    private val _tracks = store.property({ it.tracks }) { s, v -> s.copy(tracks = v) }
    override val tracks: StateFlow<List<TrackInfo>> = _tracks.asStateFlow()

    private val _chapters = store.property({ it.chapters }) { s, v -> s.copy(chapters = v) }
    override val chapters: StateFlow<List<ChapterInfo>> = _chapters.asStateFlow()

    private val _currentChapter = store.property({ it.currentChapter }) { s, v -> s.copy(currentChapter = v) }
    override val currentChapter: StateFlow<Int> = _currentChapter.asStateFlow()

    private val _secondarySubId = store.property({ it.secondarySubId }) { s, v -> s.copy(secondarySubId = v) }
    override val secondarySubId: StateFlow<Int> = _secondarySubId.asStateFlow()

    override fun registerObservers() {
        bridge.observeProperty("track-list", MpvNativeBridge.Format.NONE)
        bridge.observeProperty("chapter-list", MpvNativeBridge.Format.NONE)
        bridge.observeProperty("chapter", MpvNativeBridge.Format.INT64)
        bridge.observeProperty("secondary-sid", MpvNativeBridge.Format.INT64)
    }

    override fun onEvent(event: MpvEvent) {
        when (event) {
            is MpvEvent.Lifecycle -> when (event.id) {
                MpvNativeBridge.Event.FILE_LOADED -> {
                    refreshTracks()
                    refreshChapters()
                }
                MpvNativeBridge.Event.TRACKS_CHANGED -> refreshTracks()
                MpvNativeBridge.Event.END_FILE, MpvNativeBridge.Event.SHUTDOWN -> clearChapters()
            }
            is MpvEvent.EndFile -> when (event.reason) {
                MpvNativeBridge.EndReason.EOF,
                MpvNativeBridge.EndReason.QUIT,
                MpvNativeBridge.EndReason.ERROR -> clearChapters()
            }
            is MpvEvent.PropertyChanged -> when (event.name) {
                "track-list" -> refreshTracks()
                "chapter-list" -> refreshChapters()
            }
            is MpvEvent.LongProperty -> {
                when (event.name) {
                    "chapter" -> onChapterPropertyChanged(event.value.toInt())
                    "secondary-sid" -> _secondarySubId.value = event.value.toInt()
                }
            }
            else -> Unit
        }
    }

    override fun reset() {
        clearTracksAndChapters()
    }

    fun observeEvents(scope: CoroutineScope): Job =
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            bridge.events.collect(::onEvent)
        }

    fun refreshTracks() {
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
            EngineLog.i(TAG, "Refreshed tracks: count=$count, tracks=$trackList")
        } catch (t: Throwable) {
            EngineLog.w(TAG, "Failed to refresh tracks: ${t.message}")
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
            "secondary-sub" -> {
                // 次字幕（双语同屏）：id <= 0 关闭；主字幕轨不会被次字幕抢占
                if (id <= 0) {
                    bridge.setPropertyString("secondary-sid", "no")
                } else {
                    bridge.setPropertyString("secondary-sid", "$id")
                }
            }
        }
        refreshTracks()
    }

    override fun addSubtitle(path: String) {
        bridge.command(arrayOf("sub-add", path, "select"))
        refreshTracks()
    }

    fun refreshChapters() {
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
            EngineLog.i(TAG, "Refreshed chapters: count=$count, activeChapter=$activeChapter")
        } catch (t: Throwable) {
            EngineLog.w(TAG, "Failed to refresh chapters: ${t.message}")
        }
    }

    fun onChapterPropertyChanged(index: Int) {
        _currentChapter.value = index
    }

    fun clearChapters() {
        _chapters.value = emptyList()
        _currentChapter.value = -1
    }

    fun clearTracks() {
        _tracks.value = emptyList()
    }

    fun clearTracksAndChapters() {
        clearChapters()
        clearTracks()
    }
}
