# Android Efficiency Plugin — AI Context

> This file contains everything needed to continue work on the project without losing context.
> Update it with every significant change.

---

## 📋 What This Is

An IntelliJ Platform plugin for **Android Studio** that speeds up the build and deploy cycle.

**Problem**: The standard Run ▶ button in Android Studio is slow due to internal checks.  
**Solution**: A GUI wrapper over `./gradlew installDebug --offline --parallel` and other flags that runs the command in the IDE Terminal.

---

## 🏗️ Architecture

```
Plugin
├── PluginSettings           → Stores all settings (PersistentStateComponent)
├── BuildToolWindowPanel     → Main UI (Tool Window "Fast Deploy")
├── BuildCommandComposer     → Builds the ./gradlew command from settings
├── GradleCommandExecutor    → Runs the command (used directly, not through Panel)
├── TerminalRunner           → Runs the command in IDE Terminal (TerminalView API)
├── FlavorDetector           → Auto-detects Android build flavors
├── FlavorCache              → In-memory cache for flavors
├── AndroidCliExecutor       → Phase 2: integration with `android` CLI
├── DeviceResolver           → Phase 2: device list via adb
├── QuickBuildAction         → Action (Ctrl+Shift+F10) quick launch
└── PluginSettingsConfigurable → Page in IDE Settings → Tools
```

### Gradle task naming convention:
```
:{module}:{task}{Flavor}{BuildType}

Examples:
  :app:installDebug           (no flavor)
  :app:installDevDebug        (flavor=dev)
  :app:assembleProdRelease    (flavor=prod, task=assemble)
  :app:bundleStagingDebug     (flavor=staging, task=bundle)
```

---

## 📁 File Structure

```
AndroidEfficiencyPlugin/
├── AGENTS.md                          ← this file
├── README.md                          ← user documentation
├── build.gradle.kts                   ← IntelliJ Platform Gradle Plugin 2.6.0
├── settings.gradle.kts                ← pluginManagement + rootProject.name only
├── gradle.properties                  ← versions, build range
├── gradlew / gradlew.bat
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties      ← Gradle 8.11
└── src/
    ├── main/
    │   ├── kotlin/com/androidefficiency/plugin/
    │   │   ├── settings/
    │   │   │   ├── PluginSettings.kt                ← @Service PROJECT level, BaseState
    │   │   │   └── PluginSettingsConfigurable.kt    ← Swing FormBuilder (NOT Kotlin UI DSL)
    │   │   ├── toolwindow/
    │   │   │   ├── BuildToolWindowFactory.kt        ← simple factory
    │   │   │   └── BuildToolWindowPanel.kt          ← all UI in plain Swing
    │   │   ├── execution/
    │   │   │   ├── BuildCommandComposer.kt          ← builds command (GeneralCommandLine + string)
    │   │   │   ├── GradleCommandExecutor.kt         ← OSProcessHandler (not used in Panel)
    │   │   │   ├── TerminalRunner.kt                ← runs in IDE Terminal (TerminalView)
    │   │   │   └── AndroidCliExecutor.kt            ← Phase 2
    │   │   ├── flavor/
    │   │   │   ├── FlavorDetector.kt                ← reflection + regex fallback (both always run)
    │   │   │   └── FlavorCache.kt                   ← ConcurrentHashMap
    │   │   ├── actions/
    │   │   │   └── QuickBuildAction.kt              ← AnAction, shortcut Ctrl+Shift+F10
    │   │   └── util/
    │   │       ├── GradlewResolver.kt               ← find gradlew + chmod +x
    │   │       └── DeviceResolver.kt                ← adb devices parser
    │   └── resources/
    │       ├── META-INF/plugin.xml
    │       └── icons/pluginIcon.svg
    └── test/
        └── kotlin/com/androidefficiency/plugin/
            ├── execution/BuildCommandComposerTest.kt
            └── flavor/FlavorDetectorTest.kt
```

---

## ✅ Current Status

### Implemented

- [x] Full project structure
- [x] plugin.xml with all component registrations
- [x] PluginSettings (persisted to `.idea/AndroidEfficiencyPlugin.xml`)
- [x] BuildCommandComposer — correct task name format + `getTerminalCommand()` method
- [x] TerminalRunner — runs in IDE Terminal, supports new/active tab
- [x] FlavorDetector — combines reflection and regex (both always run, merged result)
- [x] FlavorCache — automatically invalidated after Gradle Sync (`GradleSyncListener`)
- [x] BuildToolWindowPanel — full UI: flag checkboxes, flavor picker, live preview
- [x] BuildToolWindowFactory
- [x] QuickBuildAction (Ctrl+Shift+F10)
- [x] PluginSettingsConfigurable (Swing FormBuilder)
- [x] GradlewResolver
- [x] AndroidCliExecutor, DeviceResolver (Phase 2, not integrated into UI)
- [x] Unit tests (BuildCommandComposerTest, FlavorDetectorTest)
- [x] Broad compatibility: Ladybug 242 → Quail 261

### TODO

- [ ] Phase 2 UI — Gradle/CLI toggle and device dropdown in `BuildToolWindowPanel`
- [ ] Multi-module support — `ComboBox` for module selection instead of manual input
- [ ] Migrate TerminalRunner to the new Reworked Terminal API (available from 2025.3, currently experimental)
- [ ] Test on a project with multi-dimension flavors

---

## ⚠️ Known Issues and Solutions

### 1. `PluginSettingsConfigurable` — Do NOT use Kotlin UI DSL v2
**Problem**: `DialogPanel`, `bindText`, `bindItem` — API is incompatible with this platform version.  
**Solution**: Plain Swing with `FormBuilder` + manual `apply()/reset()/isModified()`.

