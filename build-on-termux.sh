#!/data/data/com.termux/files/usr/bin/bash

# Complete build script for GlassAssistant on Termux ARM64
# This script handles all the compatibility issues
# Usage: ./build-on-termux.sh [debug|release]

BUILD_TYPE="${1:-debug}"
BUILD_TYPE_LOWER=$(echo "$BUILD_TYPE" | tr '[:upper:]' '[:lower:]')

echo "=== GlassAssistant Termux Build Script ==="
echo "Building $BUILD_TYPE_LOWER APK for Google Glass XE24 on Termux ARM64"
echo

# Validate build type
if [[ "$BUILD_TYPE_LOWER" != "debug" && "$BUILD_TYPE_LOWER" != "release" ]]; then
    echo "Error: Invalid build type. Use 'debug' or 'release'"
    echo "Usage: $0 [debug|release]"
    exit 1
fi

# 1. Set up environment for Glass (requires older Java for API 19 compatibility)
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk"
export PATH="$JAVA_HOME/bin:/data/data/com.termux/files/usr/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/34.0.0:$PATH"

echo "Step 1: Checking prerequisites..."

# Check Java (using Java 21 for modern build tools, but APK targets API 19)
if ! java -version &>/dev/null; then
    echo "Error: Java not found. Install with: pkg install gradle (includes Java 21)"
    echo "Note: Java 21 is used for build tools, APK will still target Glass XE24 (API 19)"
    exit 1
fi

# Show Java version being used
java_version=$(java -version 2>&1 | head -n 1)
echo "Using Java: $java_version"

# Check Gradle
if ! gradle -v &>/dev/null; then
    echo "Error: Gradle not found. Install with: pkg install gradle"
    exit 1
fi

# Check Android SDK
if [ ! -d "$ANDROID_HOME" ]; then
    echo "Error: Android SDK not found at $ANDROID_HOME"
    echo "Please install Android SDK with API 19 (KitKat) for Glass support"
    exit 1
fi

# Check if build-tools exist
if [ ! -d "$ANDROID_HOME/build-tools/34.0.0" ]; then
    echo "Warning: Build tools 34.0.0 not found. Using latest available..."
    # Find latest build tools
    LATEST_BUILD_TOOLS=$(find "$ANDROID_HOME/build-tools" -maxdepth 1 -type d -name "*.*.*" | sort -V | tail -n1)
    if [ -n "$LATEST_BUILD_TOOLS" ]; then
        BUILD_TOOLS_VERSION=$(basename "$LATEST_BUILD_TOOLS")
        export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION:$PATH"
        echo "Using build tools version: $BUILD_TOOLS_VERSION"
    else
        echo "Error: No build tools found in Android SDK"
        exit 1
    fi
fi

# Check qemu-x86_64 for AAPT2 wrapper
if ! command -v qemu-x86_64 &>/dev/null; then
    echo "Error: qemu-x86_64 not found. Install with: pkg install qemu-user-x86-64"
    echo "Required for patched AAPT2 to work on Termux ARM64"
    exit 1
fi

# Check for patched AAPT2 for Termux compatibility
AAPT2_PATH="$(pwd)/tools/aapt2-arm64/aapt2"
if [ -f "$AAPT2_PATH" ]; then
    echo "Found patched AAPT2 for Termux at: $AAPT2_PATH"
    # Make sure it's executable
    chmod +x "$AAPT2_PATH"
    chmod +x "$(dirname "$AAPT2_PATH")/aapt2.elf"
else
    echo "Error: Patched AAPT2 not found at $AAPT2_PATH"
    echo "Termux requires patched AAPT2 for Android builds"
    exit 1
fi

echo "Step 2: Checking project structure..."

# Verify we're in the right directory
if [ ! -f "app/build.gradle.kts" ]; then
    echo "Error: Not in GlassAssistant project root (app/build.gradle.kts not found)"
    exit 1
fi

# Check application ID matches Glass project
if ! grep -q "dev.synople.glassassistant" app/build.gradle.kts; then
    echo "Warning: Application ID might not match GlassAssistant project"
fi

echo "Step 3: Cleaning previous builds..."
./gradlew clean || {
    echo "Warning: Clean failed, continuing anyway..."
}

