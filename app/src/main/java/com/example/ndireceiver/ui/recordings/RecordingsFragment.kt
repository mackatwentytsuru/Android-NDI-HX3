package com.example.ndireceiver.ui.recordings

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ndireceiver.R
import com.example.ndireceiver.data.Recording
import com.example.ndireceiver.ui.playback.PlaybackFragment
import kotlinx.coroutines.launch

/**
 * Fragment for displaying the list of recorded videos.
 *
 * Provides functionality to:
 * - View all recorded MP4 files with thumbnails
 * - Play recordings using PlaybackFragment
 * - Delete recordings with confirmation dialog
 */
class RecordingsFragment : Fragment() {

    companion object {
        fun newInstance(): RecordingsFragment {
            return RecordingsFragment()
        }
    }

    private val viewModel: RecordingsViewModel by viewModels()
    private lateinit var adapter: RecordingAdapter

    private lateinit var btnBack: ImageButton
    private lateinit var storageInfo: TextView
    private lateinit var recordingsList: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var progress: ProgressBar

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_recordings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupRecyclerView()
        setupButtons()
        observeUiState()
    }

    private fun initializeViews(view: View) {
        btnBack = view.findViewById(R.id.btn_back)
        storageInfo = view.findViewById(R.id.storage_info)
        recordingsList = view.findViewById(R.id.recordings_list)
        emptyState = view.findViewById(R.id.empty_state)
        progress = view.findViewById(R.id.progress)
    }

    private fun setupRecyclerView() {
        adapter = RecordingAdapter(
            onPlayClick = { recording ->
                navigateToPlayback(recording)
            },
            onDeleteClick = { recording ->
                viewModel.requestDelete(recording)
            }
        )

        recordingsList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@RecordingsFragment.adapter
        }
    }

    private fun setupButtons() {
        btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
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

    private fun updateUi(state: RecordingsUiState) {
        // Loading state
        progress.isVisible = state.isLoading

        // Storage info
        storageInfo.text = state.getStorageInfo()

        // Recordings list
        if (state.recordings.isEmpty() && !state.isLoading) {
            recordingsList.isVisible = false
            emptyState.isVisible = true
        } else {
            recordingsList.isVisible = true
            emptyState.isVisible = false
            adapter.submitList(state.recordings)
        }

        // Error handling
        state.error?.let { error ->
            Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }

        // Deletion confirmation dialog
        state.deleteConfirmation?.let { recording ->
            showDeleteConfirmationDialog(recording)
        }

        // Deletion success notification
        state.deletedRecording?.let { recording ->
            Toast.makeText(
                requireContext(),
                getString(R.string.recording_deleted, recording.name),
                Toast.LENGTH_SHORT
            ).show()
            viewModel.clearDeletedNotification()
        }
    }

    private fun showDeleteConfirmationDialog(recording: Recording) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_recording_title)
            .setMessage(getString(R.string.delete_recording_message, recording.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.confirmDelete()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                viewModel.cancelDelete()
            }
            .setOnCancelListener {
                viewModel.cancelDelete()
            }
            .show()
    }

    private fun navigateToPlayback(recording: Recording) {
        parentFragmentManager.commit {
            replace(R.id.fragment_container, PlaybackFragment.newInstance(recording.file.absolutePath))
            addToBackStack(null)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh list when returning to this fragment
        viewModel.refresh()
    }
}
