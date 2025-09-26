# Building GlassAssistant

This guide covers building the GlassAssistant APK for Google Glass XE24 on different platforms.

## Build Requirements

- **Target Platform**: Google Glass XE24 (Android API 19 / KitKat)
- **Java**: JDK 8+ (compiles to Java 1.8 target)
- **Android SDK**: Build Tools 34.0.0, Platform 19

## Platform-Specific Build Instructions

### 🚀 GitHub Actions (Automated CI/CD)

GitHub Actions automatically builds APKs on every push to `main` branch:

1. **Automatic Builds**: Push to main triggers workflow
2. **APK Artifacts**: Downloaded from Actions tab (30-day retention)
3. **Releases**: Auto-created with version tags
4. **Clean Environment**: No Termux-specific overrides

**Workflow Location**: `.github/workflows/build-apk.yml`

### 📱 Termux (Android Terminal)

For local development on Android using Termux:

```bash
# Use the optimized build script
./build_glass_apk.sh
```

**Requirements**:
- Termux with Android SDK installed
- Custom aapt2 binary for ARM64 (automatically configured)
- Java and Android SDK paths auto-detected

**Features**:
- Termux environment detection
- ARM64 aapt2 override in `gradle.properties`
- Optimized for Android device builds

### 🖥️ Local Development (Linux/macOS/Windows)

For standard development environments:

```bash
# Set environment variables
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk

# Build directly with Gradle
./gradlew assembleDebug
```

## Configuration Files

### `gradle.properties`
Contains Termux-specific aapt2 override:
```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/home/git/glass/GlassAssistant/tools/aapt2-arm64/aapt2
```

**Note**: GitHub Actions automatically removes this line to use standard aapt2.

### `local.properties`
Specifies Android SDK location:
```properties
sdk.dir=/data/data/com.termux/files/home/android-sdk
```

## Build Outputs

### Debug APK
- **Location**: `app/build/outputs/apk/debug/app-debug.apk`
- **Size**: ~9MB
- **Signing**: Debug keystore
- **Installation**: `adb install app-debug.apk`

### Release APK
- **Location**: `app/build/outputs/apk/release/app-release-unsigned.apk`
- **Requires**: Release signing configuration
- **Production**: Ready for Google Glass deployment

## Glass XE24 Installation

1. **Enable Developer Options**:
   - Settings → Device Info → Tap "Build number" 7 times

2. **Enable USB Debugging**:
   - Settings → Developer Options → USB Debugging

3. **Connect via USB**:
   - Use USB cable to connect Glass to computer

4. **Install APK**:
   ```bash
   adb install app-debug.apk
   ```

5. **Launch**:
   - Voice: "Ok Glass, start Glass Assistant"
   - Touch: Tap and swipe to Glass Assistant

## Troubleshooting

### Termux Issues
- **aapt2 not found**: Ensure `tools/aapt2-arm64/aapt2` exists
- **Java version**: OpenJDK 21 compiling to Java 1.8 target works
- **SDK path**: Verify Android SDK is in `~/android-sdk`

### GitHub Actions Issues
- **aapt2 override**: Automatically removed in workflow
- **Java version**: Uses JDK 17 via setup-java action
- **SDK components**: Auto-installed (API 19, 34, Build Tools 34.0.0)

### General Issues
- **Multidex**: Enabled due to method count > 64K
- **API 19 compatibility**: All dependencies verified for KitKat
- **Glass features**: Voice triggers and touch gestures included

## Development Notes

- **Glass Gestures**: Custom `GlassGestureDetector` from Google samples
- **Voice Triggers**: Configured in `AndroidManifest.xml`
- **Performance**: Optimized for Glass hardware constraints
- **Security**: Modern security practices with API 19 compatibility

For more details, see `CLAUDE.md` for project-specific development guidelines.