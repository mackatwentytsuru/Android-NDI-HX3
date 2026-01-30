package com.example.ndireceiver.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ndireceiver.R
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch

/**
 * Fragment for app settings screen.
 */
class SettingsFragment : Fragment() {

    companion object {
        fun newInstance(): SettingsFragment {
            return SettingsFragment()
        }
    }

    private val viewModel: SettingsViewModel by viewModels()

    // Views
    private lateinit var btnBack: ImageButton
    private lateinit var switchAutoReconnect: SwitchMaterial
    private lateinit var switchScreenAlwaysOn: SwitchMaterial
    private lateinit var switchShowOsd: SwitchMaterial
    private lateinit var lastSourceContainer: LinearLayout
    private lateinit var lastSourceName: TextView
    private lateinit var btnClearLastSource: Button
    private lateinit var storagePath: TextView
    private lateinit var storageInfo: TextView
    private lateinit var versionInfo: TextView

    // Flag to prevent switch listener triggering during initialization
    private var isInitializing = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupListeners()
        observeUiState()

        // Refresh storage info when returning to this screen
        viewModel.refreshStorageInfo()
    }

    private fun initializeViews(view: View) {
        btnBack = view.findViewById(R.id.btn_back)
        switchAutoReconnect = view.findViewById(R.id.switch_auto_reconnect)
        switchScreenAlwaysOn = view.findViewById(R.id.switch_screen_always_on)
        switchShowOsd = view.findViewById(R.id.switch_show_osd)
        lastSourceContainer = view.findViewById(R.id.last_source_container)
        lastSourceName = view.findViewById(R.id.last_source_name)
        btnClearLastSource = view.findViewById(R.id.btn_clear_last_source)
        storagePath = view.findViewById(R.id.storage_path)
        storageInfo = view.findViewById(R.id.storage_info)
        versionInfo = view.findViewById(R.id.version_info)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        switchAutoReconnect.setOnCheckedChangeListener { _, isChecked ->
            if (!isInitializing) {
                viewModel.setAutoReconnect(isChecked)
            }
        }

        switchScreenAlwaysOn.setOnCheckedChangeListener { _, isChecked ->
            if (!isInitializing) {
                viewModel.setScreenAlwaysOn(isChecked)
            }
        }

        switchShowOsd.setOnCheckedChangeListener { _, isChecked ->
            if (!isInitializing) {
                viewModel.setShowOsd(isChecked)
            }
        }

        btnClearLastSource.setOnClickListener {
            viewModel.clearLastConnectedSource()
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

    private fun updateUi(state: SettingsUiState) {
        isInitializing = true

        // Update switches
        switchAutoReconnect.isChecked = state.settings.autoReconnect
        switchScreenAlwaysOn.isChecked = state.settings.screenAlwaysOn
        switchShowOsd.isChecked = state.settings.showOsd

        // Update last connected source
        val hasLastSource = state.settings.lastConnectedSourceName != null
        lastSourceContainer.isVisible = hasLastSource
        if (hasLastSource) {
            lastSourceName.text = state.settings.lastConnectedSourceName
        }

        // Update storage info
        storagePath.text = state.storageLocation
        storageInfo.text = state.storageInfo

        // Update version
        versionInfo.text = state.versionInfo

        isInitializing = false
    }

    override fun onResume() {
        super.onResume()
        // Refresh storage info in case recordings were added/deleted
        viewModel.refreshStorageInfo()
    }
}
