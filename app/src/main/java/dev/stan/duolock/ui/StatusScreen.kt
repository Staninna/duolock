package dev.stan.duolock.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stan.duolock.data.GateSnapshot
import dev.stan.duolock.data.SettingsRepository
import dev.stan.duolock.duolingo.DuolingoRepository
import dev.stan.duolock.duolingo.EnergyEstimator
import dev.stan.duolock.duolingo.EnergyStatus
import dev.stan.duolock.duolingo.LessonVerifier
import dev.stan.duolock.ui.theme.LumenGold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StatusScreen() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    val duoRepo = remember { DuolingoRepository.get() }
    val snapshot by repo.snapshot.collectAsState(initial = GateSnapshot())
    val settings = snapshot.settings
    val session = snapshot.session
    val duoState by duoRepo.state.collectAsState()

    LaunchedEffect(settings.jwt, settings.userId) {
        duoRepo.refresh(settings.jwt, settings.userId, minIntervalMs = 60_000L)
    }

    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()
    val energy = EnergyStatus.of(snapshot, now)
    Column(Modifier.padding(20.dp)) {
        Text("Status", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val active = session.remainingAllowanceMs > 0
                Text(
                    when {
                        active -> "Unlocked. ${session.remainingAllowanceMs / 60_000 + 1} min of scroll time left."
                        energy.waitText != null -> "Locked. Lesson energy refills in about ${energy.waitText}."
                        else -> "Locked. Do a Duolingo lesson to unlock."
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (active) {
                    Text(
                        if (energy.waitText != null)
                            "The clock only runs while a blocked app is on screen. The next lesson needs about ${energy.waitText} of energy refill."
                        else
                            "The clock only runs while a blocked app is on screen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { scope.launch { repo.lockNow() } }) { Text("Lock now") }
                }
                Spacer(Modifier.height(8.dp))
                BlockedAppIcons(settings.blockedPackages)
                Text(
                    if (settings.jwt.isBlank()) "Duolingo account not connected. Only the time-in-Duolingo fallback works."
                    else "Duolingo account connected (user ${settings.userId})."
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Duolingo data", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val user = duoState.user
                when {
                    settings.jwt.isBlank() -> Text("Connect your account in Settings to see live data.")
                    user == null && duoState.error != null ->
                        Text("Couldn't reach Duolingo (${duoState.error}).")
                    user == null -> Text("Loading…")
                    else -> {
                        val units = energy.units
                        if (units == null) {
                            Text("Energy: no reading yet. Open Duolingo once with the energy reader enabled (Setup tab).")
                        } else {
                            val reading = energy.reading!!
                            val ageMin = (now - reading.atMs) / 60_000
                            Text("Energy: about $units of ${EnergyEstimator.MAX_ENERGY}")
                            Text(
                                "Last read ${reading.units} from Duolingo $ageMin min ago, " +
                                    "refill assumed 1 unit per ${energy.refillMinutesPerUnit} min.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (energy.lowForLesson) {
                                Text(
                                    "Below the lesson threshold (${energy.threshold}). DuoGate will not block; you get a free pass while energy refills" +
                                        (energy.waitText?.let { " (about $it to go)." } ?: ".")
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        val xpToday = LessonVerifier.xpToday(user.xpGains, now)
                        Text("XP today: $xpToday")
                        Text("Total XP: ${user.totalXp}")
                        if (user.streak > 0) {
                            Text(
                                if (xpToday > 0) "Streak: ${user.streak} days, safe for today"
                                else "Streak: ${user.streak} days, at risk until you do a lesson",
                                color = if (xpToday > 0) Color(0xFF7BD97B) else LumenGold,
                            )
                        }
                        user.streakData?.let { Text("Daily goal: ${it.xpGoal} XP") }
                        session.pendingXpSnapshot?.let {
                            Text("Waiting for a lesson. XP snapshot: $it")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    scope.launch { duoRepo.refresh(settings.jwt, settings.userId) }
                }) { Text("Refresh") }
            }
        }
    }
}

@Composable
private fun BlockedAppIcons(blockedPackages: Set<String>) {
    val context = LocalContext.current
    if (blockedPackages.isEmpty()) {
        Text("No apps blocked yet. Pick some in the Apps tab.")
        return
    }
    val icons by produceState<List<Pair<String, ImageBitmap>>>(
        initialValue = emptyList(), blockedPackages,
    ) {
        value = withContext(Dispatchers.IO) {
            blockedPackages.sorted().mapNotNull { pkg ->
                InstalledApps.icon(context, pkg)?.let { pkg to it }
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icons.forEach { (pkg, bmp) ->
            Image(
                bitmap = bmp,
                contentDescription = pkg,
                modifier = Modifier.padding(end = 8.dp).height(32.dp).width(32.dp),
            )
        }
        if (icons.isNotEmpty()) {
            Text("${icons.size} blocked", style = MaterialTheme.typography.bodySmall)
        }
    }
}
