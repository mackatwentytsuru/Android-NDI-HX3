---
name: ndi:review
description: Launch Codex CLI in background for code review of NDI HX3 implementation
argument-hint: [specific-files-or-feature]
allowed-tools: [Bash, Read, Glob, Grep, TaskOutput]
disable-model-invocation: true
---

# NDI HX3 Code Review (Codex Background)

Launch OpenAI Codex CLI in background for code review.

## Target

$ARGUMENTS

## Launch Codex Review in Background

```bash
# Use run_in_background: true, timeout: 600000
codex exec --full-auto "You are a senior Android developer reviewing code for an NDI HX3 receiver app.

REVIEW TARGET: $ARGUMENTS

CONTEXT:
- Read ndi-receiver-app-spec.md for requirements
- Read ndi-output-claude.md for implementation details
- Focus on Kotlin files in app/src/main/java/

REVIEW CHECKLIST:
1. Correctness - logic errors, off-by-one bugs
2. Resource Management - MediaCodec/NDI properly released
3. Thread Safety - synchronized, coroutine scopes
4. Error Handling - exceptions caught, resources released
5. Performance - no ANR causes
6. Kotlin Best Practices - null safety, data classes

OUTPUT: Save review to ndi-output-codex.md with:
- Critical Issues (P0)
- High Priority (P1)
- Medium Priority (P2)
- Low Priority (P3)
- Approved files
- Summary"
```

## Monitor Progress

```
TaskOutput(task_id="<task_id>", block=false, timeout=5000)
```

## After Review

1. Read `ndi-output-codex.md` for results
2. Fix critical and high priority issues
3. Consider medium priority improvements
4. Update `ndi-dev-status.md`

Launch review now for: $ARGUMENTS
