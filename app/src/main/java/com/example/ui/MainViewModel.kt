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
    val serviceDuration = ScreenCaptureService.durationSeconds
    val serviceError = ScreenCaptureService.error

    init {
        refreshVideosList()
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
