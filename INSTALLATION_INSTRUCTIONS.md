# Google Glass XE24 Installation Instructions

## ✅ APK Build Complete!

**APK Location:** `app/build/outputs/apk/debug/app-debug.apk`
- **Size:** 8.4 MB
- **Package:** dev.synople.glassassistant
- **Version:** 1.0.2 (Build 3)
- **Min SDK:** API 19 (Android 4.4 KitKat) - Compatible with Glass XE24
- **Target SDK:** API 19

## Installation Methods

### Method 1: USB Installation (Recommended)

1. **Enable Developer Mode on Glass:**
   - Go to Settings → Device info
   - Tap build number 7 times
   - Return to Settings → Developer options
   - Enable USB debugging

2. **Connect Glass to Computer:**
   - Use the micro USB cable
   - Glass should appear when running: `adb devices`

3. **Install the APK:**
   ```bash
   export ANDROID_HOME=/home/will/Android/Sdk
   export PATH=$PATH:$ANDROID_HOME/platform-tools
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Method 2: Transfer and Install

1. **Copy APK to Glass:**
   ```bash
   adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/
   ```

2. **Install using Glass file manager or launcher**

### Method 3: Web Server Installation

1. **Host the APK:**
   ```bash
   cd app/build/outputs/apk/debug/
   python3 -m http.server 8000
   ```

2. **On Glass browser, navigate to:**
   ```
   http://[your-computer-ip]:8000/app-debug.apk
   ```

## Troubleshooting

### If Glass is not detected:
```bash
adb kill-server
adb start-server
adb devices
```

### If installation fails:
1. Check if previous version exists:
   ```bash
   adb uninstall dev.synople.glassassistant
   ```

2. Install with replacement:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

### To view logs from Glass:
```bash
adb logcat | grep -i glassassistant
```

## App Permissions
The app will request:
- Camera access (for AR features)
- Microphone access (for voice commands)
- Internet access (for API calls)
- Storage access (for caching)

## Next Steps

1. Launch the app from Glass launcher
2. Configure API keys in settings
3. Test voice commands with "OK Glass"

## Build Information

- Built with multidex support for compatibility
- Compiled against Android SDK 34
- Targets Google Glass XE24 (API 19)
- Debug build - not optimized for production

For release build, run:
```bash
./gradlew assembleRelease
```

## Support

If the app crashes or doesn't work properly on Glass:
1. Check logcat for errors
2. Ensure Glass firmware is XE24
3. Verify all permissions are granted
4. Try clearing app data and cache