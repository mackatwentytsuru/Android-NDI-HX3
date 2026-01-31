package com.example.ndireceiver.ui.player

import android.app.Application
import android.content.Context
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ndireceiver.data.SettingsRepository
import com.example.ndireceiver.media.UncompressedVideoRenderer
import com.example.ndireceiver.media.VideoDecoder
import com.example.ndireceiver.media.VideoRecorder
import com.example.ndireceiver.ndi.ConnectionState
import com.example.ndireceiver.ndi.FourCC
import com.example.ndireceiver.ndi.NdiFrameCallback
import com.example.ndireceiver.ndi.NdiReceiver
import com.example.ndireceiver.ndi.NdiSource
import com.example.ndireceiver.ndi.VideoFrameData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer

/**
 * Recording state for UI updates.
 */
sealed class RecordingState {
    object Idle : RecordingState()
    data class Recording(val durationMs: Long) : RecordingState()
    data class Stopped(val file: File?) : RecordingState()
    data class Error(val message: String) : RecordingState()
}

/**
 * UI state for the player screen.
 */
data class PlayerUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val recordingState: RecordingState = RecordingState.Idle,
    val showControls: Boolean = true,
    val showOsd: Boolean = true,
    val videoInfo: String = "",
    val bitrateInfo: String = "",
    val retryCount: Int = 0,
    val isAutoReconnecting: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0
)

