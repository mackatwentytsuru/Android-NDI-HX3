---
name: ai:all
description: Launch all AI CLIs (Claude, Codex, Gemini) simultaneously in parallel terminal windows
argument-hint: [task-description]
allowed-tools: [Bash]
disable-model-invocation: true
---

# Launch All AI CLIs

Launch Claude Code, OpenAI Codex, and Google Gemini CLIs in parallel terminal windows.

## Task

$ARGUMENTS

## Execution Steps

1. Launch Codex CLI in new terminal window
2. Launch Gemini CLI in new terminal window
3. Continue using Claude (this session) as the orchestrator

## Windows Commands

Execute the following to launch parallel AI sessions:

```bash
# Launch OpenAI Codex in new terminal
start cmd /k "cd /d \"$PWD\" && echo === OpenAI Codex CLI === && echo Task: $ARGUMENTS && codex"

# Launch Google Gemini in new terminal
start cmd /k "cd /d \"$PWD\" && echo === Google Gemini CLI === && echo Task: $ARGUMENTS && gemini"
```

## Coordination

- All AI agents will work in the same project directory: `$PWD`
- Claude (this session) continues as the main orchestrator
- Use shared files for result coordination if needed:
  - `ai-output-claude.md`
  - `ai-output-codex.md`
  - `ai-output-gemini.md`

## Usage Examples

```
/ai:all
/ai:all Implement the NDI HX3 receiver module
/ai:all Fix the authentication bug in the Android app
```

Launch the parallel sessions now.
