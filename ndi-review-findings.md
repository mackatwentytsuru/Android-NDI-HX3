# NDI HX3 Receiver — マルチエージェント・コードレビュー結果

**レビュー日:** 2026-05-30
**ブランチ:** `claude/agex3-android-app-MpoEz`
**手法:** 4並行レビューエージェント（Native/JNI、NDI Kotlin、Media、UI/Build）による静的解析

> ⚠️ ビルド・実機検証はこのクラウド環境では不可（Google Maven / Android SDK が遮断）。本レポートは静的解析ベース。
> ローカル環境で各GitHub Issueを解決してください。
> 別途、最重要の根本原因（HXデコードライブラリ欠落）は `ndi-diagnosis-hx3.md` を参照。

---

## 🔴 Critical（黒画面・クラッシュ・データ破損に直結）

### C0. HX デコードライブラリ欠落（最有力の黒画面原因）
`libndihx.so` / `libavcodec-ndi.so.58` / `libavutil-ndi.so.56` / `libswscale-ndi.so.5` が jniLibs に無い。
詳細は `ndi-diagnosis-hx3.md`。

### C1. Use-after-free: ネイティブフレームバッファを解放後に使用
- `VideoFrameData.data` は `receiverCaptureVideo` が返すネイティブ直結 `ByteBuffer`。`onVideoFrame` から戻ると `NdiReceiver.kt:198` が即 `receiverFreeVideo` で解放する。
- **VideoDecoder.kt:117-149** `submitFrame` はフレームをキューに積み、別スレッド（Decoder-Input）で後から `data` を読む → 解放済みメモリ参照。
- **VideoRecorder.kt:166-177, 210-217** 非圧縮エンコード経路は `frame.data` をコピーせずキューへ → 書き込みスレッドで解放済み参照。
- **PlayerViewModel.kt:492-497** `copyByteBuffer` が定義済みだが未使用（コピー意図が未実装）。
- **NdiReceiver.kt:193-199** `onVideoFrame` が例外を投げると free がスキップされフレームリーク。free 条件が `receiverPtrAtomic.get()` の再読みで、teardown と競合するとリーク。
- **Fix:** `onVideoFrame` 内（受信スレッド・解放前）で必ずヒープに deep-copy してから各コンシューマへ渡す。free は捕捉した元の `ptr` を使い try/finally で必ず実行。

### C2. レシーバ破棄がブロッキング・キャプチャと競合（ANR / UAF / 二重解放）
- **ndi_wrapper.c:639-647, 789-797** `wrapper->mutex` を保持したまま 1000ms ブロックする `NDIlib_recv_capture_v2` を呼ぶ。`disconnect/destroy/getPerformance/setSurface` 等が最大1秒ブロック → ANR。`receiverDestroy` がキャプチャ中の mutex/wrapper を解放 → UAF。
- **ndi_wrapper.c:636,728-757,899-905** フレームハンドルが生の `recv` を保持。レシーバ破棄後に `receiverFreeVideo/Audio` すると dangling → UAF / 二重解放。
- **NdiReceiver.kt:245-300** `disconnectSync` の join は 500ms < キャプチャ 1000ms。`interrupt()` はネイティブブロックを解除できないため、`receiverDestroy` がライブスレッドと競合。
- **NdiReceiver.kt:99-149** `connect/disconnect/setSurface` を直列化する単一ロックが無く、ポインタ/スレッド/状態を競合更新。
- **Fix:** キャプチャ中は mutex を保持しない（capture/free は SDK 的にスレッドセーフ）。キャプチャ timeout を 50-100ms に短縮し、`isReceiving` を頻繁にチェック。スレッド終了確認後にのみ `receiverDestroy`。連結ライフサイクルを単一 Mutex で直列化。フレーム未解放中は破棄禁止（refcount）。

### C3. VideoDecoder に CSD（SPS/PPS/VPS）未設定 — H.264/H.265 デコードが始まらない
- **VideoDecoder.kt:77-93,135-158** `createVideoFormat(mime,w,h)` のみで `csd-0/csd-1` 無し、全フレーム `flags=0`。in-band CSD が無い/タグ無しだと設定・デコード失敗。
- **Fix:** 最初のフレームの NAL を解析して SPS/PPS/VPS を抽出し format に設定、または `BUFFER_FLAG_CODEC_CONFIG` で先頭投入。キーフレームから開始。

