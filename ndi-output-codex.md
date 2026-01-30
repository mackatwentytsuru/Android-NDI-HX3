# Code Review Report - Android NDI HX3 Receiver

**Date:** 2026-01-30
**Reviewer:** Code Analysis Agent
**Files Reviewed:** 24 Kotlin files

---

## Executive Summary

The codebase demonstrates good architectural patterns with proper use of coroutines, StateFlow, and MVVM. However, several thread safety issues, potential memory leaks, and resource management concerns were identified. The NDI receiver and media layers require particular attention.

**Total Issues Found:**
- Critical: 3
- High: 5
- Medium: 9

**Overall Risk Level:** HIGH - Immediate attention needed for thread safety and resource management in media processing layers.

---

## 1. CRITICAL ISSUES

### 1.1 NdiSourceRepository - Mutable State Without Synchronization
**File:** `app/src/main/java/com/example/ndireceiver/ndi/NdiSourceRepository.kt`
**Lines:** 14, 20
**Severity:** CRITICAL

```kotlin
private var discoveredSources: List<NdiSource> = emptyList()
private var selectedSource: NdiSource? = null
```

**Issue:** These mutable fields are accessed and modified from multiple threads without synchronization. `MainViewModel` updates `discoveredSources` on the IO dispatcher while UI components read from multiple threads.

**Impact:** Race conditions leading to inconsistent state, potential NPE.

**Suggested Fix:**
```kotlin
private val _discoveredSources = MutableStateFlow<List<NdiSource>>(emptyList())
private val _selectedSource = MutableStateFlow<NdiSource?>(null)
```

---

### 1.2 PlayerViewModel - Race Condition in Frame Callback
**File:** `app/src/main/java/com/example/ndireceiver/ui/player/PlayerViewModel.kt`
**Lines:** 57-85, 392-445
**Severity:** CRITICAL

**Issue:** In `onVideoFrame()` callback (called from NDI receive thread), the code accesses and modifies multiple volatile fields and decoder state without synchronization:
- Line 399: `lastReceivedFrameWasCompressed = frame.isCompressed` - updated without lock
- Line 422-432: Decoder initialization uses `decoderLock` but only after check `if (!decoderInitialized)`
- Line 397-398: currentVideoWidth/Height written from callback thread

The double-check pattern at lines 422-424 has a race condition:
```kotlin
if (!decoderInitialized) {
    synchronized(decoderLock) {
        if (!decoderInitialized && surface != null) {
            // Race condition: surface could become null between outer check and lock
```

**Impact:** Potential NPE, decoder race conditions, memory leaks if decoder initialized multiple times.

**Suggested Fix:**
```kotlin
override fun onVideoFrame(frame: VideoFrameData) {
    synchronized(decoderLock) {
        val currentSurface = surface ?: return
        currentVideoWidth = frame.width
        currentVideoHeight = frame.height
        lastReceivedFrameWasCompressed = frame.isCompressed
        // ... rest of logic
    }
}
```

---

### 1.3 VideoRecorder - Non-Atomic Field Modifications
**File:** `app/src/main/java/com/example/ndireceiver/media/VideoRecorder.kt`
**Lines:** 70-79, 145-154
**Severity:** CRITICAL

**Issue:** While `isRecordingFlag` uses AtomicBoolean, other fields modified in concurrent contexts are not thread-safe:
- Lines 70-78: `muxer`, `videoTrackIndex`, `isMuxerStarted` modified in `writeLoop()` (background thread)
- Lines 145-154: Same fields initialized in main thread
- Lines 76-78: `sps`, `pps`, `vps` accessed without synchronization

**Impact:** Data races on critical fields, potential null pointer exceptions, corrupted MP4 files.

**Suggested Fix:**
```kotlin
private val csdLock = Any()
private var sps: ByteArray? = null
private var pps: ByteArray? = null
private var vps: ByteArray? = null
private var csdExtracted = false

private fun extractCsd(nalUnits: List<Pair<Int, ByteArray>>) {
    synchronized(csdLock) {
        for ((nalType, nalData) in nalUnits) {
            // Extract logic
        }
    }
}
```

---

## 2. HIGH SEVERITY ISSUES

### 2.1 RecordingRepository - MediaMetadataRetriever Leak
**File:** `app/src/main/java/com/example/ndireceiver/data/RecordingRepository.kt`
**Lines:** 124-151
**Severity:** HIGH

**Issue:** If an exception is thrown after `retriever.setDataSource()`, the retriever might not be released properly in edge cases.

**Suggested Fix:** Ensure try-finally pattern is robust with proper exception handling in finally block.

---

### 2.2 NdiFinder - finderPtr Leak on Error
**File:** `app/src/main/java/com/example/ndireceiver/ndi/NdiFinder.kt`
**Lines:** 30-51
**Severity:** HIGH

**Issue:** If an exception occurs after `finderCreate()` returns non-zero but before the cleanup in `awaitClose`, the native pointer might not be properly released.

**Suggested Fix:** Use try-finally with explicit cleanup in both normal and error paths.

---

### 2.3 PlayerFragment - Alert Dialog Leak
**File:** `app/src/main/java/com/example/ndireceiver/ui/player/PlayerFragment.kt`
**Lines:** 81, 368-388
**Severity:** HIGH

**Issue:** The `errorDialog` is cached and could leak if the dialog is shown but fragment is destroyed before it's dismissed.

