package com.example.ndireceiver.ndi

/**
 * Data class representing an NDI source on the network.
 */
data class NdiSource(
    val name: String,
    val url: String = ""
) {
    val displayName: String
        get() = name.substringBefore(" (")

    val machineName: String
        get() = name.substringAfter("(").substringBefore(")")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NdiSource) return false
        return name == other.name
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }
}