### C4. mDNS ソース発見が Wi-Fi で失敗 — MulticastLock 未取得
- **NdiReceiverApplication.kt:25-57**（および全コード）`CHANGE_WIFI_MULTICAST_STATE` 権限はあるが `WifiManager.createMulticastLock().acquire()` を呼んでいない。Wi-Fi のマルチキャストフィルタで mDNS が届かず、ソース 0 件になりがち。
- **Fix:** 発見開始時に MulticastLock を取得、停止時に release。

---

## 🟠 High

### H1. JNI 例外チェック欠如（abort 要因）
- **ndi_wrapper.c:425-438,686,700-714,855-865,956-966** `NewObject/NewDirectByteBuffer/NewStringUTF/SetObjectArrayElement` 後に `ExceptionCheck/Clear` が無い。保留例外のまま JNI 呼び出し継続で abort。
- **Fix:** 各 JNI 呼び出し後に `ExceptionCheck`、早期 return 経路で `ExceptionClear`。ループは `PushLocalFrame/PopLocalFrame` + `EnsureLocalCapacity`。

### H2. finderGetSources が非null `Array<String>` に null を返しうる
- **ndi_wrapper.c:405-429 / NdiNative.kt:116** C 側は複数経路で NULL を返すが Kotlin は非null宣言 → NPE クラッシュ。
- **Fix:** Kotlin を `Array<String>?` にするか、C 側で常に長さ0配列を返す。

### H3. オーディオ de-interleave が channel_stride==0 で破綻
- **ndi_wrapper.c:836-841** `channel_stride_in_bytes==0`（合法・既定値）だと全チャンネルがch0をエイリアス。
- **Fix:** `stride = channel_stride ? channel_stride : no_samples*sizeof(float)`。

### H4. 受信ループが 100% CPU でビジースピンしうる
- **NdiReceiver.kt:160-224** ネイティブが即 null を返すと sleep 無しで回り続ける。例外時も backoff 無し。
- **Fix:** null/例外時に短い sleep/backoff。

### H5. 接続ロスト検知が毎秒発火し、再接続も状態遷移もしない
- **NdiReceiver.kt:208-216** `onConnectionLost` 後に `isReceiving`/state を変えず、毎ティック発火。`isConnected()` が嘘をつく。
- **Fix:** 一度だけ発火（フラグ）、`_connectionState` を遷移、カウンタリセット。

### H6. Canvas ソフトウェア描画が HD/4K に追従できず受信スレッドを止める
- **UncompressedVideoRenderer.kt:72-132** フレーム毎に CPU色変換+Bitmapコピー+`lockCanvas`+スケーリングblit。50/60fps・4Kで破綻し、`onVideoFrame` から同期呼び出しのため受信スレッドが詰まる。
- **Fix:** GPU 描画（GLES/SurfaceTexture、ImageReader/codec→Surface）へ。最低でも変換を受信スレッド外へ。

### H7. レンダラ描画の正しさ: アスペクト比ストレッチ / 毎フレームDEBUGログ / UYVY奇数幅
- **UncompressedVideoRenderer.kt:118-121** src全体→canvas全体に引き伸ばし（歪み）。
- **UncompressedVideoRenderer.kt:113-115** 毎フレーム `Log.d`（割り当て・遅延）。
- **UncompressedVideoRenderer.kt:266-277 / ColorSpaceConverter.kt:65-83** UYVYが2px単位で奇数幅の最終列を欠落（右端アーティファクト）。
- **Fix:** レターボックス計算で比維持、DEBUGログ削除、奇数幅の末尾画素を処理。

### H8. Surface ライフサイクルの TOCTOU と二重/デッドな描画経路
- **PlayerViewModel.kt:178-194,395-433** `onVideoFrame`（受信スレッド）が `surface`/`decoder.initialize`/`render` を触る一方、`surfaceDestroyed`→`setSurface(null)`（メインスレッド）が release。null チェックと使用の間で解放されクラッシュしうる。
- **NdiReceiver.kt:235-239 / PlayerViewModel.kt:178** `NdiReceiver.setSurface`（`ptr==0` で false）は UI から呼ばれず実質デッドコード。ネイティブ描画とKotlin描画の二重経路が未整理。
- **Fix:** Surface set/release とフレーム消費を単一ロック/単一スレッドで直列化。描画経路を一本化し、未使用 `setSurface` は整理。

