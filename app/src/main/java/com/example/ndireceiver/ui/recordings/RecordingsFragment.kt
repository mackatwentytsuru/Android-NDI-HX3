package com.example.ndireceiver.ui.recordings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
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
    private lateinit var selectionToolbar: LinearLayout
    private lateinit var btnCancelSelection: ImageButton
    private lateinit var btnSelectAll: ImageButton
    private lateinit var btnDeleteSelected: ImageButton

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

        selectionToolbar = view.findViewById(R.id.selection_toolbar)
        btnCancelSelection = view.findViewById(R.id.btn_cancel_selection)
        btnSelectAll = view.findViewById(R.id.btn_select_all)
        btnDeleteSelected = view.findViewById(R.id.btn_delete_selected)
    }

    private fun setupRecyclerView() {
        adapter = RecordingAdapter(
            onPlayClick = { recording ->
                navigateToPlayback(recording)
            },
            onDeleteClick = { recording ->
                viewModel.requestDelete(recording)
            },
            onShareClick = { recording ->
                shareRecording(recording)
            },
            onRenameClick = { recording ->
                viewModel.requestRename(recording)
            },
            onSelectionModeChange = { enabled ->
                viewModel.setSelectionMode(enabled)
            },
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

        btnCancelSelection.setOnClickListener {
            adapter.clearSelection()
            adapter.setSelectionMode(false)
            viewModel.setSelectionMode(false)
        }

        btnSelectAll.setOnClickListener {
            adapter.selectAll()
        }

        btnDeleteSelected.setOnClickListener {
            val selected = adapter.getSelectedRecordings()
            viewModel.requestBatchDelete(selected)
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

        // Rename dialog
        state.renameDialogRecording?.let { recording ->
            showRenameDialog(recording)
        }

        // Batch delete confirmation dialog
        state.batchDeleteConfirmation?.let { recordings ->
            showBatchDeleteConfirmationDialog(recordings)
        }

        // Selection mode
        adapter.setSelectionMode(state.selectionMode)
        selectionToolbar.isVisible = state.selectionMode
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

    private fun stripMp4Extension(fileName: String): String {
        return if (fileName.endsWith(".mp4", ignoreCase = true)) {
            fileName.dropLast(4)
        } else {
            fileName
        }
    }

    private fun showRenameDialog(recording: Recording) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.rename_hint)
            setText(stripMp4Extension(recording.name))
            selectAll()
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.rename_recording)
            .setView(input)
            .setPositiveButton(R.string.rename, null)
            .setNegativeButton(R.string.cancel) { _, _ ->
                viewModel.cancelRename()
            }
            .setOnCancelListener {
                viewModel.cancelRename()
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text.toString()
                val trimmed = raw.trim()

                if (trimmed.isEmpty()) {
                    input.error = getString(R.string.rename_error_empty)
                    return@setOnClickListener
                }

                val finalName = if (trimmed.endsWith(".mp4", ignoreCase = true)) {
                    trimmed
                } else {
                    "$trimmed.mp4"
                }

                val isDuplicate = viewModel.uiState.value.recordings.any { existing ->
                    existing.file.absolutePath != recording.file.absolutePath &&
                        existing.name.equals(finalName, ignoreCase = true)
                }

                if (isDuplicate) {
                    input.error = getString(R.string.rename_error_duplicate)
                    return@setOnClickListener
                }

                viewModel.renameRecording(recording, trimmed)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showBatchDeleteConfirmationDialog(recordings: List<Recording>) {
        val count = recordings.size

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.batch_delete)
            .setMessage(getString(R.string.batch_delete_message, count))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.confirmBatchDelete()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                viewModel.cancelBatchDelete()
            }
            .setOnCancelListener {
                viewModel.cancelBatchDelete()
            }
            .show()
    }

    private fun navigateToPlayback(recording: Recording) {
        parentFragmentManager.commit {
            replace(R.id.fragment_container, PlaybackFragment.newInstance(recording.file.absolutePath))
            addToBackStack(null)
        }
    }

    private fun shareRecording(recording: Recording) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                recording.file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Failed to share recording: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh list when returning to this fragment
        viewModel.refresh()
    }
}
