package com.example.videoplayerapp

import kotlin.OptIn
import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.videoplayerapp.theme.VideoPlayerAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

// ==========================================
// 1. Data Layer: Models & Repository
// ==========================================

data class VideoItem(
    val id: Long,
    val title: String,
    val uri: Uri,
    val duration: Long,
    val size: Long
)

interface VideoRepository {
    fun fetchVideos(): Flow<List<VideoItem>>
}

class LocalVideoRepository(private val context: Context) : VideoRepository {
    override fun fetchVideos(): Flow<List<VideoItem>> = flow {
        val videoList = mutableListOf<VideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )

        val sortOrder = "${MediaStore.Video.Media.DISPLAY_NAME} ASC"

        val query = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )

        query?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "Unknown Video"
                val duration = cursor.getLong(durationColumn)
                val size = cursor.getLong(sizeColumn)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                videoList.add(VideoItem(id, name, contentUri, duration, size))
            }
        }
        emit(videoList)
    }
}

// ==========================================
// 2. ViewModel & Presentation State
// ==========================================

sealed interface VideoUiState {
    object Loading : VideoUiState
    object Empty : VideoUiState
    data class Success(val videos: List<VideoItem>) : VideoUiState
    data class Error(val message: String) : VideoUiState
}

class VideoViewModel(private val repository: VideoRepository) : ViewModel() {
    private val _uiState = MutableStateFlow<VideoUiState>(VideoUiState.Loading)
    val uiState: StateFlow<VideoUiState> = _uiState.asStateFlow()

    fun loadVideos() {
        _uiState.value = VideoUiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            repository.fetchVideos()
                .catch { exception ->
                    _uiState.value = VideoUiState.Error(exception.message ?: "Unknown error")
                }
                .collect { videos ->
                    if (videos.isEmpty()) {
                        _uiState.value = VideoUiState.Empty
                    } else {
                        _uiState.value = VideoUiState.Success(videos)
                    }
                }
        }
    }
}

class VideoViewModelFactory(private val repository: VideoRepository) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VideoViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return VideoViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// ==========================================
// 3. UI Layer: MainActivity & Composables
// ==========================================

class MainActivity : ComponentActivity() {
    val isPipMode = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VideoPlayerAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val repository = remember { LocalVideoRepository(applicationContext) }
                    val factory = remember { VideoViewModelFactory(repository) }
                    val viewModel: VideoViewModel = viewModel(factory = factory)
                    VideoApp(viewModel = viewModel)
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = android.app.PictureInPictureParams.Builder().build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPipMode.value = isInPictureInPictureMode
    }
}

fun hasVideoPermission(context: Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return ContextCompat.checkSelfPermission(
        context,
        permission
    ) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun VideoApp(viewModel: VideoViewModel) {
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(hasVideoPermission(context)) }

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        permissionGranted = isGranted
        if (isGranted) {
            viewModel.loadVideos()
        }
    }

    var selectedVideo by remember { mutableStateOf<VideoItem?>(null) }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            viewModel.loadVideos()
        } else {
            launcher.launch(permission)
        }
    }

    if (selectedVideo != null) {
        VideoPlayerScreen(
            video = selectedVideo!!,
            onBack = { selectedVideo = null }
        )
    } else {
        VideoListScreen(
            permissionGranted = permissionGranted,
            onRequestPermission = { launcher.launch(permission) },
            viewModel = viewModel,
            onVideoClick = { selectedVideo = it }
        )
    }
}

