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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
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
import dev.stan.duolock.ui.theme.StaleCoral
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
                val staleLow = energy.lowForLesson && readingAge != null &&
                    readingAge > snapshot.settings.staleReadingMinutes * 60_000L
                BlockScreen(
                    appLabel = blockedAppLabel,
                    energy = energy,
                    readingAgeMinutes = readingAge?.let { it / 60_000L },
                    staleLow = staleLow,
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

/**
 * The three things the lock screen can be saying, kept apart on purpose: they
 * used to share a headline and a "Do a lesson" button, so "Nox wants to
 * re-check your energy" read as "go do a lesson".
 */
private class BlockCopy(
    val chip: String,
    val accent: Color,
    val headline: String,
    val body: String,
    val primaryLabel: String,
)

private fun ageText(minutes: Long): String = when {
    minutes < 90 -> "$minutes min"
    minutes < 48 * 60 -> "${(minutes + 30) / 60} hours"
    else -> "${minutes / (24 * 60)} days"
}

@Composable
private fun StateChip(label: String, accent: Color) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.7f)),
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun BlockScreen(
    appLabel: String,
    energy: EnergyStatus,
    readingAgeMinutes: Long? = null,
    staleLow: Boolean = false,
    onOpenDuolingo: () -> Unit,
    onGoHome: () -> Unit,
) {
    // With no reading the gate stays optimistic: a lesson might be possible.
    val lessonPossible = energy.waitText == null
    val copy = when {
        staleLow -> BlockCopy(
            chip = "Checking your energy",
            accent = StaleCoral,
            headline = "Nox wants a second look",
            body = "His last reading said you were empty" +
                (readingAgeMinutes?.let { ", but it's ${ageText(it)} old" } ?: ", but it's old") +
                ". Open Duolingo so he can see the meter. Still empty? " +
                "You go straight through — no lesson needed.",
            primaryLabel = "Open Duolingo so Nox can look",
        )
        lessonPossible -> BlockCopy(
            chip = "Lesson ready",
            accent = MaterialTheme.colorScheme.primary,
            headline = "$appLabel is locked",
            body = "Finish a Duolingo lesson to unlock your apps. " +
                "DuoGate checks automatically. Just come back when you're done.",
            primaryLabel = "Do a lesson",
        )
        else -> BlockCopy(
            chip = "Refilling · ${energy.waitText}",
            accent = LumenGold,
            headline = "$appLabel is locked",
            body = "Not enough energy for a lesson yet — about ${energy.waitText} to go. " +
                "Open Duolingo anyway and DuoGate will let you through in the meantime.",
            primaryLabel = "Open Duolingo",
        )
    }
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
            StateChip(copy.chip, copy.accent)
            Spacer(Modifier.height(12.dp))
            energy.units?.let { units ->
                Text(
                    "Lumen's light: $units of 25" +
                        if (staleLow && readingAgeMinutes != null) " (read ${ageText(readingAgeMinutes)} ago)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = LumenGold,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                copy.headline,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                copy.body,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
            Button(onClick = onOpenDuolingo, modifier = Modifier.fillMaxWidth()) {
                Text(copy.primaryLabel)
            }
            Spacer(Modifier.height(12.dp))
            if (lessonPossible && !staleLow) {
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
