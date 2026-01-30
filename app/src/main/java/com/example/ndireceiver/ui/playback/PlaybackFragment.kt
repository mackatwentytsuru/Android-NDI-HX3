package com.example.ndireceiver.ui.playback

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.PlayerView
import com.example.ndireceiver.R
import kotlinx.coroutines.launch

/**
 * Fragment for playing recorded video files using ExoPlayer.
 *
 * Features:
 * - Full-screen video playback with ExoPlayer
 * - SeekBar with position updates
 * - Play/Pause/Stop controls
 * - Auto-hide controls
 * - Error handling with retry
 */
class PlaybackFragment : Fragment() {

    companion object {
        private const val ARG_FILE_PATH = "file_path"
        private const val CONTROLS_HIDE_DELAY_MS = 5000L
        private const val POSITION_UPDATE_INTERVAL_MS = 1000L

        fun newInstance(filePath: String): PlaybackFragment {
            return PlaybackFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_FILE_PATH, filePath)
                }
            }
        }
    }

    private val viewModel: PlaybackViewModel by viewModels()
    private val handler = Handler(Looper.getMainLooper())

    // Views
    private lateinit var playerView: PlayerView
    private lateinit var controlsOverlay: ConstraintLayout
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var errorOverlay: LinearLayout

    private lateinit var btnBack: ImageButton
    private lateinit var fileName: TextView
    private lateinit var fileInfo: TextView
    private lateinit var errorText: TextView
    private lateinit var btnRetry: Button

    private var filePath: String? = null

    // Flag to prevent handler callbacks after view destruction (Issue #3 fix)
    @Volatile
    private var isViewDestroyed = false

    private val hideControlsRunnable = Runnable {
        // Check if view is still valid before executing (Issue #3 fix)
        if (!isViewDestroyed) {
            viewModel.hideControls()
        }
    }

    private val updatePositionRunnable = object : Runnable {
        override fun run() {
            // Check if view is still valid before executing (Issue #3 fix)
            if (isViewDestroyed) return

            viewModel.updatePosition()
            handler.postDelayed(this, POSITION_UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filePath = arguments?.getString(ARG_FILE_PATH)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_playback, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Reset the destroyed flag when view is created (Issue #3 fix)
        isViewDestroyed = false

        initializeViews(view)
        setupPlayerView()
        setupControls()
        observeUiState()

        // Load the video
        filePath?.let { path ->
            viewModel.loadVideo(path)
        }
    }

    private fun initializeViews(view: View) {
        playerView = view.findViewById(R.id.player_view)
        controlsOverlay = view.findViewById(R.id.controls_overlay)
        loadingOverlay = view.findViewById(R.id.loading_overlay)
        errorOverlay = view.findViewById(R.id.error_overlay)

        btnBack = view.findViewById(R.id.btn_back)
        fileName = view.findViewById(R.id.file_name)
        fileInfo = view.findViewById(R.id.file_info)
        errorText = view.findViewById(R.id.error_text)
        btnRetry = view.findViewById(R.id.btn_retry)
    }

    private fun setupPlayerView() {
        // Connect ExoPlayer to PlayerView
        playerView.player = viewModel.getPlayer()

        // Tap on player to toggle controls
        playerView.setOnClickListener {
            viewModel.toggleControls()
            if (viewModel.uiState.value.showControls) {
                scheduleHideControls()
            }
        }

        // Show/hide PlayerView controls based on our controls state
        playerView.useController = true
    }

    private fun setupControls() {
        btnBack.setOnClickListener {
            viewModel.pause()
            parentFragmentManager.popBackStack()
        }

        btnRetry.setOnClickListener {
            viewModel.retry()
        }

        // Schedule auto-hide controls
        scheduleHideControls()
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUi(state)
                }
            }
        }
    }

    private fun updateUi(state: PlaybackUiState) {
        // Update file info
        fileName.text = state.metadata.fileName
        fileInfo.text = state.metadata.getFormattedInfo()

        // Update visibility based on playback state
        when (state.playbackState) {
            is PlaybackState.Idle -> {
                loadingOverlay.isVisible = false
                errorOverlay.isVisible = false
            }
            is PlaybackState.Loading -> {
                loadingOverlay.isVisible = true
                errorOverlay.isVisible = false
            }
            is PlaybackState.Playing,
            is PlaybackState.Paused -> {
                loadingOverlay.isVisible = false
                errorOverlay.isVisible = false
            }
            is PlaybackState.Ended -> {
                loadingOverlay.isVisible = false
                errorOverlay.isVisible = false
            }
            is PlaybackState.Error -> {
                loadingOverlay.isVisible = false
                errorOverlay.isVisible = true
                errorText.text = state.playbackState.message
            }
        }

        // Update controls visibility
        controlsOverlay.isVisible = state.showControls
        playerView.useController = state.showControls
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS)
    }

    override fun onStart() {
        super.onStart()
        // Start position updates
        handler.post(updatePositionRunnable)
    }

    override fun onStop() {
        super.onStop()
        // Pause playback when fragment is stopped
        viewModel.pause()

        // Stop position updates
        handler.removeCallbacks(updatePositionRunnable)
    }

    override fun onDestroyView() {
        // Set flag FIRST to prevent any pending callbacks from executing (Issue #3 fix)
        isViewDestroyed = true

        // Remove all pending callbacks (Issue #3 fix)
        handler.removeCallbacks(hideControlsRunnable)
        handler.removeCallbacks(updatePositionRunnable)
        // Remove any other pending messages to be safe
        handler.removeCallbacksAndMessages(null)

        // Detach player from PlayerView
        playerView.player = null

        // Release player when Fragment view is destroyed to prevent memory leak (Issue #1 fix)
        // This ensures the ExoPlayer doesn't outlive the Fragment's view
        viewModel.releasePlayerFromFragment()

        super.onDestroyView()
    }
}
