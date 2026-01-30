---
name: ai:launch
description: Launch a specific AI CLI (claude, codex, gemini) with optional task
argument-hint: <ai-name> [task-description]
allowed-tools: [Bash]
disable-model-invocation: true
---

# Launch AI CLI

Launch a specific AI coding assistant in a new terminal window.

## Arguments

- `$1` - AI name: `claude`, `codex`, or `gemini`
- `$2` onwards - Optional task description

## Supported AIs

| AI | Command | Description |
|----|---------|-------------|
| claude | `claude` | Anthropic Claude Code CLI |
| codex | `codex` | OpenAI Codex CLI |
| gemini | `gemini` | Google Gemini CLI |

## Execution (Windows CMD)

Based on the AI name provided ($1), launch the appropriate CLI in a new terminal window:

### For Claude Code
```bash
start cmd /k "cd /d \"$PWD\" && claude \"$2 $3 $4 $5\""
```

### For OpenAI Codex
```bash
start cmd /k "cd /d \"$PWD\" && codex \"$2 $3 $4 $5\""
```

### For Google Gemini
```bash
start cmd /k "cd /d \"$PWD\" && gemini \"$2 $3 $4 $5\""
```

## Usage Examples

```
/ai:launch claude Implement user authentication
/ai:launch codex Review the API endpoints
/ai:launch gemini Research best practices for Android NDK
```

Execute the appropriate launch command now for: $1
With task: $2 $3 $4 $5
