package dev.stan.duolock.duolingo

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class UserResponse(
    val totalXp: Long = 0,
    val xpGains: List<XpGain> = emptyList(),
    val streakData: StreakData? = null,
    val streak: Int = 0,
)

@Serializable
data class XpGain(val xp: Long = 0, val time: Long = 0)

@Serializable
data class StreakData(val xpGoal: Int = 0)

class DuolingoApiException(message: String, val code: Int = 0) : Exception(message)

class DuolingoApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val userAgent =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    suspend fun fetchUser(jwt: String, userId: Long): UserResponse = withContext(Dispatchers.IO) {
        val url = "https://www.duolingo.com/2017-06-30/users/$userId" +
            "?fields=totalXp,xpGains,streakData%7BxpGoal%7D,streak"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $jwt")
            .header("User-Agent", userAgent)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw DuolingoApiException("Duolingo API error ${resp.code}", resp.code)
            }
            json.decodeFromString<UserResponse>(resp.body!!.string())
        }
    }
}
