package dev.gyro.core.model

data class UserSettings(
    val autoResume: Boolean = true,
    val backgroundPlayMode: String = "pip", // "pip", "audio_only", "off"
    val seekStepSeconds: Int = 10, // 5, 10, 15, 30
    val defaultHwdec: String = "mediacodec", // "mediacodec", "mediacodec-copy", ""
    val subAutoLoad: Boolean = true,
    val gesturesEnabled: Boolean = true
)
