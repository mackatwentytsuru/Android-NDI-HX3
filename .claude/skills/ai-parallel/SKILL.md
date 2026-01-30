---
name: ai:parallel
description: Launch Claude, Codex, Gemini CLIs in parallel background tasks
argument-hint: <main-task-description>
allowed-tools: [Bash, Read, Write, Glob, Grep, TaskOutput]
disable-model-invocation: true
---

# Parallel AI CLI Orchestration (Background Execution)

Launch multiple AI coding assistants in background tasks that you can monitor.

## Available AI CLIs

| AI | Command | Role |
|----|---------|------|
| Claude Code | `claude` (this session) | Main orchestrator, implementation |
| OpenAI Codex | `codex exec --full-auto` | Code review, testing, alternative approaches |
| Google Gemini | `gemini` | Web research, documentation, best practices |

## Main Task

$ARGUMENTS

## Execution Steps

### Step 1: Launch Codex in Background

```bash
# Use run_in_background: true, timeout: 600000
codex exec --full-auto "Help with: $ARGUMENTS - Focus on code review and testing. Save findings to ai-output-codex.md"
```

Returns task ID like: `b012345`

### Step 2: Launch Gemini in Background

```bash
# Use run_in_background: true, timeout: 600000
gemini "Help with: $ARGUMENTS - Focus on researching best practices and documentation. Save findings to ai-output-gemini.md"
```

Returns task ID like: `b678901`

### Step 3: Monitor Progress

Use TaskOutput tool with `block: false` to check status without waiting:

```
TaskOutput(task_id="<codex_task_id>", block=false, timeout=5000)
TaskOutput(task_id="<gemini_task_id>", block=false, timeout=5000)
```

Status values:
- `running` - Still working
- `completed` - Finished successfully
- `failed` - Error occurred

### Step 4: Claude Works on Main Task

While Codex and Gemini work in background:
1. Implement the main task
2. Periodically check their progress
3. Read their output files when done
4. Integrate findings

## Output Files

| File | Owner | Content |
|------|-------|---------|
| `ai-output-claude.md` | Claude | Implementation progress |
| `ai-output-codex.md` | Codex | Code review, testing |
| `ai-output-gemini.md` | Gemini | Research findings |

## Usage Examples

```
/ai:parallel Implement NDI HX3 video streaming for Android
/ai:parallel Build a WebSocket real-time module
/ai:parallel Create OAuth2 authentication system
```

## Key Points

- Use `codex exec --full-auto` for non-interactive Codex execution
- Use `gemini "prompt"` directly for Gemini
- Always set `run_in_background: true` and `timeout: 600000`
- Check progress with `TaskOutput(block=false)`
- Results saved to output markdown files

Begin parallel execution now.
