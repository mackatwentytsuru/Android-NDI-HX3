# NDI HX3 Development Skills

Optimized AI CLI skills for Android NDI HX3 Receiver App development.

## AI Role Distribution

| AI | Role | Strengths |
|----|------|-----------|
| **Claude Code** | Main Orchestrator & Implementation | Architecture, core coding, MVVM patterns |
| **OpenAI Codex** | Code Review & Testing | Bug detection, edge cases, unit tests, `/review` command |
| **Google Gemini** | Research & Documentation | Google Search grounding, 1M token context, API docs |

## Available Skills

### Development Workflow

| Skill | Description | Usage |
|-------|-------------|-------|
| `/ndi:dev <task>` | Full development cycle (implement -> review -> test) | `/ndi:dev Implement NdiFinder` |
| `/ndi:phase <1-4>` | Execute specific development phase | `/ndi:phase 1` |

### Individual Tasks

| Skill | Description | AI |
|-------|-------------|-----|
| `/ndi:implement <feature>` | Implement a feature | Claude |
| `/ndi:review [files]` | Code review | Codex |
| `/ndi:test [component]` | Create & run tests | Codex |
| `/ndi:research <topic>` | Documentation research | Gemini |

### General AI Launch

| Skill | Description |
|-------|-------------|
| `/ai:launch <ai> [task]` | Launch single AI CLI |
| `/ai:all [task]` | Launch all 3 AI CLIs |
| `/ai:parallel <task>` | Launch all with sub-task assignment |

## Development Phases

```
Phase 1: Basic Receive & Display (NDI init, discovery, basic playback)
Phase 2: Recording (MediaMuxer, passthrough, recording UI)
Phase 3: Playback & Files (file list, video player, thumbnails)
Phase 4: UI/UX Polish (fullscreen, OSD, settings, error handling)
```

## Output Files

All AI agents write to shared output files:

| File | Purpose |
|------|---------|
| `ndi-output-claude.md` | Implementation details, code changes |
| `ndi-output-codex.md` | Code review results, test reports |
| `ndi-output-gemini.md` | Research findings, documentation |
| `ndi-dev-status.md` | Overall project status |

## Workflow Example

```bash
# Start Phase 1 development with all AIs
/ndi:phase 1

# Or step by step:
/ndi:implement NdiManager initialization
/ndi:review app/src/main/java/com/example/ndireceiver/ndi/
/ndi:test NdiManager
/ndi:research Devolay setup best practices
```

## Quick Reference

### Claude (Implementation)
- Reads specification, implements features
- Follows MVVM + Repository pattern
- Writes Kotlin with coroutines

### Codex (Review)
- Reviews for bugs, memory leaks, thread safety
- Creates unit and integration tests
- Uses `/review` command for diff analysis

### Gemini (Research)
- Searches official documentation
- Finds example implementations
- Identifies known issues and workarounds

## Requirements

- Windows with PowerShell 7+
- Claude Code: `npm i -g @anthropic-ai/claude-code`
- OpenAI Codex: `npm i -g @openai/codex`
- Google Gemini: `npm i -g @google/gemini-cli`
- Valid authentication for each service