@Composable
fun VideoListScreen(
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    viewModel: VideoViewModel,
    onVideoClick: (VideoItem) -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Local Videos") }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            if (!permissionGranted) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Permission required to read videos")
                    Button(onClick = onRequestPermission, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Grant Permission")
                    }
                }
            } else {
                when (state) {
                    is VideoUiState.Loading -> {
                        CircularProgressIndicator()
                    }
                    is VideoUiState.Empty -> {
                        Text("No videos found on device")
                    }
                    is VideoUiState.Error -> {
                        Text("Error: ${(state as VideoUiState.Error).message}")
                    }
                    is VideoUiState.Success -> {
                        val videos = (state as VideoUiState.Success).videos
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(videos) { video ->
                                VideoListItem(video = video, onClick = { onVideoClick(video) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VideoListItem(video: VideoItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatDuration(video.duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

@Composable
fun VideoPlayerScreen(
    video: VideoItem,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // Remember ExoPlayer instance
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(video.uri)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    // Remember MediaSession
    val mediaSession = remember(exoPlayer) {
        MediaSession.Builder(context, exoPlayer).build()
    }

    DisposableEffect(exoPlayer, mediaSession) {
        onDispose {
            mediaSession.release()
            exoPlayer.release()
        }
    }

    // State management
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableStateOf(0L) }
    var totalDuration by remember { mutableStateOf(0L) }

    var controlsVisible by remember { mutableStateOf(true) }
    var controlsVisibilityTrigger by remember { mutableStateOf(0) }

    var showForwardIndicator by remember { mutableStateOf(false) }
    var showBackwardIndicator by remember { mutableStateOf(false) }

    // Observe player states
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingChanged: Boolean) {
                isPlaying = isPlayingChanged
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    totalDuration = exoPlayer.duration.coerceAtLeast(0L)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // Periodically update currentPosition
    LaunchedEffect(exoPlayer, isPlaying) {
        if (isPlaying) {
            while (true) {
                currentPosition = exoPlayer.currentPosition
                delay(200)
            }
        }
    }

    // Auto-fade controls after 3 seconds
    LaunchedEffect(controlsVisible, controlsVisibilityTrigger) {
        if (controlsVisible) {
            delay(3000)
            controlsVisible = false
        }
    }

    // Reset double-tap indicators
    LaunchedEffect(showForwardIndicator) {
        if (showForwardIndicator) {
            delay(600)
            showForwardIndicator = false
        }
    }

    LaunchedEffect(showBackwardIndicator) {
        if (showBackwardIndicator) {
            delay(600)
            showBackwardIndicator = false
        }
    }

    // Check local PiP mode state
    val mainActivity = context as? MainActivity
    val isInPip = mainActivity?.isPipMode?.value ?: false

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Media3 PlayerView wrapped in AndroidView
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false // disable default controls
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { playerView ->
                playerView.player = exoPlayer
            },
            modifier = Modifier.fillMaxSize()
        )

        // Gestures box (Always active on top of player)
        if (!isInPip) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                val isRight = offset.x > size.width / 2
                                if (isRight) {
                                    showForwardIndicator = true
                                    exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(exoPlayer.duration))
                                } else {
                                    showBackwardIndicator = true
                                    exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0L))
                                }
                                controlsVisibilityTrigger++ // Reset fade-out timer on double tap
                            },
                            onTap = {
                                controlsVisible = !controlsVisible
                                controlsVisibilityTrigger++
                            }
                        )
                    }
            )

            // Double-tap visual indicators
            if (showBackwardIndicator) {
                SeekIndicator(isForward = false, modifier = Modifier.align(Alignment.CenterStart))
            }
            if (showForwardIndicator) {
                SeekIndicator(isForward = true, modifier = Modifier.align(Alignment.CenterEnd))
            }

            // Back button (top left)
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Video title (top center)
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Text(
                    text = video.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp, start = 64.dp, end = 64.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Custom Control Overlay (Bottom controls and Play/Pause center overlay)
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Play/Pause button in center
                    IconButton(
                        onClick = {
                            if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                            controlsVisibilityTrigger++
                        },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(72.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.5f),
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isPlaying) {
                                Icons.Default.Pause
                            } else {
                                Icons.Default.PlayArrow
                            },
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    // Progress bar & Time label at bottom
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.8f)
                                    )
                                )
                            )
                            .padding(16.dp)
                    ) {
                        var isScrubbing by remember { mutableStateOf(false) }
                        var scrubPosition by remember { mutableStateOf(0L) }

                        val displayPosition = if (isScrubbing) scrubPosition else currentPosition
                        val sliderValue = if (totalDuration > 0) displayPosition.toFloat() / totalDuration else 0f

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatDuration(displayPosition),
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall
                            )

                            Slider(
                                value = sliderValue,
                                onValueChange = { value ->
                                    isScrubbing = true
                                    scrubPosition = (value * totalDuration).toLong()
                                    controlsVisibilityTrigger++
                                },
                                onValueChangeFinished = {
                                    isScrubbing = false
                                    exoPlayer.seekTo(scrubPosition)
                                    controlsVisibilityTrigger++
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color.Gray
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            )

                            Text(
                                text = formatDuration(totalDuration),
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SeekIndicator(isForward: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth(0.25f)
            .background(
                brush = Brush.horizontalGradient(
                    colors = if (isForward) {
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.2f))
                    } else {
                        listOf(Color.White.copy(alpha = 0.2f), Color.Transparent)
                    }
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (isForward) "▶▶" else "◀◀",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isForward) "+10s" else "-10s",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
