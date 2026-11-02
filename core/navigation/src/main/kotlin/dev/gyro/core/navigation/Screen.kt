package dev.gyro.core.navigation

import android.net.Uri
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Custom serializer for Android [Uri] to support Navigation 3 backstack serialization.
 */
object UriSerializer : KSerializer<Uri> {
    override val descriptor = PrimitiveSerialDescriptor("android.net.Uri", PrimitiveKind.STRING)

    @Volatile
    var uriParser: (String) -> Uri = { str -> Uri.parse(str) }

    override fun serialize(encoder: Encoder, value: Uri) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Uri {
        return uriParser(decoder.decodeString())
    }
}

/**
 * Gyro 全局类型安全页面路由定义 (Navigation 3)
 */
@Serializable
sealed interface Screen : NavKey {

    @Serializable
    data object Home : Screen

    @Serializable
    data class Player(
        @Serializable(with = UriSerializer::class)
        val uri: Uri
    ) : Screen

    @Serializable
    data object Settings : Screen

    @Serializable
    data object FolderManagement : Screen

    @Serializable
    data object MpvConfigEditor : Screen

    @Serializable
    data object GestureSettings : Screen
}
