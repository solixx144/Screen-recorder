package com.example.updater

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class GitHubUpdater(private val context: Context) {

    sealed class UpdateState {
        object Idle : UpdateState()
        object Checking : UpdateState()
        data class NoUpdate(val currentVersion: String) : UpdateState()
        data class UpdateAvailable(val latestVersion: String, val changelog: String, val downloadUrl: String) : UpdateState()
        data class Downloading(val progress: Int) : UpdateState()
        data class DownloadComplete(val fileUri: Uri) : UpdateState()
        data class Error(val message: String) : UpdateState()
    }

    private val client = OkHttpClient()
    
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()

    private var downloadId: Long = -1L
    private var downloadReceiver: BroadcastReceiver? = null

    companion object {
        private const val TAG = "GitHubUpdater"
        // Default repository placeholder that users can customize
        const val DEFAULT_OWNER = "AkifDurmus6145"
        const val DEFAULT_REPO = "ScreenRecorder"
    }

    /**
     * Checks if a new release exists on GitHub.
     */
    fun checkForUpdates(owner: String = DEFAULT_OWNER, repo: String = DEFAULT_REPO) {
        _state.value = UpdateState.Checking
        
        val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Android-ScreenRecorder-Updater")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Network request failed for updates check", e)
                _state.value = UpdateState.Error("Failed to check for updates: ${e.localizedMessage}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        _state.value = UpdateState.Error("Server returned code ${response.code}")
                        return
                    }

                    try {
                        val body = response.body?.string() ?: throw IOException("Empty response body")
                        val json = JSONObject(body)
                        val tagName = json.getString("tag_name")
                        val changelog = json.optString("body", "No release notes provided.")
                        
                        // Parse assets to find the APK
                        val assets = json.getJSONArray("assets")
                        var downloadUrl: String? = null
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.getString("name")
                            if (name.endsWith(".apk")) {
                                downloadUrl = asset.getString("browser_download_url")
                                break
                            }
                        }

                        if (downloadUrl == null) {
                            _state.value = UpdateState.Error("Latest release does not contain any APK files")
                            return
                        }

                        val currentVersion = getCurrentVersionName()
                        val cleanedTagName = tagName.trimStart('v')
                        val cleanedCurrentVersion = currentVersion.trimStart('v')

                        if (isNewerVersion(cleanedCurrentVersion, cleanedTagName)) {
                            _state.value = UpdateState.UpdateAvailable(
                                latestVersion = tagName,
                                changelog = changelog,
                                downloadUrl = downloadUrl
                            )
                        } else {
                            _state.value = UpdateState.NoUpdate(currentVersion)
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing update details JSON", e)
                        _state.value = UpdateState.Error("Failed parsing release manifest: ${e.localizedMessage}")
                    }
                }
            }
        })
    }

    /**
     * Downloads the APK file using the DownloadManager.
     */
    fun startDownload(downloadUrl: String, latestVersion: String) {
        _state.value = UpdateState.Downloading(0)
        
        try {
            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle("Screen Recorder Update")
                setDescription("Downloading $latestVersion")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setMimeType("application/vnd.android.package-archive")
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, 
                    "ScreenRecorder_$latestVersion.apk"
                )
            }

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = manager.enqueue(request)

            registerDownloadReceiver()
            monitorDownloadProgress(manager)
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating DownloadManager request", e)
            _state.value = UpdateState.Error("Download initialization failed: ${e.localizedMessage}")
        }
    }

    private fun monitorDownloadProgress(manager: DownloadManager) {
        Thread {
            var downloading = true
            while (downloading && _state.value is UpdateState.Downloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = manager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val bytesDownloadedCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalBytesCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    
                    if (statusCol != -1 && bytesDownloadedCol != -1 && totalBytesCol != -1) {
                        val status = cursor.getInt(statusCol)
                        val bytesDownloaded = cursor.getInt(bytesDownloadedCol)
                        val totalBytes = cursor.getInt(totalBytesCol)

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            downloading = false
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            downloading = false
                            _state.value = UpdateState.Error("Download failed inside DownloadManager")
                        } else if (totalBytes > 0) {
                            val progress = (bytesDownloaded * 100L / totalBytes).toInt()
                            _state.value = UpdateState.Downloading(progress)
                        }
                    }
                }
                cursor?.close()
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.start()
    }

    private fun registerDownloadReceiver() {
        if (downloadReceiver != null) return

        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == downloadId) {
                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = manager.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        if (statusCol != -1 && cursor.getInt(statusCol) == DownloadManager.STATUS_SUCCESSFUL) {
                            val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            if (uriCol != -1) {
                                val fileUriStr = cursor.getString(uriCol)
                                if (fileUriStr != null) {
                                    val localUri = Uri.parse(fileUriStr)
                                    _state.value = UpdateState.DownloadComplete(localUri)
                                    // Trigger auto install
                                    installApk(localUri)
                                }
                            }
                        }
                    }
                    cursor?.close()
                    unregisterDownloadReceiver()
                }
            }
        }
        
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(downloadReceiver, filter)
        }
    }

    private fun unregisterDownloadReceiver() {
        downloadReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
        downloadReceiver = null
    }

    /**
     * Prompts the user to install the downloaded APK.
     */
    fun installApk(downloadedUri: Uri) {
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        try {
            val contentUri = if (downloadedUri.scheme == "file") {
                val file = File(downloadedUri.path ?: "")
                FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            } else {
                // Use the Uri directly, which is likely a content:// Uri from DownloadManager
                downloadedUri
            }
            
            installIntent.setDataAndType(contentUri, "application/vnd.android.package-archive")
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing installation Intent", e)
            _state.value = UpdateState.Error("Installation failed to start: ${e.localizedMessage}")
        }
    }

    private fun getCurrentVersionName(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0"
        }
    }

    /**
     * Simple version comparison (semantic versioning check).
     */
    private fun isNewerVersion(current: String, latest: String): Boolean {
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }

        val size = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until size) {
            val currVal = currentParts.getOrNull(i) ?: 0
            val lateVal = latestParts.getOrNull(i) ?: 0
            if (lateVal > currVal) return true
            if (currVal > lateVal) return false
        }
        return false
    }
}
