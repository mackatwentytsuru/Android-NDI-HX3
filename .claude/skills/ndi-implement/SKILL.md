---
name: ndi:implement
description: Implement feature via background Claude Code session
argument-hint: <feature-description>
allowed-tools: [Bash, Read, Glob, TaskOutput]
disable-model-invocation: true
---

# NDI HX3 Feature Implementation (Background Claude)

Implement a feature by launching a new Claude Code session in background.

## Feature to Implement

$ARGUMENTS

## Launch Background Claude for Implementation

Use Bash with `run_in_background: true` and `timeout: 600000`:

```bash
claude -p "You are implementing features for an Android NDI HX3 receiver app.

FEATURE TO IMPLEMENT: $ARGUMENTS

CONTEXT FILES TO READ FIRST:
1. ndi-receiver-app-spec.md - Full project requirements
2. ndi-output-claude.md - Previous implementation notes

ARCHITECTURE:
- Language: Kotlin
- Pattern: MVVM + Repository
- Min SDK: API 26 (Android 8.0)
- Target SDK: API 34 (Android 14)

PACKAGE STRUCTURE:
com.example.ndireceiver/
├── ui/           # Fragments, ViewModels
├── ndi/          # NDI Manager, Finder, Receiver
├── media/        # VideoDecoder, VideoRecorder
├── data/         # Repositories
└── util/         # Utilities

KEY LIBRARIES:
- Devolay 2.1.1 (NDI SDK wrapper)
- AndroidX Lifecycle
- Kotlin Coroutines
- MediaCodec / MediaMuxer

IMPLEMENTATION RULES:
1. Follow existing patterns in the codebase
2. Error handling with sealed classes
3. Proper resource cleanup (MediaCodec, NDI, files)
4. Thread safety with mutex or synchronized
5. Minimal, focused changes - do not over-engineer

YOUR TASK:
1. Read the context files
2. Analyze requirements
3. Implement the feature
4. Document in ndi-output-claude.md

OUTPUT TO ndi-output-claude.md:
## Implementation: [Feature Name]

### Files Created
- path/to/file.kt - Description

### Files Modified
- path/to/existing.kt - What changed

### Key Details
- How the feature works

### Status
- Implementation progress
- Testing notes"
```

## Monitor Progress

```
TaskOutput(task_id="<task_id>", block=false, timeout=5000)
```

## After Implementation

1. Read `ndi-output-claude.md` for results
2. Run `/ndi:review` for code review by Codex
3. Update `ndi-dev-status.md`

## Common Features

```
/ndi:implement VideoRecorder with MediaMuxer passthrough
/ndi:implement Recording list UI with thumbnails
/ndi:implement Settings screen with preferences
/ndi:implement Auto-reconnect logic
```

Launch implementation now for: $ARGUMENTS
