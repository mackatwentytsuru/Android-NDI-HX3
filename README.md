# NDI HX3 Receiver for Android

Android端末でNDI HX3ストリームを受信・表示するアプリケーションです。

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
- NDI SDK v6 対応ソース

## インストール

1. [Releases](https://github.com/mackatwentytsuru/Android-NDI-HX3/releases) から最新のAPKをダウンロード
2. Android端末で「提供元不明のアプリのインストール」を許可
3. APKをインストール

## ビルド方法

### 前提条件

- Android Studio (最新版推奨)
- NDK
- NDI SDK v6 for Android

### 手順

1. リポジトリをクローン
```bash
git clone https://github.com/mackatwentytsuru/Android-NDI-HX3.git
cd Android-NDI-HX3
```

2. NDI SDKをダウンロードして配置
   - [NDI SDK](https://ndi.video/) からAndroid版をダウンロード
   - `libndi.so` を `app/src/main/jniLibs/arm64-v8a/` に配置

3. Android Studioで開いてビルド

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
