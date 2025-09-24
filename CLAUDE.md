# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Critical First Actions

**ALWAYS before doing anything:**
1. Review `/memory/todo.md` and update with user's request as structured todos
2. Check `/memory/spec.md` (if exists) for design specifications
3. After EVERY fix/feat/chore: Update todo.md and perform lowercase conventional commit

## Project Overview

GlassAssistant is an AI-powered Android application for Google Glass Explorer Edition (XE24) that provides vision and voice capabilities through integration with OpenAI, Claude, and Gemini APIs. The app is being modernized to achieve feature parity with Meta Ray-Ban Display glasses (2025).

## Build & Development Commands

```bash
# Environment Setup (one-time)
export JAVA_HOME=~/tools/jdk-17.0.9+9
export ANDROID_HOME=~/Android/Sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH

# Build APK
./gradlew clean assembleDebug

# Install on Glass
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Run tests
./gradlew test

# Lint check
./gradlew lint

# View logs from Glass
adb logcat | grep -i glassassistant
```

## Architecture & Key Components

### Core Architecture
- **Language**: Kotlin with Android SDK
- **Min SDK**: API 19 (Android 4.4 KitKat) for Glass XE24 compatibility
- **Build System**: Gradle 8.4 with Kotlin DSL
- **Navigation**: Android Navigation Component with SafeArgs
- **Event Bus**: GreenRobot EventBus for gesture communication
- **Dependency Injection**: Manual (no DI framework currently)

### Fragment Structure
- `ApiKeyFragment`: QR code scanning for API key configuration
- `CameraFragment`: Camera capture and audio recording, main interaction point
- `LoadingFragment`: Processing state display
- `ResultFragment`: AI response display with scrollable text

### Glass-Specific Implementation
- `GlassGestureDetector`: Custom gesture detection for Glass touchpad
- Glass gestures mapped: TAP, TWO_FINGER_TAP, SWIPE_DOWN
- Camera button (KEYCODE_CAMERA) handling for voice recording

### API Integration Pattern
Current implementation uses direct HTTP calls to OpenAI. When adding providers:
1. Create provider interface in `services/ai/`
2. Implement provider-specific client
3. Add to provider factory
4. Update settings to include new provider

### Data Flow
1. User captures image + audio via `CameraFragment`
2. Files saved to app's cache directory
3. API call made with base64 encoded media
4. Response displayed in `ResultFragment`
5. EventBus used for gesture-based navigation

## Current Limitations & Considerations

- **Multidex**: Enabled due to method count >64K
- **Storage**: Uses cache directory, no persistent storage yet
- **Network**: No offline support, requires internet
- **Security**: API keys stored in SharedPreferences (needs encryption)
- **Display**: Text-only output, no AR overlay yet

## Modernization Priority

Per `/memory/todo.md`, prioritize in this order:
1. **QR-based APK installer** (security critical)
2. **OpenRouter.ai integration** with base_url customization
3. **Multi-provider support** with runtime selection
4. **Display overlay system** for AR features

## Testing Approach

- Unit tests: `app/src/test/` (JUnit)
- Instrumented tests: `app/src/androidTest/` (Espresso)
- Manual testing on Glass device required for gestures

## CI/CD

GitHub Actions workflow (`/.github/workflows/build-apk.yml`):
- Triggers on push to main/develop
- Builds debug and release APKs
- Creates releases with APK artifacts
- 30-day artifact retention

## Glass Hardware Constraints

- Display: 640×360 pixels, prism display
- Camera: 5MP photos, 720p video
- Processor: OMAP 4430 dual-core
- RAM: 2GB (682MB available to apps)
- Storage: 16GB (12GB usable)
- Battery: 570mAh (optimize for efficiency)

## Provider Integration Guide

When implementing new AI providers:
```kotlin
// 1. Create provider interface
interface AIProvider {
    suspend fun query(image: ByteArray?, audio: ByteArray?, prompt: String): Response
}

// 2. Add to Constants.kt
object ProviderConstants {
    const val PROVIDER_NAME = "provider_name"
    const val BASE_URL = "https://api.provider.com"
}

// 3. Update settings UI in ApiKeyFragment
// 4. Add provider selection in CameraFragment
```

## Commit Convention

Always use lowercase conventional commits:
```
feat: add openrouter support
fix: resolve camera crash on api 19
docs: update provider integration guide
chore: upgrade gradle to 8.5
ci: add test automation
```