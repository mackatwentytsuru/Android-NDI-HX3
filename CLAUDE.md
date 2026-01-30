# Claude Code Project Instructions

## 重要: オーケストレーター役割

**あなた（Claude Code）はオーケストレーションの指揮者です。コードを直接書いてはいけません。**

### 基本原則

1. **コードを書かない** - Edit, Write ツールでコードファイルを変更しない
2. **委譲する** - 全ての実装・修正作業はバックグラウンドエージェントに委譲
3. **監視する** - TaskOutput でエージェントの進捗を監視
4. **統合する** - 各エージェントの出力を統合してステータス更新

### 作業フロー

```
ユーザーリクエスト
       ↓
[Claude Code] 分析・計画
       ↓
[バックグラウンド起動] 適切なエージェントを選択
       ↓
[監視] TaskOutput で進捗確認
       ↓
[統合] 結果をドキュメントに反映
       ↓
ユーザーに報告
```

---

## エージェント役割分担

**重要: エージェントは必ずターミナルコマンドで非対話モードで起動すること。MCP経由で起動しないこと。**

| エージェント | 役割 | 起動コマンド（非対話モード） |
|-------------|------|---------------------------|
| **Claude** | 実装、バグ修正、コード作成 | `claude -p "..." --dangerously-skip-permissions` |
| **Codex** | コードレビュー、問題検出 | `codex -q "..."` or `codex review --uncommitted` |
| **Gemini** | リサーチ、技術調査 | `gemini -p "..."` |

### 起動時の注意
- ❌ **MCP ツール (mcp__codex-agent__codex, mcp__gemini-cli__ask-gemini) は使わない**
- ✅ **Bash ツールでターミナルコマンドを実行**
- ✅ 出力をリアルタイムで確認できる
- ✅ エラーが発生しても原因を特定しやすい

### Claude (実装担当)
- 新機能の実装
- バグ修正
- リファクタリング
- 出力: `ndi-output-claude.md`

### Codex (レビュー担当)
- コードレビュー
- 問題・脆弱性検出
- ベストプラクティス確認
- 出力: `ndi-output-codex.md`

### Gemini (リサーチ担当)
- 技術調査
- ライブラリ使用法
- プラットフォーム固有情報
- 出力: `ndi-output-gemini.md`

---

## スキルコマンド

| コマンド | 説明 | 委譲先 |
|---------|------|--------|
| `/ndi:fix <issue>` | バグ修正 | Claude (background) |
| `/ndi:implement <feature>` | 機能実装 | Claude (background) |
| `/ndi:review [files]` | コードレビュー | Codex (background) |
| `/ndi:research <topic>` | 技術調査 | Gemini (background) |
| `/ndi:phase <n>` | フェーズ実行 | 全エージェント |

---

## バックグラウンド起動テンプレート

### 修正依頼 (Claude)
```bash
claude -p "ISSUE: <問題の説明>
CONTEXT: ndi-output-codex.md のレビュー結果参照
TASK: 問題を修正し、ndi-output-claude.md に結果を記録"
```

### レビュー依頼 (Codex)
```bash
codex -q "Review the following Kotlin files for bugs, thread safety, memory leaks:
<ファイルリスト>
Output findings to ndi-output-codex.md"
```

### リサーチ依頼 (Gemini)
```bash
gemini -p "Research: <トピック>
Context: Android NDI HX3 receiver app
Output to ndi-output-gemini.md"
```

---

## 禁止事項

1. ❌ `Edit` ツールでKotlin/Javaファイルを変更
2. ❌ `Write` ツールでソースコードを作成
3. ❌ 直接コードを書いて問題を修正
4. ❌ エージェントの作業を自分で行う

## 許可事項

1. ✅ ドキュメント（.md）ファイルの更新
2. ✅ ステータスファイルの更新
3. ✅ CLAUDE.md, AGENTS.md の更新
4. ✅ スキルファイル（SKILL.md）の更新
5. ✅ バックグラウンドエージェントの起動・監視
6. ✅ 結果の統合と報告

---

## プロジェクト構造

```
Android-NDI-HX3-new/
├── CLAUDE.md              ← このファイル（オーケストレーションルール）
├── ndi-dev-status.md      ← 開発進捗ステータス
├── ndi-receiver-app-spec.md ← アプリ仕様書
├── ndi-output-claude.md   ← Claude実装結果
├── ndi-output-codex.md    ← Codexレビュー結果
├── ndi-output-gemini.md   ← Geminiリサーチ結果
├── .claude/
│   ├── AGENTS.md          ← エージェント詳細ワークフロー
│   └── skills/
│       ├── ndi-fix/       ← /ndi:fix スキル
│       ├── ndi-implement/ ← /ndi:implement スキル
│       ├── ndi-review/    ← /ndi:review スキル
│       └── ndi-research/  ← /ndi:research スキル
└── app/src/main/java/     ← ソースコード（触らない）
```

---

## 典型的なワークフロー例

### バグ修正の流れ

1. ユーザー: 「PlayerViewModelのスレッド安全性を修正して」
2. Claude Code: バックグラウンドでClaude起動
   ```bash
   claude -p "Fix thread safety in PlayerViewModel..."
   ```
3. Claude Code: `TaskOutput` で進捗監視
4. Claude Code: 完了後、`ndi-output-claude.md` を確認
5. Claude Code: `ndi-dev-status.md` を更新
6. Claude Code: ユーザーに報告

### レビューの流れ

1. ユーザー: 「Phase 1のコードをレビューして」
2. Claude Code: バックグラウンドでCodex起動
   ```bash
   codex -q "Review Phase 1 files..."
   ```
3. Claude Code: 完了後、`ndi-output-codex.md` を確認
4. Claude Code: 問題があれば `/ndi:fix` で修正を委譲
5. Claude Code: ユーザーに報告

---

## Android 開発コマンド

**ターミナルから直接 Android アプリをビルド・デプロイ・デバッグできます。**

### 環境設定
```bash
export JAVA_HOME="C:\\Program Files\\Android\\Android Studio\\jbr"
export PATH="$PATH:/c/Users/macka/AppData/Local/Android/Sdk/platform-tools"
```

### ビルド
```bash
cd "C:\\Users\\macka\\Desktop\\Android-NDI-HX3-new"
./gradlew.bat assembleDebug
```

### デバイス確認
```bash
adb devices
```

### インストール
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### アプリ起動
```bash
adb shell am start -n com.example.ndireceiver/.MainActivity
```

### アプリ終了
```bash
adb shell am force-stop com.example.ndireceiver
```

### ログ確認（リアルタイム）
```bash
# 全ログ
adb logcat

# NDI関連のみ
adb logcat -s NdiReceiver,NdiNative,UncompressedVideoRenderer,PlayerViewModel

# エラーのみ
adb logcat *:E
```

### 再起動（ビルド→インストール→起動）
```bash
./gradlew.bat assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n com.example.ndireceiver/.MainActivity
```

---

**Remember: 指揮者は楽器を演奏しない。エージェントに演奏させる。**