/**
 * ViewModel for the player screen - handles NDI stream connection, video decoding, and recording.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application), NdiFrameCallback {

    private val receiver = NdiReceiver()
    private var decoder: VideoDecoder? = null
    private var uncompressedRenderer: UncompressedVideoRenderer? = null
    private var recorder: VideoRecorder? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var isDisconnecting = false
    private var currentSource: NdiSource? = null

    private val settingsRepository = SettingsRepository.getInstance(application)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // Thread-safe flags for decoder state
    @Volatile private var decoderInitialized = false
    private val decoderLock = Any()

    @Volatile private var lastReceivedFrameWasCompressed = false

    @Volatile private var lastInfoWidth = 0
    @Volatile private var lastInfoHeight = 0
    @Volatile private var lastInfoFourCC: FourCC = FourCC.UNKNOWN
    @Volatile private var lastInfoIsCompressed = false

    // Recording state
    @Volatile private var isRecordingEnabled = false
    @Volatile private var currentVideoWidth = 0
    @Volatile private var currentVideoHeight = 0
    @Volatile private var currentIsHevc = false

    // Auto-reconnect state
    private var autoReconnectAttempts = 0
    private val maxAutoReconnectAttempts = 5
    private val autoReconnectDelayMs = 3000L
    private var autoReconnectJob: Job? = null

    // Bitrate tracking
    private var lastBitrateUpdateTime = 0L
    private var bytesReceivedSinceLastUpdate = 0L
    private var currentBitrateKbps = 0.0

    init {
        receiver.setFrameCallback(this)

        // Load initial OSD setting
        _uiState.value = _uiState.value.copy(showOsd = settingsRepository.isOsdEnabled())

        // Observe connection state from receiver
        viewModelScope.launch {
            receiver.connectionState.collect { state ->
                _uiState.value = _uiState.value.copy(connectionState = state)

                // Stop recording if connection is lost
                if (state is ConnectionState.Error || state is ConnectionState.Disconnected) {
                    if (isRecordingEnabled) {
                        stopRecordingInternal()
                    }

                    // Attempt auto-reconnect if enabled
                    if (state is ConnectionState.Error && settingsRepository.isAutoReconnectEnabled()) {
                        attemptAutoReconnect()
                    }
                }

                // Reset auto-reconnect attempts on successful connection
                if (state is ConnectionState.Connected) {
                    autoReconnectAttempts = 0
                    _uiState.value = _uiState.value.copy(isAutoReconnecting = false, retryCount = 0)

                    // Save last connected source
                    currentSource?.let { source ->
                        settingsRepository.setLastConnectedSource(source.name, source.url)
                    }
                }
            }
        }
    }

    /**
     * Attempt auto-reconnect on connection loss.
     */
    private fun attemptAutoReconnect() {
        if (autoReconnectAttempts >= maxAutoReconnectAttempts) {
            _uiState.value = _uiState.value.copy(
                isAutoReconnecting = false,
                retryCount = autoReconnectAttempts
            )
            return
        }

        autoReconnectAttempts++
        _uiState.value = _uiState.value.copy(
            isAutoReconnecting = true,
            retryCount = autoReconnectAttempts
        )

        // Cancel any existing auto-reconnect job before starting a new one
        autoReconnectJob?.cancel()
        autoReconnectJob = viewModelScope.launch {
            delay(autoReconnectDelayMs)
            currentSource?.let { source ->
                receiver.connect(source)
            }
        }
    }

    /**
     * Initialize the recorder with the app's external files directory.
     */
    fun initializeRecorder(context: Context) {
        if (recorder == null) {
            val recordingsDir = File(context.getExternalFilesDir(null), "recordings")
            recorder = VideoRecorder(recordingsDir)
        }
    }

    /**
     * Set the surface for video rendering.
     */
    fun setSurface(surface: Surface?) {
        this.surface = surface

        if (surface != null) {
            if (uncompressedRenderer == null) {
                uncompressedRenderer = UncompressedVideoRenderer()
            }
            uncompressedRenderer?.setSurface(surface)

            if (decoder == null) {
                decoder = VideoDecoder()
            }
        } else {
            releaseRenderer()
            releaseDecoder()
        }
    }

    /**
     * Connect to an NDI source.
     */
    fun connect(source: NdiSource) {
        currentSource = source

        viewModelScope.launch {
            receiver.connect(source)
        }
    }

    /**
     * Disconnect from the current NDI source.
     */
    fun disconnect() {
        if (isDisconnecting) return
        isDisconnecting = true

        viewModelScope.launch {
            // Stop recording first
            if (isRecordingEnabled) {
                stopRecordingInternal()
            }

            receiver.disconnect()
            releaseDecoder()
            isDisconnecting = false
        }
    }

    /**
     * Retry connection to the last source.
     */
    fun retry() {
        currentSource?.let { connect(it) }
    }

    /**
     * Toggle control overlay visibility.
     */
    fun toggleControls() {
        _uiState.value = _uiState.value.copy(
            showControls = !_uiState.value.showControls
        )
    }

    /**
     * Show control overlay.
     */
    fun showControls() {
        _uiState.value = _uiState.value.copy(showControls = true)
    }

    /**
     * Hide control overlay.
     */
    fun hideControls() {
        _uiState.value = _uiState.value.copy(showControls = false)
    }

    /**
     * Toggle OSD visibility.
     */
    fun toggleOsd() {
        val newOsdState = !_uiState.value.showOsd
        _uiState.value = _uiState.value.copy(showOsd = newOsdState)
        settingsRepository.setShowOsd(newOsdState)
    }

    /**
     * Set OSD visibility.
     */
    fun setOsdVisible(visible: Boolean) {
        _uiState.value = _uiState.value.copy(showOsd = visible)
    }

    /**
     * Check if screen should stay on (based on settings).
     */
    fun isScreenAlwaysOnEnabled(): Boolean {
        return settingsRepository.isScreenAlwaysOnEnabled()
    }

    /**
     * Cancel auto-reconnect attempts.
     */
    fun cancelAutoReconnect() {
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        autoReconnectAttempts = maxAutoReconnectAttempts
        _uiState.value = _uiState.value.copy(isAutoReconnecting = false)
    }

    // ========== Recording Methods ==========

    /**
     * Check if recording is currently active.
     */
    fun isRecording(): Boolean = isRecordingEnabled

    /**
     * Start recording the NDI stream.
     */
    fun startRecording(): Boolean {
        if (isRecordingEnabled) return false

        if (currentVideoWidth == 0 || currentVideoHeight == 0) {
            _uiState.value = _uiState.value.copy(
                recordingState = RecordingState.Error("No video stream to record")
            )
            return false
        }

        val rec = recorder ?: run {
            _uiState.value = _uiState.value.copy(
                recordingState = RecordingState.Error("Recorder not initialized")
            )
            return false
        }

        try {
            if (lastReceivedFrameWasCompressed) {
                rec.startRecording(currentVideoWidth, currentVideoHeight, currentIsHevc)
            } else {
                // It's an uncompressed format that we can encode
                if (lastInfoFourCC == FourCC.UYVY || lastInfoFourCC == FourCC.BGRA || lastInfoFourCC == FourCC.BGRX) {
                    rec.startRecording(currentVideoWidth, currentVideoHeight, lastInfoFourCC)
                } else {
                    _uiState.value = _uiState.value.copy(
                        recordingState = RecordingState.Error("Unsupported format for recording: ${lastInfoFourCC.name}")
                    )
                    return false
                }
            }

            isRecordingEnabled = true
            viewModelScope.launch {
                while (isActive && isRecordingEnabled) {
                    val duration = rec.getRecordingDurationMs()
                    _uiState.value = _uiState.value.copy(
                        recordingState = RecordingState.Recording(duration)
                    )
                    delay(1000)
                }
            }
            return true
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                recordingState = RecordingState.Error("Failed to start recording: ${e.message}")
            )
            return false
        }
    }


    /**
     * Stop recording.
     */
    fun stopRecording() {
        stopRecordingInternal()
    }

    /**
     * Internal stop recording implementation.
     */
    private fun stopRecordingInternal() {
        if (!isRecordingEnabled) return

        isRecordingEnabled = false

        val file = recorder?.stopRecording()
        _uiState.value = _uiState.value.copy(
            recordingState = RecordingState.Stopped(file)
        )

        // Reset to idle after a short delay
        viewModelScope.launch {
            delay(3000)
            if (_uiState.value.recordingState is RecordingState.Stopped) {
                _uiState.value = _uiState.value.copy(
                    recordingState = RecordingState.Idle
                )
            }
        }
    }

    /**
     * Toggle recording on/off.
     */
    fun toggleRecording() {
        if (isRecordingEnabled) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    // ========== NdiFrameCallback implementation ==========

    override fun onVideoFrame(frame: VideoFrameData) {
        val currentSurface = surface ?: return

        currentVideoWidth = frame.width
        currentVideoHeight = frame.height
        lastReceivedFrameWasCompressed = frame.isCompressed
        lastInfoFourCC = frame.fourCC

        maybeUpdateVideoInfo(frame)

        if (isRecordingEnabled) {
            recorder?.writeFrame(frame)
        }

        if (!frame.isCompressed) {
            if (decoderInitialized) {
                releaseDecoder()
            }
            uncompressedRenderer?.render(frame)
        } else {
            val mimeType = when (frame.fourCC) {
                FourCC.H264 -> VideoDecoder.MIME_H264
                FourCC.HEVC -> VideoDecoder.MIME_H265
                else -> VideoDecoder.MIME_H265
            }
            currentIsHevc = (mimeType == VideoDecoder.MIME_H265)

            if (!decoderInitialized) {
                synchronized(decoderLock) {
                    if (!decoderInitialized && surface != null) {
                        decoder = decoder ?: VideoDecoder()
                        decoder?.initialize(currentSurface, frame.width, frame.height, mimeType)
                        decoder?.start()
                        decoderInitialized = true
                    }
                }
            }
            decoder?.submitFrame(frame)
        }
        updateBitrateInfo(frame.data.remaining())
    }

    override fun onConnectionLost() {
        viewModelScope.launch {
            // Stop recording on connection loss
            if (isRecordingEnabled) {
                stopRecordingInternal()
            }

            _uiState.value = _uiState.value.copy(
                connectionState = ConnectionState.Error("Connection lost")
            )
        }
    }

    private fun maybeUpdateVideoInfo(frame: VideoFrameData) {
        val shouldUpdate = frame.width != lastInfoWidth ||
            frame.height != lastInfoHeight ||
            frame.fourCC != lastInfoFourCC ||
            frame.isCompressed != lastInfoIsCompressed

        if (!shouldUpdate) return

        lastInfoWidth = frame.width
        lastInfoHeight = frame.height
        lastInfoFourCC = frame.fourCC
        lastInfoIsCompressed = frame.isCompressed

        updateVideoInfo(frame)
    }

    private fun updateVideoInfo(frame: VideoFrameData) {
        val fps = if (frame.frameRateD > 0) {
            frame.frameRateN.toFloat() / frame.frameRateD
        } else {
            0f
        }

        val label = if (frame.isCompressed) {
            when (frame.fourCC) {
                FourCC.H264 -> "H.264"
                FourCC.HEVC -> "H.265"
                else -> "Compressed (${frame.fourCC.name})"
            }
        } else {
            "Raw ${frame.fourCC.name}"
        }

        val info = "${frame.width}x${frame.height} @ ${String.format("%.1f", fps)}fps | $label"

        _uiState.value = _uiState.value.copy(
            videoInfo = info,
            videoWidth = frame.width,
            videoHeight = frame.height
        )
    }

    private fun copyByteBuffer(src: ByteBuffer): ByteBuffer {
        val dup = src.duplicate()
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        return ByteBuffer.wrap(bytes)
    }

    /**
     * Update bitrate information based on received data.
     */
    private fun updateBitrateInfo(frameBytes: Int) {
        val currentTime = System.currentTimeMillis()
        bytesReceivedSinceLastUpdate += frameBytes

        // Update bitrate every second
        if (currentTime - lastBitrateUpdateTime >= 1000) {
            val elapsedSeconds = (currentTime - lastBitrateUpdateTime) / 1000.0
            if (elapsedSeconds > 0) {
                currentBitrateKbps = (bytesReceivedSinceLastUpdate * 8.0) / (elapsedSeconds * 1000.0)
            }
            bytesReceivedSinceLastUpdate = 0
            lastBitrateUpdateTime = currentTime

            val bitrateStr = when {
                currentBitrateKbps >= 1000 -> String.format("%.1f Mbps", currentBitrateKbps / 1000.0)
                else -> String.format("%.0f Kbps", currentBitrateKbps)
            }
            _uiState.value = _uiState.value.copy(bitrateInfo = bitrateStr)
        }
    }

    private fun releaseRenderer() {
        uncompressedRenderer?.release()
        uncompressedRenderer = null
    }

    private fun releaseDecoder() {
        decoder?.release()
        decoder = null
        decoderInitialized = false
    }

    override fun onCleared() {
        super.onCleared()

        // Stop recording synchronously
        if (isRecordingEnabled) {
            isRecordingEnabled = false
            recorder?.stopRecording()
        }

        // Cancel auto-reconnect
        autoReconnectJob?.cancel()

        // Non-blocking disconnect - receive thread checks isReceiving flag
        receiver.disconnectSync()

        // Release resources
        releaseRenderer()
        releaseDecoder()
        recorder?.release()
        recorder = null
    }
}
