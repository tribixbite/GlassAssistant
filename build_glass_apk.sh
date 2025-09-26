#!/bin/bash

echo "Building APK for Google Glass XE24..."

# Detect environment and set appropriate paths
if [ "$GITHUB_ACTIONS" = "true" ]; then
    echo "🚀 Running on GitHub Actions"
    # GitHub Actions environment - use default JAVA_HOME and ANDROID_HOME
    if [ -z "$JAVA_HOME" ]; then
        echo "❌ JAVA_HOME not set in GitHub Actions"
        exit 1
    fi
    if [ -z "$ANDROID_HOME" ]; then
        echo "❌ ANDROID_HOME not set in GitHub Actions"
        exit 1
    fi
elif [ -d "/data/data/com.termux" ]; then
    echo "📱 Running on Termux"
    # Termux environment
    if [ -z "$JAVA_HOME" ]; then
        export JAVA_HOME=/data/data/com.termux/files/usr
        export PATH=$PATH:$JAVA_HOME/bin
    fi

    # Use the complete Android SDK in home directory
    export ANDROID_HOME=/data/data/com.termux/files/home/android-sdk
    export ANDROID_SDK_ROOT=$ANDROID_HOME
    export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
    export PATH=$PATH:$ANDROID_HOME/platform-tools
else
    echo "🖥️ Running on unknown environment - using existing environment variables"
    # Generic Linux/Unix environment - rely on existing JAVA_HOME and ANDROID_HOME
    if [ -z "$JAVA_HOME" ]; then
        echo "❌ JAVA_HOME not set. Please set it to your JDK installation path."
        exit 1
    fi
    if [ -z "$ANDROID_HOME" ]; then
        echo "❌ ANDROID_HOME not set. Please set it to your Android SDK path."
        exit 1
    fi
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