package dev.stan.duolock.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as SysSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.stan.duolock.permissions.SystemPermissions
import dev.stan.duolock.ui.theme.LumenGold

private fun packageUri(context: Context) = Uri.parse("package:${context.packageName}")

/** Everything DuoGate needs granted, with its check and its settings page. */
private enum class RequiredPermission(
    val title: String,
    val isGranted: (Context) -> Boolean,
    val settingsIntent: (Context) -> Intent,
) {
    USAGE_ACCESS(
        "Usage access",
        { SystemPermissions.hasUsageAccess(it) },
        { Intent(SysSettings.ACTION_USAGE_ACCESS_SETTINGS) },
    ),
    OVERLAY(
        "Display over other apps",
        { SysSettings.canDrawOverlays(it) },
        { Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(it)) },
    ),
    ENERGY_READER(
        "Energy reader (accessibility)",
        { SystemPermissions.isEnergyReaderEnabled(it) },
        { Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS) },
    ),
    BATTERY(
        "Ignore battery optimization",
        { SystemPermissions.ignoresBatteryOptimizations(it) },
        { Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(it)) },
    ),
}

@Composable
fun PermissionsScreen() {
    val context = LocalContext.current
    // Re-check the permission states every time the app comes back to the
    // foreground, so granting something in system settings shows up instantly.
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val granted = remember(refresh) {
        RequiredPermission.entries.associateWith { it.isGranted(context) }
    }
    Column(
        Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "DuoGate needs these to work. Each button opens the right settings page.",
            style = MaterialTheme.typography.bodyMedium,
        )
        RequiredPermission.entries.forEach { permission ->
            PermissionRow(
                title = permission.title,
                granted = granted[permission] == true,
            ) {
                context.startActivity(permission.settingsIntent(context))
            }
        }
        Text(
            "Tip: on Xiaomi/HyperOS also enable Autostart for DuoGate in app settings, " +
                "or the service may be killed in the background.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            Text(
                if (granted) "Granted" else "Not granted yet",
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) Color(0xFF7BD97B) else LumenGold,
            )
        }
        if (granted) {
            OutlinedButton(onClick = onClick) { Text("Open") }
        } else {
            Button(onClick = onClick) { Text("Grant") }
        }
    }
}
