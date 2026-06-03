# NDI|HX3 受信 統合ガイド

このドキュメントは、本アプリで **NDI|HX3（圧縮 H.264/HEVC）ストリームを実機で正しく受信** するために必要な作業をまとめたものです。

最終更新: 2026-06-04

---

## 0. 結論（なぜ申請が必要か）

| SDK | HX3圧縮データをアプリに渡せるか | 入手 |
|-----|------------------------------|------|
| 標準NDI SDK（無料・**同梱中** v5.6.1） | ❌ 不可（デコード済みピクセルのみ） | 即DL |
| **NDI Advanced SDK** | ✅ 可（H.264/HEVCパススルー） | **申請が必要** |

> 標準SDKはHX3の圧縮ストリームをアプリへ渡せないため、現状のビルドではHX3ソースは**黒画面**になります（公式FAQで明言されている仕様）。HX3対応には Advanced SDK が必須です。

**費用**: 開発・評価用の Advanced SDK は **無料**（申請フォームでの登録・規約同意が必要）。ライセンス費用は **商用配布段階のみ**（`sdk@ndi.video` で vendor ID 取得）。本アプリはプライベートプロジェクトのため、開発・自己利用の範囲では費用は発生しません。

---

## 1. Advanced SDK の申請・入手

1. https://ndi.video/for-developers/ndi-advanced/ にアクセスし、Advanced SDK（トライアル/開発用）を申請
2. 案内に従い **Android版** と、テストソース作成用に **Apple版（macOS）** を入手
3. Android Advanced SDK には以下が含まれます：
   - ABIごとの `libndi.so`（`arm64-v8a` ほか）
   - Cヘッダ群（`Processing.NDI.*.h`。**`Processing.NDI.Advanced.h`** に圧縮列挙・`NDIlib_compressed_packet_t` が含まれる）

---

## 2. ライブラリとヘッダの差し替え

```bash
# 1) Advanced SDK の arm64-v8a libndi.so で置き換え
cp <Advanced-SDK>/lib/android/arm64-v8a/libndi.so \
   app/src/main/jniLibs/arm64-v8a/libndi.so

# 2) ヘッダを Advanced SDK のもので更新（compressed 列挙・packet 構造体を含む版）
cp <Advanced-SDK>/include/*.h app/src/main/cpp/include/
```

> **バージョン整合に注意**：ヘッダと `libndi.so` は同一バージョンに揃えること（現状の同梱はヘッダv6 / libv5.6.1で不一致。Advanced SDKで両方を同一版にする）。

---

## 3. HX3モードを有効化（コードは実装済み）

圧縮パススルー受信の経路は **実装済み** です。有効化はフラグ1つ：

`app/src/main/java/com/example/ndireceiver/ndi/NdiReceiver.kt`
```kotlin
// false → 非圧縮(BGRX_BGRA)。true → 圧縮H.264/HEVCパススルー(COMPRESSED_V5)
const val USE_COMPRESSED_HX = true   // ← Advanced SDK導入後に true へ
```

これにより受信は `NDIlib_recv_color_format_compressed_v5`(=307) を要求し、HX3フレームが
`NDIlib_compressed_packet_t` として届き、MediaCodecでハードウェアデコードされます。

### 実装済みの内訳

| レイヤ | ファイル | 内容 |
|--------|----------|------|
| Native | `cpp/ndi_wrapper.c` | 圧縮カラーフォーマット(307)へのマッピング、H264/HEVC（高/低帯域）FourCC判定 |
| Kotlin | `ndi/NdiCompressedPacket.kt` | `NDIlib_compressed_packet_t`(44byteヘッダ)解析、Annex-B組み立て、キーフレーム判定（**単体テスト済み**） |
| Kotlin | `ndi/NdiReceiver.kt` | 圧縮時はパケット解析→コピー済みAnnex-Bバッファを生成（解放後も安全） |
| Kotlin | `media/VideoDecoder.kt` | 先頭キーフレーム待ち、`BUFFER_FLAG_KEY_FRAME`付与 |
| 配線 | `ui/player/PlayerViewModel.kt` | `isCompressed`でMediaCodec経路へ振り分け（既存） |

