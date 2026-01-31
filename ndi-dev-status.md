# NDI HX3 Development Status

**Last Updated:** 2026-01-31

## Current Phase: ALL COMPLETE - Ready for Device Testing

### Recent Fixes (2026-01-31)
- [x] **Kotlin Syntax Error**: Fixed `roundToInt()` extension function usage in PlayerFragment.kt (lines 398, 401)
  - Changed from: `kotlin.math.roundToInt(expression)`
  - Changed to: `(expression).roundToInt()`
  - Build verified: APK generated successfully at 2026-01-31 12:36:41

- [x] **Debug Logging Added**: Right edge pixel cutoff investigation (UncompressedVideoRenderer.kt)
  - Added logging to monitor Canvas vs Bitmap dimensions
  - Added logging for srcRect and dstRect coordinates
  - Purpose: Investigate 1-2 pixel right edge clipping in test pattern
  - Location: Lines 112-114 (after lockCanvas, before drawBitmap)
  - Ready for test build to verify dimensions

## Phase 1: Basic Receive & Display

| Task | Status |
|------|--------|
| Implementation | ✅ Complete |
| Codex Review | ✅ Complete |
| Gemini Research | ✅ Complete |
| Bug Fixes | ✅ Complete |
| Device Testing | ⏳ Pending |

### Completed Tasks
- [x] Gradle project setup with Devolay 2.1.1
- [x] NdiManager: SDK initialization
- [x] NdiFinder: Source discovery with Flow
- [x] NdiReceiver: Stream connection
- [x] VideoDecoder: MediaCodec H.264/H.265
- [x] MainFragment: Source list UI
- [x] PlayerFragment: Basic video display

### Bug Fixes Applied (All Complete)
- [x] **Use-after-free**: ByteBuffer now copied before freeVideo()
- [x] **DevolaySource loss**: NdiSourceRepository created for source sharing
- [x] **Disconnect race**: Timeout adjusted (1s receive, 3s join)
- [x] **NdiFinder spam**: Now emits only on actual changes
- [x] **Thread safety**: PlayerViewModel - @Volatile, synchronized, local capture
- [x] **onCleared scope**: ~~runBlocking~~ → disconnectSync() (non-blocking)
- [x] **VideoDecoder join**: Thread interrupt before join
- [x] **ANR fix**: Removed runBlocking from onCleared() (2026-01-30)
- [x] **std::bad_alloc fix**: Idempotent disconnect() + removed duplicate call from onStop() (2026-01-30)
- [ ] **fourCC codec detection**: Needs verification with real hardware

### Codex Review Findings (ndi-output-codex.md)

**Critical Issues Found:**
1. Use-after-free of NDI video buffers → ✅ **FIXED**
2. DevolaySource lost in PlayerFragment → ✅ **FIXED**
3. fourCC == H.264/H.265 assumption → ⚠️ **NEEDS HARDWARE VERIFICATION**
4. Disconnect/cleanup race condition → ✅ **FIXED**

**High Priority Issues:**
5. onCleared() viewModelScope issue → ✅ **FIXED**
6. VideoDecoder join timeout race → ✅ **FIXED**
7. NdiFinder emits every second → ✅ **FIXED**
8. PlayerViewModel thread safety → ✅ **FIXED**

### Gemini Research Findings (ndi-output-gemini.md)

- Devolay setup confirmed correct
- MediaCodec H.265: Use async mode, KEY_LOW_LATENCY
- RK3576: Use SurfaceView (not TextureView), watch for green artifacts
- Rockchip MPP abstracted via MediaCodec (no direct access needed)

### Files Created
```
app/src/main/java/com/example/ndireceiver/
├── ndi/
│   ├── NdiManager.kt
│   ├── NdiFinder.kt
│   ├── NdiReceiver.kt
│   ├── NdiSource.kt
│   └── NdiSourceRepository.kt  ← NEW
├── media/
│   └── VideoDecoder.kt
└── ui/
    ├── main/
    │   ├── MainFragment.kt
    │   ├── MainViewModel.kt
    │   └── NdiSourceAdapter.kt
    └── player/
        ├── PlayerFragment.kt
        └── PlayerViewModel.kt
```

---

## Multi-Agent Workflow

| Agent | Output File | Status |
|-------|-------------|--------|
| Claude | ndi-output-claude.md | ✅ |
| Codex | ndi-output-codex.md | ✅ |
| Gemini | ndi-output-gemini.md | ✅ |

### Skill Commands

| Command | Purpose |
|---------|---------|
| `/ndi:phase <n>` | Execute dev phase with all 3 AIs |
| `/ndi:fix <issue>` | Fix issue via background Claude |
| `/ndi:review [files]` | Review via background Codex |
| `/ndi:research <topic>` | Research via background Gemini |
| `/ndi:implement <feature>` | Implement via background Claude |

