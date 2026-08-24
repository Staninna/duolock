package dev.stan.duolock.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** The one place PackageManager labels, icons and app lists come from. */
object InstalledApps {

    data class App(val pkg: String, val label: String)

    /** Human label for [pkg], falling back to the package name. */
    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    /** App icon as a small ImageBitmap, or null when the app is gone. */
    fun icon(context: Context, pkg: String, sizePx: Int = 96): ImageBitmap? = try {
        context.packageManager.getApplicationIcon(pkg).toBitmap(sizePx, sizePx).asImageBitmap()
    } catch (_: Exception) {
        null
    }

    /** All launchable apps except DuoGate and Duolingo, sorted by label. */
    fun launchable(context: Context, excluding: Set<String>): List<App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .asSequence()
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it !in excluding }
            .mapNotNull { pkg ->
                try {
                    App(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString())
                } catch (_: Exception) { null }
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
