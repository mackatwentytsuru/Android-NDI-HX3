# Android NDI HX3 レシーバーアプリ開発仕様書

## プロジェクト概要

ライブエンターテイメント会場（キャバレー）向けの業務用NDI HX3映像受信・録画・再生アプリをAndroid向けに開発する。

### 背景
- 会場に設置された複数の大型タブレット（24.5インチ）でNDIカメラの映像をリアルタイム表示
- 既存の商用アプリには録画機能がなく、品質も低いため自作が必要
- 複数タブレットが同時に同じストリームを受信する運用

---

## ターゲットハードウェア

### 受信デバイス: FPD CP25-J1 タブレット
| 項目 | スペック |
|------|----------|
| 画面サイズ | 24.5インチ |
| 解像度 | 1920×1080 (FHD) |
| OS | Android 14 |
| チップセット | Rockchip RK3576 |
| CPU | Cortex-A72 x4 @2.2GHz + Cortex-A53 x4 @1.8GHz |
| GPU | Mali G52 MC3 |
| RAM | 8GB |
| ストレージ | 内蔵 + microSD対応 |
| ネットワーク | WiFi 6（有線LAN非搭載） |
| ビデオデコード | H.265 4K@120fps, H.264 4K@60fps（ハードウェア） |

### 映像ソース: FoMaKo K20UH PTZカメラ
| 項目 | スペック |
|------|----------|
| NDI規格 | NDI 6 / HX3 認証取得 |
| 最大出力 | **1080p@30fps**（Ethernetが100Mbpsのため） |
| コーデック | H.264 / H.265 |
| ビットレート | 20〜40 Mbps（可変） |
| 遅延 | 100ms以下 |

---

## 機能要件

### 1. NDIソース検出・接続
- [ ] ネットワーク上のNDIソースを自動検出（mDNS/Bonjour）
- [ ] NDI Discovery Server対応（異なるサブネットのソースも検出可能）
- [ ] ソース一覧をリスト表示
- [ ] ワンタップで接続/切断
- [ ] 接続状態のインジケーター表示

### 2. 映像表示
- [ ] フルスクリーン表示（24.5インチ画面に最適化）
- [ ] アスペクト比維持（レターボックス/ピラーボックス対応）
- [ ] 低遅延再生（100ms以下目標）
- [ ] フレームレート・解像度・ビットレートのOSD表示（トグル可能）
- [ ] 画面タップでUI表示/非表示切り替え

### 3. 録画機能
- [ ] ストリームをMP4ファイルとして録画
- [ ] **H.264/H.265パススルー録画**（再エンコードなし、CPU負荷ゼロ）
- [ ] 録画開始/停止ボタン
- [ ] 録画中インジケーター（RECマーク + 経過時間）
- [ ] 録画ファイルは内部ストレージまたはmicroSDに保存
- [ ] ファイル名自動生成（日時ベース）

### 4. 再生機能
- [ ] 録画ファイル一覧表示（サムネイル付き）
- [ ] 録画ファイルの再生
- [ ] シークバー対応
- [ ] 再生/一時停止/停止
- [ ] ファイル削除機能

### 5. 設定
- [ ] 保存先ディレクトリの選択
- [ ] 自動接続（前回接続したソースに自動再接続）
- [ ] 画面常時オン設定
- [ ] OSD表示設定

---

## 技術仕様

### 開発言語・フレームワーク
- **言語**: Kotlin
- **最小SDK**: API 26 (Android 8.0)
- **ターゲットSDK**: API 34 (Android 14)
- **ビルドシステム**: Gradle (Kotlin DSL)
- **アーキテクチャ**: MVVM + Repository パターン

### 主要ライブラリ

#### NDI受信: Devolay
```kotlin
// build.gradle.kts
dependencies {
    implementation("me.walkerknapp:devolay:2.1.1") {
        artifact {
            name = "devolay"
            type = "aar"
        }
    }
}
```

**Devolayについて:**
- NDI SDKのJava/Kotlinラッパー
- AARにネイティブライブラリ（arm64-v8a, armeabi-v7a, x86, x86_64）を同梱
- Maven Centralから配布
- ライセンス: LGPL-3.0（商用利用可）

**参考リポジトリ:**
- https://github.com/WalkerKnapp/devolay
- https://github.com/daubli/NdiMonitor （動作確認済みのAndroid実装例）

