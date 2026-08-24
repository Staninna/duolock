package dev.stan.duolock.duolingo

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object DuolingoAuth {

    /**
     * The JWT `sub` claim is the Duolingo numeric user id.
     * Returns null if the token can't be parsed.
     */
    fun userIdFromJwt(jwt: String): Long? = try {
        val payload = jwt.trim().split(".")[1]
        val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val obj = Json.parseToJsonElement(String(decoded)).jsonObject
        obj["sub"]?.jsonPrimitive?.content?.toLongOrNull()
    } catch (_: Exception) {
        null
    }
}