# Determine gradle task and output path
if [ "$BUILD_TYPE_LOWER" = "release" ]; then
    echo "Step 4: Building Release APK for Google Glass..."
    echo "Note: Release builds require signing configuration."
    echo "Creating a test signing key for release build..."

    # Create a test keystore for release builds if not present
    if [ ! -f "release.keystore" ]; then
        keytool -genkey -v -keystore release.keystore -alias glassrelease \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -storepass android -keypass android \
            -dname "CN=GlassAssistant, OU=Synople, O=Synople, L=Test, S=Test, C=US" 2>/dev/null || {
            echo "Warning: Could not create release keystore"
        }
    fi

    # Set environment variables for release signing
    export RELEASE_KEYSTORE="release.keystore"
    export RELEASE_KEYSTORE_PASSWORD="android"
    export RELEASE_KEY_ALIAS="glassrelease"
    export RELEASE_KEY_PASSWORD="android"

    GRADLE_TASK="assembleRelease"
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
else
    GRADLE_TASK="assembleDebug"
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    echo "Step 4: Building Debug APK for Google Glass..."
fi

echo "This may take a few minutes on first run..."

# Build with Termux-specific configuration (optimized for Glass/API 19)
./gradlew $GRADLE_TASK \
    -Dorg.gradle.jvmargs="-Xmx2048m -XX:MaxMetaspaceSize=512m" \
    -Pandroid.aapt2FromMavenOverride="$AAPT2_PATH" \
    --no-daemon \
    --warning-mode=none \
    --console=plain \
    --parallel \
    --build-cache \
    2>&1 | tee build-${BUILD_TYPE_LOWER}.log

# Check build result
if [ -f "$APK_PATH" ]; then
    echo
    echo "=== BUILD SUCCESSFUL! ==="
    echo "GlassAssistant APK created at: $APK_PATH"
    echo
    ls -lh "$APK_PATH"
    echo

    # Copy to /sdcard/glassassistant/ for easy updates
    if [ "$BUILD_TYPE_LOWER" = "debug" ]; then
        echo "Copying APK to /sdcard/glassassistant/ for updates..."
        mkdir -p /sdcard/glassassistant
        cp "$APK_PATH" /sdcard/glassassistant/glassassistant-debug.apk
        if [ -f "/sdcard/glassassistant/glassassistant-debug.apk" ]; then
            echo "APK copied to: /sdcard/glassassistant/glassassistant-debug.apk"
            ls -lh /sdcard/glassassistant/glassassistant-debug.apk
        else
            echo "Warning: Failed to copy APK to /sdcard/glassassistant/"
        fi
    else
        echo "Copying release APK to /sdcard/glassassistant/ for distribution..."
        mkdir -p /sdcard/glassassistant
        cp "$APK_PATH" /sdcard/glassassistant/glassassistant-release.apk
        if [ -f "/sdcard/glassassistant/glassassistant-release.apk" ]; then
            echo "APK copied to: /sdcard/glassassistant/glassassistant-release.apk"
            ls -lh /sdcard/glassassistant/glassassistant-release.apk
        fi
    fi
    
    echo
    echo "=== BUILD COMPLETE ==="
    echo "To install on Google Glass manually:"
    echo "  1. Copy APK to Glass device (via USB or file transfer)"
    echo "  2. Enable 'Unknown sources' in Glass Settings → Device info → Turn on debug"
    echo "  3. Install using: adb install -r <apk_path>"
    echo "  4. Or use a file manager to open the APK"
    echo
    if [ "$BUILD_TYPE_LOWER" = "release" ]; then
        echo "Note: Release APK uses test signing key. For production, use proper signing."
    fi
    echo "Glass-specific notes:"
    echo "  - Ensure Glass is in debug mode for sideloading"
    echo "  - This APK is optimized for Glass XE24 (API 19/KitKat)"
    echo "  - Camera and voice features require Glass permissions"
else
    echo
    echo "=== BUILD FAILED ==="
    echo "Check build-${BUILD_TYPE_LOWER}.log for details"
    echo
    echo "Common issues:"
    echo "1. AAPT2 compatibility - patched AAPT2 required for Termux"
    echo "2. Memory issues - try closing other apps or reducing -Xmx value"
    echo "3. Java version - Java 11 recommended for Glass/API 19 compatibility"
    echo "4. SDK version mismatch - ensure API 19 is available in Android SDK"
    echo "5. Missing dependencies - run './gradlew --refresh-dependencies'"
    echo
    echo "Glass-specific issues:"
    echo "- Ensure targetSdk = 19 in app/build.gradle.kts"
    echo "- MultiDex issues require androidx.multidex:multidex:2.0.1"
    exit 1
fi