package dev.stan.duolock.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.stan.duolock.data.SettingsRepository
import dev.stan.duolock.duolingo.DuolingoAuth

/**
 * The one Duolingo-token entry flow: parse the JWT, save it with its user id,
 * report the result. Settings and onboarding both edit through this.
 */
class TokenField(private val repo: SettingsRepository, private val failureHint: String) {
    var text by mutableStateOf("")
    var status by mutableStateOf("")

    /** Parses and saves [text]; no-op when blank. */
    suspend fun commit() {
        if (text.isBlank()) return
        val userId = DuolingoAuth.userIdFromJwt(text)
        if (userId != null) {
            repo.setAuth(text.trim(), userId)
            status = "Saved. Duolingo user id $userId."
            text = ""
        } else {
            status = failureHint
        }
    }
}

@Composable
fun TokenTextField(field: TokenField) {
    OutlinedTextField(
        value = field.text,
        onValueChange = { field.text = it },
        label = { Text("Duolingo token (starts with eyJ)") },
        modifier = Modifier.fillMaxWidth(),
    )
    if (field.status.isNotBlank()) {
        Text(field.status, style = MaterialTheme.typography.bodySmall)
    }
}
