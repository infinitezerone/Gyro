package dev.gyro.core.model

/**
 * Representation of custom mpv configuration and parsed options.
 *
 * @property rawText The raw mpv.conf configuration text.
 * @property options Parsed key-value options extracted from the raw text.
 */
data class MpvConfig(
    val rawText: String = "",
    val options: Map<String, String> = parse(rawText)
) {
    companion object {
        val DEFAULT_TEMPLATE: String = """
            # Gyro Player - Custom mpv.conf
            #
            # Options defined here will be passed directly to the mpv engine during startup.
            # Note: GUI settings (hardware decoder, playback speed, subtitles, colors, etc.)
            # automatically take highest precedence and override options defined here.
            
            # Video & Rendering
            vo=libmpv
            gpu-context=android
            
            # Subtitles & OSD
            blend-subtitles=no
            sub-ass-override=no
        """.trimIndent()

        /**
         * Parses raw mpv.conf text into a Map of key-value options.
         * Handles comments (#), inline comments, leading dashes (--),
         * quoted strings, and boolean flags without values (e.g. deband -> deband=yes).
         */
        fun parse(text: String): Map<String, String> {
            val map = linkedMapOf<String, String>()
            text.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach

                val cleanLine = line.removePrefix("--").trim()
                if (cleanLine.isEmpty() || cleanLine.startsWith("=") || cleanLine.startsWith("#")) return@forEach

                val eqIndex = cleanLine.indexOf('=')
                if (eqIndex > 0) {
                    val key = cleanLine.substring(0, eqIndex).trim()
                    var rawVal = cleanLine.substring(eqIndex + 1).trim()

                    val commentIdx = findInlineCommentIndex(rawVal)
                    if (commentIdx >= 0) {
                        rawVal = rawVal.substring(0, commentIdx).trim()
                    }

                    if ((rawVal.startsWith('"') && rawVal.endsWith('"')) ||
                        (rawVal.startsWith('\'') && rawVal.endsWith('\''))
                    ) {
                        if (rawVal.length >= 2) {
                            rawVal = rawVal.substring(1, rawVal.length - 1)
                        }
                    }
                    if (key.isNotEmpty()) {
                        map[key] = rawVal
                    }
                } else if (cleanLine.isNotEmpty()) {
                    var rawFlag = cleanLine
                    val commentIdx = findInlineCommentIndex(rawFlag)
                    if (commentIdx >= 0) {
                        rawFlag = rawFlag.substring(0, commentIdx).trim()
                    }
                    if (rawFlag.isNotEmpty() && !rawFlag.contains('=')) {
                        if (rawFlag.startsWith("no-")) {
                            val flagKey = rawFlag.removePrefix("no-").trim()
                            if (flagKey.isNotEmpty()) {
                                map[flagKey] = "no"
                            }
                        } else {
                            map[rawFlag] = "yes"
                        }
                    }
                }
            }
            return map
        }

        private fun findInlineCommentIndex(str: String): Int {
            var inSingleQuote = false
            var inDoubleQuote = false
            for (i in str.indices) {
                val c = str[i]
                if (c == '\'' && !inDoubleQuote) {
                    inSingleQuote = !inSingleQuote
                } else if (c == '"' && !inSingleQuote) {
                    inDoubleQuote = !inDoubleQuote
                } else if (c == '#' && !inSingleQuote && !inDoubleQuote) {
                    if (i > 0 && str[i - 1].isWhitespace()) {
                        return i
                    }
                }
            }
            return -1
        }
    }
}
