# NDI HX3 受信失敗の根本原因診断 (Opus 4.8 調査)

**調査日:** 2026-05-30
**ブランチ:** `claude/agex3-android-app-MpoEz`
**調査者:** Claude (Opus 4.8) — クラウド実行環境

---

## TL;DR（結論）

**過去にHX3映像が出なかった最有力原因は「NDI|HX デコード用のネイティブライブラリが APK に含まれていない」ことです。**

`app/src/main/jniLibs/arm64-v8a/` には `libndi.so` しか入っていません。
しかし NDI|HX / HX3 のストリーム（H.264/H.265 圧縮）をデコードするために、
`libndi.so` は**実行時に以下を動的ロード（dlopen）します**:

| 必要ライブラリ | 役割 | 現状 |
|----------------|------|------|
| `libndihx.so` | NDI\|HX コーデックプラグイン（HX/HX3デコードの本体） | ❌ 欠落 |
| `libavcodec-ndi.so.58` | NDI版FFmpeg avcodec（H.264/H.265デコーダ） | ❌ 欠落 |
| `libavutil-ndi.so.56` | FFmpeg avutil | ❌ 欠落 |
| `libswscale-ndi.so.5` | FFmpeg swscale（色空間変換） | ❌ 欠落 |
| `libndi.so` | NDI本体 | ✅ あり |

これらが無いと、アプリは
- ✅ NDI初期化
- ✅ HX3ソースの発見
- ✅ 接続

までは成功しますが、**圧縮ストリームのデコードに失敗し、1フレームも届かず黒画面**になります。
（`NdiReceiver` のループで `receiverCaptureVideo` が永遠に null を返す状態）

### なぜ確実にこれが効くのか

`NdiReceiver.connect()` は `colorFormat = NdiNative.ColorFormat.BGRX_BGRA` で受信します。
これは「SDK側でHX3を内部デコードして、非圧縮BGRAフレームでよこせ」という指定です。
内部デコードは `libndihx.so` + `libavcodec-ndi.so.58` が担うため、これらが無いと
非圧縮フレームは絶対に生成されません。

---

## 証拠（このリポジトリ内で検証済み）

`libndi.so` のシンボル/文字列を解析した結果:

```
NDI SDK ANDROID 12:52:44 Feb 16 2024 5.6.1
libndihx.so
libavcodec-ndi.so.58
libavutil-ndi.so.56
libswscale-ndi.so.5
avcodec_send_packet / avcodec_receive_frame / avcodec_find_decoder
NDI-HX-CRYPTO-V1 / NDI-HX-1
(c)2019 NewTek inc. Sony Camera NDI|HX implementation
```

`find app/src/main/jniLibs -type f` の結果は `libndi.so` のみ（他は `.gitkeep`）。

---

## 副次的な問題: ヘッダと .so のバージョン不一致

- **ヘッダ** (`app/src/main/cpp/include/`): `Copyright (C) 2023-2026 Vizrt NDI AB` → **NDI v6世代**
- **ライブラリ** (`libndi.so`): `5.6.1`（NewTek時代, Feb 2024）→ **NDI 5.6.x**

`ndi_wrapper.c` は v6 ヘッダの構造体レイアウトでビルドされますが、リンク先は 5.6.1 です。
NDIは概ねABI互換を保ちますが、`NDIlib_recv_create_v3_t` 等の構造体に差異があると
**未定義動作（クラッシュ・文字化けフレーム）**を招きます。
ヘッダと .so は**同一バージョンに揃えるべき**です（推奨: libndi.so も v6 にする、
または 5.6.x のヘッダに合わせる）。

---

## 修正手順（ユーザー側の作業が必要）

これらは NDI の**プロプライエタリ・バイナリ**で、再配布制約があり、
かつ本クラウド環境は `dl.google.com` / `ndi.video` 等へのネットワークが遮断されているため、
**ここでは取得・同梱できません**。ローカルのNDI SDKからコピーしてください。

### 1. HX デコードライブラリを jniLibs に追加

ローカルの NDI SDK for Android（`Install_NDI_SDK_v6_Android/NDI SDK for Android/lib/arm64-v8a/`）
から以下を `app/src/main/jniLibs/arm64-v8a/` にコピー:

```
libndihx.so
libavcodec-ndi.so.58   (または libavcodec.so.58)
libavutil-ndi.so.56    (または libavutil.so.56)
libswscale-ndi.so.5    (または libswscale.so.5)
```

> Android 7+ では、APK同梱のネイティブライブラリはアプリのリンカ名前空間に入るため、
> `libndi.so` からの `dlopen("libndihx.so")` は名前解決できます（jniLibs に置けば十分）。

### 2. ヘッダと .so のバージョンを統一

- 推奨: `libndi.so`（と上記HXライブラリ）を **NDI SDK v6** のものに統一し、
  既存の v6 ヘッダと一致させる。

### 3. .so がAPKに含まれているか確認

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "lib/arm64-v8a"
# libndi.so, libndihx.so, libavcodec-ndi.so.58, libavutil-ndi.so.56,
# libswscale-ndi.so.5, libndi_wrapper.so が並ぶこと
```

### 4. 実機ログで確認

```bash
adb logcat | grep -iE "ndihx|dlopen|cannot locate|library.*not found|NdiNative|NdiReceiver"
```

`cannot locate symbol` / `library "libndihx.so" not found` が出ていれば本診断が確定します。

---

## この環境（クラウド）でできなかったこと

- ❌ ビルド検証: `maven.google.com`（Android Gradle Plugin / AndroidX）と
  `dl.google.com`（Android SDK/NDK）がネットワークポリシーで遮断されているため、
  Gradleビルドが実行できません。
- ❌ 実機テスト: デバイス・NDI HX3ソースが無い。
- ❌ HXデコード用プロプライエタリ .so の取得。

そのため本ドキュメントは**静的解析に基づく診断**です。上記手順を適用後、実機での確認が必要です。
