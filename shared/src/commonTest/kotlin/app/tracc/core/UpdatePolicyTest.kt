package app.tracc.core

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class UpdatePolicyTest {
    @Test fun onlyExactReleaseAssetsAreAccepted() {
        val url = "https://github.com/example/TRACC/releases/download/v1.1.0/TRACC.apk"
        fun valid(u: String, hash: String = "a".repeat(64), size: Long = 100) =
            UpdatePolicy.validAsset(u, "example/TRACC", "v1.1.0", hash, size)
        assertTrue(valid(url))
        listOf(
            url.replace("github.com", "github.com.evil.test"),
            url.replace("example/TRACC", "other/TRACC"),
            url.replace("v1.1.0/", "v1.1.1/"),
            url.replace("TRACC.apk", "../TRACC.apk"),
            url.replace("TRACC.apk", "%2e%2e%2fTRACC.apk"),
            url + "?redirect=evil", url.replace("https:", "http:")
        ).forEach { assertFalse(valid(it), it) }
        assertFalse(valid(url, "z".repeat(64)))
        assertFalse(valid(url, size = 0))
        assertFalse(valid(url, size = UpdatePolicy.MAX_DOWNLOAD + 1))
    }
}
