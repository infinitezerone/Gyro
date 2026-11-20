package dev.gyro.engine.tracks

import dev.gyro.core.model.ChapterInfo
import dev.gyro.engine.EngineSubsystem
import dev.gyro.engine.MpvEvent
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.model.TrackInfo
import dev.gyro.engine.model.TrackType
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
                // "no" 之类的非数值值会以 MPV_FORMAT_NONE 回调（无数据），必须按关闭处理，
                // 否则关闭次字幕后 UI 仍停留在旧的选中轨道。
                "secondary-sid" -> _secondarySubId.value = 0
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

    @Synchronized
    fun refreshTracks() {
        try {
            val count = bridge.getLong("track-list/count") ?: 0L
            val trackList = mutableListOf<TrackInfo>()
            for (i in 0 until count) {
                val id = bridge.getLong("track-list/$i/id")?.toInt() ?: (i.toInt() + 1)
                val rawType = bridge.getPropertyString("track-list/$i/type") ?: ""
                val type = when (rawType.trim().lowercase()) {
                    "video" -> TrackType.VIDEO
                    "audio" -> TrackType.AUDIO
                    "sub" -> TrackType.SUBTITLE
                    else -> TrackType.UNKNOWN
                }
                val title = bridge.getPropertyString("track-list/$i/title")
                val lang = bridge.getPropertyString("track-list/$i/lang")
                val selected = bridge.getBoolean("track-list/$i/selected") ?: false
                trackList.add(TrackInfo(id, type, title, lang, selected))
            }
            _tracks.value = trackList
            // 次字幕指向的轨道可能随换片/轨道刷新消失，或者变成当前主字幕轨，必须同步清掉，
            // 否则同一条字幕会被渲染两次，UI 也会显示一个不存在的次字幕。
            val subtitleIds = trackList
                .filter { it.type == TrackType.SUBTITLE }
                .mapTo(mutableSetOf()) { it.id }
            val primarySubId = trackList
                .firstOrNull { it.type == TrackType.SUBTITLE && it.isSelected }
                ?.id ?: 0
            val secondary = _secondarySubId.value
            // 次字幕只在“有主字幕且与主字幕不同”时才有意义；主字幕关闭或指向同一轨道都必须清掉。
            if (secondary > 0 &&
                (secondary !in subtitleIds || secondary == primarySubId || primarySubId == 0)
            ) {
                bridge.setPropertyString("secondary-sid", "no")
                // 不等 mpv 的属性回调（"no" 可能以 NONE 格式返回），先本地归零保持 UI 一致。
                _secondarySubId.value = 0
            }
            EngineLog.i(TAG, "Refreshed tracks: count=$count, tracks=$trackList")
        } catch (t: Throwable) {
            EngineLog.w(TAG, "Failed to refresh tracks: ${t.message}")
        }
    }

    override fun selectTrack(type: TrackType, id: Int) {
        when (type) {
            TrackType.VIDEO -> {
                bridge.setPropertyString("vid", if (id <= 0) "no" else "$id")
            }
            TrackType.AUDIO -> {
                bridge.setPropertyString("aid", if (id <= 0) "no" else "$id")
            }
            TrackType.SUBTITLE -> {
                val primaryOff = id <= 0
                bridge.setPropertyString("sid", if (primaryOff) "no" else "$id")
                // 主字幕关闭，或主字幕切到当前次字幕轨时，清掉次字幕：
                // 前者避免留下一个 UI 里已无法管理的次字幕，后者避免同一条字幕被渲染两次。
                if (_secondarySubId.value > 0 && (primaryOff || id == _secondarySubId.value)) {
                    bridge.setPropertyString("secondary-sid", "no")
                    _secondarySubId.value = 0
                }
            }
            TrackType.SECONDARY_SUBTITLE -> {
                // 次字幕（双语同屏）：必须有主字幕且不能与主字幕同轨，否则一律按关闭处理。
                val primaryId = _tracks.value
                    .firstOrNull { it.type == TrackType.SUBTITLE && it.isSelected }
                    ?.id ?: 0
                val effectiveId = if (id > 0 && primaryId > 0 && id != primaryId) id else 0
                bridge.setPropertyString("secondary-sid", if (effectiveId <= 0) "no" else "$effectiveId")
                _secondarySubId.value = effectiveId
            }
            TrackType.UNKNOWN -> return
        }
        // 乐观同步内存中的选中状态，消除在 UI 主线程阻塞发起数十次同步 JNI 往返；
        // 底层 mpv 生效后会触发 TRACKS_CHANGED 事件在后台协程自动权威核实刷新。
        if (type != TrackType.SECONDARY_SUBTITLE) {
            _tracks.value = _tracks.value.map { track ->
                if (track.type == type) {
                    track.copy(isSelected = (id > 0 && track.id == id))
                } else {
                    track
                }
            }
        }
    }

    override fun addSubtitle(path: String) {
        bridge.command(arrayOf("sub-add", path, "select"))
        // sub-add 命令由 mpv 执行后会发布 TRACKS_CHANGED 事件，自动触发后台权威刷新
    }

    @Synchronized
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
        // 换片/销毁后旧的次字幕 id 不再有意义，避免把上一条视频的次字幕带到下一条的 UI 上。
        _secondarySubId.value = 0
    }
}
