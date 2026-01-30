package com.example.ndireceiver.ui.playback

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Playback state for UI updates.
 */
sealed class PlaybackState {
    object Idle : PlaybackState()
    object Loading : PlaybackState()
    object Playing : PlaybackState()
    object Paused : PlaybackState()
    object Ended : PlaybackState()
    data class Error(val message: String) : PlaybackState()
}

/**
 * Video metadata for display.
 */
data class VideoMetadata(
    val fileName: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val codec: String = ""
) {
    fun getFormattedInfo(): String {
        val resolution = if (width > 0 && height > 0) "${width}x${height}" else ""
        val codecInfo = codec.takeIf { it.isNotEmpty() } ?: ""

        return listOf(resolution, codecInfo)
            .filter { it.isNotEmpty() }
            .joinToString(" | ")
    }
}

/**
 * UI state for the playback screen.
 */
data class PlaybackUiState(
    val playbackState: PlaybackState = PlaybackState.Idle,
    val metadata: VideoMetadata = VideoMetadata(),
    val showControls: Boolean = true,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val isPlaying: Boolean = false
)

/**
 * ViewModel for video playback using ExoPlayer.
 *
 * Manages:
 * - ExoPlayer lifecycle
 * - Playback state
 * - Video metadata extraction
 * - Play/Pause/Seek controls
 */
class PlaybackViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "PlaybackViewModel"
        private const val CONTROLS_HIDE_DELAY_MS = 5000L
    }

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private var player: ExoPlayer? = null
    private var currentFilePath: String? = null

    // Mutex for synchronizing player state transitions (Issue #2 fix)
    private val playerMutex = Mutex()

    // Flag to track if Fragment has released the player (Issue #1 fix)
    @Volatile
    private var isPlayerReleasedByFragment = false

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val state = when (playbackState) {
                Player.STATE_IDLE -> PlaybackState.Idle
                Player.STATE_BUFFERING -> PlaybackState.Loading
                Player.STATE_READY -> if (player?.isPlaying == true) PlaybackState.Playing else PlaybackState.Paused
                Player.STATE_ENDED -> PlaybackState.Ended
                else -> PlaybackState.Idle
            }

            _uiState.value = _uiState.value.copy(
                playbackState = state,
                isPlaying = player?.isPlaying == true,
                duration = player?.duration ?: 0
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val state = if (isPlaying) PlaybackState.Playing else PlaybackState.Paused
            _uiState.value = _uiState.value.copy(
                playbackState = state,
                isPlaying = isPlaying
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.message}", error)
            _uiState.value = _uiState.value.copy(
                playbackState = PlaybackState.Error(error.message ?: "Playback error")
            )
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            _uiState.value = _uiState.value.copy(
                currentPosition = newPosition.positionMs
            )
        }
    }

    /**
     * Get the ExoPlayer instance.
     * Creates a new player if one doesn't exist.
     *
     * Note: Player lifecycle is managed by the Fragment (Issue #1 fix).
     * Call releasePlayerFromFragment() when Fragment is destroyed.
     */
    fun getPlayer(): ExoPlayer {
        // Reset the flag when Fragment requests player (new Fragment attached)
        isPlayerReleasedByFragment = false
        return player ?: createPlayer()
    }

    private fun createPlayer(): ExoPlayer {
        val context = getApplication<Application>().applicationContext
        val newPlayer = ExoPlayer.Builder(context)
            .build()
            .apply {
                addListener(playerListener)
            }
        player = newPlayer
        return newPlayer
    }

    /**
     * Release the player when Fragment is destroyed (Issue #1 fix).
     * This ensures the player doesn't outlive the Fragment's view.
     */
    fun releasePlayerFromFragment() {
        isPlayerReleasedByFragment = true
        releasePlayer()
    }

    /**
     * Load a video file for playback.
     *
     * Uses mutex to prevent race conditions with other player operations (Issue #2 fix).
     *
     * @param filePath Absolute path to the video file
     */
    fun loadVideo(filePath: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(playbackState = PlaybackState.Loading)

            currentFilePath = filePath

            // Extract metadata in background
            val metadata = extractMetadata(filePath)
            _uiState.value = _uiState.value.copy(metadata = metadata)

            // Load video into player
            val file = File(filePath)
            if (!file.exists()) {
                _uiState.value = _uiState.value.copy(
                    playbackState = PlaybackState.Error("File not found")
                )
                return@launch
            }

            // Synchronize player access (Issue #2 fix)
            playerMutex.withLock {
                // Check if player was released by Fragment
                if (isPlayerReleasedByFragment) {
                    Log.w(TAG, "Player was released by Fragment, skipping loadVideo")
                    return@launch
                }

                try {
                    val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                    getPlayer().apply {
                        setMediaItem(mediaItem)
                        prepare()
                        play()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading video", e)
                    _uiState.value = _uiState.value.copy(
                        playbackState = PlaybackState.Error("Failed to load video: ${e.message}")
                    )
                }
            }
        }
    }

    /**
     * Extract metadata from the video file.
     */
    private suspend fun extractMetadata(filePath: String): VideoMetadata = withContext(Dispatchers.IO) {
        val file = File(filePath)
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(filePath)

            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""

            val codec = when {
                mimeType.contains("hevc", ignoreCase = true) || mimeType.contains("h265", ignoreCase = true) -> "H.265"
                mimeType.contains("avc", ignoreCase = true) || mimeType.contains("h264", ignoreCase = true) -> "H.264"
                mimeType.contains("video") -> mimeType.substringAfter("video/").uppercase()
                else -> ""
            }

            VideoMetadata(
                fileName = file.name,
                width = width,
                height = height,
                durationMs = durationMs,
                codec = codec
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting metadata", e)
            VideoMetadata(fileName = file.name)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    /**
     * Play or pause the video.
     *
     * Uses mutex to prevent race conditions with loadVideo (Issue #2 fix).
     */
    fun togglePlayPause() {
        viewModelScope.launch {
            playerMutex.withLock {
                if (isPlayerReleasedByFragment) return@launch

                player?.let {
                    if (it.isPlaying) {
                        it.pause()
                    } else {
                        // If ended, restart from beginning
                        if (it.playbackState == Player.STATE_ENDED) {
                            it.seekTo(0)
                        }
                        it.play()
                    }
                }
            }
        }
    }

    /**
     * Play the video.
     */
    fun play() {
        viewModelScope.launch {
            playerMutex.withLock {
                if (isPlayerReleasedByFragment) return@launch
                player?.play()
            }
        }
    }

    /**
     * Pause the video.
     */
    fun pause() {
        // Pause can be called synchronously (e.g., onStop)
        // Use tryLock to avoid blocking if mutex is held
        val currentPlayer = player
        if (currentPlayer != null && !isPlayerReleasedByFragment) {
            currentPlayer.pause()
        }
    }

    /**
     * Stop the video and reset position.
     */
    fun stop() {
        viewModelScope.launch {
            playerMutex.withLock {
                if (isPlayerReleasedByFragment) return@launch
                player?.apply {
                    stop()
                    seekTo(0)
                }
                _uiState.value = _uiState.value.copy(
                    playbackState = PlaybackState.Idle,
                    isPlaying = false,
                    currentPosition = 0
                )
            }
        }
    }

    /**
     * Seek to a specific position.
     *
     * @param positionMs Position in milliseconds
     */
    fun seekTo(positionMs: Long) {
        viewModelScope.launch {
            playerMutex.withLock {
                if (isPlayerReleasedByFragment) return@launch
                player?.seekTo(positionMs)
                _uiState.value = _uiState.value.copy(currentPosition = positionMs)
            }
        }
    }

    /**
     * Seek forward by the specified amount.
     *
     * @param ms Milliseconds to seek forward (default: 10 seconds)
     */
    fun seekForward(ms: Long = 10_000) {
        viewModelScope.launch {
            playerMutex.withLock {
                if (isPlayerReleasedByFragment) return@launch
                player?.let {
                    val newPosition = (it.currentPosition + ms).coerceAtMost(it.duration)
                    it.seekTo(newPosition)
                }
            }
        }
    }

    /**
     * Seek backward by the specified amount.
     *
     * @param ms Milliseconds to seek backward (default: 10 seconds)
     */
    fun seekBackward(ms: Long = 10_000) {
        viewModelScope.launch {
            playerMutex.withLock {
                if (isPlayerReleasedByFragment) return@launch
                player?.let {
                    val newPosition = (it.currentPosition - ms).coerceAtLeast(0)
                    it.seekTo(newPosition)
                }
            }
        }
    }

    /**
     * Toggle controls visibility.
     */
    fun toggleControls() {
        _uiState.value = _uiState.value.copy(
            showControls = !_uiState.value.showControls
        )
    }

    /**
     * Show controls.
     */
    fun showControls() {
        _uiState.value = _uiState.value.copy(showControls = true)
    }

    /**
     * Hide controls.
     */
    fun hideControls() {
        _uiState.value = _uiState.value.copy(showControls = false)
    }

    /**
     * Retry loading the current video.
     */
    fun retry() {
        currentFilePath?.let { loadVideo(it) }
    }

    /**
     * Update the current position (for UI updates).
     *
     * This is called from Fragment's handler, so we check release state (Issue #1 fix).
     */
    fun updatePosition() {
        if (isPlayerReleasedByFragment) return
        player?.let {
            _uiState.value = _uiState.value.copy(
                currentPosition = it.currentPosition,
                duration = it.duration
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.apply {
            removeListener(playerListener)
            release()
        }
        player = null
    }
}
