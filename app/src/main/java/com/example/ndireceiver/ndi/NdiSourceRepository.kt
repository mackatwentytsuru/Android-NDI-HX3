package com.example.ndireceiver.ndi

/**
 * Singleton repository for sharing NDI source state across fragments.
 * This solves the problem of DevolaySource being lost when passing NdiSource
 * through Fragment arguments (DevolaySource is not serializable).
 */
object NdiSourceRepository {

    /**
     * Currently discovered NDI sources.
     * Updated by MainViewModel when discovery finds sources.
     */
    private var discoveredSources: List<NdiSource> = emptyList()

    /**
     * Currently selected source for playback.
     * Set by MainFragment when user selects a source.
     */
    private var selectedSource: NdiSource? = null

    /**
     * Update the list of discovered sources.
     */
    fun updateDiscoveredSources(sources: List<NdiSource>) {
        discoveredSources = sources
    }

    /**
     * Get all discovered sources.
     */
    fun getDiscoveredSources(): List<NdiSource> = discoveredSources

    /**
     * Set the selected source for playback.
     */
    fun setSelectedSource(source: NdiSource) {
        selectedSource = source
    }

    /**
     * Get the selected source.
     */
    fun getSelectedSource(): NdiSource? = selectedSource

    /**
     * Find a source by name from discovered sources.
     * Useful for reconnecting when the original DevolaySource reference was lost.
     */
    fun findSourceByName(name: String): NdiSource? {
        return discoveredSources.find { it.name == name }
    }

    /**
     * Clear the selected source.
     */
    fun clearSelectedSource() {
        selectedSource = null
    }

    /**
     * Clear all state (for cleanup).
     */
    fun clear() {
        discoveredSources = emptyList()
        selectedSource = null
    }
}
