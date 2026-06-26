package com.example.ui

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.engine.ScreenRecorder
import com.example.service.ScreenCaptureService
import com.example.updater.GitHubUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()

    // Screen recording custom specifications
    private val _width = MutableStateFlow("1920")
    val width = _width.asStateFlow()

    private val _height = MutableStateFlow("1080")
    val height = _height.asStateFlow()

    private val _fps = MutableStateFlow("30")
    val fps = _fps.asStateFlow()

    private val _customBitrate = MutableStateFlow("8") // Default in Mbps
    val customBitrate = _customBitrate.asStateFlow()

    private val _useHevc = MutableStateFlow(true)
    val useHevc = _useHevc.asStateFlow()

    private val _autoBitrate = MutableStateFlow(true)
    val autoBitrate = _autoBitrate.asStateFlow()

    // Dynamic calculated bitrate
    val calculatedBitrate: StateFlow<Int> = combine(
        _width, _height, _fps, _useHevc
    ) { wStr, hStr, fStr, hevc ->
        val w = wStr.toIntOrNull() ?: 1920
        val h = hStr.toIntOrNull() ?: 1080
        val f = fStr.toIntOrNull() ?: 30
        ScreenRecorder.calculateIdealBitrate(w, h, f, hevc)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 5_000_000)

    // Log files
    private val _recordedVideos = MutableStateFlow<List<File>>(emptyList())
    val recordedVideos = _recordedVideos.asStateFlow()

    // Self-updater
    private val updater = GitHubUpdater(context)
    val updateState = updater.state

    // Repository configurations
    private val _ownerName = MutableStateFlow(GitHubUpdater.DEFAULT_OWNER)
    val ownerName = _ownerName.asStateFlow()

    private val _repoName = MutableStateFlow(GitHubUpdater.DEFAULT_REPO)
    val repoName = _repoName.asStateFlow()

    // Expose service recording states
    val isServiceRecording = ScreenCaptureService.isRecording
    val isServicePaused = ScreenCaptureService.isPaused
    val serviceDuration = ScreenCaptureService.durationSeconds
    val serviceError = ScreenCaptureService.error

    private val prefs = context.getSharedPreferences("screen_recorder_prefs", Context.MODE_PRIVATE)

    private val _maxDurationLimit = MutableStateFlow<Long?>(null) // in seconds, null = no limit
    val maxDurationLimit = _maxDurationLimit.asStateFlow()

    private val _maxFileSizeLimit = MutableStateFlow<Long?>(null) // in MB, null = no limit
    val maxFileSizeLimit = _maxFileSizeLimit.asStateFlow()

    private val _customFolderUri = MutableStateFlow<String?>(null)
    val customFolderUri = _customFolderUri.asStateFlow()

    private val _customFolderName = MutableStateFlow<String?>(null)
    val customFolderName = _customFolderName.asStateFlow()

    private val _showOverlayPreview = MutableStateFlow(true)
    val showOverlayPreview = _showOverlayPreview.asStateFlow()

    init {
        val savedDuration = prefs.getLong("max_duration_limit", -1L)
        _maxDurationLimit.value = if (savedDuration > 0) savedDuration else null
        
        val savedFileSize = prefs.getLong("max_file_size_limit", -1L)
        _maxFileSizeLimit.value = if (savedFileSize > 0) savedFileSize else null

        _customFolderUri.value = prefs.getString("custom_folder_uri", null)
        _customFolderName.value = prefs.getString("custom_folder_name", null)
        _showOverlayPreview.value = prefs.getBoolean("show_overlay_preview", true)
        refreshVideosList()
    }

    fun setMaxDurationLimit(seconds: Long?) {
        _maxDurationLimit.value = seconds
        prefs.edit().putLong("max_duration_limit", seconds ?: -1L).apply()
    }

    fun setMaxFileSizeLimit(mb: Long?) {
        _maxFileSizeLimit.value = mb
        prefs.edit().putLong("max_file_size_limit", mb ?: -1L).apply()
    }

    fun setShowOverlayPreview(show: Boolean) {
        _showOverlayPreview.value = show
        prefs.edit().putBoolean("show_overlay_preview", show).apply()
    }

    fun setCustomSaveDir(uri: android.net.Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to take persistable URI permission", e)
        }
        val documentFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
        val name = documentFile?.name ?: uri.path ?: "Custom Folder"
        _customFolderUri.value = uri.toString()
        _customFolderName.value = name
        prefs.edit()
            .putString("custom_folder_uri", uri.toString())
            .putString("custom_folder_name", name)
            .apply()
    }

    fun clearCustomSaveDir() {
        _customFolderUri.value = null
        _customFolderName.value = null
        prefs.edit()
            .remove("custom_folder_uri")
            .remove("custom_folder_name")
            .apply()
    }

    fun pauseRecording() {
        val intent = android.content.Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_PAUSE
        }
        context.startService(intent)
    }

    fun resumeRecording() {
        val intent = android.content.Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_RESUME
        }
        context.startService(intent)
    }

    fun setWidth(value: String) {
        _width.value = value.filter { it.isDigit() }
    }

    fun setHeight(value: String) {
        _height.value = value.filter { it.isDigit() }
    }

    fun setFps(value: String) {
        _fps.value = value.filter { it.isDigit() }
    }

    fun setCustomBitrate(value: String) {
        _customBitrate.value = value.filter { it.isDigit() }
    }

    fun setUseHevc(value: Boolean) {
        _useHevc.value = value
    }

    fun setAutoBitrate(value: Boolean) {
        _autoBitrate.value = value
    }

    fun setOwnerName(value: String) {
        _ownerName.value = value
    }

    fun setRepoName(value: String) {
        _repoName.value = value
    }

    fun applyPreset(w: Int, h: Int, f: Int) {
        _width.value = w.toString()
        _height.value = h.toString()
        _fps.value = f.toString()
    }

    /**
     * Refreshes the local video list from directory
     */
    fun refreshVideosList() {
        viewModelScope.launch {
            val movieDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            if (movieDir != null && movieDir.exists()) {
                val files = movieDir.listFiles { _, name ->
                    name.endsWith(".mp4") || name.endsWith(".mkv")
                }?.sortedByDescending { it.lastModified() } ?: emptyList()
                _recordedVideos.value = files
            }
        }
    }

    /**
     * Deletes a recorded video
     */
    fun deleteVideo(file: File) {
        viewModelScope.launch {
            if (file.exists()) {
                file.delete()
                refreshVideosList()
            }
        }
    }

    /**
     * Trigger GitHub Update check
     */
    fun checkForUpdates() {
        updater.checkForUpdates(_ownerName.value, _repoName.value)
    }

    /**
     * Start APK Download
     */
    fun startUpdateDownload(url: String, version: String) {
        updater.startDownload(url, version)
    }

    /**
     * Manually install APK if download is complete
     */
    fun installDownloadedApk(uri: android.net.Uri) {
        updater.installApk(uri)
    }
}
