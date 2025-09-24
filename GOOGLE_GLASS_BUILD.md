# Building for Google Glass XE24

## Prerequisites

1. **Run the setup script first** (requires sudo password):
   ```bash
   ./setup_build_env.sh
   source ~/.bashrc
   ```

2. **Google Glass Setup**:
   - Enable Developer Options (Settings → Device info → Tap build number 7 times)
   - Enable USB Debugging (Settings → Developer options → USB debugging)
   - Connect Glass to computer via USB

## Building the APK

Run the build script:
```bash
./build_glass_apk.sh
```

## Installing on Glass

After successful build:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or for release build:
```bash
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

## Troubleshooting

### If Java is not found:
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$PATH:$JAVA_HOME/bin
```

### If Android SDK is not found:
```bash
export ANDROID_HOME=~/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools
```

### If Glass is not detected:
1. Check USB connection
2. Verify USB debugging is enabled
3. Run `adb devices` to see if Glass appears
4. Try `adb kill-server && adb start-server`

## Compatibility Notes

- Google Glass XE24 runs Android API 19 (KitKat 4.4)
- Some modern Android features may not be available
- The app has been configured with minSdk=19 and targetSdk=19 for Glass compatibility
- Test thoroughly on actual Glass hardware