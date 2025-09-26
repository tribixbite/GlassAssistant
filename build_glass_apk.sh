#!/bin/bash

echo "Building APK for Google Glass XE24..."

# This script is optimized for Termux environment
# For GitHub Actions, use the workflow in .github/workflows/build-apk.yml

if [ -d "/data/data/com.termux" ]; then
    echo "📱 Running on Termux"
    # Termux-specific environment setup
    if [ -z "$JAVA_HOME" ]; then
        export JAVA_HOME=/data/data/com.termux/files/usr
        export PATH=$PATH:$JAVA_HOME/bin
    fi

    # Use the complete Android SDK in home directory
    export ANDROID_HOME=/data/data/com.termux/files/home/android-sdk
    export ANDROID_SDK_ROOT=$ANDROID_HOME
    export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
    export PATH=$PATH:$ANDROID_HOME/platform-tools

    # Verify aapt2 override is configured for Termux
    if [ ! -f "tools/aapt2-arm64/aapt2" ]; then
        echo "⚠️  Warning: Termux aapt2 binary not found at tools/aapt2-arm64/aapt2"
        echo "   This is required for Termux builds. The gradle.properties file"
        echo "   contains: android.aapt2FromMavenOverride=/data/data/com.termux/files/home/git/glass/GlassAssistant/tools/aapt2-arm64/aapt2"
    fi
else
    echo "❌ This script is designed for Termux environment."
    echo "For other platforms:"
    echo "  - GitHub Actions: Use .github/workflows/build-apk.yml"
    echo "  - Local development: Use './gradlew assembleDebug' directly"
    echo "  - Make sure JAVA_HOME and ANDROID_HOME are set"
    exit 1
fi

# Verify environment
echo "Java version:"
java -version
echo ""
echo "Android SDK location: $ANDROID_HOME"
if [ ! -d "$ANDROID_HOME" ]; then
    echo "❌ Android SDK not found at $ANDROID_HOME"
    echo "Please install Android SDK: pkg install android-sdk"
    exit 1
fi
echo ""

# Clean previous builds
echo "Cleaning previous builds..."
./gradlew clean

# Build debug APK
echo "Building debug APK..."
./gradlew assembleDebug

# Check if build was successful
if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Build successful!"
    echo ""
    echo "APK location:"
    find app/build/outputs/apk -name "*.apk" -type f
    echo ""
    echo "To install on Google Glass:"
    echo "1. Enable Developer Options on your Glass"
    echo "2. Enable USB Debugging"
    echo "3. Connect Glass via USB"
    echo "4. Run: adb install app/build/outputs/apk/debug/app-debug.apk"
else
    echo "❌ Build failed. Please check the error messages above."
    exit 1
fi