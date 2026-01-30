---
name: ndi:fix
description: Fix specific issues by delegating to background Claude Code
argument-hint: <issue-description-or-file:line>
allowed-tools: [Bash, Read, Glob, TaskOutput]
disable-model-invocation: true
---

# NDI HX3 Bug Fix (Background Claude)

Fix issues by launching a new Claude Code session in background.

## Issue to Fix

$ARGUMENTS

## Launch Background Claude for Fix

Use Bash with `run_in_background: true` and `timeout: 600000`:

```bash
claude -p "You are fixing bugs in an Android NDI HX3 receiver app.

ISSUE TO FIX: $ARGUMENTS

CONTEXT FILES TO READ:
1. ndi-receiver-app-spec.md - Project requirements
2. ndi-output-codex.md - Code review findings with line numbers

YOUR TASK:
1. Read the context files first
2. Locate the issue in the codebase
3. Implement the fix
4. Test that the fix compiles (if possible)
5. Document what you changed

OUTPUT:
Save your progress to ndi-output-claude.md with:
- Issue Summary
- Files Modified
- Changes Made
- Testing Notes

IMPORTANT:
- Make minimal, focused changes
- Do not over-engineer
- Keep the fix simple and direct"
```

## Monitor Progress

```
TaskOutput(task_id="<task_id>", block=false, timeout=5000)
```

## After Fix Complete

1. Read `ndi-output-claude.md` for results
2. Review the changes
3. Run `/ndi:review` if needed for code review
4. Update `ndi-dev-status.md`

## Common Fix Patterns

```
/ndi:fix Use-after-free in NdiReceiver.kt:177
/ndi:fix DevolaySource lost in PlayerFragment
/ndi:fix Thread safety in PlayerViewModel
/ndi:fix Memory leak in VideoDecoder
```

Launch fix now for: $ARGUMENTS