---

## Next Steps

1. **Device testing** on FPD CP25-J1 tablet:
   - Build and install APK
   - NDI source discovery test
   - Video streaming test
   - Green artifact check (RK3576 specific)
   - Verify fourCC codec detection with real NDI HX3 source

2. **Begin Phase 2** (Recording) after device testing:
   - VideoRecorder with MediaMuxer passthrough
   - Recording UI controls (start/stop, duration)
   - Storage permissions (WRITE_EXTERNAL_STORAGE / MediaStore)
   - Recording list fragment

---

## Phase 2: Recording

| Task | Status |
|------|--------|
| Gemini Research | ✅ Complete |
| Implementation | ✅ Complete |
| Codex Review | ✅ Complete |
| Bug Fixes | ✅ Complete |
| Device Testing | ⏳ Pending |

### Completed Tasks
- [x] MediaMuxer passthrough research (Gemini)
- [x] VideoRecorder.kt - H.264/H.265 passthrough recording
- [x] NAL unit parsing (CSD extraction, keyframe detection)
- [x] PlayerViewModel recording integration
- [x] PlayerFragment recording UI
- [x] Recording indicator and button

### Bug Fixes Applied
- [x] NAL unit start code detection - boundary check fixed
- [x] ByteBuffer race condition - duplicate() before rewind()
- [x] Recording state race - AtomicBoolean with compareAndSet()

### Files Created
- `app/src/main/java/com/example/ndireceiver/media/VideoRecorder.kt`

### Files Modified
- `PlayerViewModel.kt` - RecordingState, start/stop methods
- `PlayerFragment.kt` - Recording button, duration display
- `fragment_player.xml` - REC indicator, button styling
- `strings.xml` - Recording-related strings
- `colors.xml` - Recording button color

## Phase 3: Playback & File Management

| Task | Status |
|------|--------|
| Gemini Research | ✅ Complete |
| Implementation | ✅ Complete |
| Code Review | ✅ Complete |
| Bug Fixes | ✅ Complete |
| Device Testing | ⏳ Pending |

### Completed Tasks
- [x] ExoPlayer vs MediaPlayer research (Gemini)
- [x] Thumbnail generation research (Gemini)
- [x] RecordingRepository - file listing, metadata
- [x] RecordingAdapter - RecyclerView with thumbnails
- [x] RecordingsFragment/ViewModel - recordings list UI
- [x] PlaybackFragment/ViewModel - ExoPlayer playback
- [x] SeekBar with position updates
- [x] File deletion with confirmation

### Bug Fixes Applied
- [x] ExoPlayer memory leak - Fragment lifecycle release
- [x] Concurrent player access - Mutex synchronization
- [x] Handler callbacks - isViewDestroyed flag + cleanup

### Files Created
- `RecordingRepository.kt` - Recording data class, file operations
- `RecordingAdapter.kt` - RecyclerView adapter with Glide
- `RecordingsViewModel.kt` - State management
- `RecordingsFragment.kt` - Recordings list UI
- `PlaybackViewModel.kt` - ExoPlayer control
- `PlaybackFragment.kt` - Playback UI
- `fragment_recordings.xml`, `item_recording.xml`, `fragment_playback.xml`

### Dependencies Added
- ExoPlayer (Media3): 1.2.1
- Glide: 4.16.0

## Phase 4: UI/UX Polish

| Task | Status |
|------|--------|
| Implementation | ✅ Complete |
| Code Review | ✅ Complete |
| Bug Fixes | ✅ Complete |
| Device Testing | ⏳ Pending |

### Completed Tasks
- [x] Fullscreen immersive mode
- [x] OSD display (resolution, fps, bitrate, codec)
- [x] Settings screen (auto-reconnect, screen-on, OSD toggle)
- [x] SettingsRepository with SharedPreferences
- [x] Auto-reconnect on connection loss
- [x] Connection error dialog with retry

### Bug Fixes Applied
- [x] SharedPreferences thread safety - commit() instead of apply()
- [x] Fullscreen lifecycle - setupScreenSettings() in onResume()
- [x] Auto-reconnect cancellation - Job tracking

### Files Created
- `SettingsRepository.kt` - App settings with SharedPreferences
- `SettingsFragment.kt` - Settings UI
- `SettingsViewModel.kt` - Settings state
- `fragment_settings.xml` - Settings layout

---

## Phase 5: NDI SDK v6 Native Integration

**目的:** Devolay wrapper から公式 NDI SDK v6 for Android への移行

