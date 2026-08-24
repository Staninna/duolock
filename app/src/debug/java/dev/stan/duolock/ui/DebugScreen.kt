package dev.stan.duolock.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import dev.stan.duolock.Notifications
import dev.stan.duolock.blocking.BlockingActivity
import dev.stan.duolock.data.GateSnapshot
import dev.stan.duolock.data.GrantSource
import dev.stan.duolock.data.SettingsRepository
import dev.stan.duolock.duolingo.DuolingoRepository
import dev.stan.duolock.duolingo.DebugUserOverride
import dev.stan.duolock.duolingo.EnergyEstimator
import dev.stan.duolock.duolingo.EnergyStatus
import dev.stan.duolock.duolingo.LessonVerifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Developer tooling. Debug builds only; release builds get an empty stub. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugScreen() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    val snapshot by repo.snapshot.collectAsState(initial = GateSnapshot())
    val settings = snapshot.settings
    val session = snapshot.session
    val scope = rememberCoroutineScope()

    // Ticks once a second so the live state actually moves.
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowTick = System.currentTimeMillis()
            delay(1000)
        }
    }

    Column(
        Modifier
            .padding(20.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsSectionHeader("Live state")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val energy = EnergyStatus.of(snapshot, nowTick)
                val mono = MaterialTheme.typography.bodySmall
                Text(
                    "allowance: ${session.remainingAllowanceMs / 1000}s of ${session.grantedAllowanceMs / 1000}s " +
                        "(source: ${session.grantSource?.name?.lowercase() ?: "none"})",
                    style = mono,
                )
                Text("xp snapshot: ${session.pendingXpSnapshot ?: "none (no block pending)"}", style = mono)
                Text(
                    "energy raw: ${session.energy?.units ?: "none"}, " +
                        "read ${session.energy?.let { "${(nowTick - it.atMs) / 1000}s ago" } ?: "never"}",
                    style = mono,
                )
                Text(
                    "energy estimate: ${energy.units} (threshold ${energy.threshold}, " +
                        "refill ${energy.refillMinutesPerUnit} min/unit)",
                    style = mono,
                )
                Text("until lesson: ${energy.minutesUntilLesson?.let { "$it min" } ?: "unknown (no reading)"}", style = mono)
                Text("blocked: ${settings.blockedPackages.size} apps", style = mono)
                Text(
                    "streak saver: ${if (settings.streakSaverEnabled) "on" else "off"}, " +
                        "from ${settings.streakSaverStartHour}:00, whitelist ${settings.streakSaverWhitelist.size} apps",
                    style = mono,
                )
            }
        }

        SettingsSectionHeader("Lock and session")
        Button(
            onClick = {
                context.startActivity(
                    Intent(context, BlockingActivity::class.java)
                        .putExtra(
                            BlockingActivity.EXTRA_BLOCKED_PKG,
                            settings.blockedPackages.firstOrNull() ?: "com.example.app"
                        )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Show lock screen (harmless preview)") }
        Button(
            onClick = { scope.launch { repo.lockNow() } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Reset lock (clear session + snapshot)") }
        Button(
            onClick = { scope.launch { repo.grantAllowance(2 * 60_000L, GrantSource.DEBUG) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Grant 2 min test pass") }
        Button(
            onClick = { scope.launch { repo.grantAllowance(15_000L, GrantSource.DEBUG) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Grant 15 s pass (watch it expire)") }

        SettingsSectionHeader("Energy")
        var fakeEnergy by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = fakeEnergy,
                onValueChange = { fakeEnergy = it },
                label = { Text("Fake energy reading") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                fakeEnergy.toIntOrNull()?.let { v ->
                    scope.launch { repo.recordEnergy(v, System.currentTimeMillis()) }
                }
            }) { Text("Set") }
        }
        // FlowRow, not Row: three preset buttons don't fit one line on a
        // phone, and a squeezed OutlinedButton renders its label vertically.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch { repo.recordEnergy(0, System.currentTimeMillis()) }
            }) { Text("Empty (0)", maxLines = 1) }
            OutlinedButton(onClick = {
                scope.launch {
                    repo.recordEnergy(
                        (settings.minEnergyForLesson - 1).coerceAtLeast(0), System.currentTimeMillis()
                    )
                }
            }) { Text("Just below threshold", maxLines = 1) }
            OutlinedButton(onClick = {
                scope.launch { repo.recordEnergy(EnergyEstimator.MAX_ENERGY, System.currentTimeMillis()) }
            }) { Text("Full (25)", maxLines = 1) }
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    session.energy?.let { repo.recordEnergy(it.units, it.atMs - 60 * 60_000L) }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Age reading by 1 hour (simulates regen)") }

        SettingsSectionHeader("Streak Saver")
        Text(
            "The monitor re-checks XP every few minutes, so a trigger can take that long to bite. " +
                "It only activates with a token set, a streak > 0 and zero XP today.",
            style = MaterialTheme.typography.bodySmall,
        )
        var fakeUser by remember { mutableStateOf(DebugUserOverride.mode) }
        Text(
            when (fakeUser) {
                DebugUserOverride.Mode.OFF -> "Duolingo data: real account"
                DebugUserOverride.Mode.STREAK_AT_RISK ->
                    "Duolingo data: FAKE - 5-day streak, no XP today (Saver trigger state)"
                DebugUserOverride.Mode.LESSON_DONE ->
                    "Duolingo data: FAKE - 5-day streak, lesson done today"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                DebugUserOverride.mode = DebugUserOverride.Mode.STREAK_AT_RISK
                fakeUser = DebugUserOverride.mode
            }) { Text("Fake: streak at risk", maxLines = 1) }
            OutlinedButton(onClick = {
                DebugUserOverride.mode = DebugUserOverride.Mode.LESSON_DONE
                fakeUser = DebugUserOverride.mode
            }) { Text("Fake: lesson done", maxLines = 1) }
            OutlinedButton(onClick = {
                DebugUserOverride.mode = DebugUserOverride.Mode.OFF
                fakeUser = DebugUserOverride.mode
            }) { Text("Real data", maxLines = 1) }
        }
        Button(
            onClick = {
                scope.launch {
                    repo.setStreakSaverStartHour(java.time.LocalTime.now().hour)
                    repo.setStreakSaverEnabled(true)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Arm Streak Saver from this hour") }
        OutlinedButton(
            onClick = { scope.launch { repo.setStreakSaverEnabled(false) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Disarm Streak Saver") }

        SettingsSectionHeader("Notifications")
        Button(
            onClick = {
                Notifications.event(
                    context, "Test notification",
                    "If you can read this, the events channel works.", id = 99,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Send test event notification") }

        SettingsSectionHeader("Duolingo API")
        var apiResult by remember { mutableStateOf("") }
        Button(
            onClick = {
                apiResult = "checking…"
                scope.launch {
                    val t0 = System.currentTimeMillis()
                    val state = DuolingoRepository.get().refresh(settings.jwt, settings.userId)
                    val ms = System.currentTimeMillis() - t0
                    val user = state.user
                    apiResult = when {
                        state.error != null -> "failed: ${state.error}"
                        user != null ->
                            "ok in ${ms}ms: totalXp=${user.totalXp}, xpToday=${LessonVerifier.xpToday(user.xpGains, System.currentTimeMillis())}"
                        else -> "no data"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Check Duolingo API now") }
        if (apiResult.isNotBlank()) Text(apiResult, style = MaterialTheme.typography.bodySmall)
    }
}
