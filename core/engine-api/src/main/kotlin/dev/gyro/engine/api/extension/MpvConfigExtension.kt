package dev.gyro.engine.api.extension

import dev.gyro.engine.api.EngineExtension

/**
 * Extension for engines supporting advanced mpv option key-value configuration.
 */
interface MpvConfigExtension : EngineExtension {
    fun setPropertyString(name: String, value: String): Boolean
    fun getPropertyString(name: String): String?
    fun updateOptions(options: Map<String, String>)
}