| Task | Status |
|------|--------|
| NDI SDK v6 インストール | ✅ Complete |
| SDK ヘッダ分析 | ✅ Complete |
| Gemini リサーチ | ✅ Complete |
| Codex JNI 書き換え | ✅ Complete |
| ビルドテスト | ✅ Complete |
| 非圧縮フレームレンダラー | ✅ Complete |
| Gemini コードレビュー | ✅ Complete |
| Codex コードレビュー | ✅ Complete |
| @Volatile 修正 | ✅ Complete |
| ユニットテスト作成 | ✅ Complete (41 tests passing) |
| デバイステスト | ⏳ Pending |

### 🚨 Critical Discovery: SDK が HX3 を内部でデコード

**Gemini リサーチで判明した重要事項:**

NDI SDK v6 は HX3 ストリーム（H.264/H.265）を**内部で自動デコード**する。
アプリが受け取るのは**非圧縮フレーム**（UYVY, BGRA 等）であり、圧縮データではない。

**アーキテクチャ変更:**
```
旧: NDI HX3 → Devolay → H.264/H.265 → MediaCodec → Surface
新: NDI HX3 → SDK Internal Decode → UYVY/BGRA → OpenGL ES/Canvas → Surface
```

**影響:**
- ❌ `VideoDecoder.kt`（MediaCodec）は不要
- ❌ NAL unit パース、CSD 処理は不要
- ✅ SDK から直接ピクセルデータを受信
- ✅ OpenGL ES または Canvas でレンダリング

### Completed Tasks
- [x] NDI SDK v6 for Android をインストール（Install_NDI_SDK_v6_Android/）
- [x] SDK ヘッダファイルを分析
  - `Processing.NDI.Lib.h` - メイン API
  - `Processing.NDI.Find.h` - ソース検出
  - `Processing.NDI.Recv.h` - ビデオ受信
  - `Processing.NDI.structs.h` - データ構造体
- [x] NDI SDK headers を `app/src/main/cpp/include/` にコピー（17ファイル）
- [x] Gemini で SDK v6 統合リサーチ（ndi-output-gemini.md Phase 5）
- [x] Codex で JNI wrapper を SDK v6 対応に全面書き換え
  - スレッドセーフな Finder/Receiver ラッパー（pthread_mutex）
  - VideoFrame - DirectByteBuffer でゼロコピー
  - AudioFrame - planar → interleaved 変換
  - ReceiverPerformance - 接続品質メトリクス
- [x] CMakeLists.txt を更新（Pure C ビルド、libndi.so リンク）
- [x] ビルドテスト成功
  - Gradle 8.5
  - NDK 25.1.8937393
  - CMake 3.22.1
  - APK: `app/build/outputs/apk/debug/app-debug.apk`
- [ ] デバイステスト

### SDK API 使用予定
```c
// 初期化
NDIlib_initialize()
NDIlib_destroy()
NDIlib_version()

// ソース検出
NDIlib_find_create_v2()
NDIlib_find_destroy()
NDIlib_find_wait_for_sources()
NDIlib_find_get_current_sources()

// 受信
NDIlib_recv_create_v3()
NDIlib_recv_destroy()
NDIlib_recv_connect()
NDIlib_recv_capture_v2()
NDIlib_recv_free_video_v2()
NDIlib_recv_get_performance()
```

### Files Created/Modified

**New Files:**
- `app/src/main/java/com/example/ndireceiver/media/UncompressedVideoRenderer.kt`
  - BGRA/BGRX/RGBA/RGBX/UYVY フレームのレンダリング
  - BT.601 YUV→BGRA 色変換
  - スレッドセーフ（synchronized renderLock）
  - Surface.lockCanvas/unlockCanvasAndPost

**Test Files:**
- `app/src/test/java/com/example/ndireceiver/media/UncompressedVideoRendererTest.kt`
  - BT.601 色変換テスト（黒、白、赤、緑、青）
  - clamp8、normalizeStride、validateSizes テスト
- `app/src/test/java/com/example/ndireceiver/ui/player/PlayerViewModelTest.kt`
  - 状態管理テスト
  - ByteBuffer コピーテスト
  - ビットレートフォーマットテスト

**Modified Files:**
- `app/src/main/cpp/ndi_wrapper.c` - JNI wrapper 全面書き換え
- `app/src/main/cpp/CMakeLists.txt` - include/link パス更新
- `app/src/main/java/com/example/ndireceiver/ndi/NdiReceiver.kt` - colorFormat を BGRX_BGRA に変更
- `app/src/main/java/com/example/ndireceiver/ui/player/PlayerViewModel.kt`
  - UncompressedVideoRenderer 統合
  - 圧縮/非圧縮フレームのルーティング
  - @Volatile 追加（currentVideoWidth, currentVideoHeight, currentIsHevc）

### Code Review Summary

**Gemini Review (ndi-output-gemini.md):**
- UncompressedVideoRenderer: ✅ スレッドセーフ、メモリ管理、BT.601変換 全て正しい
- PlayerViewModel: ⚠️ @Volatile 不足 → ✅ 修正済み

