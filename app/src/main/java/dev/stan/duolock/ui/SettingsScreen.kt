package dev.stan.duolock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.stan.duolock.data.GateSnapshot
import dev.stan.duolock.data.Settings
import dev.stan.duolock.data.SettingsRepository
import kotlinx.coroutines.launch

@Composable
fun SettingsSectionHeader(title: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
    }
}

/**
 * An integer setting with its valid range stated at the edit site: shows an
 * inline error for junk or out-of-range input instead of silently mangling it.
 */
private class IntField(
    val label: String,
    val range: IntRange,
    val save: suspend (Int) -> Unit,
    /** When set, blank input is valid and runs this instead of [save]. */
    val onBlank: (suspend () -> Unit)? = null,
) {
    var text by mutableStateOf("")
    var error by mutableStateOf<String?>(null)

    /** Validates; returns false (and shows the error) when the input is bad. */
    suspend fun commit(): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank() && onBlank != null) {
            error = null
            onBlank.invoke()
            return true
        }
        val value = trimmed.toIntOrNull()
        if (value == null || value !in range) {
            error = "Enter a number between ${range.first} and ${range.last}" +
                if (onBlank != null) ", or leave blank for auto." else "."
            return false
        }
        error = null
        save(value)
        return true
    }
}

@Composable
private fun IntSettingField(field: IntField, hint: String? = null) {
    OutlinedTextField(
        value = field.text,
        onValueChange = { field.text = it; field.error = null },
        label = { Text(field.label) },
        isError = field.error != null,
        supportingText = (field.error ?: hint)?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    val snapshot by repo.snapshot.collectAsState(initial = GateSnapshot())
    val settings = snapshot.settings
    val scope = rememberCoroutineScope()

    val sessionMin = remember {
        IntField("Session length after a lesson (minutes)", 1..240, repo::setSessionMinutes)
    }
    val fallbackMin = remember {
        IntField("Fallback: minutes in Duolingo counts as a lesson", 1..60, repo::setFallbackLessonMinutes)
    }
    val minEnergy = remember {
        IntField("Energy needed to finish a lesson", 1..25, repo::setMinEnergyForLesson)
    }
    val saverHour = remember {
        IntField("Start hour (0-23)", 0..23, repo::setStreakSaverStartHour)
    }
    val staleMin = remember {
        IntField("Re-check low energy after (minutes)", 15..1440, repo::setStaleReadingMinutes)
    }
    val warnHour = remember {
        IntField("Streak warning from hour (0-23)", 0..23, repo::setStreakWarnHour)
    }
    // Blank means "auto": use the rate observed from Duolingo's energy drawer.
    val refillOverride = remember {
        IntField(
            "Energy refill: minutes per unit (blank = auto)", 1..720,
            save = { repo.setRefillMinutesOverride(it) },
            onBlank = { repo.setRefillMinutesOverride(null) },
        )
    }

    LaunchedEffect(settings.sessionMinutes) { sessionMin.text = settings.sessionMinutes.toString() }
    LaunchedEffect(settings.fallbackLessonMinutes) { fallbackMin.text = settings.fallbackLessonMinutes.toString() }
    LaunchedEffect(settings.minEnergyForLesson) { minEnergy.text = settings.minEnergyForLesson.toString() }
    LaunchedEffect(settings.streakSaverStartHour) { saverHour.text = settings.streakSaverStartHour.toString() }
    LaunchedEffect(settings.staleReadingMinutes) { staleMin.text = settings.staleReadingMinutes.toString() }
    LaunchedEffect(settings.streakWarnHour) { warnHour.text = settings.streakWarnHour.toString() }
    LaunchedEffect(settings.refillMinutesOverride) {
        refillOverride.text = settings.refillMinutesOverride?.toString() ?: ""
    }

    val token = remember {
        TokenField(repo, "That doesn't parse as a JWT. Copy the whole jwt_token cookie value.")
    }

    Column(
        Modifier
            .padding(20.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsSectionHeader("Scroll time")
        IntSettingField(sessionMin)
        IntSettingField(fallbackMin)

        SettingsSectionHeader("Energy")
        IntSettingField(
            refillOverride,
            hint = snapshot.session.observedRefillMinutesPerUnit
                ?.let { "Auto-detected from Duolingo: $it min/unit." }
                ?: "Nothing detected yet; default is ${Settings.DEFAULT_REFILL_MINUTES_PER_UNIT} min/unit.",
        )
        IntSettingField(minEnergy)

        SettingsSectionHeader("Nox's voice")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = settings.notifyGateOpen,
                onCheckedChange = { on -> scope.launch { repo.setNotifyGateOpen(on) } },
            )
            Spacer(Modifier.width(12.dp))
            Text("Notify when you enter a blocked app with time left")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = settings.notifyHalfway,
                onCheckedChange = { on -> scope.launch { repo.setNotifyHalfway(on) } },
            )
            Spacer(Modifier.width(12.dp))
            Text("Remind at half time that a lesson resets the clock")
        }
        IntSettingField(staleMin)
        Text(
            "When Nox last saw a low meter longer ago than this, he asks you to open " +
                "Duolingo once before handing out more free passes. " +
                "With Streak Saver active he always checks.",
            style = MaterialTheme.typography.bodySmall,
        )
        IntSettingField(warnHour)

        SettingsSectionHeader("Streak Saver")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = settings.streakSaverEnabled,
                onCheckedChange = { on -> scope.launch { repo.setStreakSaverEnabled(on) } },
            )
            Spacer(Modifier.width(12.dp))
            Text("Lock everything until today's lesson is done")
        }
        IntSettingField(saverHour)
        Text(
            "From the start hour until midnight, every app is locked while today's XP is zero. " +
                "Doing a lesson lifts it. Pick allowed apps in the Apps tab (phone, SMS, launcher and Duolingo always work). " +
                "Needs the Duolingo token.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (dev.stan.duolock.BuildConfig.DEBUG) {
            SettingsSectionHeader("Developer")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.showDebugTab,
                    onCheckedChange = { on -> scope.launch { repo.setShowDebugTab(on) } },
                )
                Spacer(Modifier.width(12.dp))
                Text("Show the Debug tab")
            }
        }

        SettingsSectionHeader("Duolingo account")
        TokenTextField(token)
        var guideOpen by remember { mutableStateOf(false) }
        Card(
            Modifier
                .fillMaxWidth()
                .clickable { guideOpen = !guideOpen },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (guideOpen) "How do I get this token? (tap to close)"
                    else "How do I get this token? (tap to open)",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (guideOpen) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        """
                        You need a computer for this, one time.

                        1. On the computer, log in at duolingo.com.
                        2. Press F12 and click the Console tab.
                        3. Type this short line and press Enter:

                        document.cookie.match(/jwt[^;]+/)

                        4. It answers with something like:
                        jwt_token="eyJhbG...long code..."
                        Copy only the long code between the quotes.
                        5. Send it to your phone, paste it above, tap Save.

                        Treat the token like a password: it is your Duolingo login. It stays on this phone. It lasts months; if DuoGate says the login expired, repeat these steps.

                        No computer? Skip this. A few minutes inside Duolingo then counts as your lesson instead.
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Button(
            onClick = {
                scope.launch {
                    listOf(sessionMin, fallbackMin, minEnergy, saverHour, staleMin, warnHour, refillOverride)
                        .forEach { it.commit() }
                    token.commit()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
    }
}
