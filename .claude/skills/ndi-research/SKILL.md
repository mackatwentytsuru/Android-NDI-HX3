---
name: ndi:research
description: Launch Gemini CLI in background for documentation research
argument-hint: <topic-to-research>
allowed-tools: [Bash, Read, Write, Glob, TaskOutput]
disable-model-invocation: true
---

# NDI HX3 Research (Gemini Background)

Launch Google Gemini CLI in background for documentation research.

## Topic

$ARGUMENTS

## Gemini Strengths

- **Google Search Grounding**: Real-time web search
- **Large Context**: 1M token context window
- **Documentation**: API docs analysis
- **Best Practices**: Industry standards research

## Launch Gemini Research in Background

```bash
# Use run_in_background: true, timeout: 600000
gemini "You are a technical researcher for an Android NDI HX3 receiver app project.

RESEARCH TOPIC: $ARGUMENTS

PROJECT CONTEXT:
- Android app receiving NDI HX3 video streams
- Target device: FPD CP25-J1 tablet (Rockchip RK3576, Android 14)
- Video source: FoMaKo K20UH PTZ camera (1080p30, H.264/H.265)
- Key library: Devolay (NDI SDK wrapper)

YOUR RESEARCH TASKS:
1. Find official documentation
2. Discover best practices
3. Find example implementations on GitHub
4. Check for known issues and workarounds
5. Hardware-specific considerations for RK3576

OUTPUT: Save findings to ndi-output-gemini.md with:
- Summary
- Official Documentation
- Best Practices
- Code Examples
- Known Issues
- Recommendations
- References"
```

## Monitor Progress

```
TaskOutput(task_id="<task_id>", block=false, timeout=5000)
```

## Common Research Topics

```
/ndi:research Devolay NDI receiver setup
/ndi:research MediaCodec H.265 hardware decoding
/ndi:research MediaMuxer passthrough recording
/ndi:research Rockchip RK3576 video decode
/ndi:research Android SurfaceView low latency
```

Launch research for: $ARGUMENTS
