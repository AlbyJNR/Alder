package dev.alder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.net.URL

/**
 * Updates from the GitHub releases, as Plume does: a release tagged `v1.2` with an .apk attached is offered to every TV
 * on an older version, and Android's package installer puts it in place (same signing key, so data stays).
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/AlbyJNR/CAS/releases/latest"
    private const val RELEASES = "https://github.com/AlbyJNR/CAS/releases/download/"
    private const val DAY = 24 * 3600_000L

    class Release(val version: String, val apk: String)

    fun mine(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName.orEmpty()

    /** The newest release, or null if the check failed or it has no .apk. Blocks: run it off the main thread. */
    fun latest(c: Context): Release? = runCatching {
        val json = JSONObject(Http.get(LATEST, timeoutMs = 10_000))
        // No release published yet: GitHub answers "Not Found", so nothing is newer than this version.
        val release = if (json.optString("message") == "Not Found") Release(mine(c), "") else {
            val assets = json.getJSONArray("assets")
            // Only this repository's own release files: Android checks the signing key on install anyway, but there's
            // no reason to download anything else that an answer might name.
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url")
                ?.takeIf { it.startsWith(RELEASES) } ?: return null
            Release(json.getString("tag_name").removePrefix("v"), apk)
        }
        release.also { prefs(c).edit().putLong("checked", System.currentTimeMillis()).apply() }
    }.onFailure { android.util.Log.w("CAS", "update check failed: $it") }.getOrNull()

    /** A day since the last check: Home asks once a day, Settings whenever it's opened. */
    fun due(c: Context) = System.currentTimeMillis() - prefs(c).getLong("checked", 0) > DAY

    /** Home offers each new version once; Settings always shows it. */
    fun offerOnce(c: Context, version: String): Boolean {
        if (prefs(c).getString("offered", null) == version) return false
        prefs(c).edit().putString("offered", version).apply()
        return true
    }

    fun canInstall(c: Context) = c.packageManager.canRequestPackageInstalls()

    /**
     * Opens Android's page for letting CAS install apps. False when this TV has no such page (some Fire TV models
     * keep it under Developer options instead): starting it there crashed CAS.
     */
    fun askToInstall(c: Context): Boolean = try {
        c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: RuntimeException) { // ActivityNotFoundException, or a SecurityException from a locked-down settings app
        android.util.Log.w("CAS", "no install permission page: $e")
        false
    }

    /** Downloads [release] into an install session and hands it to Android. Blocks: run it off the main thread. */
    fun install(c: Context, release: Release) = commit(c, release, quiet = false)

    /** What a quiet (overnight) check did, for the log. */
    enum class Quiet { NOTHING, INSTALLING, READY_TO_CONFIRM, FAILED }

    /**
     * The overnight path: with Settings › About › Updates on Overnight, a newer release is downloaded and handed to
     * Android with nobody at the remote. On Android 12+ where CAS installed itself last time, it goes in without a
     * word and is there at the next start; where Android insists on its confirm box, the session is kept and Home
     * offers it ([pending], [confirmPending]) next time someone is watching. No toast, no dialog, ever. Blocks.
     */
    fun quiet(c: Context): Quiet {
        if (!Settings.autoUpdate(c) || !canInstall(c)) return Quiet.NOTHING
        if (pending(c) != null) return Quiet.READY_TO_CONFIRM // one waiting already
        val p = prefs(c)
        if (!quietDue(p.getLong("quietChecked", 0), System.currentTimeMillis())) return Quiet.NOTHING
        p.edit().putLong("quietChecked", System.currentTimeMillis()).apply()
        val release = latest(c) ?: return Quiet.FAILED
        if (!newerVersion(release.version, mine(c))) return Quiet.NOTHING
        return runCatching { commit(c, release, quiet = true); Quiet.INSTALLING }
            .onFailure { android.util.Log.w("CAS", "quiet update of ${release.version} failed: $it") }
            .getOrDefault(Quiet.FAILED)
    }

    /** A quiet download Android would not put in place by itself: its version, for Home to offer. Null when none. */
    fun pending(c: Context): Release? {
        val p = prefs(c)
        val id = p.getInt("pendingSession", -1).takeIf { it >= 0 } ?: return null
        val version = p.getString("pendingVersion", null)
        // The session is gone (installed, abandoned, or Android cleared it): nothing to offer.
        if (version == null || c.packageManager.packageInstaller.getSessionInfo(id) == null) { clearPending(c); return null }
        return Release(version, "")
    }

    /** Shows Android's confirm box for the quiet download that waits; false when there is none left to confirm. */
    fun confirmPending(c: Context): Boolean {
        pending(c) ?: return false
        val confirm = prefs(c).getString("pendingIntent", null)?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
        if (confirm == null) { clearPending(c); return false }
        return runCatching { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
            .onFailure { android.util.Log.w("CAS", "pending update confirm failed: $it"); clearPending(c) }.getOrDefault(false)
    }

    private fun clearPending(c: Context) = prefs(c).edit().remove("pendingSession").remove("pendingVersion").remove("pendingIntent").apply()

    /**
     * One install session for both paths: the file downloaded straight into it, checked for length, then committed to
     * Android with [Status] as the listener. A [quiet] commit tells Status not to raise the confirm box.
     */
    private fun commit(c: Context, release: Release, quiet: Boolean) {
        if (!release.apk.startsWith(RELEASES)) throw java.io.IOException("not a CAS release file")
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        // Android 12+ can skip the confirm box when CAS installed itself last time; otherwise Android asks.
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                // Timeouts: a stalled download used to wait forever with no word. A short file (a dropped connection)
                // is refused here, before Android's installer calls it a package it can't parse.
                val conn = URL(release.apk).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
                val expected = conn.contentLengthLong
                val written = conn.inputStream.use { input ->
                    session.openWrite("CAS.apk", 0, -1).use { out -> input.copyTo(out).also { session.fsync(out) } }
                }
                if (expected > 0 && written != expected) throw java.io.IOException("download cut short: $written of $expected bytes")
                val done = PendingIntent.getBroadcast(c, if (quiet) 1 else 0,
                    Intent(c, Status::class.java).putExtra("quiet", quiet).putExtra("version", release.version).putExtra("session", id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(done.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** The install session reports here: show Android's confirm box (or keep it for later, overnight), or say why it failed. */
    class Status : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val quiet = intent.getBooleanExtra("quiet", false)
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    if (!quiet) return c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    // Nobody is watching: the download waits in its session, and Home offers it next time.
                    prefs(c).edit().putInt("pendingSession", intent.getIntExtra("session", -1))
                        .putString("pendingVersion", intent.getStringExtra("version"))
                        .putString("pendingIntent", confirm.toUri(Intent.URI_INTENT_SCHEME)).apply()
                    android.util.Log.i("CAS", "quiet update ${intent.getStringExtra("version")} waits for a confirm")
                }
                PackageInstaller.STATUS_SUCCESS -> { clearPending(c); prefs(c).edit().remove("offered").apply() }
                PackageInstaller.STATUS_FAILURE_ABORTED -> clearPending(c)
                else -> {
                    clearPending(c)
                    android.util.Log.w("CAS", "update install failed: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                    if (!quiet) Toast.makeText(c, "Couldn't install the update", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("updates", Context.MODE_PRIVATE)
}

/** A quiet check is worth making again six hours after the last ([lastMs] 0 for never). */
internal fun quietDue(lastMs: Long, nowMs: Long): Boolean = nowMs - lastMs > 6 * 3600_000L

/** True when version [a] is newer than [b] ("1.10" > "1.9"; missing parts count as 0). */
internal fun newerVersion(a: String, b: String): Boolean {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}
