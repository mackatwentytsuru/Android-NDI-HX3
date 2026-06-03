# NDI HX3 Receiver for Android

Android端末でNDI HX3ストリームを受信・表示するアプリケーションです。

---

## ⚠️ 現状ステータス（2026-06-04 時点）

実機（Pixel 7a / Android）での検証を行い、現在地を整理しました。

| 項目 | 状態 |
|------|------|
| ビルド（標準SDK同梱・arm64-v8a） | ✅ 成功（`assembleDebug`） |
| 実機インストール・起動 | ✅ 成功（クラッシュなし） |
| NDIソース検出（mDNS） | ✅ 成功 |
| **非圧縮NDI（BGRA）受信・表示** | ✅ **実機で表示確認済み**（Macのテスト送信機から1280×720 BGRA @30fpsを受信・描画） |
| 切断・再接続 | ✅ 安定 |
| 単体テスト（圧縮パケット解析ほか） | ✅ 全通過（`testDebugUnitTest`） |
| **NDI|HX3（圧縮H.264/HEVC）受信** | ⛔ **未検証**。下記の理由により、現状の同梱SDKでは黒画面になります |

### HX3が現状動かない理由（重要）

このリポジトリに同梱されている `libndi.so` は **無料の標準NDI SDK（v5.6.1）** です。
**標準SDKはアプリへ圧縮H.264/HEVCデータを渡せず、デコード済みピクセルしか返しません。**
NDI|HX3の「圧縮パススルー＆デコード」は **NDI Advanced SDK 専用機能** です（公式FAQで明言）。

したがってHX3を正しく受信するには、**NDI Advanced SDK の入手（申請が必要）** が前提になります。

- 開発・評価目的の Advanced SDK は **無料**（申請フォームでの登録・規約同意が必要）
- ライセンス費用が関わるのは **完成品を商用配布する段階のみ**（`sdk@ndi.video` でvendor ID取得）
- 申請: https://ndi.video/for-developers/ndi-advanced/

### 何が実装済みで、何が未確認か

- ✅ 圧縮パススルー受信のコード経路はこのブランチで**実装済み**：
  受信カラーフォーマットの切替（`COMPRESSED_V5`）、`NDIlib_compressed_packet_t` の解析（Annex-B組み立て・キーフレーム判定・コーデック判別）、MediaCodecへの配線、単体テスト。
- ⛔ ただし **Advanced SDKの入手・差し替えが未完のため、実機でのHX3受信テストは未実施**です。
  申請許可が下りてAdvanced SDKを組み込んだ後の動作確認は**まだ行えていません**。

> 👉 HX3を有効化する手順は **[docs/HX3-INTEGRATION.md](docs/HX3-INTEGRATION.md)** に全手順をまとめてあります。

---

## 機能

- **NDIソース検出**: ネットワーク上のNDIソースを自動検出
- **映像再生**: 非圧縮（BGRA/RGBA/UYVY）および圧縮（H.264/H.265）ストリーム対応
- **録画**: パススルーエンコーディングでMP4録画
- **再生**: ExoPlayerを使用した録画ファイルの再生
- **設定**: 自動再接続、OSDオーバーレイ、画面常時オン

## スクリーンショット

（準備中）

## 動作要件

- Android 8.0 (API 26) 以上
- arm64-v8a デバイス
- 非圧縮NDIソース：標準SDKで受信可
- **NDI|HX3（圧縮）ソース：NDI Advanced SDK が必要**（[docs/HX3-INTEGRATION.md](docs/HX3-INTEGRATION.md) 参照）

## インストール

1. [Releases](https://github.com/mackatwentytsuru/Android-NDI-HX3/releases) から最新のAPKをダウンロード
2. Android端末で「提供元不明のアプリのインストール」を許可
3. APKをインストール

## ビルド方法

### 前提条件（検証済みツールチェーン）

- Android SDK Platform 34 / Build-Tools 34 / Platform 35
- NDK `26.1.10909125`、CMake `3.22.1`
- JDK 17〜21（AGP 8.2.2 / Gradle 8.5。検証は OpenJDK 21 で実施）
- 同梱の `libndi.so`（標準SDK v5.6.1）で非圧縮NDIはビルド・受信可能

### 手順

1. リポジトリをクローン
```bash
git clone https://github.com/mackatwentytsuru/Android-NDI-HX3.git
cd Android-NDI-HX3
```

2. NDI SDKを配置
   - 非圧縮のみ：標準 [NDI SDK](https://ndi.video/) の `libndi.so` を `app/src/main/jniLibs/arm64-v8a/` に配置（リポジトリに同梱済み）
   - **HX3（圧縮）対応**：[NDI Advanced SDK](https://ndi.video/for-developers/ndi-advanced/) を申請・入手し、その `libndi.so` で置き換え＋ヘッダ更新 → **[docs/HX3-INTEGRATION.md](docs/HX3-INTEGRATION.md)** の手順を実施

3. ビルド
```bash
# CLI（検証済み）
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # 環境に合わせて
./gradlew assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # 単体テスト
# もしくは Android Studio で開いてビルド
```

## アーキテクチャ

```
app/src/main/
├── java/com/example/ndireceiver/
│   ├── ndi/          # NDI関連 (JNIラッパー, Finder, Receiver)
│   ├── media/        # メディア処理 (Decoder, Renderer, Recorder)
│   ├── ui/           # UI (Fragments, ViewModels)
│   └── data/         # データ層 (Repositories)
└── cpp/
    └── ndi_wrapper.c # NDI SDK JNIラッパー (Pure C)
```

## 技術スタック

- **言語**: Kotlin, C
- **UI**: Android View + Fragment
- **非同期処理**: Kotlin Coroutines + StateFlow
- **動画再生**: ExoPlayer
- **動画デコード**: MediaCodec
- **NDI**: NDI SDK v6 (JNI経由)

## ライセンス

このプロジェクトはプライベートプロジェクトです。

NDI® is a registered trademark of Vizrt NDI AB.

## 謝辞

- [NDI SDK](https://ndi.video/) by Vizrt
- [ExoPlayer](https://github.com/google/ExoPlayer) by Google

---

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>