### H9. フォアグラウンドサービス無し — バックグラウンドで捕捉/録画が停止
- **AndroidManifest.xml** `<service>`/`FOREGROUND_SERVICE*` 無し、WakeLock 無し。背景化で受信・録画が凍結/プロセスkill→録画破損。
- **Fix:** 捕捉/録画を foreground Service へ移し、`FOREGROUND_SERVICE(_DATA_SYNC)` と `foregroundServiceType` を宣言。スコープ外なら明記。

### H10. ネイティブ .so パッケージングと Android 15 の 16KB ページ整合
- **app/build.gradle.kts** `packaging{}` 無し。`-Wl,-z,max-page-size=16384` 未指定。prebuilt `libndi.so`(5.6.1) の16KB整合も未確認。targetSdk35化で16KBデバイスのロード失敗リスク。AGP 8.2.2/Kotlin 1.9.22/SDK34 と全体的に古い。
- **Fix:** `packaging{ jniLibs{ ... } }` 追加、リンクフラグ追加、16KB整合の v6 lib に更新、AGP/SDK を 35 へ。

---

## 🟡 Medium

### M1. 録画/Muxer の堅牢性
- **VideoRecorder.kt:166-177,230-232,262** passthrough が二重〜三重コピー。
- **VideoRecorder.kt:370-419** CSD未到達で停止すると 0サンプルで `muxer.stop()` が例外、0バイト/無効mp4。
- **UncompressedVideoEncoder.kt:107,132-133** fps=30/8Mbps 固定（50/60fps ソースで速度/タイムスタンプ不整合）。`frameRateN/D` 未使用。
- **VideoDecoder.kt:202-219** `reconfigure` 未呼び出し（解像度/コーデック変化に未対応）。
- **VideoDecoder.kt:52-54,224-231** デコード統計が非同期で torn read。
- **VideoRecorder.kt:268-309** NAL パーサが脆弱（境界再検出/末尾欠落）。
- **VideoDecoder.kt:148-157** 入力サイズに `data.limit()` を使用（position/容量考慮不足、容量超過で例外）。

### M2. スレッド安全性・可視性
- **NdiReceiver.kt:86,193** `frameCallback`（および `connectedSourceName`）が非 `@Volatile`。
- **PlayerViewModel.kt:104,485-519 等** `_uiState.value = _uiState.value.copy(...)` を複数スレッドで実行 → lost-update。`update{}` を使用。
- **NdiSourceRepository.kt:14-67** 可変フィールドが非スレッドセーフ。
- **NdiReceiver.kt:81,202** `consecutiveNullFrames++` は volatile 上の非アトミックRMW。
- **NdiFinder.kt:41-139** flow ループと`finderDestroy`の競合（UAF窓）、初回emit保証なし。

### M3. ネイティブ堅牢性（中）
- **ndi_wrapper.c:137-216** 3つの GlobalRef を解放する `JNI_OnUnload` が無い。
- **ndi_wrapper.c:577-582** `receiverConnect` が常に true（void 関数の結果を見ていない）。
- **ndi_wrapper.c:666-698** 非圧縮で `line_stride==0` の正規フレームを buffer_size=0 で破棄しうる（FourCCから導出すべき）。
- **ndi_wrapper.c:392,645,795** 負の `timeoutMs` が巨大値に化ける。

---

## 🟢 Low / 品質

- **AndroidManifest.xml:26** `usesCleartextTraffic="true"` がアプリ全体。LAN限定の network security config 推奨。
- **NdiReceiverApplication.kt:59-62** `onTerminate()` 依存は実機で呼ばれずデッドコード。
- **ColorSpaceConverter.kt:122-139** NV12 クロマが2x2の左上点サンプリング（平均化が高品質）。
- **UncompressedVideoRenderer.kt:141-143** 常に `setHasAlpha(true)`。ビデオ表示は不透明既定が無難。
- 各所の毎フレーム・ヒープ割り当て（GC圧）。バッファプール化推奨。
- **CMakeLists.txt:20** `CMAKE_CXX_COMPILER_WORKS FALSE` は NDK によりツールチェーン検出を壊しうる。
</content>
