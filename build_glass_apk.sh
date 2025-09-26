#!/bin/bash

echo "Building APK for Google Glass XE24..."

# Set environment variables for Termux
if [ -z "$JAVA_HOME" ]; then
    export JAVA_HOME=/data/data/com.termux/files/usr
    export PATH=$PATH:$JAVA_HOME/bin
fi

if [ -z "$ANDROID_HOME" ]; then
    export ANDROID_HOME=/data/data/com.termux/files/usr/share/android-sdk
    export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
    export PATH=$PATH:$ANDROID_HOME/platform-tools
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