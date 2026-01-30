package com.example.ndireceiver.ui.recordings

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.example.ndireceiver.R
import com.example.ndireceiver.data.Recording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * RecyclerView adapter for displaying recordings with thumbnails.
 *
 * Uses Glide for efficient thumbnail caching and loading.
 */
class RecordingAdapter(
    private val onPlayClick: (Recording) -> Unit,
    private val onDeleteClick: (Recording) -> Unit
) : ListAdapter<Recording, RecordingAdapter.RecordingViewHolder>(RecordingDiffCallback()) {

    /**
     * ViewHolder for recording items.
     */
    inner class RecordingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.thumbnail)
        private val durationOverlay: TextView = itemView.findViewById(R.id.duration_overlay)
        private val fileName: TextView = itemView.findViewById(R.id.file_name)
        private val fileInfo: TextView = itemView.findViewById(R.id.file_info)
        private val resolutionInfo: TextView = itemView.findViewById(R.id.resolution_info)
        private val btnPlay: ImageButton = itemView.findViewById(R.id.btn_play)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete)

        private var thumbnailJob: Job? = null

        fun bind(recording: Recording) {
            // Cancel previous thumbnail loading job
            thumbnailJob?.cancel()

            // Set file name
            fileName.text = recording.name

            // Set file info (date | duration | size)
            val info = buildString {
                append(recording.getFormattedDate())
                append(" | ")
                append(recording.getFormattedDuration())
                append(" | ")
                append(recording.getFormattedSize())
            }
            fileInfo.text = info

            // Set resolution info
            if (recording.width > 0 && recording.height > 0) {
                resolutionInfo.text = "${recording.width}x${recording.height}"
                resolutionInfo.visibility = View.VISIBLE
            } else {
                resolutionInfo.visibility = View.GONE
            }

            // Set duration overlay
            durationOverlay.text = recording.getFormattedDuration()

            // Load thumbnail
            loadThumbnail(recording)

            // Set click listeners
            btnPlay.setOnClickListener { onPlayClick(recording) }
            btnDelete.setOnClickListener { onDeleteClick(recording) }

            // Make the whole card clickable for play
            itemView.setOnClickListener { onPlayClick(recording) }
        }

        private fun loadThumbnail(recording: Recording) {
            // Clear previous thumbnail first
            thumbnail.setImageResource(android.R.color.transparent)

            // Use Glide to load thumbnail from video file
            // Glide can generate thumbnails from video files automatically
            Glide.with(itemView.context)
                .asBitmap()
                .load(recording.file.absolutePath)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .signature(ObjectKey("${recording.file.absolutePath}_${recording.dateModified}"))
                .centerCrop()
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(thumbnail)
        }

        fun recycle() {
            thumbnailJob?.cancel()
            thumbnailJob = null
            // Clear Glide request
            Glide.with(itemView.context).clear(thumbnail)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording, parent, false)
        return RecordingViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecordingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: RecordingViewHolder) {
        super.onViewRecycled(holder)
        holder.recycle()
    }

    /**
     * DiffUtil callback for efficient list updates.
     */
    class RecordingDiffCallback : DiffUtil.ItemCallback<Recording>() {
        override fun areItemsTheSame(oldItem: Recording, newItem: Recording): Boolean {
            return oldItem.file.absolutePath == newItem.file.absolutePath
        }

        override fun areContentsTheSame(oldItem: Recording, newItem: Recording): Boolean {
            return oldItem == newItem
        }
    }
}

/**
 * Helper class for generating video thumbnails using MediaMetadataRetriever.
 * Used as a fallback when Glide's built-in video thumbnail generation doesn't work.
 */
object VideoThumbnailHelper {

    /**
     * Generate a thumbnail from a video file at the given time position.
     *
     * @param file The video file
     * @param timeUs Position in microseconds (default: 0 = first frame)
     * @return Bitmap thumbnail or null if generation fails
     */
    suspend fun generateThumbnail(file: File, timeUs: Long = 0): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)

            // Try to get frame at specified time
            var bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

            // If that fails, try getting a frame at 1 second
            if (bitmap == null) {
                bitmap = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }

            // If still null, try getting any frame
            if (bitmap == null) {
                bitmap = retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
            }

            bitmap
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore release errors
            }
        }
    }
}