**Test Results:**
- 41 tests passing
- Coverage: Color conversion, buffer validation, state management

### SDK Location
```
Install_NDI_SDK_v6_Android/NDI SDK for Android/
├── include/            ← Headers (copied to app/src/main/cpp/include/)
├── lib/arm64-v8a/     ← libndi.so (already in jniLibs)
└── examples/C++/      ← Reference implementation
```

---

## Phase 6: V0 UI Integration & Testing

| Task | Status |
|------|--------|
| V0 UI Component Download | ✅ Complete |
| Project Analysis (Codex) | ✅ Complete |
| Integration Strategy (Claude) | ✅ Complete |
| Test Suite Generation (Gemini) | ✅ Complete |
| V0 UI Installation | ⏳ Manual steps required |
| WebView Integration | 📋 Planned |
| Test Implementation | 📋 Planned |

### Current Status (2026-01-30)

**3 Parallel Tasks Completed:**

1. **Codex - V0 UI Setup** ✅
   - Analyzed Next.js 16 + React 19 project structure
   - Identified pnpm as package manager
   - Documented manual installation steps (sandbox blocked npm/node/pnpm)
   - Ready for `pnpm install && pnpm dev`

2. **Claude - Integration Strategy** ✅
   - Comprehensive integration plan written to `ndi-output-claude.md`
   - **Recommended: Option 3 - Static Export + WebView**
   - 5-phase implementation plan (3-4 days total)
   - Detailed JavaScriptInterface API design
   - Testing strategy included

3. **Gemini - Test Suite Generation** ✅
   - Comprehensive test skeletons for 100% coverage target
   - NDI layer: NdiManager, NdiReceiver, NdiFinder, NdiSourceRepository
   - Media layer: VideoDecoder, VideoRecorder
   - UI layer: All ViewModels and Fragments
   - Test dependencies documented

### V0 UI Project Details

**Location:** `ndi-receiver-app/`

**Stack:**
- Next.js 16.0.10 + React 19.2.0
- shadcn/ui (Radix UI components)
- Tailwind CSS v4
- pnpm package manager

**Screens:**
- Main Screen - NDI source discovery/selection
- Player Screen - Video playback controls
- Recordings Screen - Recording management
- Settings Screen - App configuration

**Features:**
- Dark theme optimized for broadcast
- English/Japanese i18n
- React Context state management
- Professional broadcast aesthetic

### Integration Strategy Summary

**Selected Approach:** Static Export + WebView Embedding

**Workflow:**
1. Configure Next.js for static export (`output: 'export'`)
2. Build static files to `out/` directory
3. Copy to Android `assets/web/`
4. Create WebView Fragment with JavaScriptInterface bridge
5. Implement bidirectional communication (Kotlin ↔ JavaScript)

**Benefits:**
- Beautiful professional UI without full rewrite
- Native NDI performance preserved
- Offline operation (no network dependency)
- Low memory overhead
- Easy maintenance

### Test Coverage Plan

**Generated Test Files:**
- `NdiManagerTest.kt` - Discovery, connection, error handling
- `NdiReceiverTest.kt` - Frame capture, buffer management
- `NdiFinderTest.kt` - Source discovery, deduplication
- `NdiSourceRepositoryTest.kt` - Caching, persistence
- `VideoDecoderTest.kt` - H.264/H.265 decoding
- `VideoRecorderTest.kt` - Recording, muxer, file I/O
- `MainViewModelTest.kt`, `PlaybackViewModelTest.kt`, etc.
- `MainFragmentTest.kt`, `PlayerFragmentTest.kt` (Robolectric)

**Required Dependencies:**
```kotlin
testImplementation("junit:junit:4.13.2")
testImplementation("androidx.arch.core:core-testing:2.2.0")
testImplementation("io.mockk:mockk:1.13.8")
testImplementation("org.robolectric:robolectric:4.11.1")
testImplementation("androidx.fragment:fragment-testing:1.6.2")
```

### Next Steps

1. **Manual V0 Setup:**
   ```bash
   cd ndi-receiver-app
   pnpm install
   pnpm dev  # Test at http://localhost:3000
   ```

2. **Implement Tests:**
   - Copy test skeletons from Gemini output
   - Run `./gradlew test`
   - Measure coverage with `./gradlew testDebugUnitTestCoverage`

3. **WebView Integration:**
   - Follow Claude's integration plan in `ndi-output-claude.md`
   - Phase 1: Static export setup
   - Phase 2: WebView Fragment + JavaScriptInterface
   - Phase 3: Bidirectional communication

4. **Device Testing:**
   - Test on FPD CP25-J1 tablet
   - Verify WebView performance
   - Test touch interactions on 24.5" screen
