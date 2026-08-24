package dev.stan.duolock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.stan.duolock.blocking.AppMonitorService
import dev.stan.duolock.data.Settings
import dev.stan.duolock.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which package set the picker is editing. */
private enum class PickerMode(val label: String) {
    BLOCKED("Blocked apps"), WHITELIST("Streak Saver allowed")
}

/**
 * App picker. In the main UI it edits either the blocked list or the Streak
 * Saver whitelist (chip switch); onboarding calls it with the switch hidden.
 */
@Composable
fun AppPickerScreen(showModeSwitch: Boolean = true) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    val settings by repo.settings.collectAsState(initial = Settings())
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(PickerMode.BLOCKED) }

    val apps by produceState<List<InstalledApps.App>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            InstalledApps.launchable(
                context,
                excluding = setOf(context.packageName, AppMonitorService.DUOLINGO_PKG),
            )
        }
    }

    val loaded = apps
    if (loaded == null) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Loading apps…")
        }
        return
    }

    val selected = when (mode) {
        PickerMode.BLOCKED -> settings.blockedPackages
        PickerMode.WHITELIST -> settings.streakSaverWhitelist
    }
    val onToggle: (String, Boolean) -> Unit = { pkg, checked ->
        scope.launch {
            val newSet = if (checked) selected + pkg else selected - pkg
            when (mode) {
                PickerMode.BLOCKED -> repo.setBlockedPackages(newSet)
                PickerMode.WHITELIST -> repo.setStreakSaverWhitelist(newSet)
            }
        }
    }

    Column {
        if (showModeSwitch) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PickerMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m },
                        label = { Text(m.label) },
                    )
                }
            }
            if (mode == PickerMode.WHITELIST) {
                Text(
                    "These stay usable during a Streak Saver lockdown. Phone, SMS, launcher and Duolingo are always allowed.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        AppList(apps = loaded, mode = mode, selected = selected, onToggle = onToggle)
    }
}

/**
 * Searchable checkbox list. Already-selected apps sit in a labelled section on
 * top; the section is snapshotted per mode so toggling doesn't reshuffle the
 * list under the user's finger.
 */
@Composable
private fun AppList(
    apps: List<InstalledApps.App>,
    mode: PickerMode,
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val pinned = remember(apps, mode) { selected }
    val (top, rest) = remember(apps, pinned, query) {
        val matches = if (query.isBlank()) apps else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.pkg.contains(query, ignoreCase = true)
        }
        matches.partition { it.pkg in pinned }
    }

    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search apps") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.padding(horizontal = 8.dp)) {
            if (top.isNotEmpty()) {
                item(key = "header:selected") {
                    Text(
                        "Selected",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                items(top, key = { "pin:${it.pkg}" }) { app ->
                    AppRow(app, app.pkg in selected, onToggle)
                }
                item(key = "header:all") {
                    Text(
                        "All apps",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
            items(rest, key = { it.pkg }) { app ->
                AppRow(app, app.pkg in selected, onToggle)
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApps.App,
    checked: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle(app.pkg, it) },
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(app.label)
            Text(app.pkg, style = MaterialTheme.typography.bodySmall)
        }
    }
    HorizontalDivider()
}