#### その他の依存関係
```kotlin
dependencies {
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    
    // RecyclerView（ソース一覧・録画一覧用）
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    
    // Glide（サムネイル表示用）
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
```

---

## アーキテクチャ設計

### データフロー
```
[NDI Source (K20UH)]
        │
        │ Network (WiFi)
        ▼
[NsdManager] ─────────────────┐
        │                     │
        │ Discovery           │ Discovery Server
        ▼                     ▼
[Devolay NDI Finder] ◄────────┘
        │
        │ NDI HX3 Stream (H.264/H.265 compressed)
        ▼
[Devolay NDI Receiver]
        │
        ├──────────────────────────────────┐
        │                                  │
        ▼                                  ▼
[MediaCodec]                        [MediaMuxer]
(Hardware Decode)                   (MP4 Recording)
        │                                  │
        ▼                                  ▼
[SurfaceView]                       [Storage]
(Display)                           (Internal/SD)
```

### パッケージ構成
```
com.example.ndireceiver/
├── MainActivity.kt
├── ui/
│   ├── main/
│   │   ├── MainFragment.kt
│   │   └── MainViewModel.kt
│   ├── player/
│   │   ├── PlayerFragment.kt
│   │   └── PlayerViewModel.kt
│   └── recordings/
│       ├── RecordingsFragment.kt
│       └── RecordingsViewModel.kt
├── ndi/
│   ├── NdiManager.kt           # NDI初期化・終了
│   ├── NdiFinder.kt            # ソース検出
│   ├── NdiReceiver.kt          # ストリーム受信
│   └── NdiSource.kt            # ソースデータクラス
├── media/
│   ├── VideoDecoder.kt         # MediaCodecラッパー
│   ├── VideoRecorder.kt        # MediaMuxerラッパー
│   └── VideoPlayer.kt          # 録画再生用
├── data/
│   ├── RecordingRepository.kt
│   └── SettingsRepository.kt
└── util/
    ├── FileUtils.kt
    └── TimeUtils.kt
```

---

## 実装詳細

### 1. NDI初期化（NdiManager.kt）

```kotlin
import me.walkerknapp.devolay.Devolay

object NdiManager {
    private var isInitialized = false
    
    fun initialize(): Boolean {
        if (isInitialized) return true
        
        // Devolay/NDI SDKの初期化
        isInitialized = Devolay.loadLibraries()
        return isInitialized
    }
    
    fun destroy() {
        // クリーンアップ処理
        isInitialized = false
    }
}
```

### 2. NDIソース検出（NdiFinder.kt）

```kotlin
import me.walkerknapp.devolay.DevolayFinder
import me.walkerknapp.devolay.DevolaySource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class NdiFinder {
    private var finder: DevolayFinder? = null
    
    fun startDiscovery(): Flow<List<NdiSource>> = flow {
        finder = DevolayFinder()
        
        while (true) {
            // ソースの変更を待機（タイムアウト: 1000ms）
            if (finder?.waitForSources(1000) == true) {
                val sources = finder?.currentSources?.map { source ->
                    NdiSource(
                        name = source.sourceName,
                        url = source.urlAddress
                    )
                } ?: emptyList()
                emit(sources)
            }
        }
    }
    
    fun stopDiscovery() {
        finder?.close()
        finder = null
    }
}

data class NdiSource(
    val name: String,
    val url: String
)
```

### 3. NDI受信・デコード（NdiReceiver.kt）

```kotlin
import me.walkerknapp.devolay.DevolayReceiver
import me.walkerknapp.devolay.DevolayVideoFrame
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface

class NdiReceiver(
    private val surface: Surface,
    private val onFrameReceived: ((DevolayVideoFrame) -> Unit)? = null
) {
    private var receiver: DevolayReceiver? = null
    private var decoder: MediaCodec? = null
    private var isReceiving = false
    
    fun connect(source: NdiSource) {
        // レシーバー作成
        receiver = DevolayReceiver.Builder()
            .sourceName(source.name)
            .colorFormat(DevolayReceiver.ColorFormat.UYVY) // または BGRA
            .build()
        
        startReceiving()
    }
    
    private fun startReceiving() {
        isReceiving = true
        
        // 受信ループ（別スレッドで実行）
        Thread {
            while (isReceiving) {
                val videoFrame = DevolayVideoFrame()
                
                // フレーム受信（タイムアウト: 5000ms）
                val frameType = receiver?.receiveCapture(videoFrame, null, null, 5000)
                
                when (frameType) {
                    DevolayReceiver.FrameType.VIDEO -> {
                        processVideoFrame(videoFrame)
                        onFrameReceived?.invoke(videoFrame)
                    }
                    DevolayReceiver.FrameType.NONE -> {
                        // タイムアウトまたは切断
                    }
                    else -> {}
                }
                
                // フレームリソース解放
                receiver?.freeReceiveCapture(videoFrame, null, null)
            }
        }.start()
    }
    
    private fun processVideoFrame(frame: DevolayVideoFrame) {
        // MediaCodecでデコードしてSurfaceに描画
        // 実装詳細は VideoDecoder.kt に委譲
    }
    
    fun disconnect() {
        isReceiving = false
        receiver?.close()
        receiver = null
    }
}
```

