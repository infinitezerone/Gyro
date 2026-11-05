package android.net

import android.os.Parcel

/**
 * JVM 测试环境下替代 android.net.Uri 的开箱即用实现
 */
class TestUri(private val uriString: String = "content://media/1") : Uri() {
    override fun isHierarchical(): Boolean = true
    override fun isRelative(): Boolean = false
    override fun getScheme(): String = "content"
    override fun getSchemeSpecificPart(): String = "//media/1"
    override fun getEncodedSchemeSpecificPart(): String = "//media/1"
    override fun getAuthority(): String = "media"
    override fun getEncodedAuthority(): String = "media"
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String = "media"
    override fun getPort(): Int = -1
    override fun getPath(): String = "/1"
    override fun getEncodedPath(): String = "/1"
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): List<String> = listOf("1")
    override fun getLastPathSegment(): String = "1"
    override fun buildUpon(): Builder? = null
    override fun toString(): String = uriString
    override fun writeToParcel(dest: Parcel, flags: Int) {}
    override fun describeContents(): Int = 0
    override fun compareTo(other: Uri?): Int = uriString.compareTo(other?.toString() ?: "")
    override fun equals(other: Any?): Boolean = other is Uri && uriString == other.toString()
    override fun hashCode(): Int = uriString.hashCode()
}
