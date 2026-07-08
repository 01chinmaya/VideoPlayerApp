package com.example.videoplayerapp

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.DefaultRenderersFactory
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
import kotlinx.coroutines.withContext

// ==========================================
// 1. Data Layer: Models & Repository
// ==========================================

data class VideoItem(
    val id: Long,
    val title: String,
    val uri: Uri,
    val duration: Long,
    val size: Long,
    val subtitleUris: List<Uri> = emptyList()
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

        try {
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
                    val subtitles = findSubtitlesForVideo(context, name)
                    videoList.add(VideoItem(id, name, contentUri, duration, size, subtitles))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        emit(videoList)
    }

    private fun findSubtitlesForVideo(context: Context, videoName: String): List<Uri> {
        val subtitleUris = mutableListOf<Uri>()
        val baseName = videoName.substringBeforeLast(".")
        if (baseName.isEmpty()) return emptyList()

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME
        )
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? AND (${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.srt' OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.vtt')"
        val selectionArgs = arrayOf("$baseName%")

        val queryUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        try {
            context.contentResolver.query(
                queryUri,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: continue
                    val fileBaseName = name.substringBeforeLast(".")
                    if (fileBaseName.equals(baseName, ignoreCase = true)) {
                        val uri = ContentUris.withAppendedId(queryUri, id)
                        subtitleUris.add(uri)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return subtitleUris
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

// Session resume helpers
fun savePlaybackPosition(context: Context, videoUri: String, position: Long) {
    val prefs = context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)
    prefs.edit().putLong(videoUri, position).apply()
}

fun getPlaybackPosition(context: Context, videoUri: String): Long {
    val prefs = context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)
    return prefs.getLong(videoUri, 0L)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoListScreen(
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    viewModel: VideoViewModel,
    onVideoClick: (VideoItem) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Universal Player", fontWeight = FontWeight.Bold) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Glassmorphism Search Bar
            if (permissionGranted) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(14.dp)
                        )
                ) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search local video library...") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true
                    )
                }
            }

            Box(
                modifier = Modifier.weight(1f),
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
                            val filteredVideos = remember(videos, searchQuery) {
                                videos.filter { it.title.contains(searchQuery, ignoreCase = true) }
                            }

                            if (filteredVideos.isEmpty()) {
                                Text("No videos match your search")
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(filteredVideos) { video ->
                                        VideoListItem(video = video, onClick = { onVideoClick(video) })
                                    }
                                }
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
    val context = LocalContext.current
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(video.uri) {
        withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    thumbnail = context.contentResolver.loadThumbnail(
                        video.uri,
                        android.util.Size(200, 150),
                        null
                    )
                } else {
                    val cursor = context.contentResolver.query(
                        MediaStore.Video.Thumbnails.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Video.Thumbnails.DATA),
                        "${MediaStore.Video.Thumbnails.VIDEO_ID} = ?",
                        arrayOf(video.id.toString()),
                        null
                    )
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val path = it.getString(it.getColumnIndexOrThrow(MediaStore.Video.Thumbnails.DATA))
                            if (path != null) {
                                thumbnail = BitmapFactory.decodeFile(path)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // High-fidelity video thumbnail preview
            Box(
                modifier = Modifier
                    .size(100.dp, 75.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = "Video Thumbnail",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Placeholder",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatDuration(video.duration),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        text = formatFileSize(video.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
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

// Format timeline seek offsets
fun formatOffset(ms: Long): String {
    val absMs = Math.abs(ms)
    val totalSecs = absMs / 1000
    val s = totalSecs % 60
    val m = totalSecs / 60
    val sign = if (ms >= 0) "+" else "-"
    return String.format("%s%d:%02d", sign, m, s)
}

// Drag gesture types
enum class DragType {
    NONE, HORIZONTAL, VERTICAL_BRIGHTNESS, VERTICAL_VOLUME
}

// Track selection information
data class TrackInfo(
    val groupIndex: Int,
    val trackIndex: Int,
    val name: String,
    val isSelected: Boolean,
    val type: Int
)

@Composable
fun VideoPlayerScreen(
    video: VideoItem,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // Resume position setup
    val initialPosition = remember(video.uri) {
        getPlaybackPosition(context, video.uri.toString())
    }

    // Remember ExoPlayer instance with custom DefaultRenderersFactory preferring extension decoders
    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }
        ExoPlayer.Builder(context, renderersFactory).build().apply {
            val mediaItemBuilder = MediaItem.Builder().setUri(video.uri)
            if (video.subtitleUris.isNotEmpty()) {
                val subtitleConfigs = video.subtitleUris.map { subUri ->
                    val mimeType = if (subUri.toString().endsWith(".vtt", ignoreCase = true)) {
                        MimeTypes.TEXT_VTT
                    } else {
                        MimeTypes.APPLICATION_SUBRIP
                    }
                    MediaItem.SubtitleConfiguration.Builder(subUri)
                        .setMimeType(mimeType)
                        .setLanguage("en")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                }
                mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
            }
            val mediaItem = mediaItemBuilder.build()
            setMediaItem(mediaItem)
            prepare()
            if (initialPosition > 0L) {
                seekTo(initialPosition)
            }
            playWhenReady = true
        }
    }

    // Remember MediaSession
    val mediaSession = remember(exoPlayer) {
        MediaSession.Builder(context, exoPlayer).build()
    }

    // Save position periodically and on dispose
    DisposableEffect(exoPlayer, mediaSession) {
        onDispose {
            val pos = exoPlayer.currentPosition
            savePlaybackPosition(context, video.uri.toString(), pos)
            mediaSession.release()
            exoPlayer.release()
        }
    }

    // Orientation lock and status bar hide
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        
        // Lock screen to Landscape
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        
        // Hide status and navigation bars
        controller?.let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onDispose {
            // Restore orientation
            activity?.requestedOrientation = originalOrientation
            // Restore status and navigation bars
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // State management for custom overlays (title, back button, double-tap indicators)
    var controlsVisible by remember { mutableStateOf(true) }
    var showForwardIndicator by remember { mutableStateOf(false) }
    var showBackwardIndicator by remember { mutableStateOf(false) }

    // Gesture control states
    var gestureVolume by remember { mutableStateOf(-1f) }
    var gestureBrightness by remember { mutableStateOf(-1f) }
    var scrubTimeOffset by remember { mutableStateOf<Long?>(null) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

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

    // Update position and check playback complete
    LaunchedEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    savePlaybackPosition(context, video.uri.toString(), 0L)
                }
            }
        }
        exoPlayer.addListener(listener)
        
        while (true) {
            if (exoPlayer.isPlaying) {
                val pos = exoPlayer.currentPosition
                savePlaybackPosition(context, video.uri.toString(), pos)
            }
            delay(1000)
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
        var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

        // Media3 PlayerView wrapped in AndroidView
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true // enable default controls
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        controlsVisible = (visibility == View.VISIBLE)
                    })
                }
            },
            update = { playerView ->
                playerView.player = exoPlayer
                playerViewRef = playerView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Custom Track/Speed selector menu state
        var showSettingsMenu by remember { mutableStateOf(false) }
        var currentSpeed by remember { mutableStateOf(1.0f) }

        // Gestures box (Always active on top of player)
        if (!isInPip) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        var dragType = DragType.NONE
                        var initialDragX = 0f
                        var initialDragY = 0f
                        var totalDragX = 0f
                        var totalDragY = 0f
                        
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragType = DragType.NONE
                                initialDragX = offset.x
                                initialDragY = offset.y
                                totalDragX = 0f
                                totalDragY = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                totalDragX += dragAmount.x
                                totalDragY += dragAmount.y
                                
                                if (dragType == DragType.NONE) {
                                    if (Math.abs(totalDragX) > Math.abs(totalDragY) && Math.abs(totalDragX) > 25) {
                                        dragType = DragType.HORIZONTAL
                                    } else if (Math.abs(totalDragY) > Math.abs(totalDragX) && Math.abs(totalDragY) > 25) {
                                        if (initialDragX > size.width / 2) {
                                            dragType = DragType.VERTICAL_VOLUME
                                        } else {
                                            dragType = DragType.VERTICAL_BRIGHTNESS
                                        }
                                    }
                                }
                                
                                when (dragType) {
                                    DragType.HORIZONTAL -> {
                                        // Seek scrubbing preview
                                        val factor = 1000L // 1 sec per pixel
                                        val duration = exoPlayer.duration.coerceAtLeast(0L)
                                        val offsetMs = (totalDragX * factor).toLong()
                                        val targetPosition = (exoPlayer.currentPosition + offsetMs).coerceIn(0L, duration)
                                        scrubTimeOffset = offsetMs
                                    }
                                    DragType.VERTICAL_VOLUME -> {
                                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                        val currentVal = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                        // Vertical swipe scale
                                        val delta = -dragAmount.y / size.height
                                        val step = (delta * maxVolume * 2).toInt()
                                        val newVal = (currentVal + step).coerceIn(0, maxVolume)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVal, 0)
                                        gestureVolume = newVal.toFloat() / maxVolume
                                    }
                                    DragType.VERTICAL_BRIGHTNESS -> {
                                        val activity = context as? Activity
                                        activity?.window?.attributes?.let { lp ->
                                            val currentBrightness = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
                                            val delta = -dragAmount.y / size.height
                                            val newBrightness = (currentBrightness + delta).coerceIn(0f, 1f)
                                            lp.screenBrightness = newBrightness
                                            activity.window.attributes = lp
                                            gestureBrightness = newBrightness
                                        }
                                    }
                                    else -> {}
                                }
                            },
                            onDragEnd = {
                                if (dragType == DragType.HORIZONTAL && scrubTimeOffset != null) {
                                    val duration = exoPlayer.duration.coerceAtLeast(0L)
                                    val target = (exoPlayer.currentPosition + scrubTimeOffset!!).coerceIn(0L, duration)
                                    exoPlayer.seekTo(target)
                                    scrubTimeOffset = null
                                }
                                gestureVolume = -1f
                                gestureBrightness = -1f
                                dragType = DragType.NONE
                            },
                            onDragCancel = {
                                scrubTimeOffset = null
                                gestureVolume = -1f
                                gestureBrightness = -1f
                                dragType = DragType.NONE
                            }
                        )
                    }
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
                            },
                            onTap = {
                                playerViewRef?.let {
                                    if (it.isControllerFullyVisible) {
                                        it.hideController()
                                    } else {
                                        it.showController()
                                    }
                                }
                            }
                        )
                    }
            )

            // Left Brightness Overlay Bar
            if (gestureBrightness >= 0f) {
                VerticalProgressBar(
                    value = gestureBrightness,
                    icon = { Text("☀️", fontSize = 20.sp) },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 32.dp)
                )
            }

            // Right Volume Overlay Bar
            if (gestureVolume >= 0f) {
                VerticalProgressBar(
                    value = gestureVolume,
                    icon = { Text("🔊", fontSize = 20.sp) },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 32.dp)
                )
            }

            // Timeline scrubbing preview pop-up
            if (scrubTimeOffset != null) {
                val duration = exoPlayer.duration.coerceAtLeast(0L)
                val target = (exoPlayer.currentPosition + scrubTimeOffset!!).coerceIn(0L, duration)
                ScrubPopup(
                    offset = scrubTimeOffset!!,
                    targetTime = target,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

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

            // Settings/Options gear button (top right)
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Box(modifier = Modifier.padding(16.dp)) {
                    IconButton(onClick = { showSettingsMenu = !showSettingsMenu }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Compose audio & subtitle streams Selector Dropdown Menu
                    DropdownMenu(
                        expanded = showSettingsMenu,
                        onDismissRequest = { showSettingsMenu = false },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.9f))
                    ) {
                        val tracks = exoPlayer.currentTracks
                        val audioTracks = mutableListOf<TrackInfo>()
                        val subtitleTracks = mutableListOf<TrackInfo>()

                        tracks.groups.forEachIndexed { groupIndex, group ->
                            val trackType = group.type
                            if (trackType == C.TRACK_TYPE_AUDIO || trackType == C.TRACK_TYPE_TEXT) {
                                for (trackIndex in 0 until group.length) {
                                    val format = group.getTrackFormat(trackIndex)
                                    val isSelected = group.isTrackSelected(trackIndex)
                                    val language = format.language ?: "und"
                                    val label = format.label ?: ""
                                    
                                    val trackName = if (label.isNotEmpty()) {
                                        "$label ($language)"
                                    } else {
                                        val prefix = if (trackType == C.TRACK_TYPE_AUDIO) "Audio" else "Sub"
                                        "$prefix #${trackIndex + 1} ($language)"
                                    }
                                    
                                    val info = TrackInfo(groupIndex, trackIndex, trackName, isSelected, trackType)
                                    if (trackType == C.TRACK_TYPE_AUDIO) {
                                        audioTracks.add(info)
                                    } else {
                                        subtitleTracks.add(info)
                                    }
                                }
                            }
                        }

                        // Audio Track Header & List
                        Text(
                            text = "Audio Language",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        audioTracks.forEach { track ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (track.isSelected) "✓ ${track.name}" else "   ${track.name}",
                                        color = Color.White,
                                        fontSize = 14.sp
                                    )
                                },
                                onClick = {
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                        .buildUpon()
                                        .setOverrideForType(
                                            TrackSelectionOverride(tracks.groups[track.groupIndex].mediaTrackGroup, track.trackIndex)
                                        )
                                        .build()
                                    showSettingsMenu = false
                                }
                            )
                        }

                        Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.Gray.copy(alpha = 0.3f)))

                        // Subtitle Track Header & List
                        Text(
                            text = "Subtitle Streams",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        DropdownMenuItem(
                            text = {
                                val isOff = !subtitleTracks.any { it.isSelected }
                                Text(
                                    text = if (isOff) "✓ Off" else "   Off",
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                            },
                            onClick = {
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                    .build()
                                showSettingsMenu = false
                            }
                        )
                        subtitleTracks.forEach { track ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (track.isSelected) "✓ ${track.name}" else "   ${track.name}",
                                        color = Color.White,
                                        fontSize = 14.sp
                                    )
                                },
                                onClick = {
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                        .buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                        .setOverrideForType(
                                            TrackSelectionOverride(tracks.groups[track.groupIndex].mediaTrackGroup, track.trackIndex)
                                        )
                                        .build()
                                    showSettingsMenu = false
                                }
                            )
                        }

                        Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.Gray.copy(alpha = 0.3f)))

                        // Playback Speed Velocity Modifier
                        Text(
                            text = "Playback Speed",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        listOf(0.5f, 1.0f, 1.5f, 2.0f, 2.5f).forEach { speed ->
                            val isSelected = currentSpeed == speed
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (isSelected) "✓ ${speed}x" else "   ${speed}x",
                                        color = Color.White,
                                        fontSize = 14.sp
                                    )
                                },
                                onClick = {
                                    currentSpeed = speed
                                    exoPlayer.playbackParameters = exoPlayer.playbackParameters.withSpeed(speed)
                                    showSettingsMenu = false
                                }
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
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.15f))
                    } else {
                        listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)
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

@Composable
fun VerticalProgressBar(
    value: Float,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(48.dp)
            .fillMaxHeight(0.6f)
            .background(Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(14.dp))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        icon()
        
        Box(
            modifier = Modifier
                .width(6.dp)
                .weight(1f)
                .padding(vertical = 10.dp)
                .background(Color.White.copy(alpha = 0.3f), shape = CircleShape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(value.coerceIn(0f, 1f))
                    .align(Alignment.BottomCenter)
                    .background(Color.White, shape = CircleShape)
            )
        }
        
        Text(
            text = "${(value * 100).toInt()}%",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun ScrubPopup(
    offset: Long,
    targetTime: Long,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = formatOffset(offset),
                color = if (offset >= 0) Color.Green else Color.Red,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatDuration(targetTime),
                color = Color.White,
                fontSize = 16.sp
            )
        }
    }
}
