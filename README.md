# Android Efficiency Plugin — Fast Deploy ⚡

> An Android Studio plugin that replaces the slow Run ▶ button with a direct `./gradlew` call using optimized flags via the IDE Terminal.

## Why Is This Faster?

The standard Android Studio run performs many internal checks before each build. A direct terminal call allows you to:
- Use `--offline` — skip network dependency checks
- Use `--parallel` — build modules in parallel
- Use `--configuration-cache` — cache configuration between runs
- Avoid the Android Studio overhead itself

**Typical gain: 30–60% of launch time.**

## Features

- **Tool Window "Fast Deploy"** — right-side panel with full configuration
- **Checkboxes for all Gradle flags**: `--offline`, `--parallel`, `--configuration-cache`, `--build-cache`, `--daemon`, `--configure-on-demand`, `--stacktrace`, `--info`, `--debug`, `--dry-run`
- **Auto-detection of build flavors** from the project — reflection on GradleAndroidModel + regex fallback, automatically updated after Gradle Sync
- **Manual flavor input** if auto-detection fails
- **Live preview** of the final command — updates on every change
- **Run in IDE Terminal** — command executes in the Terminal tool window with full PTY, ANSI colors, and history
- **Terminal tab selection** — new tab or the currently active one
- **Settings persistence** between IDE restarts (`.idea/AndroidEfficiencyPlugin.xml`)
- **Hotkey** `Ctrl+Shift+F10` (mac: `Cmd+Shift+F10`) for quick launch
- **Copy command** to clipboard

## Development Setup

### Requirements
- JDK 17+
- Gradle 8.11+ (via wrapper, downloaded automatically)
- Android Studio Ladybug (2024.2.1) or higher

### Building the Plugin

```bash
./gradlew buildPlugin --no-configuration-cache
```

The plugin ZIP will appear in `build/distributions/`.

### Installing in Android Studio

1. Android Studio → Settings → Plugins → ⚙️ → Install Plugin from Disk…
2. Select the ZIP from `build/distributions/`
3. Restart the IDE

## Usage

1. Open an **Android project** in Android Studio
2. Open the **"Fast Deploy"** Tool Window (right panel)
3. Configure flags and select a flavor
4. Click **▶ Run in Terminal** or use `Ctrl+Shift+F10`

The command will open in a new Terminal tab (or the active one if "Use active tab" is enabled).

## Project Structure

```
src/main/kotlin/.../
├── settings/
│   ├── PluginSettings.kt              # Persistent settings
│   └── PluginSettingsConfigurable.kt  # Preferences page
├── toolwindow/
│   ├── BuildToolWindowFactory.kt      # Tool Window registration
│   └── BuildToolWindowPanel.kt        # Main UI
├── execution/
│   ├── BuildCommandComposer.kt        # Command construction
│   ├── GradleCommandExecutor.kt       # Run via GeneralCommandLine
│   ├── TerminalRunner.kt              # Run in IDE Terminal
│   └── AndroidCliExecutor.kt          # Phase 2: Android CLI
├── flavor/
│   ├── FlavorDetector.kt              # Build flavor detection
│   └── FlavorCache.kt                 # Cache (invalidated after Gradle Sync)
├── actions/
│   └── QuickBuildAction.kt            # Shortcut action
└── util/
    ├── GradlewResolver.kt             # Locate gradlew
    └── DeviceResolver.kt              # Device list
```

## Example Generated Commands

```bash
# No flavor (Debug)
./gradlew :app:installDebug \
    --offline --parallel \
    --configuration-cache --build-cache

# With flavor (Dev + Release)
./gradlew :app:installDevRelease \
    --offline --parallel \
    --configuration-cache --build-cache \
    --daemon

# Assemble APK only (prod + Release)
./gradlew :app:assembleProdRelease \
    --offline --parallel \
    --configuration-cache
```

## Compatibility

| Android Studio | Codename | Build | Support |
|---|---|---|---|
| 2024.2.1 | Ladybug | 242.x | ✅ minimum |
| 2024.3.1 | Meerkat | 243.x | ✅ |
| 2025.1.1 | Narwhal | 251.x | ✅ |
| 2025.2.1 | Otter | 252.x | ✅ |
| 2025.3.x | Panda | 253.x | ✅ |
| 2026.1.1 | Quail | 261.x | ✅ |

## License

Apache 2.0
