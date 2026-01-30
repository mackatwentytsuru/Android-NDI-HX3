---
name: ndi:test
description: Launch Codex CLI for test creation and execution for NDI HX3 app
argument-hint: [component-or-feature-to-test]
allowed-tools: [Bash, Read, Write, Glob, Grep]
disable-model-invocation: true
---

# NDI HX3 Test Creation & Execution (Codex)

Launch OpenAI Codex CLI for test-driven development.

## Target

$ARGUMENTS

## Test Types

### Unit Tests (`app/src/test/java/`)
- ViewModel logic
- Repository data transformations
- Utility functions
- State management

### Instrumented Tests (`app/src/androidTest/java/`)
- UI interactions
- MediaCodec integration
- File system operations
- NDI connection (with mock)

## Launch Codex Test Session (Windows)

```bash
start cmd /k "cd /d \"$PWD\" && codex \"You are writing tests for an Android NDI HX3 receiver app. TEST TARGET: $ARGUMENTS. CONTEXT: Read ndi-receiver-app-spec.md for requirements. Read ndi-output-claude.md for implementation details. TEST FRAMEWORK: JUnit 5, Mockito/MockK, Espresso, Turbine for Flow. YOUR TASKS: 1. Analyze implementation 2. Create unit tests for public methods and error conditions 3. Create integration tests if needed 4. Run tests with ./gradlew test 5. Save test report to ndi-output-codex.md with Test Classes Created, Test Results, Failing Tests, Coverage Analysis, Recommendations.\""
```

## Test Commands

```bash
# Run all unit tests
./gradlew test

# Run specific test class
./gradlew test --tests "com.example.ndireceiver.ndi.NdiFinderTest"

# Run with coverage
./gradlew testDebugUnitTestCoverage

# Run instrumented tests
./gradlew connectedDebugAndroidTest
```

Launch test session for: $ARGUMENTS
