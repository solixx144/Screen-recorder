package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import android.view.TextureView
import android.graphics.SurfaceTexture
import android.view.Surface
import kotlin.math.roundToInt
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.service.ScreenCaptureService
import com.example.ui.MainViewModel
import com.example.ui.theme.MyApplicationTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.example.updater.GitHubUpdater
import java.io.File
import java.text.DecimalFormat

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Activity launcher for POST_NOTIFICATIONS permission (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Notifications are required for foreground recording feedback.", Toast.LENGTH_LONG).show()
        }
    }

    // Activity launcher for Screen Capture permission flow
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startRecordingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen recording permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MyApplicationTheme(darkTheme = true) { // Always dark theme for sleek aesthetic
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    ScreenRecorderDashboard(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding),
                        onStartRecordingRequested = { requestScreenCapturePermission() },
                        onStopRecordingRequested = { stopRecordingService() }
                    )
                }
            }
        }
    }

    private fun requestScreenCapturePermission() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startRecordingService(resultCode: Int, data: Intent) {
        val widthVal = viewModel.width.value.toIntOrNull() ?: 1920
        val heightVal = viewModel.height.value.toIntOrNull() ?: 1080
        val fpsVal = viewModel.fps.value.toIntOrNull() ?: 30
        
        val bitrateVal = if (viewModel.autoBitrate.value) {
            0 // 0 tells service to calculate ideal bitrate
        } else {
            (viewModel.customBitrate.value.toFloatOrNull() ?: 8.0f).toInt() * 1_000_000
        }
        val useHevcVal = viewModel.useHevc.value
        val maxDurationVal = viewModel.maxDurationLimit.value ?: -1L
        val maxFileSizeVal = viewModel.maxFileSizeLimit.value ?: -1L

        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_WIDTH, widthVal)
            putExtra(ScreenCaptureService.EXTRA_HEIGHT, heightVal)
            putExtra(ScreenCaptureService.EXTRA_FPS, fpsVal)
            putExtra(ScreenCaptureService.EXTRA_BITRATE, bitrateVal)
            putExtra(ScreenCaptureService.EXTRA_USE_HEVC, useHevcVal)
            putExtra(ScreenCaptureService.EXTRA_MAX_DURATION, maxDurationVal)
            putExtra(ScreenCaptureService.EXTRA_MAX_FILE_SIZE, maxFileSizeVal)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopRecordingService() {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(serviceIntent)
        // Refresh local recordings directory list immediately
        viewModel.refreshVideosList()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshVideosList()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Color.White.copy(alpha = 0.06f),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.15f),
                    Color.White.copy(alpha = 0.02f)
                )
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun GlassCardAmber(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Color(0x1AF59E0B),
        border = BorderStroke(
            1.dp,
            Color(0x33F59E0B)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun GlassInnerBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.25f), shape = RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(12.dp))
            .padding(12.dp),
        content = content
    )
}

