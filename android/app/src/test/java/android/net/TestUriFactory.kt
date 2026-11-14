package android.net

import android.os.Parcel

/**
 * JVM 단위 테스트 환경에서 Robolectric 없이도 실제 non-null android.net.Uri 인스턴스를 생성하는 헬퍼.
 */
object TestUriFactory {
    fun create(uriString: String): Uri {
        return object : Uri() {
            override fun isHierarchical(): Boolean = true
            override fun isRelative(): Boolean = false
            override fun getScheme(): String = uriString.substringBefore(":", "file")
            override fun getEncodedSchemeSpecificPart(): String = uriString.substringAfter(":", "")
            override fun getSchemeSpecificPart(): String = uriString.substringAfter(":", "")
            override fun getEncodedAuthority(): String? = null
            override fun getAuthority(): String? = null
            override fun getEncodedUserInfo(): String? = null
            override fun getUserInfo(): String? = null
            override fun getHost(): String? = null
            override fun getPort(): Int = -1
            override fun getEncodedPath(): String = uriString
            override fun getPath(): String = uriString
            override fun getEncodedQuery(): String? = null
            override fun getQuery(): String? = null
            override fun getEncodedFragment(): String? = null
            override fun getFragment(): String? = null
            override fun getPathSegments(): MutableList<String> = mutableListOf()
            override fun getLastPathSegment(): String? = uriString.substringAfterLast("/")
            override fun buildUpon(): Builder = Builder()
            override fun describeContents(): Int = 0
            override fun writeToParcel(out: Parcel, flags: Int) {}
            override fun toString(): String = uriString
            override fun equals(other: Any?): Boolean = other is Uri && other.toString() == uriString
            override fun hashCode(): Int = uriString.hashCode()
            override fun compareTo(other: Uri?): Int = uriString.compareTo(other?.toString() ?: "")
        }
    }
}
