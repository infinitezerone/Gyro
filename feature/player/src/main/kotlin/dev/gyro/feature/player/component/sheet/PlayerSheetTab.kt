package dev.gyro.feature.player.component.sheet

import androidx.annotation.StringRes
import dev.gyro.core.designsystem.R

enum class PlayerSheetTab(@StringRes val titleRes: Int) {
    SETTINGS_HUB(R.string.settings_title),
    PLAYLIST(R.string.sheet_tab_playlist),
    CHAPTERS(R.string.sheet_tab_chapters),
    SUBTITLES(R.string.sheet_tab_subtitles),
    AUDIO(R.string.sheet_tab_audio),
    SHADERS(R.string.sheet_tab_shaders),
    AUDIOVISUAL(R.string.sheet_tab_audiovisual),
    SYNC(R.string.sheet_tab_sync),
    SPEED(R.string.sheet_tab_speed),
    ASPECT(R.string.sheet_tab_aspect),
    STATS(R.string.sheet_tab_stats),
    INFO(R.string.sheet_tab_info)
}
