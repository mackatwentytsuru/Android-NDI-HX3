package com.example.ndireceiver.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data class representing a recorded video file with its metadata.
 */
data class Recording(
    val file: File,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateModified: Long,
    val width: Int = 0,
    val height: Int = 0
) {
    /**
     * Get formatted duration as HH:MM:SS or MM:SS.
     */
    fun getFormattedDuration(): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Get formatted file size (e.g., "1.5 GB", "256 MB").
     */
    fun getFormattedSize(): String {
        val kb = sizeBytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }

    /**
     * Get formatted date (e.g., "2026/01/29 14:30").
     */
    fun getFormattedDate(): String {
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        return dateFormat.format(Date(dateModified))
    }
}

/**
 * Repository for managing recorded video files.
 *
 * Provides methods for listing, deleting, and retrieving metadata for recordings.
 */
class RecordingRepository(private val context: Context) {

    companion object {
        private const val TAG = "RecordingRepository"
        private const val RECORDINGS_DIR = "recordings"
    }

    /**
     * Get the recordings directory.
     */
    fun getRecordingsDir(): File {
        val dir = File(context.getExternalFilesDir(null), RECORDINGS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Get list of all recordings, sorted by date (newest first).
     */
    suspend fun getRecordings(): List<Recording> = withContext(Dispatchers.IO) {
        val recordingsDir = getRecordingsDir()

        if (!recordingsDir.exists() || !recordingsDir.isDirectory) {
            return@withContext emptyList()
        }

        val mp4Files = recordingsDir.listFiles { file ->
            file.isFile && file.extension.equals("mp4", ignoreCase = true)
        } ?: emptyArray()

        mp4Files
            .mapNotNull { file ->
                try {
                    getRecordingMetadata(file)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get metadata for ${file.name}", e)
                    // Return basic info if metadata extraction fails
                    Recording(
                        file = file,
                        name = file.name,
                        durationMs = 0,
                        sizeBytes = file.length(),
                        dateModified = file.lastModified()
                    )
                }
            }
            .sortedByDescending { it.dateModified }
    }

    /**
     * Get metadata for a single recording file.
     */
    private fun getRecordingMetadata(file: File): Recording {
        var durationMs = 0L
        var width = 0
        var height = 0

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)

            // Get duration
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = durationStr?.toLongOrNull() ?: 0L

            // Get dimensions
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            width = widthStr?.toIntOrNull() ?: 0
            height = heightStr?.toIntOrNull() ?: 0

        } catch (e: Exception) {
            Log.e(TAG, "Error extracting metadata from ${file.name}", e)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaMetadataRetriever", e)
            }
        }

        return Recording(
            file = file,
            name = file.name,
            durationMs = durationMs,
            sizeBytes = file.length(),
            dateModified = file.lastModified(),
            width = width,
            height = height
        )
    }

    /**
     * Delete a recording file.
     *
     * @param recording The recording to delete
     * @return true if deletion was successful
     */
    suspend fun deleteRecording(recording: Recording): Boolean = withContext(Dispatchers.IO) {
        try {
            if (recording.file.exists()) {
                val deleted = recording.file.delete()
                if (deleted) {
                    Log.i(TAG, "Deleted recording: ${recording.name}")
                } else {
                    Log.e(TAG, "Failed to delete recording: ${recording.name}")
                }
                deleted
            } else {
                Log.w(TAG, "Recording file does not exist: ${recording.name}")
                true // Consider it deleted if it doesn't exist
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting recording: ${recording.name}", e)
            false
        }
    }

    /**
     * Delete multiple recordings.
     *
     * @param recordings List of recordings to delete
     * @return Number of successfully deleted recordings
     */
    suspend fun deleteRecordings(recordings: List<Recording>): Int = withContext(Dispatchers.IO) {
        var deletedCount = 0
        for (recording in recordings) {
            if (deleteRecording(recording)) {
                deletedCount++
            }
        }
        deletedCount
    }

    /**
     * Get total size of all recordings.
     */
    suspend fun getTotalSize(): Long = withContext(Dispatchers.IO) {
        val recordingsDir = getRecordingsDir()

        if (!recordingsDir.exists() || !recordingsDir.isDirectory) {
            return@withContext 0L
        }

        recordingsDir.listFiles { file ->
            file.isFile && file.extension.equals("mp4", ignoreCase = true)
        }?.sumOf { it.length() } ?: 0L
    }

    /**
     * Check if any recordings exist.
     */
    suspend fun hasRecordings(): Boolean = withContext(Dispatchers.IO) {
        val recordingsDir = getRecordingsDir()

        if (!recordingsDir.exists() || !recordingsDir.isDirectory) {
            return@withContext false
        }

        recordingsDir.listFiles { file ->
            file.isFile && file.extension.equals("mp4", ignoreCase = true)
        }?.isNotEmpty() ?: false
    }
}
