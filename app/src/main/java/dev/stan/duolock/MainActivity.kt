package dev.stan.duolock

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.stan.duolock.blocking.AppMonitorService
import dev.stan.duolock.data.Settings
import dev.stan.duolock.data.SettingsRepository
import dev.stan.duolock.ui.AppPickerScreen
import dev.stan.duolock.ui.DebugScreen
import dev.stan.duolock.ui.OnboardingFlow
import dev.stan.duolock.ui.PermissionsScreen
import dev.stan.duolock.ui.SettingsScreen
import dev.stan.duolock.ui.StatusScreen
import dev.stan.duolock.ui.theme.DuoGateTheme

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        AppMonitorService.start(this)
        setContent { DuoGateTheme { DuoGateUi() } }
    }
}

enum class HomeTab(val label: String) {
    STATUS("Status"), APPS("Apps"), SETTINGS("Settings"), SETUP("Setup"), DEBUG("Debug");

    companion object {
        // The Debug tab (and its screen) only exists in debug builds.
        val visible: List<HomeTab> =
            if (BuildConfig.DEBUG) entries else entries - DEBUG
    }
}

@Composable
fun DuoGateUi() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    // null until DataStore has actually loaded, so we never flash the
    // onboarding (or the wrong tab set) on a default value.
    val loadedSettings by produceState<Settings?>(initialValue = null) {
        repo.settings.collect { value = it }
    }
    val settings = loadedSettings ?: run {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        return
    }
    if (!settings.onboardingDone) {
        OnboardingFlow()
        return
    }
    var tab by rememberSaveable { mutableStateOf(HomeTab.STATUS) }
    Scaffold { padding ->
        Column(Modifier.padding(padding)) {
            TabRow(selectedTabIndex = HomeTab.visible.indexOf(tab)) {
                HomeTab.visible.forEach { t ->
                    Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) })
                }
            }
            when (tab) {
                HomeTab.STATUS -> StatusScreen()
                HomeTab.APPS -> AppPickerScreen()
                HomeTab.SETTINGS -> SettingsScreen()
                HomeTab.SETUP -> PermissionsScreen()
                HomeTab.DEBUG -> DebugScreen()
            }
        }
    }
}
