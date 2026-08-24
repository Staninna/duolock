package dev.stan.duolock.blocking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.stan.duolock.data.GateSnapshot
import dev.stan.duolock.data.SettingsRepository
import dev.stan.duolock.duolingo.EnergyStatus
import dev.stan.duolock.ui.InstalledApps
import dev.stan.duolock.ui.NoxWithLumen
import dev.stan.duolock.ui.theme.DuoGateTheme
import dev.stan.duolock.ui.theme.LumenGold
import kotlinx.coroutines.delay

class BlockingActivity : ComponentActivity() {

    companion object {
        const val EXTRA_BLOCKED_PKG = "blocked_pkg"
        const val ACTION_UNLOCKED = "dev.stan.duolock.UNLOCKED"
    }

    private var blockedAppLabel by mutableStateOf("this app")

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        blockedAppLabel = intent.getStringExtra(EXTRA_BLOCKED_PKG)
            ?.let { InstalledApps.label(this, it) } ?: "this app"

        val filter = IntentFilter(ACTION_UNLOCKED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(unlockReceiver, filter)
        }

        setContent {
            DuoGateTheme {
                BackHandler { goHome() }
                val repo = remember { SettingsRepository.get(this) }
                val snapshot by repo.snapshot.collectAsState(initial = GateSnapshot())
                // A ticking clock, so the wait estimate on a screen someone is
                // staring at actually counts down.
                val now by produceState(System.currentTimeMillis()) {
                    while (true) {
                        value = System.currentTimeMillis()
                        delay(30_000)
                    }
                }
                val energy = EnergyStatus.of(snapshot, now)
                val readingAge = energy.reading?.let { now - it.atMs }
                BlockScreen(
                    appLabel = blockedAppLabel,
                    energy = energy,
                    staleLow = energy.lowForLesson && readingAge != null &&
                        readingAge > snapshot.settings.staleReadingMinutes * 60_000L,
                    onOpenDuolingo = { openDuolingo() },
                    onGoHome = { goHome() },
                )
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(unlockReceiver)
        super.onDestroy()
    }

    private fun openDuolingo() {
        val launch = packageManager.getLaunchIntentForPackage(AppMonitorService.DUOLINGO_PKG)
        if (launch != null) {
            startActivity(launch)
        } else {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.duolingo.com/lesson"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun BlockScreen(
    appLabel: String,
    energy: EnergyStatus,
    staleLow: Boolean = false,
    onOpenDuolingo: () -> Unit,
    onGoHome: () -> Unit,
) {
    // With no reading the gate stays optimistic: a lesson might be possible.
    val lessonPossible = energy.waitText == null
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Nox watches; Lumen flies around her, glowing with the live energy level.
            NoxWithLumen(energy = energy.units)
            Spacer(Modifier.height(16.dp))
            energy.units?.let { units ->
                Text(
                    "Lumen's light: $units of 25",
                    style = MaterialTheme.typography.bodySmall,
                    color = LumenGold,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                "$appLabel is locked",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    staleLow ->
                        "Nox thinks you're out of energy, but that reading is hours old. " +
                            "Open Duolingo so he can check. If you're still empty, you go straight through."
                    lessonPossible ->
                        "Finish a Duolingo lesson to unlock your apps. " +
                            "DuoGate checks automatically. Just come back when you're done."
                    else ->
                        "Not enough energy for a lesson yet. About ${energy.waitText} " +
                            "until you can do one. Open Duolingo and DuoGate will let you through in the meantime."
                },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
            Button(onClick = onOpenDuolingo, modifier = Modifier.fillMaxWidth()) {
                Text("Do a lesson")
            }
            Spacer(Modifier.height(12.dp))
            if (lessonPossible) {
                Text(
                    "Out of energy? Open Duolingo anyway. DuoGate reads the meter and lets you through on its own.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
            }
            OutlinedButton(onClick = onGoHome, modifier = Modifier.fillMaxWidth()) {
                Text("Never mind, take me home")
            }
        }
    }
}
