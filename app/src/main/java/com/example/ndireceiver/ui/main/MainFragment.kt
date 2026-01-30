package com.example.ndireceiver.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
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
import com.example.ndireceiver.ndi.NdiSource
import com.example.ndireceiver.ndi.NdiSourceRepository
import com.example.ndireceiver.ui.player.PlayerFragment
import com.example.ndireceiver.ui.recordings.RecordingsFragment
import com.example.ndireceiver.ui.settings.SettingsFragment
import kotlinx.coroutines.launch

/**
 * Main fragment showing list of available NDI sources.
 */
class MainFragment : Fragment() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: NdiSourceAdapter

    private lateinit var sourceList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnRefresh: Button
    private lateinit var btnRecordings: Button
    private lateinit var btnSettings: ImageButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize views
        sourceList = view.findViewById(R.id.source_list)
        emptyText = view.findViewById(R.id.empty_text)
        statusText = view.findViewById(R.id.status_text)
        progressBar = view.findViewById(R.id.progress)
        btnRefresh = view.findViewById(R.id.btn_refresh)
        btnRecordings = view.findViewById(R.id.btn_recordings)
        btnSettings = view.findViewById(R.id.btn_settings)

        setupRecyclerView()
        setupButtons()
        observeUiState()
    }

    private fun setupRecyclerView() {
        adapter = NdiSourceAdapter { source ->
            navigateToPlayer(source)
        }

        sourceList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@MainFragment.adapter
        }
    }

    private fun setupButtons() {
        btnRefresh.setOnClickListener {
            viewModel.refresh()
        }

        btnRecordings.setOnClickListener {
            navigateToRecordings()
        }

        btnSettings.setOnClickListener {
            navigateToSettings()
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

    private fun updateUi(state: MainUiState) {
        progressBar.isVisible = state.isLoading && state.sources.isEmpty()

        when {
            state.error != null -> {
                statusText.text = state.error
                emptyText.isVisible = true
                sourceList.isVisible = false
            }
            state.sources.isEmpty() && !state.isLoading -> {
                statusText.text = getString(R.string.no_sources_found)
                emptyText.isVisible = true
                sourceList.isVisible = false
            }
            else -> {
                statusText.text = if (state.isLoading) {
                    getString(R.string.searching_sources)
                } else {
                    "${state.sources.size} NDI source(s) found"
                }
                emptyText.isVisible = false
                sourceList.isVisible = true
                adapter.submitList(state.sources)
            }
        }
    }

    private fun navigateToPlayer(source: NdiSource) {
        // Store the selected source in repository so PlayerFragment can access it
        // with the full DevolaySource reference
        NdiSourceRepository.setSelectedSource(source)

        parentFragmentManager.commit {
            replace(R.id.fragment_container, PlayerFragment.newInstance(source))
            addToBackStack(null)
        }
    }

    private fun navigateToRecordings() {
        parentFragmentManager.commit {
            replace(R.id.fragment_container, RecordingsFragment.newInstance())
            addToBackStack(null)
        }
    }

    private fun navigateToSettings() {
        parentFragmentManager.commit {
            replace(R.id.fragment_container, SettingsFragment.newInstance())
            addToBackStack(null)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.startDiscovery()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopDiscovery()
    }
}
