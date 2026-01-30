package com.example.ndireceiver.ui.main

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.ndireceiver.R
import com.example.ndireceiver.ndi.NdiSource

/**
 * Adapter for displaying NDI sources in a RecyclerView.
 */
class NdiSourceAdapter(
    private val onSourceClick: (NdiSource) -> Unit
) : ListAdapter<NdiSource, NdiSourceAdapter.SourceViewHolder>(SourceDiffCallback()) {

    private var connectedSource: NdiSource? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ndi_source, parent, false)
        return SourceViewHolder(view)
    }

    override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
        val source = getItem(position)
        holder.bind(source, source == connectedSource, onSourceClick)
    }

    /**
     * Update the connected source to show visual feedback.
     */
    fun setConnectedSource(source: NdiSource?) {
        val previousConnected = connectedSource
        connectedSource = source

        // Refresh items that changed state
        currentList.forEachIndexed { index, item ->
            if (item == previousConnected || item == source) {
                notifyItemChanged(index)
            }
        }
    }

    class SourceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.icon)
        private val nameView: TextView = itemView.findViewById(R.id.source_name)
        private val infoView: TextView = itemView.findViewById(R.id.source_info)
        private val statusIndicator: View = itemView.findViewById(R.id.status_indicator)

        fun bind(source: NdiSource, isConnected: Boolean, onClick: (NdiSource) -> Unit) {
            nameView.text = source.displayName
            infoView.text = source.machineName

            // Update status indicator color
            val indicatorColor = if (isConnected) {
                ContextCompat.getColor(itemView.context, R.color.connected_green)
            } else {
                ContextCompat.getColor(itemView.context, R.color.disconnected_gray)
            }
            (statusIndicator.background as? GradientDrawable)?.setColor(indicatorColor)

            itemView.setOnClickListener { onClick(source) }
        }
    }

    private class SourceDiffCallback : DiffUtil.ItemCallback<NdiSource>() {
        override fun areItemsTheSame(oldItem: NdiSource, newItem: NdiSource): Boolean {
            return oldItem.name == newItem.name && oldItem.url == newItem.url
        }

        override fun areContentsTheSame(oldItem: NdiSource, newItem: NdiSource): Boolean {
            return oldItem == newItem
        }
    }
}
