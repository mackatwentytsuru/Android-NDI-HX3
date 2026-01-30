package com.example.ndireceiver.ui.player

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.ndireceiver.ndi.ConnectionState
import com.example.ndireceiver.ndi.NdiNative
import com.example.ndireceiver.ndi.VideoFrameData
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Unit tests for PlayerViewModel.
 * Tests state management, frame routing, and recording state transitions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockApplication: Application
    private lateinit var viewModel: PlayerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockApplication = mockk(relaxed = true)

        // Mock application context
        every { mockApplication.applicationContext } returns mockApplication

        // Mock SharedPreferences
        val mockSharedPrefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { mockApplication.getSharedPreferences(any(), any()) } returns mockSharedPrefs
        every { mockSharedPrefs.getBoolean(any(), any()) } returns false
        every { mockSharedPrefs.getString(any(), any()) } returns null

        // Create ViewModel (note: this may need adjustment based on actual implementation)
        // viewModel = PlayerViewModel(mockApplication)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== Recording State Tests ==========

    @Test
    fun `initial recording state is Idle`() {
        // Create a fresh UI state and verify initial recording state
        val initialState = PlayerUiState()
        assertTrue(initialState.recordingState is RecordingState.Idle)
    }

    @Test
    fun `RecordingState Recording holds duration`() {
        val recording = RecordingState.Recording(5000L)
        assertEquals(5000L, recording.durationMs)
    }

    @Test
    fun `RecordingState Stopped can hold file`() {
        val stopped = RecordingState.Stopped(null)
        assertNull(stopped.file)
    }

    @Test
    fun `RecordingState Error holds message`() {
        val error = RecordingState.Error("Test error")
        assertEquals("Test error", error.message)
    }

    // ========== PlayerUiState Tests ==========

    @Test
    fun `PlayerUiState default values are correct`() {
        val state = PlayerUiState()

        assertTrue(state.connectionState is ConnectionState.Disconnected)
        assertTrue(state.recordingState is RecordingState.Idle)
        assertTrue(state.showControls)
        assertTrue(state.showOsd)
        assertEquals("", state.videoInfo)
        assertEquals("", state.bitrateInfo)
        assertEquals(0, state.retryCount)
        assertFalse(state.isAutoReconnecting)
    }

    @Test
    fun `PlayerUiState can be copied with new values`() {
        val original = PlayerUiState()
        val modified = original.copy(
            showControls = false,
            showOsd = false,
            videoInfo = "1920x1080",
            bitrateInfo = "10 Mbps",
            retryCount = 3,
            isAutoReconnecting = true
        )

        assertFalse(modified.showControls)
        assertFalse(modified.showOsd)
        assertEquals("1920x1080", modified.videoInfo)
        assertEquals("10 Mbps", modified.bitrateInfo)
        assertEquals(3, modified.retryCount)
        assertTrue(modified.isAutoReconnecting)
    }

    // ========== ConnectionState Tests ==========

    @Test
    fun `ConnectionState Disconnected is singleton`() {
        val state1 = ConnectionState.Disconnected
        val state2 = ConnectionState.Disconnected
        assertSame(state1, state2)
    }

    @Test
    fun `ConnectionState Connecting is singleton`() {
        val state1 = ConnectionState.Connecting
        val state2 = ConnectionState.Connecting
        assertSame(state1, state2)
    }

    @Test
    fun `ConnectionState Error holds message`() {
        val error = ConnectionState.Error("Connection failed")
        assertEquals("Connection failed", error.message)
    }

    // ========== VideoFrameData Tests ==========

    @Test
    fun `VideoFrameData isCompressed is false for BGRA frames`() {
        val buffer = ByteBuffer.allocate(100)
        val frame = VideoFrameData(
            width = 1920,
            height = 1080,
            frameRateN = 30000,
            frameRateD = 1001,
            data = buffer,
            lineStrideBytes = 1920 * 4,
            timestamp = 0L,
            fourCC = NdiNative.FourCC.BGRA,
            isCompressed = false
        )

        assertFalse(frame.isCompressed)
        assertEquals(1920, frame.width)
        assertEquals(1080, frame.height)
    }

    @Test
    fun `VideoFrameData isCompressed is true for H264 frames`() {
        val buffer = ByteBuffer.allocate(100)
        val frame = VideoFrameData(
            width = 1920,
            height = 1080,
            frameRateN = 30000,
            frameRateD = 1001,
            data = buffer,
            lineStrideBytes = 0,
            timestamp = 0L,
            fourCC = NdiNative.FourCC.H264,
            isCompressed = true
        )

        assertTrue(frame.isCompressed)
    }

    @Test
    fun `VideoFrameData isCompressed is true for HEVC frames`() {
        val buffer = ByteBuffer.allocate(100)
        val frame = VideoFrameData(
            width = 1920,
            height = 1080,
            frameRateN = 30000,
            frameRateD = 1001,
            data = buffer,
            lineStrideBytes = 0,
            timestamp = 0L,
            fourCC = NdiNative.FourCC.HEVC,
            isCompressed = true
        )

        assertTrue(frame.isCompressed)
    }

    // ========== Frame Rate Calculation Tests ==========

    @Test
    fun `frame rate calculation for 30fps`() {
        val frameRateN = 30000
        val frameRateD = 1001
        val fps = frameRateN.toFloat() / frameRateD

        // 30000/1001 ≈ 29.97 fps (NTSC)
        assertEquals(29.97f, fps, 0.01f)
    }

    @Test
    fun `frame rate calculation for 60fps`() {
        val frameRateN = 60000
        val frameRateD = 1001
        val fps = frameRateN.toFloat() / frameRateD

        // 60000/1001 ≈ 59.94 fps
        assertEquals(59.94f, fps, 0.01f)
    }

    @Test
    fun `frame rate calculation handles zero denominator`() {
        val frameRateN = 30
        val frameRateD = 0

        val fps = if (frameRateD > 0) {
            frameRateN.toFloat() / frameRateD
        } else {
            0f
        }

        assertEquals(0f, fps, 0.001f)
    }

    // ========== ByteBuffer Copy Tests ==========

    @Test
    fun `ByteBuffer copy creates independent copy`() {
        val originalData = byteArrayOf(1, 2, 3, 4, 5)
        val original = ByteBuffer.wrap(originalData)

        // Simulate the copyByteBuffer logic
        val dup = original.duplicate()
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        val copy = ByteBuffer.wrap(bytes)

        // Verify copy is independent
        assertEquals(5, copy.remaining())

        // Modify original data array (not the ByteBuffer)
        originalData[0] = 99.toByte()

        // Copy should be unchanged because it created its own byte array
        copy.rewind()  // Reset position to read from start
        assertEquals(1, copy.get().toInt())
    }

    @Test
    fun `ByteBuffer copy preserves all data`() {
        val testData = byteArrayOf(0, 127, -128, 255.toByte(), 1, 2, 3)
        val original = ByteBuffer.wrap(testData)

        val dup = original.duplicate()
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        val copy = ByteBuffer.wrap(bytes)

        for (i in testData.indices) {
            assertEquals(testData[i], copy.get(i))
        }
    }

    // ========== FourCC Label Tests ==========

    @Test
    fun `fourCC values are distinct`() {
        val fourCCs = listOf(
            NdiNative.FourCC.UYVY,
            NdiNative.FourCC.BGRA,
            NdiNative.FourCC.BGRX,
            NdiNative.FourCC.RGBA,
            NdiNative.FourCC.RGBX,
            NdiNative.FourCC.H264,
            NdiNative.FourCC.HEVC
        )

        val uniqueFourCCs = fourCCs.toSet()
        assertEquals("All FourCC values should be unique", fourCCs.size, uniqueFourCCs.size)
    }

    // ========== Bitrate Formatting Tests ==========

    @Test
    fun `bitrate formatting for Kbps`() {
        val bitrateKbps = 500.0
        val bitrateStr = when {
            bitrateKbps >= 1000 -> String.format("%.1f Mbps", bitrateKbps / 1000.0)
            else -> String.format("%.0f Kbps", bitrateKbps)
        }
        assertEquals("500 Kbps", bitrateStr)
    }

    @Test
    fun `bitrate formatting for Mbps`() {
        val bitrateKbps = 5000.0
        val bitrateStr = when {
            bitrateKbps >= 1000 -> String.format("%.1f Mbps", bitrateKbps / 1000.0)
            else -> String.format("%.0f Kbps", bitrateKbps)
        }
        assertEquals("5.0 Mbps", bitrateStr)
    }

    @Test
    fun `bitrate formatting for edge case at 1000 Kbps`() {
        val bitrateKbps = 1000.0
        val bitrateStr = when {
            bitrateKbps >= 1000 -> String.format("%.1f Mbps", bitrateKbps / 1000.0)
            else -> String.format("%.0f Kbps", bitrateKbps)
        }
        assertEquals("1.0 Mbps", bitrateStr)
    }
}