### 4. 録画（VideoRecorder.kt）

**重要: パススルー録画の実装**

NDI HX3はH.264/H.265で圧縮済みのため、**再エンコードせずにそのままMP4コンテナに格納**することでCPU負荷をゼロにできる。

```kotlin
import android.media.MediaMuxer
import android.media.MediaFormat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class VideoRecorder(
    private val outputDir: File
) {
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex: Int = -1
    private var isRecording = false
    private var startTime: Long = 0
    
    fun startRecording(videoFormat: MediaFormat): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date())
        val outputFile = File(outputDir, "NDI_$timestamp.mp4")
        
        muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        
        // ビデオトラック追加（元のフォーマットをそのまま使用）
        videoTrackIndex = muxer!!.addTrack(videoFormat)
        muxer!!.start()
        
        isRecording = true
        startTime = System.nanoTime()
        
        return outputFile
    }
    
    fun writeVideoFrame(data: ByteArray, presentationTimeUs: Long) {
        if (!isRecording || muxer == null) return
        
        val buffer = java.nio.ByteBuffer.wrap(data)
        val bufferInfo = android.media.MediaCodec.BufferInfo().apply {
            offset = 0
            size = data.size
            this.presentationTimeUs = presentationTimeUs
            flags = 0
        }
        
        muxer?.writeSampleData(videoTrackIndex, buffer, bufferInfo)
    }
    
    fun stopRecording() {
        isRecording = false
        muxer?.stop()
        muxer?.release()
        muxer = null
    }
    
    fun isRecording() = isRecording
}
```

### 5. 映像表示（PlayerFragment.kt）

**SurfaceViewを使用する理由:**
- TextureViewより低遅延
- GPUメモリ効率が良い
- HDR/DRM対応
- ハードウェアオーバーレイ活用可能

```kotlin
class PlayerFragment : Fragment() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var viewModel: PlayerViewModel
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return FrameLayout(requireContext()).apply {
            surfaceView = SurfaceView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            addView(surfaceView)
            
            // UI要素（録画ボタン等）もここに追加
        }
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                viewModel.setSurface(holder.surface)
            }
            
            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {}
            
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                viewModel.setSurface(null)
            }
        })
        
        // 画面常時オン
        requireActivity().window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }
}
```

---

## UI設計

### メイン画面
```
┌─────────────────────────────────────────────────────────┐
│ [≡] NDI Receiver                            [⚙️ Settings]│
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │                                                 │   │
│  │                                                 │   │
│  │              NDIソース一覧                      │   │
│  │                                                 │   │
│  │  ┌─────────────────────────────────────────┐   │   │
│  │  │ 📹 FoMaKo K20UH (CAMERA1)              │   │   │
│  │  │    1920x1080 @ 30fps                    │   │   │
│  │  └─────────────────────────────────────────┘   │   │
│  │                                                 │   │
│  │  ┌─────────────────────────────────────────┐   │   │
│  │  │ 📹 OBS Studio (PC-MAIN)                │   │   │
│  │  │    1920x1080 @ 60fps                    │   │   │
│  │  └─────────────────────────────────────────┘   │   │
│  │                                                 │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
│  [🔄 更新]                          [📁 録画一覧]       │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### プレイヤー画面
```
┌─────────────────────────────────────────────────────────┐
│                                                         │
│                                                         │
│                                                         │
│                   [映像表示領域]                        │
│                   (フルスクリーン)                      │
│                                                         │
│                                                         │
│                                                         │
├─────────────────────────────────────────────────────────┤
│ [←戻る]  FoMaKo K20UH     ● REC 00:05:23    [⏹️停止]   │
│          1080p30 | 28.5 Mbps | H.265                    │
│                                                         │
│          [⏺️ 録画開始]              [📁 録画一覧]       │
└─────────────────────────────────────────────────────────┘
```

### 録画一覧画面
```
┌─────────────────────────────────────────────────────────┐
│ [←戻る] 録画一覧                                        │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌──────┬────────────────────────────────────────────┐ │
│  │ 🎬   │ NDI_20260129_143052.mp4                    │ │
│  │      │ 2026/01/29 14:30 | 00:15:23 | 1.2GB        │ │
│  │      │                              [▶️][🗑️]      │ │
│  └──────┴────────────────────────────────────────────┘ │
│                                                         │
│  ┌──────┬────────────────────────────────────────────┐ │
│  │ 🎬   │ NDI_20260129_120015.mp4                    │ │
│  │      │ 2026/01/29 12:00 | 00:45:12 | 3.8GB        │ │
│  │      │                              [▶️][🗑️]      │ │
│  └──────┴────────────────────────────────────────────┘ │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

