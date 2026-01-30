# NDI HX3 Multi-Agent Development Workflow

## Core Principle: Context Preservation

**The primary Claude Code session (Opus 4.5) is the ORCHESTRATOR, not the implementer.**

- **DO NOT** implement fixes or features directly in the main session
- **ALWAYS** delegate work to background AI agents
- **MINIMIZE** context usage by using skills and background tasks

## Agent Roles

**重要: エージェントは必ず Bash ツールでターミナルコマンドとして起動すること。MCP ツールは使用しない。**

| Agent | Command (Non-Interactive) | Role | Output File |
|-------|--------------------------|------|-------------|
| Claude Code (Main) | - | Orchestrator, planning, delegation | - |
| Claude Code (Background) | `claude -p "..." --dangerously-skip-permissions` | Implementation, bug fixes | `ndi-output-claude.md` |
| OpenAI Codex | `codex -q "..."` or `codex review --uncommitted` | Code review, testing | `ndi-output-codex.md` |
| Google Gemini | `gemini -p "..."` | Web research, documentation, best practices | `ndi-output-gemini.md` |

### 禁止: MCP 経由での起動
```
❌ mcp__codex-agent__codex
❌ mcp__gemini-cli__ask-gemini
```

### 推奨: Bash ツールでターミナル実行
```bash
# Bash ツールを使ってターミナルコマンドを実行
gemini -p "Research: ..."
codex -q "Review: ..."
claude -p "Implement: ..." --dangerously-skip-permissions
```

## Workflow Pattern

### 1. Planning Phase (Main Claude)
- Read requirements and current state
- Create todo list
- Decide which agents to launch

### 2. Execution Phase (Background Agents)
- Launch agents with `run_in_background: true`
- Each agent works independently
- Results saved to output files

### 3. Integration Phase (Main Claude)
- Monitor progress with `TaskOutput`
- Read output files when complete
- Integrate findings into dev status

## Standard Background Launch Commands

### Claude Code (Implementation)
```bash
# run_in_background: true, timeout: 600000
claude -p "TASK: [description]

Read: ndi-receiver-app-spec.md, ndi-output-codex.md (for issues)

IMPLEMENT:
1. [specific fix 1]
2. [specific fix 2]

Save progress to ndi-output-claude.md"
```

### Codex (Review)
```bash
# run_in_background: true, timeout: 600000
codex exec --full-auto "TASK: [description]

Read: ndi-receiver-app-spec.md, ndi-output-claude.md

REVIEW:
1. Check for bugs
2. Check for memory leaks
3. Check thread safety

Save findings to ndi-output-codex.md"
```

### Gemini (Research)
```bash
# run_in_background: true, timeout: 600000
gemini "TASK: [description]

PROJECT: Android NDI HX3 receiver app

RESEARCH:
1. [topic 1]
2. [topic 2]

Save findings to ndi-output-gemini.md"
```

## Monitoring Pattern

```
# Check status without blocking
TaskOutput(task_id="<id>", block=false, timeout=5000)

# Wait for completion
TaskOutput(task_id="<id>", block=true, timeout=120000)
```

## Available Skills

| Skill | Purpose |
|-------|---------|
| `/ndi:phase <n>` | Execute development phase with all 3 AIs |
| `/ndi:fix <issue>` | Fix specific issue via background Claude |
| `/ndi:review [files]` | Review code via background Codex |
| `/ndi:research <topic>` | Research via background Gemini |
| `/ai:parallel <task>` | Launch all 3 AIs for a task |

## Key Files

| File | Purpose |
|------|---------|
| `ndi-receiver-app-spec.md` | Project requirements |
| `ndi-dev-status.md` | Current development status |
| `ndi-output-claude.md` | Claude implementation output |
| `ndi-output-codex.md` | Codex review output |
| `ndi-output-gemini.md` | Gemini research output |

## Important Rules

1. **Never implement directly** - Always use background agents
2. **One task per agent** - Clear, focused prompts
3. **Read before acting** - Always read output files after completion
4. **Update status** - Keep `ndi-dev-status.md` current

---

## オーケストレーター禁止事項

### ❌ 絶対にやってはいけないこと

1. **Editツールでソースコードを変更**
   - `.kt`, `.java`, `.xml` ファイルを直接編集しない
   - 「ちょっとした修正」でも委譲する

2. **Writeツールでソースコードを作成**
   - 新しいKotlin/Javaファイルを直接書かない
   - 全てバックグラウンドClaudeに委譲

3. **バックグラウンドタスクがスタックした時に自分で作業**
   - スタックしたら新しいバックグラウンドタスクを起動
   - 自分で引き継がない

### ✅ オーケストレーターがやること

1. **計画** - タスクを分析し、適切なエージェントを選択
2. **委譲** - バックグラウンドエージェントを起動
3. **監視** - TaskOutputで進捗確認
4. **統合** - 結果をドキュメントに反映
5. **報告** - ユーザーに状況を説明

### ドキュメントのみ編集可

- `CLAUDE.md` - オーケストレーションルール
- `AGENTS.md` - エージェントワークフロー
- `ndi-dev-status.md` - 開発ステータス
- `.claude/skills/*/SKILL.md` - スキル定義
- その他 `.md` ファイル

---

## バックグラウンドタスクがスタックした場合

1. ユーザーに状況を説明
2. 新しいバックグラウンドタスクを起動（別のタスクID）
3. 同じプロンプトで再試行、または問題を細分化
4. **自分で作業を引き継がない**

```bash
# スタックした場合の再起動例
claude -p "Previous task stalled. Retry: [same task description]"
```
