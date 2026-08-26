package dev.stan.duolock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.stan.duolock.updates.Updater
import kotlinx.coroutines.launch

/**
 * The Settings "Updates" block: one button that asks GitHub what the newest
 * release is, and — only when there is a newer one — a second that fetches
 * that APK and installs it. Nothing here happens without a tap.
 */
@Composable
fun UpdatesSection() {
    val context = LocalContext.current
    val updater = remember { Updater.get(context) }
    val state by updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    var needsPermission by remember { mutableStateOf(false) }

    SettingsSectionHeader("Updates")

    // A dev build cannot update itself: the release APK has a different package
    // id, so installing it would add a second app rather than replace this one.
    // Saying "you're up to date" here would be true only by accident -- Version
    // .parse rejects the "-dev" suffix, so the check always returns UpToDate no
    // matter what has been released.
    if (dev.stan.duolock.BuildConfig.DEBUG) {
        Text(
            "Dev build ${updater.currentVersion} — installed from the laptop, not from a " +
                "release. Updates go to the release build, which is installed alongside this one.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    Text("You're running version ${updater.currentVersion}.", style = MaterialTheme.typography.bodySmall)

    val busy = state is Updater.State.Checking ||
        state is Updater.State.Downloading ||
        state is Updater.State.Installing

    Button(
        onClick = { scope.launch { updater.check() } },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (state is Updater.State.Checking) "Checking…" else "Check for updates")
    }

    when (val s = state) {
        is Updater.State.UpToDate ->
            Text("Nothing new — ${s.version} is the latest.", style = MaterialTheme.typography.bodySmall)

        is Updater.State.Failed ->
            Text(
                s.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

        is Updater.State.Downloading -> {
            val total = s.totalBytes.takeIf { it > 0 }
            Text(
                if (total != null) "Downloading… ${s.downloadedBytes.mb()} of ${total.mb()} MB"
                else "Downloading… ${s.downloadedBytes.mb()} MB",
                style = MaterialTheme.typography.bodySmall,
            )
            if (total != null) {
                LinearProgressIndicator(
                    progress = { (s.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Updater.State.Installing ->
            Text(
                "Handing it to Android. DuoGate will restart on its own.",
                style = MaterialTheme.typography.bodySmall,
            )

        is Updater.State.Available -> {
            var notesOpen by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("${s.release.tag} is available", style = MaterialTheme.typography.titleSmall)
                    if (s.release.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (notesOpen) "What's new (tap to close)" else "What's new (tap to open)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { notesOpen = !notesOpen },
                        )
                        if (notesOpen) {
                            Spacer(Modifier.height(8.dp))
                            Text(s.release.notes, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (!updater.canInstallPackages()) {
                                needsPermission = true
                                context.startActivity(updater.unknownSourcesIntent())
                            } else {
                                needsPermission = false
                                scope.launch { updater.downloadAndInstall(s.release) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Download & install") }
                }
            }
            if (needsPermission) {
                Text(
                    "Android needs your permission first: allow DuoGate to install apps, " +
                        "then tap Download & install again.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Updater.State.Idle, Updater.State.Checking -> Unit
    }
}

/** Bytes as a one-decimal megabyte string, without pulling in a formatter. */
private fun Long.mb(): String = "%.1f".format(this / 1_048_576.0)
