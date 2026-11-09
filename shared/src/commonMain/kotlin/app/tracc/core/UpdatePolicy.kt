package app.tracc.core

object UpdatePolicy {
    const val MAX_DOWNLOAD = 512L * 1024 * 1024
    fun validVersion(version: String): Boolean = Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version)
    fun validAsset(url: String, repo: String, tag: String, hash: String, size: Long): Boolean =
        Regex("https://github\\.com/${Regex.escape(repo)}/releases/download/${Regex.escape(tag)}/[A-Za-z0-9][A-Za-z0-9._-]*").matches(url) &&
            Regex("[0-9a-fA-F]{64}").matches(hash) && size in 1..MAX_DOWNLOAD
}
