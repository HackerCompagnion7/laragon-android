# Laragon Android - MVP

A self-contained local web development environment for Android devices. Run PHP scripts and serve static websites directly from your phone or tablet.

## Features (MVP)

- **Local HTTP Server** (Ktor CIO) serving static files and PHP scripts
- **PHP Execution** via embedded `php-cgi` binary (CGI mode)
- **Project Management** using Android Storage Access Framework (SAF) — no storage permissions needed
- **Built-in WebView** preview for instant feedback
- **Text Editor** for quick file edits without leaving the app
- **Real-time Diagnostics** — server status, IP, port, RAM, CPU, logs
- **Foreground Service** that survives screen rotation and app minimization

## Requirements

- Android 8.0 (API 26) or higher
- ARM64 (aarch64) device
- ~80 MB storage for the APK

## Build Instructions

### Prerequisites

1. **Android Studio** (Flamingo 2022.2.1 or later)
2. **JDK 17**
3. **Android SDK** with platform 34
4. **PHP Binary** (see below)

### Step 1: Get the PHP Binary

Before building, you must provide the `php-cgi` binary for ARM64:

**Option A: Use the download script**
```bash
cd laragon-android
./download_php_binary.sh
```

**Option B: Manual extraction from Termux**
1. Install Termux from F-Droid on your device
2. Run `pkg install php` inside Termux
3. Copy `/data/data/com.termux/files/usr/bin/php-cgi` from your device
4. Place it at `app/src/main/assets/bin/php/arm64/php-cgi`

**Option C: Build from Termux packages source**
1. Clone `https://github.com/termux/termux-packages`
2. Set up the build environment following their README
3. Build the PHP package for aarch64
4. Copy the resulting `php-cgi` binary to the assets directory

> **Important**: The binary must be a statically-linked ELF binary for aarch64 Linux (Android Bionic). The Termux-built binary is recommended as it's compiled specifically for Android.

### Step 2: Open in Android Studio

```bash
# Open the project
cd laragon-android
# If using command line:
# studio .
```

Or: File → Open → select the `laragon-android` directory

### Step 3: Sync Gradle

Android Studio will prompt you to sync Gradle. Click "Sync Now" or use:
```
File → Sync Project with Gradle Files
```

### Step 4: Build the APK

**Debug APK:**
```bash
./gradlew assembleDebug
```

The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

**Release APK:**
```bash
./gradlew assembleRelease
```

### Step 5: Install and Test

```bash
# Install on connected device/emulator
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or drag and drop the APK to your device.

## Testing the Minimum Viable Flow

1. **Open the app** on your device
2. **Grant notification permission** when prompted
3. **Tap "Select Project Folder"** and choose a folder on your device
4. **Create a new project** (tap the + button or menu → "New Project")
   - Name it "test" — the app creates a folder with `index.php`
5. **Tap "Start Server"** — a persistent notification appears
6. **Tap "Preview"** — the WebView loads `http://localhost:8080/`
7. You should see the PHP output (welcome page or `phpinfo()`)
8. **Edit a file**: tap on a project → "Edit index.php"
9. Make changes → tap Save → the preview auto-reloads
10. **Check diagnostics**: menu → "Diagnostics" to see server stats and logs

## Project Structure

