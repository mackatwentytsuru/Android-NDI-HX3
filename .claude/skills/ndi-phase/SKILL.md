---
name: ndi:phase
description: Execute a specific development phase of the NDI HX3 project with all 3 AIs
argument-hint: <phase-number: 1|2|3|4>
allowed-tools: [Bash, Read, Write, Edit, Glob, Grep, TaskOutput]
disable-model-invocation: true
---

# NDI HX3 Development Phase Execution

Execute a specific development phase with coordinated AI agents running in background.

## Phase: $1

## Development Phases Overview

| Phase | Name | Tasks |
|-------|------|-------|
| 1 | Basic Receive & Display | Project setup, NDI init, source discovery, basic display |
| 2 | Recording | MediaMuxer MP4, passthrough recording, recording UI |
| 3 | Playback & Files | Recording list, video playback, file management |
| 4 | UI/UX Polish | Fullscreen, OSD, settings, error handling |

## Launch Phase Execution

### Step 1: Launch Codex (Review & Test) in Background

Use `codex exec --full-auto` for non-interactive execution:

```bash
# Run in background with run_in_background: true
codex exec --full-auto "NDI HX3 Project - Phase $1. Read ndi-receiver-app-spec.md for spec. Read ndi-output-claude.md for progress. Your role: Review code, create unit tests, save findings to ndi-output-codex.md"
```

### Step 2: Launch Gemini (Research) in Background

Gemini accepts prompts directly:

```bash
# Run in background with run_in_background: true
gemini "NDI HX3 Project - Phase $1 Research. Read ndi-receiver-app-spec.md for context. Your role: Research documentation, find best practices, save findings to ndi-output-gemini.md"
```

### Step 3: Monitor Progress

Use TaskOutput tool with `block: false` to check status:

```
TaskOutput(task_id="<codex_task_id>", block=false)
TaskOutput(task_id="<gemini_task_id>", block=false)
```

Or read output files directly:
- Codex output: Check temp file path returned on launch
- Gemini output: Check temp file path returned on launch

### Step 4: Check Results

Once tasks complete, read the output files:
- `ndi-output-codex.md` - Code review results, test results
- `ndi-output-gemini.md` - Research findings, documentation

## Phase Details

### Phase 1: Basic Receive & Display
**Claude**: Implementation (Gradle, NdiManager, NdiFinder, NdiReceiver, VideoDecoder, UI)
**Codex**: Review NDI lifecycle, test source detection, test connection cycles
**Gemini**: Devolay setup, MediaCodec H.265, SurfaceView optimization

### Phase 2: Recording
**Claude**: VideoRecorder, passthrough recording, recording UI, storage permissions
**Codex**: Review cleanup, test recording start/stop, test long recording
**Gemini**: MediaMuxer passthrough, NAL unit handling, storage best practices

### Phase 3: Playback & File Management
**Claude**: RecordingsFragment, ExoPlayer, thumbnails, deletion, RecordingRepository
**Codex**: Review file operations, test playback, test deletion
**Gemini**: ExoPlayer setup, thumbnail extraction, RecyclerView performance

### Phase 4: UI/UX Polish
**Claude**: Fullscreen, OSD, settings, auto-reconnect, error handling
**Codex**: Review UI consistency, test settings, test error scenarios
**Gemini**: Material Design 3, large screen UX, error patterns

## Output Files

| File | Owner | Content |
|------|-------|---------|
| `ndi-output-claude.md` | Claude | Implementation progress |
| `ndi-output-codex.md` | Codex | Code review, test results |
| `ndi-output-gemini.md` | Gemini | Research findings |
| `ndi-dev-status.md` | Claude | Overall status |

Execute Phase $1 now by launching background tasks.