## 注意事項・制約

### 1. Devolayの制約
- **x86_64エミュレータでは動作しない**（x86またはARMエミュレータ、実機を使用）
- 初回ビルド時にネイティブライブラリのダウンロードに時間がかかる場合がある

### 2. RK3576固有の考慮事項
- MediaCodecでH.265デコード時に緑色のアーティファクトが出る場合がある
- その場合はRockchipのMPP（Media Process Platform）ライブラリの使用を検討
- MPPは `/system/lib64/librockchip_mpp.so` として提供されている可能性

### 3. NDI HX3の特性
- 圧縮済みストリーム（H.264/H.265）
- Devolayで受信した生データを直接MediaMuxerに渡すことでパススルー録画が可能
- フルNDI（非圧縮）とは異なり、デコードが必須

### 4. ネットワーク考慮事項
- WiFi環境では遅延やパケットロスに注意
- 可能であれば5GHz帯を使用
- 複数タブレットが同時受信する場合、NDI Bridgeでの複製を推奨（別途PC必要）

### 5. ライセンス表記
アプリ内またはAbout画面に以下を記載：
```
NDI® is a registered trademark of Vizrt NDI AB.
```

---

## 開発フェーズ

### Phase 1: 基本受信・表示（2-3日）
- [ ] プロジェクトセットアップ（Gradle, Devolay依存関係）
- [ ] NDI初期化・終了処理
- [ ] ソース検出・一覧表示
- [ ] 基本的な映像受信・表示

### Phase 2: 録画機能（2-3日）
- [ ] MediaMuxerによるMP4録画
- [ ] パススルー録画の実装
- [ ] 録画UI（開始/停止ボタン、インジケーター）

### Phase 3: 再生・ファイル管理（1-2日）
- [ ] 録画ファイル一覧
- [ ] 動画再生機能
- [ ] ファイル削除機能

### Phase 4: UI/UX改善（1-2日）
- [ ] フルスクリーン最適化
- [ ] OSD表示
- [ ] 設定画面
- [ ] エラーハンドリング

---

## テスト項目

### 機能テスト
- [ ] NDIソースの検出（同一サブネット）
- [ ] NDIソースへの接続・切断
- [ ] 映像の表示（1080p30）
- [ ] 録画の開始・停止
- [ ] 録画ファイルの再生
- [ ] 長時間録画（1時間以上）
- [ ] 複数回の接続・切断サイクル

### 性能テスト
- [ ] 遅延測定（目標: 100ms以下）
- [ ] CPU使用率（録画中含む）
- [ ] メモリ使用量
- [ ] 発熱状況

### 互換性テスト
- [ ] FPD CP25-J1での動作確認
- [ ] FoMaKo K20UHからの受信確認
- [ ] WiFi環境での安定性

---

## 参考リソース

### 公式ドキュメント
- NDI SDK Documentation: https://docs.ndi.video/
- Devolay GitHub: https://github.com/WalkerKnapp/devolay
- Android MediaCodec: https://developer.android.com/reference/android/media/MediaCodec
- Android MediaMuxer: https://developer.android.com/reference/android/media/MediaMuxer

### 参考実装
- NdiMonitor (Android): https://github.com/daubli/NdiMonitor
- NDIPlayer (Android TV): https://github.com/dreamgen/NDIPlayer

### NDI SDKライセンス
- Standard SDK: ロイヤリティフリー、商用利用可
- 要件: ndi.videoへのリンク + 商標表記のみ