@Composable
fun ScreenRecorderDashboard(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onStartRecordingRequested: () -> Unit,
    onStopRecordingRequested: () -> Unit
) {
    val isRecording by viewModel.isServiceRecording.collectAsState()
    val duration by viewModel.serviceDuration.collectAsState()
    val serviceError by viewModel.serviceError.collectAsState()
    val context = LocalContext.current

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.setCustomSaveDir(uri)
        }
    }

    // Show error toasts if they occur in service
    LaunchedEffect(serviceError) {
        serviceError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }

    var selectedTab by remember { mutableStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0B14))
    ) {
        // Glowing Ambient Mesh Blobs (Frosted Glass theme)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(350.dp)
                .offset(x = (-100).dp, y = (-100).dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF312E81).copy(alpha = 0.35f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(350.dp)
                .offset(x = 100.dp, y = 100.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF5B21B6).copy(alpha = 0.3f), Color.Transparent)
                    )
                )
        )

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Hero Top Banner Block
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_recorder_banner),
                    contentDescription = "Futuristic Screen Recorder Banner",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                
                // Premium semi-transparent dark-cyberpunk overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xFF0D0B14)),
                                startY = 50f
                            )
                        )
                )

            // Content inside the Hero Header
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = "AETHER CAPTURE",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineLarge
                        )
                        Text(
                            text = "Next-Gen HW Screen Recording",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Recording State Indicator Badge
                    Surface(
                        color = if (isRecording) Color(0xFFFF3355) else Color(0xFF2C2C2E),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            if (isRecording) {
                                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                                val scale by infiniteTransition.animateFloat(
                                    initialValue = 0.8f,
                                    targetValue = 1.2f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(800, easing = FastOutSlowInEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "pulse"
                                )
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .scale(scale)
                                        .background(Color.White, shape = CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = String.format("%02d:%02d", duration / 60, duration % 60),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color.Gray, shape = CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "IDLE",
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Navigation Tabs Row
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = Color.White,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = Color(0xFF6366F1)
                )
            },
            divider = {}
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Settings", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { 
                    selectedTab = 1
                    viewModel.refreshVideosList()
                },
                text = { Text("Videos", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.VideoLibrary, contentDescription = "Videos") }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Updater", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.CloudDownload, contentDescription = "Updater") }
            )
        }

        // Display current content depending on selected tab
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            when (selectedTab) {
                0 -> CaptureSettingsTab(
                    viewModel = viewModel,
                    isRecording = isRecording,
                    onStartRecording = onStartRecordingRequested,
                    onStopRecording = onStopRecordingRequested,
                    onSelectFolder = { folderLauncher.launch(null) }
                )
                1 -> RecordedVideosTab(
                    viewModel = viewModel
                )
                2 -> SelfUpdaterTab(
                    viewModel = viewModel
                )
            }
        }
    }

    // Draggable Live Capture Overlay Preview
    val showOverlayPreview by viewModel.showOverlayPreview.collectAsState()
    if (isRecording && showOverlayPreview) {
        var previewOffset by remember { mutableStateOf(Offset(50f, 150f)) }
        
        Box(
            modifier = Modifier
                .offset { IntOffset(previewOffset.x.roundToInt(), previewOffset.y.roundToInt()) }
                .size(width = 140.dp, height = 240.dp)
                .background(Color(0xE61F1A30), shape = RoundedCornerShape(16.dp))
                .border(1.5.dp, Color(0xFF6366F1), shape = RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        previewOffset = Offset(
                            x = (previewOffset.x + dragAmount.x).coerceAtLeast(0f),
                            y = (previewOffset.y + dragAmount.y).coerceAtLeast(0f)
                        )
                    }
                }
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val pulseTransition = rememberInfiniteTransition(label = "pulsing_live")
                        val liveAlpha by pulseTransition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulsing_live"
                        )
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .scale(liveAlpha)
                                .background(Color.Red, shape = CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "LIVE CAPTURE",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black, shape = RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
                                        val surface = Surface(texture)
                                        ScreenCaptureService.setPreviewSurface(surface)
                                    }

                                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) {}

                                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                        ScreenCaptureService.setPreviewSurface(null)
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
}