### 2. `BaseState.by string("")` returns `String?`
**Problem**: All `by string(...)` fields are nullable `String?`.  
**Solution**: Use `?: ""` or `.orEmpty()` everywhere:
```kotlin
val module = (state.selectedModule ?: "").trim().ifEmpty { "app" }
```

### 3. `settings.gradle.kts` — without `org.jetbrains.intellij.platform.settings` plugin
**Problem**: The settings plugin does not register the `intellijPlatform` extension in `dependencyResolutionManagement`.  
**Solution**: Settings plugin removed. Repositories in `build.gradle.kts`:
```kotlin
repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}
```

### 4. `runIde` task unavailable
**Solution**: `buildPlugin` → Install from Disk in the real Android Studio.

### 5. Panda 4 incompatibility (fixed)
**Cause**: `platformVersion=2024.3.1.14` (Meerkat 243) + `performanceTesting.jar` bug in IPGP 2.1.0.  
**Solution**:
- `platformVersion=2024.2.1.12` (Ladybug — build target = lower bound)
- IPGP 2.1.0 → 2.6.0 (fix #1738)
- `pluginSinceBuild=242`, `pluginUntilBuild=261.*`

### 6. TerminalRunner — deprecated API
`TerminalView` / `ShellTerminalWidget` deprecated in favor of the Reworked Terminal API (2025.3+, experimental).  
Suppressed via `@file:Suppress("DEPRECATION")` with a TODO to migrate when `sinceBuild >= 253` and the API is stable.

### 7. BoxLayout Y_AXIS — mixed alignmentX
**Problem**: All child panels of BoxLayout Y_AXIS must have the same `alignmentX = LEFT_ALIGNMENT`. Otherwise mixing 0.0 and 0.5 shifts content to the right.  
**Solution**: `alignmentX = Component.LEFT_ALIGNMENT` on every row panel.

---

## 🔧 Tech Stack

| Component | Version / Technology |
|-----------|---------------------|
| Language | Kotlin 2.1.0 (100%) |
| Build system | Gradle 8.11 |
| IntelliJ Platform Plugin | `org.jetbrains.intellij.platform` 2.6.0 |
| Target IDE | Android Studio 2024.2.1 Ladybug (build 242, floor) |
| Compatibility | builds 242–261 (Ladybug Oct'24 → Quail Canary) |
| UI | Swing (JPanel, JRadioButton, JCheckBox, ComboBox, JBScrollPane) |
| Settings persistence | `SimplePersistentStateComponent<BaseState>` |
| Build execution | `TerminalRunner` → IDE Terminal (TerminalView API) |
| Flavor detection | Reflection (GradleAndroidModel) + regex, both always run, merged |
| Flavor invalidation | `GradleSyncListener` — automatically after sync |
| Testing | JUnit 4 + Mockito |

---

## 🚀 Commands

```bash
# Compile
./gradlew compileKotlin --no-configuration-cache

# Run tests
./gradlew test --no-configuration-cache

# Build ZIP
./gradlew buildPlugin --no-configuration-cache

# Full build with tests
./gradlew build --no-configuration-cache
```

### Installing in the real Android Studio:
1. `./gradlew buildPlugin` → ZIP in `build/distributions/`
2. Android Studio → Settings → Plugins → ⚙️ → Install Plugin from Disk → select ZIP → Restart

---

## 🗝️ Key Decisions

### Why Swing and not Kotlin UI DSL v2?
`bindText` requires `KMutableProperty0<String>` (non-null), but `BaseState.by string()` returns `String?`. Plain Swing with manual `apply()/reset()` is simpler.

### Why PROJECT-level settings?
Different Android projects have different flavors and flags. Stored in `.idea/AndroidEfficiencyPlugin.xml`.

### Why reflection for FlavorDetector?
`GradleAndroidModel` is an internal API that changes between versions. Reflection + regex fallback ensures compatibility. Both methods always run and results are merged.

### Why no Plugin Console?
The built-in ConsoleView caused UX problems: it appeared at the bottom on launch and shrank the config area with a scrollbar. IDE Terminal is the native solution without these issues.

### Why is the Plugin Console radio button disabled?
Left as a placeholder for possible future integration, but without an implementation.

---

## 📦 Dependencies (IntelliJ Platform)

```kotlin
intellijPlatform {
    androidStudio("2024.2.1.12")              // Build target = floor (Ladybug)
    bundledPlugin("org.jetbrains.android")    // Android plugin API (for FlavorDetector)
    bundledPlugin("org.jetbrains.plugins.terminal") // Terminal API (for TerminalRunner)
    pluginVerifier()
    testFramework(TestFrameworkType.Platform)
}
testImplementation("junit:junit:4.13.2")
testImplementation("org.mockito:mockito-core:5.8.0")
testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
```

---

## 💬 Development History

- **Phase 1**: All base components written in a single session
- **Gradle config fix**: settings plugin removed, repositories moved to build.gradle.kts
- **Compilation fixes**: nullable String?, Configurable rewritten in Swing
- **Panda 4 fix**: platformVersion downgraded to Ladybug, IPGP 2.1→2.6
- **UI fix**: BoxLayout alignmentX, BorderLayout NORTH for viewport width
- **Terminal integration**: TerminalRunner added, plugin console removed
- **Flavor fix**: merged detection strategies, GradleSyncListener for auto-update
- **Code health**: kotlinOptions→compilerOptions, Charset.forName→Charsets.UTF_8, CopyPasteManager