---

## 4. ビルド

```bash
./gradlew clean assembleDebug
./gradlew testDebugUnitTest         # パーサ等の単体テスト
```

`compileSdk`/NDK/CMake は README「ビルド方法」を参照。

---

## 5. HXテストソースの用意（macOS）

> **Mac版のNDI Tools（Test Pattern等）はフルバンド非圧縮のみ**で、HXは出せません（Screen Capture HXはWindows専用と公式明記）。

確実なHX(圧縮)ソースの作り方：

- **(推奨) Advanced SDK + VideoToolbox で圧縮送信機を自作**：H.264/HEVCにエンコード → Annex-B化 → `NDIlib_compressed_packet_t` に詰めて `NDIlib_send_send_video_v2` で送出。
  - 参考実装: `satoshi0212/NDIHXSenderSample`（macOS）
  - 本リポジトリでの非圧縮テスト送信機の作り方は下記「付録」を参照（同じ要領で圧縮版を作成可能）
- **ハードウェアHX3ソース**：HX3対応カメラ/エンコーダ（BirdDog, PTZOptics HX 等）をLANに接続

---

## 6. 受信テスト手順（実機）

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
SER=<your-device-serial>            # adb devices で確認
$ADB -s $SER install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -s $SER logcat -c
# アプリ起動 → ソース選択。以下のログが出れば成功：
$ADB -s $SER logcat | grep -iE "VideoDecoder|Decoder created|compressed"
```

確認ポイント：
- `Decoder created: video/avc|video/hevc WxH`
- デコード済みフレームが連続描画（OSDで解像度/コーデック表示）
- 黒画面のままなら §2 のバージョン整合と §3 のフラグ、`logcat` のMediaCodecエラーを確認

---

## 7. 技術メモ（仕様の出典）

- 圧縮受信カラーフォーマット: `NDIlib_recv_color_format_compressed_v5 = 307`（v1=300, v2=301, v3=302, v4=303, v5=307）
- `NDIlib_compressed_packet_t`：`#pragma pack(1)`・44byteヘッダ・`version_0=44`・レイアウトは `[header][data][extra_data]`
  - `flags` bit0 = keyframe。`extra_data` はキーフレームにのみ存在
  - `data`/`extra_data` は **Annex-B（`00 00 00 01` スタートコード付き）**。`extra_data` は H.264: SPS,PPS / HEVC: VPS,SPS,PPS
  - MediaCodecには **config NAL（extra_data）を先頭**に置く必要があるため、キーフレームでは `extra_data + data` の順で結合
- FourCC: `H264`=0x34363248 / `HEVC`=0x43564548（高帯域・大文字）、`h264`/`hevc`（低帯域・小文字）
- 出典: docs.ndi.video「Using H.264, H.265, and AAC Codecs」「Receiving (Advanced SDK)」、`Processing.NDI.Advanced.h`

---

## 8. 未確認事項（正直な現状）

- 本ガイドの§2〜§6は **Advanced SDK 入手後に未実施**。申請許可が下りていないため、**実機でのHX3受信テストはまだ行えていません**。
- HX3実機テスト後に判明し得る調整点（MediaCodecのプロファイル/レベル対応、10bit/HDR、Annex-Bの3byte/4byteスタートコード差異、解像度切替時の再構成 等）は、テスト時に追記する。

---

## 付録: 非圧縮テスト送信機（検証で使用したもの）

検証では、UnrealのlibndiとリポジトリのNDIヘッダを使い、Mac上に非圧縮BGRA送信機を自作して受信を確認しました。圧縮版もこれを土台に作成できます（VideoToolboxでエンコード→Annex-B→`NDIlib_compressed_packet_t`）。

```c
// 概略（非圧縮版）。NDIlib_send_create → BGRAフレームを NDIlib_send_send_video_v2 で送出。
// 圧縮版は FourCC を H264/HEVC にし、p_data を NDIlib_compressed_packet_t にする。
```
