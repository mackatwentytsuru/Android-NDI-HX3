---
name: ndi:dev
description: Execute full development cycle for NDI HX3 app - implement, review, and test
argument-hint: <feature-or-task-description>
allowed-tools: [Bash, Read, Write, Edit, Glob, Grep]
disable-model-invocation: true
---

# NDI HX3 Full Development Cycle

Execute a complete development cycle: **Implement (Claude) -> Review (Codex) -> Test (Codex/Gemini)**

## Task

$ARGUMENTS

## Development Workflow

### Phase 1: Implementation (Claude - This Session)

Claude (current session) will:
1. Read the specification from `ndi-receiver-app-spec.md`
2. Implement the requested feature/task
3. Write code following MVVM + Repository pattern
4. Save implementation progress to `ndi-output-claude.md`

### Phase 2: Code Review (Codex - New Terminal)

Launch Codex with review focus:

```bash
start cmd /k "cd /d \"$PWD\" && codex \"You are reviewing code for an Android NDI HX3 receiver app. TASK: $ARGUMENTS. YOUR ROLE: 1. Read ndi-output-claude.md for implementation details 2. Review all new/modified Kotlin files in app/src/main/java/ 3. Check for logic errors, memory leaks, thread safety issues 4. Save your review findings to ndi-output-codex.md\""
```

### Phase 3: Testing (Codex - Same Terminal)

After review, Codex continues with test creation:

The review Codex session should also:
1. Create unit tests for new code in `app/src/test/java/`
2. Create instrumented tests if needed in `app/src/androidTest/java/`
3. Run tests with `./gradlew test`
4. Append test results to `ndi-output-codex.md`

### Phase 4: Research Support (Gemini - New Terminal)

Launch Gemini for documentation research:

```bash
start cmd /k "cd /d \"$PWD\" && gemini \"You are researching for an Android NDI HX3 receiver app. TASK: $ARGUMENTS. YOUR ROLE: 1. Research relevant documentation for Devolay, MediaCodec, MediaMuxer 2. Find best practices and example implementations 3. Save findings to ndi-output-gemini.md\""
```

## Output Files

| File | Owner | Purpose |
|------|-------|---------|
| `ndi-output-claude.md` | Claude | Implementation details, code changes |
| `ndi-output-codex.md` | Codex | Code review results, test results |
| `ndi-output-gemini.md` | Gemini | Research findings, documentation |
| `ndi-dev-status.md` | Claude | Overall development status |

## Execution

1. Claude starts implementation immediately
2. Launch Codex for review (wait for implementation to complete)
3. Launch Gemini for parallel research support
4. Claude synthesizes all outputs into `ndi-dev-status.md`

Begin the development cycle now for: $ARGUMENTS
