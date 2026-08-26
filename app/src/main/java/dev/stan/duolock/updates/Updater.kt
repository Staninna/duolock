package dev.stan.duolock.updates

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import dev.stan.duolock.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The whole in-app update: ask GitHub what the newest release is, and if it
 * beats the running build, fetch that APK and install it over ourselves.
 *
 * Driven entirely by the Settings buttons — nothing here runs on its own.
 * A process-wide singleton so a download survives leaving the Settings tab.
 */
class Updater private constructor(private val app: Context) {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        /** Checked, and we already have the newest release. */
        data class UpToDate(val version: String) : State
        data class Available(val release: Release) : State
        data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : State
        /** Handed to the system; the process may be killed at any moment now. */
        data object Installing : State
        data class Failed(val message: String) : State
    }

    // Survives the composable: the retry is kicked off from a broadcast callback.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val releases = GithubReleases()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** True once the user has allowed DuoGate to install apps. */
    fun canInstallPackages(): Boolean = app.packageManager.canRequestPackageInstalls()

    /** The system screen where that permission is granted. */
    fun unknownSourcesIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${app.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun check() {
        _state.value = State.Checking
        _state.value = try {
            val release = releases.latest()
            if (Version.isNewer(release.tag, currentVersion)) State.Available(release)
            else State.UpToDate(currentVersion)
        } catch (e: ReleaseException) {
            State.Failed(e.message ?: "Couldn't reach GitHub.")
        } catch (e: Exception) {
            State.Failed("Couldn't reach GitHub: ${e.message ?: "no connection"}")
        }
    }

    fun dismiss() {
        _state.value = State.Idle
    }

    /** Downloads the release APK, then hands it to Android's package installer. */
    suspend fun downloadAndInstall(release: Release) {
        val apk = try {
            download(release)
        } catch (e: Exception) {
            _state.value = State.Failed("Download failed: ${e.message ?: "unknown error"}")
            return
        }
        _state.value = State.Installing
        try {
            install(apk, allowSilent = true)
        } catch (e: Exception) {
            _state.value = State.Failed("Install failed: ${e.message ?: "unknown error"}")
        }
    }

    /**
     * Some OEM builds — HyperOS among them — refuse a silent self-update by
     * killing the session outright ("INSTALL_FAILED_ABORTED: Permission
     * denied") rather than reporting PENDING_USER_ACTION and letting us show
     * the installer. So a silent attempt that fails is retried once the
     * ordinary way, with the system's confirmation dialog.
     */
    private fun retryWithConfirmation(apk: File) {
        scope.launch {
            _state.value = State.Installing
            try {
                install(apk, allowSilent = false)
            } catch (e: Exception) {
                _state.value = State.Failed("Install failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    private suspend fun download(release: Release): File = withContext(Dispatchers.IO) {
        // One APK at a time: a half-written file from a failed run must never
        // be the thing we install.
        val dir = File(app.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val target = File(dir, "duolock-${release.tag}.apk")
        _state.value = State.Downloading(0, release.sizeBytes)

        val req = Request.Builder().url(release.apkUrl).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ReleaseException("GitHub said ${resp.code}")
            val body = resp.body ?: throw ReleaseException("empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReported = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        // Report per ~256KB: a state change per 64KB chunk is
                        // recomposition noise, not information.
                        if (done - lastReported >= 256 * 1024) {
                            lastReported = done
                            _state.value = State.Downloading(done, total)
                        }
                    }
                    _state.value = State.Downloading(done, total)
                }
            }
        }
        target
    }

    private suspend fun install(apk: File, allowSilent: Boolean) = withContext(Dispatchers.IO) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(app.packageName)
            // Android 12+ lets an app update *itself* without a confirmation
            // dialog. When the OS declines, the session reports
            // PENDING_USER_ACTION and we show the installer screen instead.
            if (allowSilent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("duogate", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            session.commit(statusReceiver(sessionId, apk, allowSilent).intentSender)
        }
    }

    /**
     * A receiver registered for this one session: it owns the state updates,
     * which a manifest receiver in a fresh process could not do.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun statusReceiver(sessionId: Int, apk: File, allowSilent: Boolean): PendingIntent {
        val action = "${app.packageName}.INSTALL_STATUS.$sessionId"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = IntentCompat.getParcelableExtra(
                            intent, Intent.EXTRA_INTENT, Intent::class.java,
                        )
                        if (confirm != null) {
                            app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } else {
                            _state.value = State.Failed("Android wants confirmation but gave no screen.")
                        }
                    }
                    // On a silent update the process is killed before this
                    // arrives; seeing it at all means the installer screen ran.
                    PackageInstaller.STATUS_SUCCESS -> {
                        _state.value = State.Idle
                        unregister(this)
                    }
                    else -> {
                        unregister(this)
                        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        if (allowSilent) {
                            retryWithConfirmation(apk)
                        } else {
                            _state.value = State.Failed(msg ?: "Android refused the install.")
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        return PendingIntent.getBroadcast(
            app,
            sessionId,
            Intent(action).setPackage(app.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun unregister(receiver: BroadcastReceiver) {
        runCatching { app.unregisterReceiver(receiver) }
    }

    companion object {
        @Volatile private var instance: Updater? = null

        fun get(context: Context): Updater =
            instance ?: synchronized(this) {
                instance ?: Updater(context.applicationContext).also { instance = it }
            }
    }
}
