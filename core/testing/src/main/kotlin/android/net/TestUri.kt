package android.net

import android.os.Parcel

/**
 * JVM 测试环境下替代 android.net.Uri 的开箱即用实现
 */
class TestUri(private val uriString: String = "content://media/1") : Uri() {
    private val schemePart: String? = if (uriString.contains("://")) uriString.substringBefore("://") else null
    private val rest: String = if (uriString.contains("://")) uriString.substringAfter("://") else uriString
    private val pathPart: String = if (rest.contains('/')) rest.substringAfter('/') else ""
    private val authorityPart: String = if (rest.contains('/')) rest.substringBefore('/') else rest

    override fun isHierarchical(): Boolean = true
    override fun isRelative(): Boolean = schemePart == null
    override fun getScheme(): String? = schemePart ?: "content"
    override fun getSchemeSpecificPart(): String = "//$rest"
    override fun getEncodedSchemeSpecificPart(): String = "//$rest"
    override fun getAuthority(): String? = authorityPart.ifEmpty { null }
    override fun getEncodedAuthority(): String? = authorityPart.ifEmpty { null }
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String? = authorityPart.ifEmpty { null }
    override fun getPort(): Int = -1
    override fun getPath(): String = if (pathPart.isNotEmpty()) "/$pathPart" else ""
    override fun getEncodedPath(): String = getPath()
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): List<String> = pathPart.split('/').filter { it.isNotEmpty() }
    override fun getLastPathSegment(): String? = getPathSegments().lastOrNull()
    override fun buildUpon(): Builder? = null
    override fun toString(): String = uriString
    override fun writeToParcel(dest: Parcel, flags: Int) {}
    override fun describeContents(): Int = 0
    override fun compareTo(other: Uri?): Int = uriString.compareTo(other?.toString() ?: "")
    override fun equals(other: Any?): Boolean = other is Uri && uriString == other.toString()
    override fun hashCode(): Int = uriString.hashCode()
}