@Composable
fun CaptureSettingsTab(
    viewModel: MainViewModel,
    isRecording: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onSelectFolder: () -> Unit
) {
    val width by viewModel.width.collectAsState()
    val height by viewModel.height.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val customBitrate by viewModel.customBitrate.collectAsState()
    val useHevc by viewModel.useHevc.collectAsState()
    val autoBitrate by viewModel.autoBitrate.collectAsState()
    val calculatedBitrate by viewModel.calculatedBitrate.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Preset Configurations Chips Section
            Text(
                text = "Dynamic Resolution Presets",
                fontSize = 14.sp,
                color = Color.LightGray,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val presets = listOf(
                    Pair("1080p", Triple(1920, 1080, 60)),
                    Pair("4K UHD", Triple(3840, 2160, 60)),
                    Pair("Ultra-Wide", Triple(3840, 1728, 60)),
                    Pair("Mobile", Triple(1080, 2400, 60))
                )
                presets.forEach { (label, config) ->
                    SuggestionChip(
                        onClick = { viewModel.applyPreset(config.first, config.second, config.third) },
                        label = { Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color.White.copy(alpha = 0.08f),
                            labelColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }

        item {
            // Resolution Input Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = width,
                    onValueChange = { viewModel.setWidth(it) },
                    label = { Text("Width") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Black.copy(alpha = 0.25f),
                        unfocusedContainerColor = Color.Black.copy(alpha = 0.15f),
                        focusedBorderColor = Color(0xFF6366F1),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                        focusedLabelColor = Color(0xFF6366F1),
                        unfocusedLabelColor = Color.Gray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = height,
                    onValueChange = { viewModel.setHeight(it) },
                    label = { Text("Height") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Black.copy(alpha = 0.25f),
                        unfocusedContainerColor = Color.Black.copy(alpha = 0.15f),
                        focusedBorderColor = Color(0xFF6366F1),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                        focusedLabelColor = Color(0xFF6366F1),
                        unfocusedLabelColor = Color.Gray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }

        item {
            // FPS Input Section
            OutlinedTextField(
                value = fps,
                onValueChange = { viewModel.setFps(it) },
                label = { Text("FPS (Framerate)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Black.copy(alpha = 0.25f),
                    unfocusedContainerColor = Color.Black.copy(alpha = 0.15f),
                    focusedBorderColor = Color(0xFF6366F1),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                    focusedLabelColor = Color(0xFF6366F1),
                    unfocusedLabelColor = Color.Gray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )
        }

        item {
            // Codec Selection Row
            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Hardware Optimization",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = if (useHevc) "H.265 / HEVC codec (Prioritized)" else "H.264 / AVC legacy codec",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    Switch(
                        checked = useHevc,
                        onCheckedChange = { viewModel.setUseHevc(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF6366F1)
                        )
                    )
                }
            }
        }

        item {
            // Bitrate management block
            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Intelligent Bitrate",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (autoBitrate) {
                                    "Dynamic: ${(calculatedBitrate / 1_000_000f)} Mbps"
                                } else {
                                    "Manual Bitrate Mode"
                                },
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = autoBitrate,
                            onCheckedChange = { viewModel.setAutoBitrate(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF6366F1)
                            )
                        )
                    }

                    AnimatedVisibility(visible = !autoBitrate) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = customBitrate,
                            onValueChange = { viewModel.setCustomBitrate(it) },
                            label = { Text("Manual Bitrate (Mbps)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Black.copy(alpha = 0.25f),
                                unfocusedContainerColor = Color.Black.copy(alpha = 0.15f),
                                focusedBorderColor = Color(0xFF6366F1),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                focusedLabelColor = Color(0xFF6366F1),
                                unfocusedLabelColor = Color.Gray,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }
        }

        // Custom Save Location
        item {
            val customFolderName by viewModel.customFolderName.collectAsState()
            val customFolderUri by viewModel.customFolderUri.collectAsState()

            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Save Location",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = customFolderName ?: "Default (App Movies Folder)",
                                fontSize = 12.sp,
                                color = Color.Gray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (customFolderUri != null) {
                                IconButton(
                                    onClick = { viewModel.clearCustomSaveDir() }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Reset Location",
                                        tint = Color.Red.copy(alpha = 0.8f)
                                    )
                                }
                            }
                            Button(
                                onClick = onSelectFolder,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF6366F1)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = "Select Folder",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Choose", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Live Capture Preview Overlay Card
        item {
            val showOverlayPreview by viewModel.showOverlayPreview.collectAsState()

            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Live Overlay Preview",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Floating preview of active capture screen",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    Switch(
                        checked = showOverlayPreview,
                        onCheckedChange = { viewModel.setShowOverlayPreview(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF6366F1)
                        )
                    )
                }
            }
        }

        // Auto Stop Recording Limits Section
        item {
            val maxDurationLimit by viewModel.maxDurationLimit.collectAsState()
            val maxFileSizeLimit by viewModel.maxFileSizeLimit.collectAsState()

            var durationText by remember { mutableStateOf(maxDurationLimit?.toString() ?: "") }
            var fileSizeText by remember { mutableStateOf(maxFileSizeLimit?.toString() ?: "") }

            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "Auto-Stop Thresholds",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Automatically stops recording once bounds are reached",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = durationText,
                            onValueChange = {
                                val clean = it.filter { c -> c.isDigit() }
                                durationText = clean
                                viewModel.setMaxDurationLimit(clean.toLongOrNull())
                            },
                            label = { Text("Max Duration (sec)", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Black.copy(alpha = 0.25f),
                                unfocusedContainerColor = Color.Black.copy(alpha = 0.15f),
                                focusedBorderColor = Color(0xFF6366F1),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        OutlinedTextField(
                            value = fileSizeText,
                            onValueChange = {
                                val clean = it.filter { c -> c.isDigit() }
                                fileSizeText = clean
                                viewModel.setMaxFileSizeLimit(clean.toLongOrNull())
                            },
                            label = { Text("Max File Size (MB)", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Black.copy(alpha = 0.25f),
                                unfocusedContainerColor = Color.Black.copy(alpha = 0.15f),
                                focusedBorderColor = Color(0xFF6366F1),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }
        }

        // Dual Control Action Buttons
        item {
            val isPaused by viewModel.isServicePaused.collectAsState()
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isRecording) {
                    Button(
                        onClick = {
                            if (isPaused) {
                                viewModel.resumeRecording()
                            } else {
                                viewModel.pauseRecording()
                            }
                        },
                        modifier = Modifier
                            .weight(1.0f)
                            .height(64.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPaused) Color(0xFFF59E0B) else Color(0xFF4B5563)
                        ),
                        shape = RoundedCornerShape(18.dp),
                        elevation = ButtonDefaults.buttonElevation(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (isPaused) "Resume" else "Pause",
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isPaused) "RESUME" else "PAUSE",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        if (isRecording) {
                            onStopRecording()
                        } else {
                            onStartRecording()
                        }
                    },
                    modifier = Modifier
                        .weight(1.0f)
                        .height(64.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color(0xFFEF4444) else Color(0xFF6366F1)
                    ),
                    shape = RoundedCornerShape(18.dp),
                    elevation = ButtonDefaults.buttonElevation(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            contentDescription = "Record Control Action",
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRecording) "STOP RECORD" else "START CAPTURE",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RecordedVideosTab(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val videos by viewModel.recordedVideos.collectAsState()

    if (videos.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.VideoLibrary,
                contentDescription = "Empty",
                modifier = Modifier.size(64.dp),
                tint = Color.Gray
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No Screen Recordings Yet",
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Start capturing and your video files will appear here.",
                color = Color.Gray,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(videos) { file ->
                VideoFileRow(
                    file = file,
                    onPlayClick = {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/*")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "No compatible video player found.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onDeleteClick = {
                        viewModel.deleteVideo(file)
                        Toast.makeText(context, "Recording deleted", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@Composable
fun VideoFileRow(
    file: File,
    onPlayClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val sizeMB = file.length() / (1024f * 1024f)
    val decimalFormat = DecimalFormat("#.##")
    
    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play circular action indicator with Indigo glow
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0xFF6366F1).copy(alpha = 0.15f), shape = CircleShape)
                    .clip(CircleShape)
                    .clickable { onPlayClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play Video",
                    tint = Color(0xFF6366F1),
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${decimalFormat.format(sizeMB)} MB",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }

            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete File",
                    tint = Color.Gray
                )
            }
        }
    }
}

@Composable
fun SelfUpdaterTab(
    viewModel: MainViewModel
) {
    val state by viewModel.updateState.collectAsState()
    val owner by viewModel.ownerName.collectAsState()
    val repo by viewModel.repoName.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Configurable Repository details
        GlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text(
                    text = "GitHub Release Settings",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = owner,
                        onValueChange = { viewModel.setOwnerName(it) },
                        label = { Text("Owner") },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Black.copy(alpha = 0.25f),
                            unfocusedContainerColor = Color.Black.copy(alpha = 0.15f),
                            focusedBorderColor = Color(0xFF6366F1),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                            focusedLabelColor = Color(0xFF6366F1),
                            unfocusedLabelColor = Color.Gray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = repo,
                        onValueChange = { viewModel.setRepoName(it) },
                        label = { Text("Repository") },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Black.copy(alpha = 0.25f),
                            unfocusedContainerColor = Color.Black.copy(alpha = 0.15f),
                            focusedBorderColor = Color(0xFF6366F1),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                            focusedLabelColor = Color(0xFF6366F1),
                            unfocusedLabelColor = Color.Gray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
        }

        // Action Trigger
        Button(
            onClick = { viewModel.checkForUpdates() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF6366F1)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.CloudSync, contentDescription = "Sync")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Check GitHub Releases for Updates", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // State Renderer
        when (val currState = state) {
            is GitHubUpdater.UpdateState.Idle -> {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No check performed yet. Press above to connect.",
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            is GitHubUpdater.UpdateState.Checking -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF6366F1))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Querying GitHub API...", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
            is GitHubUpdater.UpdateState.NoUpdate -> {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = "Latest",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Up to Date",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Current local version (${currState.currentVersion}) is the latest available.",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            is GitHubUpdater.UpdateState.UpdateAvailable -> {
                GlassCardAmber(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdateAlt,
                                contentDescription = "Update Available",
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Update Available: ${currState.latestVersion}",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "New binary release discovered on GitHub",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Changelog section
                        Text(
                            text = "Release Notes:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.LightGray
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = currState.changelog,
                            fontSize = 12.sp,
                            color = Color.Gray,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    if (!context.packageManager.canRequestPackageInstalls()) {
                                        Toast.makeText(context, "Please enable unknown sources settings to allow self-update install.", Toast.LENGTH_LONG).show()
                                        val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(settingsIntent)
                                        return@Button
                                    }
                                }
                                viewModel.startUpdateDownload(currState.downloadUrl, currState.latestVersion)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Download & Auto-Install APK", fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                }
            }
            is GitHubUpdater.UpdateState.Downloading -> {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = "Downloading APK release...",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { currState.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF6366F1),
                            trackColor = Color.White.copy(alpha = 0.1f),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Progress: ${currState.progress}%",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }
            }
            is GitHubUpdater.UpdateState.DownloadComplete -> {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.DownloadForOffline,
                            contentDescription = "Done",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Download Complete!",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.installDownloadedApk(currState.fileUri) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                        ) {
                            Text("Relaunch Package Installer", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            is GitHubUpdater.UpdateState.Error -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0x1AFF3355),
                    border = BorderStroke(1.dp, Color(0x33FF3355))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "Error",
                                tint = Color(0xFFFF3355)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Updater Check Error",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = currState.message,
                            fontSize = 12.sp,
                            color = Color.LightGray
                        )
                    }
                }
            }
        }
    }
}