**Suggested Fix:**
```kotlin
override fun onDestroyView() {
    super.onDestroyView()
    handler.removeCallbacks(hideControlsRunnable)
    if (errorDialog?.isShowing == true) {
        try {
            errorDialog?.dismiss()
        } catch (e: Exception) {
            Log.w(TAG, "Error dismissing dialog", e)
        }
    }
    errorDialog = null
    requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
```

---

### 2.4 UncompressedVideoRenderer - Potential Bitmap Recycle Issue
**File:** `app/src/main/java/com/example/ndireceiver/media/UncompressedVideoRenderer.kt`
**Lines:** 121-138
**Severity:** HIGH

**Issue:** In `ensureBuffers()`, the bitmap is recycled and recreated, but synchronization scope should be clearly documented.

---

### 2.5 VideoDecoder - Input/Output Thread Lifetime Management
**File:** `app/src/main/java/com/example/ndireceiver/media/VideoDecoder.kt`
**Lines:** 103-109, 135-196
**Severity:** HIGH

**Issue:** Thread references are cleared before ensuring threads have fully stopped, creating a race window where frames might be submitted to dead threads.

**Suggested Fix:** Clear references and queue only AFTER threads are confirmed stopped with proper join() calls.

---

## 3. MEDIUM SEVERITY ISSUES

### 3.1 PlaybackViewModel - Mutex Ineffective for pause()
**File:** `app/src/main/java/com/example/ndireceiver/ui/playback/PlaybackViewModel.kt`
**Lines:** 304-311
**Severity:** MEDIUM

**Issue:** `pause()` method calls player methods without locking, creating inconsistency with other methods.

---

### 3.2 NdiReceiver - Race in disconnectSync()
**File:** `app/src/main/java/com/example/ndireceiver/ndi/NdiReceiver.kt`
**Lines:** 217-230
**Severity:** MEDIUM

**Issue:** `cleanup()` might deallocate `receiverPtr` while receive thread is still in native code.

---

### 3.3 SettingsRepository - Thread-Unsafe Singleton
**File:** `app/src/main/java/com/example/ndireceiver/data/SettingsRepository.kt`
**Lines:** 41-53
**Severity:** MEDIUM

**Issue:** Minor optimization issue in double-checked locking pattern (functional but could be cleaner).

---

### 3.4 VideoRecorder - Missing Close on Exception
**File:** `app/src/main/java/com/example/ndireceiver/media/VideoRecorder.kt`
**Lines:** 442-494
**Severity:** MEDIUM

**Issue:** Exception during `addTrack()` or `start()` should have more explicit cleanup.

---

### 3.5 NdiManager - Missing NdiReceiver Cleanup
**File:** `app/src/main/java/com/example/ndireceiver/ndi/NdiManager.kt`
**Lines:** 9-102
**Severity:** MEDIUM

**Issue:** No synchronization to prevent new receivers during shutdown.

---

### 3.6 PlayerViewModel - Decoder Null Check Race
**File:** `app/src/main/java/com/example/ndireceiver/ui/player/PlayerViewModel.kt`
**Lines:** 437-438
**Severity:** MEDIUM

**Issue:** `decoder?.submitFrame()` could encounter null decoder due to concurrent release.

---

### 3.7 PlaybackViewModel - Player Null Access Inconsistency
**File:** `app/src/main/java/com/example/ndireceiver/ui/playback/PlaybackViewModel.kt`
**Lines:** 358-361
**Severity:** MEDIUM

**Issue:** Inconsistent pattern for player null checks across methods.

---

### 3.8 VideoDecoder - Silent Exception Swallowing
**File:** `app/src/main/java/com/example/ndireceiver/media/VideoDecoder.kt`
**Lines:** 159-163, 191-195
**Severity:** MEDIUM

**Issue:** Exceptions in processing threads are logged but threads continue, potentially leaving decoder non-functional silently.

---

### 3.9 NdiReceiver - Swallowed Exceptions in Callback
**File:** `app/src/main/java/com/example/ndireceiver/ndi/NdiReceiver.kt`
**Lines:** 192-196
**Severity:** MEDIUM

**Issue:** Fatal exceptions are logged but loop continues.

---

## 4. RECOMMENDATIONS

### High-Priority Fixes:
1. **NdiSourceRepository**: Convert to StateFlow-based implementation
2. **PlayerViewModel**: Protect all frame callback code with `decoderLock`
3. **VideoRecorder**: Make CSD fields atomic or protected by lock
4. **NdiFinder**: Add proper cleanup in finally block
5. **PlayerFragment**: Dismiss dialog in `onDestroyView()`

### Testing Recommendations:
- Add stress tests for concurrent frame handling
- Test rapid connect/disconnect cycles
- Verify no native crashes during aggressive threading scenarios
- Monitor for memory leaks using Android Profiler

### Code Quality Improvements:
- Add `@ThreadSafe` annotations to thread-safe classes
- Document thread affinity for methods
- Use consistent patterns for resource cleanup

---

## Action Items

| Priority | Issue | File | Action |
|----------|-------|------|--------|
| P0 | Race condition | NdiSourceRepository.kt | Convert to StateFlow |
| P0 | Frame callback race | PlayerViewModel.kt | Expand decoderLock scope |
| P0 | CSD field races | VideoRecorder.kt | Add synchronization |
| P1 | Dialog leak | PlayerFragment.kt | Dismiss in onDestroyView |
| P1 | Native ptr leak | NdiFinder.kt | Add try-finally cleanup |
| P1 | Thread join race | VideoDecoder.kt | Wait for thread stop |
| P2 | Mutex inconsistency | PlaybackViewModel.kt | Use mutex for pause() |
| P2 | Disconnect race | NdiReceiver.kt | Brief join before cleanup |
