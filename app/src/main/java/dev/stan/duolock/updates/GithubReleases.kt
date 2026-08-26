package dev.stan.duolock.updates

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** The published release, reduced to the four things an updater needs. */
data class Release(
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

class ReleaseException(message: String) : Exception(message)

@Serializable
private data class ReleaseJson(
    @SerialName("tag_name") val tagName: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val assets: List<AssetJson> = emptyList(),
)

@Serializable
private data class AssetJson(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

/**
 * Reads the newest release of the app's own GitHub repo. Unauthenticated:
 * the repo is public and the rate limit is per-IP and generous, while a
 * button pressed by hand asks maybe twice a week.
 */
class GithubReleases(
    private val repo: String = "Staninna/duolock",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {

    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = client.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> throw ReleaseException("No releases published yet.")
                resp.code == 403 -> throw ReleaseException("GitHub is rate-limiting us. Try again later.")
                !resp.isSuccessful -> throw ReleaseException("GitHub said ${resp.code}.")
                else -> resp.body!!.string()
            }
        }
        parse(body)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Split out from the fetch so the parsing rules are unit-testable. */
        fun parse(body: String): Release {
            val release = try {
                json.decodeFromString<ReleaseJson>(body)
            } catch (e: Exception) {
                throw ReleaseException("Couldn't read GitHub's answer.")
            }
            if (release.tagName.isBlank()) throw ReleaseException("That release has no tag.")
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: throw ReleaseException("Release ${release.tagName} has no APK attached.")
            return Release(
                tag = release.tagName,
                notes = release.body.trim(),
                apkUrl = apk.browserDownloadUrl,
                sizeBytes = apk.size,
            )
        }
    }
}
