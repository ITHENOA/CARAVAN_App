package com.example.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.util.BsPatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val changelog: String,
    val fullApkUrl: String,
    val fullApkSha256: String? = null,
    val patchUrl: String? = null,
    val patchFromVersionCode: Int? = null,
    val patchSha256: String? = null,
    val isPatchAvailable: Boolean = false
)

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data object Checking : UpdateDownloadState
    data class Available(val info: UpdateInfo) : UpdateDownloadState
    data object UpToDate : UpdateDownloadState
    data class Downloading(val progressPercent: Int, val isPatch: Boolean, val statusText: String) : UpdateDownloadState
    data class ReadyToInstall(val apkFile: File) : UpdateDownloadState
    data class Error(val message: String) : UpdateDownloadState
}

class AppUpdateManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "AppUpdateManager"
    }

    suspend fun checkForUpdates(apiBaseUrl: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val cleanBase = apiBaseUrl.trim().trimEnd('/')
        val url = "$cleanBase/api/version"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Caravan-Android/${BuildConfig.VERSION_NAME}")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to check version: HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                val remoteCode = json.optInt("versionCode", 0)
                val remoteName = json.optString("versionName", "")
                val changelog = json.optString("changelog", "Bug fixes and performance improvements")
                val rawFullApkUrl = json.optString("downloadUrl", "")
                val fullApkUrl = if (rawFullApkUrl.startsWith("/")) "$cleanBase$rawFullApkUrl" else rawFullApkUrl
                val fullApkSha256 = json.optString("sha256", "").ifBlank { null }

                // Differential patch support
                val patchObj = json.optJSONObject("patch")
                var patchUrl: String? = null
                var patchFromCode: Int? = null
                var patchSha256: String? = null
                var isPatchAvailable = false

                if (patchObj != null) {
                    val rawPatchUrl = patchObj.optString("patchUrl", "").ifBlank { null }
                    patchUrl = if (rawPatchUrl?.startsWith("/") == true) "$cleanBase$rawPatchUrl" else rawPatchUrl
                    patchFromCode = patchObj.optInt("fromVersionCode", -1)
                    patchSha256 = patchObj.optString("patchSha256", "").ifBlank { null }
                    if (patchUrl != null && patchFromCode == BuildConfig.VERSION_CODE) {
                        isPatchAvailable = true
                    }
                }

                if (remoteCode > BuildConfig.VERSION_CODE) {
                    return@withContext UpdateInfo(
                        versionCode = remoteCode,
                        versionName = remoteName,
                        changelog = changelog,
                        fullApkUrl = fullApkUrl,
                        fullApkSha256 = fullApkSha256,
                        patchUrl = patchUrl,
                        patchFromVersionCode = patchFromCode,
                        patchSha256 = patchSha256,
                        isPatchAvailable = isPatchAvailable
                    )
                }
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for update: ${e.message}", e)
            return@withContext null
        }
    }

    /**
     * Downloads either the differential patch or full APK, applies patch if applicable,
     * and yields progressive status callbacks.
     */
    suspend fun downloadAndPrepareApk(
        info: UpdateInfo,
        onProgress: (percent: Int, isPatch: Boolean, status: String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val baseDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val updatesDir = File(baseDir, "updates").apply { mkdirs() }
        val targetApk = File(updatesDir, "caravan-v${info.versionName}.apk")

        if (targetApk.exists() && targetApk.length() > 0) {
            targetApk.delete()
        }

        val canUsePatch = info.isPatchAvailable && !info.patchUrl.isNullOrBlank()
        if (canUsePatch) {
            try {
                onProgress(5, true, "Downloading delta patch...")
                val patchFile = File(updatesDir, "update.patch")
                if (patchFile.exists()) patchFile.delete()

                downloadFile(info.patchUrl!!, patchFile) { downloaded, total ->
                    val pct = if (total > 0) ((downloaded * 70) / total).toInt() else 35
                    onProgress(5 + pct, true, "Downloading delta patch (${downloaded / 1024} KB)...")
                }

                onProgress(80, true, "Applying delta patch to current version...")
                val currentApkPath = context.applicationInfo.sourceDir
                val currentApkFile = File(currentApkPath)

                if (!currentApkFile.exists() || !currentApkFile.canRead()) {
                    throw IllegalStateException("Cannot read installed APK source for delta patch")
                }

                BsPatch.patch(currentApkFile, targetApk, patchFile)
                patchFile.delete()

                onProgress(100, true, "Update ready")
                return@withContext targetApk
            } catch (e: Exception) {
                Log.w(TAG, "Patch application failed, falling back to full APK download: ${e.message}", e)
                if (targetApk.exists()) targetApk.delete()
            }
        }

        // Full APK download fallback or standard route
        onProgress(5, false, "Downloading update package...")
        downloadFile(info.fullApkUrl, targetApk) { downloaded, total ->
            val pct = if (total > 0) ((downloaded * 90) / total).toInt() else 45
            onProgress(5 + pct, false, "Downloading update (${downloaded / 1024 / 1024} MB)...")
        }

        onProgress(100, false, "Download complete")
        targetApk
    }

    private fun downloadFile(
        fileUrl: String,
        destination: File,
        onBytesCopied: (copied: Long, total: Long) -> Unit
    ) {
        val request = Request.Builder().url(fileUrl).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Download failed with HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("Empty download response body")
            val totalBytes = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    var copied: Long = 0
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        copied += read
                        onBytesCopied(copied, totalBytes)
                    }
                    output.flush()
                }
            }
        }
    }

    /**
     * Prompts the system PackageInstaller to install the downloaded APK.
     */
    fun promptInstall(apkFile: File) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Log.e(TAG, "Cannot install: APK does not exist or is empty: ${apkFile.absolutePath}")
            return
        }

        // Android 8.0+ (API 26+) requires user permission to install unknown apps
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                return
            }
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Explicitly grant read URI permissions to any matching package installer activities
            val resolvedActivities = context.packageManager.queryIntentActivities(
                intent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )
            for (res in resolvedActivities) {
                val pkg = res.activityInfo.packageName
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
        }
    }
}