```
laragon-android/
├── app/
│   ├── build.gradle.kts           # App-level build config
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   └── bin/php/arm64/
│       │       └── php-cgi         # ARM64 PHP binary (must be provided)
│       ├── java/com/laragon/android/
│       │   ├── service/
│       │   │   └── LaragonService.kt    # ForegroundService + server lifecycle
│       │   ├── server/
│       │   │   └── LaragonServer.kt     # Ktor HTTP server
│       │   ├── php/
│       │   │   └── PhpCgiHandler.kt     # PHP-CGI process execution
│       │   ├── ui/
│       │   │   ├── main/
│       │   │   │   ├── MainActivity.kt   # Project list + server controls
│       │   │   │   └── ProjectAdapter.kt # RecyclerView adapter
│       │   │   ├── editor/
│       │   │   │   └── EditorActivity.kt # Text file editor
│       │   │   ├── preview/
│       │   │   │   └── PreviewActivity.kt# WebView preview
│       │   │   └── diagnostics/
│       │   │       └── DiagnosticsFragment.kt # Server stats
│       │   └── util/
│       │       ├── ServerConfig.kt      # Constants and config
│       │       ├── LogRotator.kt        # Rotating log files
│       │       ├── ResourceMonitor.kt   # CPU/RAM/IP monitoring
│       │       └── PhpBinaryManager.kt  # Binary extraction/setup
│       └── res/
│           ├── layout/                  # XML layouts
│           ├── menu/                    # Action bar menus
│           └── values/                  # Strings, colors, themes
├── build.gradle.kts                    # Root build config
├── settings.gradle.kts                 # Project settings
├── gradle.properties                   # Gradle properties
├── gradle/wrapper/
│   └── gradle-wrapper.properties       # Gradle 8.5
├── download_php_binary.sh              # Script to download PHP binary
└── README.md
```

## Architecture

### Server Flow
```
User taps "Start" → LaragonService (ForegroundService)
  → Extracts php-cgi binary (first run only)
  → Starts Ktor CIO server on port 8080
  → Shows persistent notification

HTTP Request → Ktor Routing
  → .php file? → PhpCgiHandler.execute()
    → ProcessBuilder("php-cgi", envVars)
    → Parse CGI response (headers + body)
    → Return to Ktor → Client
  → Static file? → Read via DocumentFile/SAF → Return bytes

User taps "Stop" → LaragonService stops Ktor → Notification removed
```

### Key Design Decisions

1. **Storage Access Framework (SAF)**: No `READ_EXTERNAL_STORAGE` or `WRITE_EXTERNAL_STORAGE` permissions. All file access uses content URIs and `DocumentFile`.

2. **PHP via CGI**: Each PHP request spawns a `php-cgi` process. Not pooled (acceptable for local development). Uses `Dispatchers.IO` to avoid blocking.

3. **Ktor CIO**: Lightweight HTTP engine with no native dependencies. Suitable for Android's constraints.

4. **Foreground Service**: Required for long-running server. Uses `START_STICKY` and `foregroundServiceType="dataSync"`.

5. **Log Rotation**: Logs are written to internal storage with automatic rotation (512 KB max, 3 rotations).

## Limitations (MVP)

- **No MySQL/MariaDB** — Only SQLite (via PHP's built-in sqlite3 extension)
- **No virtual hosts** — Single document root
- **No DNS proxy** — Access via `localhost:8080` only
- **No FastCGI pooling** — One `php-cgi` process per request
- **ARM64 only** — No x86/x86_64 or ARM32 support in this version
- **PHP binary must be provided separately** — Not included in the repository due to licensing
- **Termux integration** — Stub only, not implemented in this version

## Troubleshooting

### "PHP Binary Not Found" error
- Ensure `php-cgi` is placed at `app/src/main/assets/bin/php/arm64/php-cgi`
- The binary must be executable and compiled for aarch64 Android
- Run `download_php_binary.sh` for automated download or manual instructions

### Server won't start
- Check that a project folder has been selected via SAF
- Check Diagnostics for error logs
- Ensure notification permission is granted (Android 13+)

### PHP pages show errors
- Verify the php-cgi binary includes required extensions (sqlite3, json, mbstring, session)
- Check PHP Log in Diagnostics
- Test the binary separately: run `php-cgi -v` in Termux

### WebView shows "Server Not Running"
- Start the server from the main screen first
- Check that the foreground service notification is visible

## License

This project is provided as-is for development and educational purposes.
The PHP binary is subject to the PHP License (https://www.php.net/license).
