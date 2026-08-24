package dev.stan.duolock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.stan.duolock.data.SettingsRepository
import dev.stan.duolock.duolingo.DuolingoAuth
import kotlinx.coroutines.launch

/**
 * First-launch flow: meet the mascots, grant permissions, pick apps,
 * optionally connect Duolingo. Ends by setting onboardingDone.
 */
@Composable
fun OnboardingFlow() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    val lastStep = 3

    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (step) {
                0 -> Column(
                    Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    NoxWithLumen(energy = null)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Meet Nox and Lumen",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Nox guards your doomscrolling apps. One Duolingo lesson opens the gate " +
                            "for a while; then it closes again. Lumen is your Duolingo energy: " +
                            "when her light is out, no lesson is possible and Nox lets you through for free.",
                        textAlign = TextAlign.Center,
                    )
                }
                1 -> Column(Modifier.fillMaxSize()) {
                    Text(
                        "Step 1 of 3: permissions",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 20.dp, top = 20.dp),
                    )
                    PermissionsScreen()
                }
                2 -> Column(Modifier.fillMaxSize()) {
                    Text(
                        "Step 2 of 3: pick the apps Nox should guard",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
                    )
                    Box(Modifier.weight(1f)) { AppPickerScreen(showModeSwitch = false) }
                }
                3 -> Column(
                    Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Step 3 of 3: connect Duolingo", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "With your Duolingo token, DuoGate verifies lessons by your XP. " +
                            "You can skip this: a few minutes inside Duolingo then counts as a lesson. " +
                            "The how-to guide lives in Settings under the token field."
                    )
                    var token by remember { mutableStateOf("") }
                    var status by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Duolingo token (starts with eyJ)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            val userId = DuolingoAuth.userIdFromJwt(token)
                            if (userId != null) {
                                scope.launch { repo.setAuth(token.trim(), userId) }
                                status = "Saved. Duolingo user id $userId."
                            } else {
                                status = "That doesn't parse as a token. You can also do this later in Settings."
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save token") }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (step > 0) {
                OutlinedButton(onClick = { step-- }) { Text("Back") }
            } else {
                Spacer(Modifier.height(1.dp))
            }
            Button(onClick = {
                if (step < lastStep) step++
                else scope.launch { repo.setOnboardingDone(true) }
            }) {
                Text(if (step < lastStep) "Next" else "Finish")
            }
        }
    }
    }
}
