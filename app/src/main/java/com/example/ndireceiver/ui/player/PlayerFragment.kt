package com.example.ndireceiver.ui.player

import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ndireceiver.R
import com.example.ndireceiver.ndi.ConnectionState
import com.example.ndireceiver.ndi.NdiSource
import com.example.ndireceiver.ndi.NdiSourceRepository
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Fragment for displaying NDI video stream with recording capability.
 */
class PlayerFragment : Fragment() {

    companion object {
        private const val ARG_SOURCE_NAME = "source_name"
        private const val ARG_SOURCE_URL = "source_url"
        private const val CONTROLS_HIDE_DELAY_MS = 5000L

        fun newInstance(source: NdiSource): PlayerFragment {
            return PlayerFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SOURCE_NAME, source.name)
                    putString(ARG_SOURCE_URL, source.url)
                }
            }
        }
    }

    private val viewModel: PlayerViewModel by viewModels()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var surfaceView: SurfaceView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var controlsOverlay: ConstraintLayout
    private lateinit var errorOverlay: LinearLayout

    private lateinit var sourceName: TextView
    private lateinit var connectingText: TextView
    private lateinit var osdInfo: TextView
    private lateinit var osdBitrate: TextView
    private lateinit var recordingIndicator: TextView
    private lateinit var errorText: TextView
    private lateinit var autoReconnectText: TextView

    private lateinit var btnBack: ImageButton
    private lateinit var btnRecord: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnRetry: Button
    private lateinit var btnOsd: ImageButton
    private lateinit var btnCancelReconnect: Button

    private var sourceToBind: NdiSource? = null
    private var errorDialog: AlertDialog? = null

    private val hideControlsRunnable = Runnable {
        viewModel.hideControls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let { args ->
            val sourceName = args.getString(ARG_SOURCE_NAME, "")

            // First try to get the source from the repository (has DevolaySource)
            // If not found, fall back to creating a new NdiSource (won't have DevolaySource)
            sourceToBind = NdiSourceRepository.findSourceByName(sourceName)
                ?: NdiSourceRepository.getSelectedSource()
                ?: NdiSource(
                    name = sourceName,
                    url = args.getString(ARG_SOURCE_URL, "")
                )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_player, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize recorder with app context
        viewModel.initializeRecorder(requireContext().applicationContext)

        initializeViews(view)
        setupSurface()
        setupControls()
        observeUiState()

        sourceToBind?.let {
            sourceName.text = it.displayName
        }
    }

    override fun onResume() {
        super.onResume()
        // Restore screen settings on resume (handles config changes and lifecycle)
        setupScreenSettings()
    }

    /**
     * Setup screen settings (keep screen on, fullscreen).
     */
    private fun setupScreenSettings() {
        // Keep screen on based on settings
        if (viewModel.isScreenAlwaysOnEnabled()) {
            requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Enter immersive fullscreen mode
        enterFullscreen()
    }

    /**
     * Enter immersive sticky fullscreen mode.
     */
    private fun enterFullscreen() {
        val window = requireActivity().window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    /**
     * Show system bars when controls are visible.
     */
    private fun showSystemBars() {
        val window = requireActivity().window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    /**
     * Hide system bars when controls are hidden.
     */
    private fun hideSystemBars() {
        enterFullscreen()
    }

    private fun initializeViews(view: View) {
        surfaceView = view.findViewById(R.id.surface_view)
        loadingOverlay = view.findViewById(R.id.loading_overlay)
        controlsOverlay = view.findViewById(R.id.controls_overlay)
        errorOverlay = view.findViewById(R.id.error_overlay)

        sourceName = view.findViewById(R.id.source_name)
        connectingText = view.findViewById(R.id.connecting_text)
        osdInfo = view.findViewById(R.id.osd_info)
        osdBitrate = view.findViewById(R.id.osd_bitrate)
        recordingIndicator = view.findViewById(R.id.recording_indicator)
        errorText = view.findViewById(R.id.error_text)
        autoReconnectText = view.findViewById(R.id.auto_reconnect_text)

        btnBack = view.findViewById(R.id.btn_back)
        btnRecord = view.findViewById(R.id.btn_record)
        btnDisconnect = view.findViewById(R.id.btn_disconnect)
        btnRetry = view.findViewById(R.id.btn_retry)
        btnOsd = view.findViewById(R.id.btn_osd)
        btnCancelReconnect = view.findViewById(R.id.btn_cancel_reconnect)
    }

    private fun setupSurface() {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                viewModel.setSurface(holder.surface)

                // Connect to source once surface is ready
                sourceToBind?.let { source ->
                    viewModel.connect(source)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // Surface dimensions changed - could reconfigure decoder here if needed
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                viewModel.setSurface(null)
            }
        })

        // Tap to toggle controls
        surfaceView.setOnClickListener {
            viewModel.toggleControls()
            scheduleHideControls()
        }
    }

    private fun setupControls() {
        btnBack.setOnClickListener {
            viewModel.disconnect()
            parentFragmentManager.popBackStack()
        }

        btnDisconnect.setOnClickListener {
            viewModel.disconnect()
            parentFragmentManager.popBackStack()
        }

        btnRecord.setOnClickListener {
            viewModel.toggleRecording()
            // Keep controls visible while recording
            if (viewModel.isRecording()) {
                handler.removeCallbacks(hideControlsRunnable)
            } else {
                scheduleHideControls()
            }
        }

        btnRetry.setOnClickListener {
            viewModel.retry()
        }

        btnOsd.setOnClickListener {
            viewModel.toggleOsd()
            scheduleHideControls()
        }

        btnCancelReconnect.setOnClickListener {
            viewModel.cancelAutoReconnect()
        }

        // Auto-hide controls after delay
        scheduleHideControls()
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        // Don't auto-hide if recording
        if (!viewModel.isRecording()) {
            handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS)
        }
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

    private fun updateUi(state: PlayerUiState) {
        // Update visibility based on connection state
        when (state.connectionState) {
            is ConnectionState.Disconnected -> {
                loadingOverlay.isVisible = false
                errorOverlay.isVisible = false
            }
            is ConnectionState.Connecting -> {
                loadingOverlay.isVisible = true
                errorOverlay.isVisible = false
                connectingText.text = getString(R.string.connecting)
            }
            is ConnectionState.Connected -> {
                loadingOverlay.isVisible = false
                errorOverlay.isVisible = false
            }
            is ConnectionState.Error -> {
                loadingOverlay.isVisible = false

                // Show error with auto-reconnect info if applicable
                if (state.isAutoReconnecting) {
                    errorOverlay.isVisible = true
                    errorText.text = state.connectionState.message
                    autoReconnectText.isVisible = true
                    autoReconnectText.text = getString(R.string.auto_reconnecting, state.retryCount)
                    btnCancelReconnect.isVisible = true
                    btnRetry.isVisible = false
                } else {
                    // Show error dialog for connection errors
                    showConnectionErrorDialog(state.connectionState.message)
                    errorOverlay.isVisible = true
                    errorText.text = state.connectionState.message
                    autoReconnectText.isVisible = false
                    btnCancelReconnect.isVisible = false
                    btnRetry.isVisible = true
                }
            }
        }

        // Update controls visibility and sync with system bars
        controlsOverlay.isVisible = state.showControls
        if (state.showControls) {
            // Show system bars when controls visible (optional - keep hidden for true fullscreen)
            // showSystemBars()
        } else {
            hideSystemBars()
        }

        // Update OSD visibility based on settings
        val osdVisible = state.showOsd && state.connectionState is ConnectionState.Connected
        osdInfo.isVisible = osdVisible && state.videoInfo.isNotEmpty()
        osdBitrate.isVisible = osdVisible && state.bitrateInfo.isNotEmpty()

        // Update OSD content
        if (state.videoInfo.isNotEmpty()) {
            osdInfo.text = state.videoInfo
        }
        if (state.bitrateInfo.isNotEmpty()) {
            osdBitrate.text = state.bitrateInfo
        }

        // Update recording state
        updateRecordingUi(state.recordingState)
    }

    /**
     * Show connection error dialog with retry option.
     */
    private fun showConnectionErrorDialog(message: String) {
        // Don't show dialog if one is already showing or view is destroyed
        if (errorDialog?.isShowing == true || !isAdded) return

        errorDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.connection_error)
            .setMessage(message)
            .setPositiveButton(R.string.retry) { dialog, _ ->
                dialog.dismiss()
                viewModel.retry()
            }
            .setNegativeButton(R.string.back) { dialog, _ ->
                dialog.dismiss()
                viewModel.disconnect()
                parentFragmentManager.popBackStack()
            }
            .setCancelable(false)
            .create()

        errorDialog?.show()
    }

    /**
     * Update UI elements based on recording state.
     */
    private fun updateRecordingUi(recordingState: RecordingState) {
        when (recordingState) {
            is RecordingState.Idle -> {
                recordingIndicator.isVisible = false
                btnRecord.text = getString(R.string.start_recording)
                btnRecord.isEnabled = true
            }
            is RecordingState.Recording -> {
                recordingIndicator.isVisible = true
                recordingIndicator.text = formatRecordingDuration(recordingState.durationMs)
                btnRecord.text = getString(R.string.stop_recording)
                btnRecord.isEnabled = true
            }
            is RecordingState.Stopped -> {
                recordingIndicator.isVisible = false
                btnRecord.text = getString(R.string.start_recording)
                btnRecord.isEnabled = true

                // Show toast with saved file info
                recordingState.file?.let { file ->
                    Toast.makeText(
                        requireContext(),
                        "Recording saved: ${file.name}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            is RecordingState.Error -> {
                recordingIndicator.isVisible = false
                btnRecord.text = getString(R.string.start_recording)
                btnRecord.isEnabled = true

                Toast.makeText(
                    requireContext(),
                    "Recording error: ${recordingState.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Format recording duration as HH:MM:SS.
     */
    private fun formatRecordingDuration(durationMs: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60

        return String.format(Locale.US, "%s %02d:%02d:%02d",
            getString(R.string.rec_indicator),
            hours,
            minutes,
            seconds
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(hideControlsRunnable)
        errorDialog?.dismiss()
        errorDialog = null

        // Clear FLAG_KEEP_SCREEN_ON to prevent window flag leak
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        super.onStop()
        // Removed: viewModel.disconnect() - handled by button click and ViewModel.onCleared()
    }
}
