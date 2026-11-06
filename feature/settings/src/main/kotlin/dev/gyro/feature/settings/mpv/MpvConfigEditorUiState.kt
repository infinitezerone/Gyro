package dev.gyro.feature.settings.mpv

/**
 * UI State for the mpv.conf editor screen.
 */
data class MpvConfigEditorUiState(
    val content: String = "",
    val originalContent: String = "",
    val isModified: Boolean = false,
    /** True until the conf file has been read off the IO dispatcher. */
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val showResetDialog: Boolean = false,
    val showDiscardDialog: Boolean = false,
    val showHelpDialog: Boolean = false,
    val userMessageRes: Int? = null,
    val errorMessage: String? = null
) {
    val lineCount: Int
        get() = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1

    val charCount: Int
        get() = content.length
}

/**
 * Preset configuration snippets for quick insertion.
 */
data class MpvPresetOption(
    val label: String,
    val snippet: String,
    val description: String
)

val MPV_QUICK_PRESETS = listOf(
    MpvPresetOption("vo=gpu-next", "vo=gpu-next", "libplacebo GPU renderer"),
    MpvPresetOption("hwdec=auto", "hwdec=auto", "Auto hardware decoding"),
    MpvPresetOption("deband=yes", "deband=yes", "Debanding filter"),
    MpvPresetOption("demuxer-cache", "demuxer-max-bytes=150MiB", "150MB network buffer"),
    MpvPresetOption("sub-fuzzy", "sub-auto=fuzzy", "Fuzzy subtitle autoload"),
    MpvPresetOption("blend-subtitles", "blend-subtitles=yes", "Subtitles rendered to video"),
    MpvPresetOption("keep-open", "keep-open=yes", "Keep player open at EOF")
)
