# Android Efficiency Plugin — AI Context

> Этот файл содержит всё необходимое для продолжения работы над проектом без потери контекста.
> Обновляй его при каждом значимом изменении.

---

## 📋 Что это

IntelliJ Platform плагин для **Android Studio**, ускоряющий цикл сборки и деплоя.

**Проблема**: Стандартная кнопка Run ▶ в Android Studio медленная из-за внутренних проверок.  
**Решение**: GUI-обёртка над `./gradlew installDebug --offline --parallel` и другими флагами.  
**Бонус Phase 2**: Интеграция с Android CLI (новый инструмент Google, май 2026).

---

## 🏗️ Архитектура

```
Plugin
├── PluginSettings           → Хранение всех настроек (PersistentStateComponent)
├── BuildToolWindowPanel     → Главный UI (Tool Window "Fast Deploy")
├── BuildCommandComposer     → Формирование ./gradlew команды из настроек
├── GradleCommandExecutor    → Запуск команды + ConsoleView output
├── FlavorDetector           → Автоопределение Android build flavors
├── FlavorCache              → In-memory кэш для flavors
├── AndroidCliExecutor       → Phase 2: интеграция с `android` CLI
├── DeviceResolver           → Phase 2: список устройств через adb
├── QuickBuildAction         → Action (Ctrl+Shift+F10) быстрый запуск
└── PluginSettingsConfigurable → Страница в IDE Settings → Tools
```

### Naming convention для Gradle task:
```
:{module}:{task}{Flavor}{BuildType}

Примеры:
  :app:installDebug           (нет flavor)
  :app:installDevDebug        (flavor=dev)
  :app:assembleProdRelease    (flavor=prod, task=assemble)
  :app:bundleStagingDebug     (flavor=staging, task=bundle)
```

---

## 📁 Структура файлов

```
AndroidEfficiencyPlugin/
├── GEMINI.md                          ← этот файл
├── README.md                          ← пользовательская документация
├── build.gradle.kts                   ← IntelliJ Platform Gradle Plugin 2.1.0
├── settings.gradle.kts                ← только pluginManagement + rootProject.name
├── gradle.properties                  ← версии, build range, local dev path
├── gradlew / gradlew.bat
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties      ← Gradle 8.11
└── src/
    ├── main/
    │   ├── kotlin/com/androidefficiency/plugin/
    │   │   ├── settings/
    │   │   │   ├── PluginSettings.kt                ← @Service PROJECT level, BaseState
    │   │   │   └── PluginSettingsConfigurable.kt    ← Swing FormBuilder (НЕ Kotlin UI DSL)
    │   │   ├── toolwindow/
    │   │   │   ├── BuildToolWindowFactory.kt        ← простая фабрика
    │   │   │   └── BuildToolWindowPanel.kt          ← весь UI на чистом Swing
    │   │   ├── execution/
    │   │   │   ├── BuildCommandComposer.kt          ← строит GeneralCommandLine
    │   │   │   ├── GradleCommandExecutor.kt         ← OSProcessHandler + background task
    │   │   │   └── AndroidCliExecutor.kt            ← Phase 2
    │   │   ├── flavor/
    │   │   │   ├── FlavorDetector.kt                ← reflection + regex fallback
    │   │   │   └── FlavorCache.kt                   ← ConcurrentHashMap
    │   │   ├── compat/
    │   │   │   └── IdeCompat.kt                     ← Phase 2 runtime gate (build ≥ Panda 4)
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

## ✅ Текущий статус

### Реализовано (Phase 1 — Gradle wrapper)
- [x] Вся структура проекта
- [x] plugin.xml с регистрацией всех компонентов
- [x] PluginSettings (15 полей, сохранение в .idea/AndroidEfficiencyPlugin.xml)
- [x] BuildCommandComposer с правильным форматом task name
- [x] GradleCommandExecutor (background thread, progress, notifications)
- [x] FlavorDetector (reflection на AndroidModuleModel + regex fallback)
- [x] FlavorCache
- [x] BuildToolWindowPanel (полный UI: чекбоксы, flavor picker, live preview, консоль)
- [x] BuildToolWindowFactory
- [x] QuickBuildAction (Ctrl+Shift+F10)
- [x] PluginSettingsConfigurable (Swing FormBuilder)
- [x] GradlewResolver
- [x] Иконка SVG
- [x] Unit тесты (BuildCommandComposerTest, FlavorDetectorTest)

### Реализовано (Phase 2 — Android CLI)
- [x] AndroidCliExecutor (isCliAvailable, buildRunCommand, describeProject, emulator commands)
- [x] DeviceResolver (adb devices -l parser)
- [x] IdeCompat.kt — runtime гейт Phase 2 (build ≥ Panda 4)

### TODO (не реализовано, требует работы)
- [ ] **Компиляция не верифицирована** — нужно прогнать `./gradlew compileKotlin` и исправить оставшиеся ошибки (теперь против Ladybug 242)
- [ ] Интеграция Phase 2 UI в BuildToolWindowPanel (переключатель Gradle/CLI, dropdown девайсов)
- [ ] Обновление PluginSettings для Phase 2 полей (useAndroidCli, targetDevice — есть в State, но не привязаны к UI)
- [ ] Инвалидация FlavorCache при изменении build.gradle (VirtualFileListener)
- [ ] Инвалидация FlavorCache при Gradle Sync (ProjectSyncListener)
- [ ] Поддержка multi-module (выбор модуля из списка вместо ручного ввода)
- [ ] Тест на реальном Android-проекте с flavors
- [ ] Иконка на тулбаре Android Studio

---

## ⚠️ Известные проблемы и их решения

### 1. `PluginSettingsConfigurable` — НЕ использовать Kotlin UI DSL v2
**Проблема**: `DialogPanel`, `bindText`, `bindItem`, `comment`, `align` — API несовместимо с этой версией платформы.  
**Решение**: Переписан на чистый Swing с `FormBuilder` + ручной `apply()/reset()/isModified()`.

### 2. `BaseState.by string("")` возвращает `String?`
**Проблема**: Все поля `by string(...)` в `BaseState` — nullable `String?`, несмотря на default value.  
**Решение**: Везде добавлен `?: ""` или `.orEmpty()`:
```kotlin
val module = (state.selectedModule ?: "").trim().ifEmpty { "app" }
val custom  = (state.customFlags ?: "").trim()
```

### 3. `settings.gradle.kts` — НЕ использовать `org.jetbrains.intellij.platform.settings` plugin
**Проблема**: Плагин `org.jetbrains.intellij.platform.settings` загружается, но не регистрирует `intellijPlatform` extension в `dependencyResolutionManagement`.  
**Решение**: Убрали settings plugin полностью. Репозитории настроены в `build.gradle.kts`:
```kotlin
repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}
```

### 4. `runIde` task — `ideDir` не существует в IPGP 2.x
**Проблема**: `ideDir = file(...)` — Unresolved reference в IPGP 2.1.0.  
**Решение**: Блок `runIde` убран из build.gradle.kts. Для тестирования использовать `buildPlugin` + Install from Disk.

### 5. Build compatibility range + платформа
**Проблема**: Плагин не грузился на Panda 4 (`253.32098.37`). Причина — `platformVersion=2024.3.1.14` (Meerkat): компиляция против 243 подтягивала символы, удалённые в 253.  
**Решение**:
- Понижен `platformVersion` до **Ladybug** (`2024.2.1.12`) — build target = нижняя граница, форвард-совместим со всеми более новыми версиями  
- Расширен `pluginUntilBuild=261.*` (захватывает Quail Canary)
```properties
platformVersion=2024.2.1.12
pluginSinceBuild=242
pluginUntilBuild=261.*
```
**Phase 2 runtime гейт** — `IdeCompat.isPhase2Supported()` в `compat/IdeCompat.kt` сравнивает текущий build с `253.32098.37` (Panda 4).

---

## 🔧 Техстек

| Компонент | Версия / Технология |
|-----------|---------------------|
| Language | Kotlin 2.1.0 (100%) |
| Build system | Gradle 8.11 |
| IntelliJ Platform Plugin | `org.jetbrains.intellij.platform` 2.1.0 |
| Target IDE | Android Studio 2024.2.1 Ladybug (build 242, floor) |
| Совместимость | builds 242–261 (Ladybug Oct'24 → Quail Canary) |
| Phase 2 гейт | Panda 4+ (build ≥ 253.32098.37) + `android` CLI в PATH |
| UI | Swing (JPanel, JCheckBox, ComboBox, JBSplitter) |
| Settings persistence | `SimplePersistentStateComponent<BaseState>` |
| Process execution | `GeneralCommandLine` + `OSProcessHandler` |
| Flavor detection | Reflection на `GradleAndroidModel` + regex |
| Console output | `TextConsoleBuilderFactory` + `ConsoleView` |
| Testing | JUnit 4 + Mockito |

---

## 🚀 Команды для работы

```bash
# Проверить конфигурацию
./gradlew help --no-configuration-cache

# Скомпилировать (первый раз ~5 мин, скачивает платформу)
./gradlew compileKotlin --no-configuration-cache

# Запустить тесты
./gradlew test --no-configuration-cache

# Запустить в sandboxed Android Studio (тест)
./gradlew runIde --no-configuration-cache

# Собрать ZIP для установки в реальный Android Studio
./gradlew buildPlugin --no-configuration-cache

# Полная сборка с тестами
./gradlew build --no-configuration-cache
```

### Установка в реальный Android Studio:
1. `./gradlew buildPlugin` → ZIP в `build/distributions/`
2. Android Studio → Settings → Plugins → ⚙️ → Install Plugin from Disk → выбери ZIP → Restart

---

## 🗝️ Ключевые решения (и почему так)

### Почему Swing, а не Kotlin UI DSL v2?
Kotlin UI DSL v2 (`panel { }`, `bindText`, `bindSelected`) отлично работает для диалогов настроек (`BoundConfigurable`), но:
- `DialogPanel.isModified/apply/reset` — доступны, но в этой версии платформы есть конфликты типов
- `bindText` требует `KMutableProperty0<String>` (non-null), а `BaseState.by string()` возвращает `String?`
- Проще и надёжнее использовать Swing напрямую с ручным `apply()/reset()`

### Почему PROJECT-level, а не APP-level settings?
Разные Android проекты имеют разные flavors, модули и флаги. PROJECT-level позволяет иметь отдельные настройки для каждого проекта. Хранится в `.idea/AndroidEfficiencyPlugin.xml`.

### Почему reflection для FlavorDetector?
`GradleAndroidModel` API — внутреннее, меняется между версиями Android Studio. Reflection + fallback на regex обеспечивает максимальную совместимость.

### Почему нет `pluginUntilBuild` с высоким значением?
Для публикации в JetBrains Marketplace рекомендуется указывать конкретный диапазон. Для разработки — обновляй `pluginUntilBuild` в `gradle.properties` при переходе на новую версию студии.

---

## 📦 Зависимости (IntelliJ Platform)

```kotlin
intellijPlatform {
    androidStudio("2024.3.1.14")          // Целевая платформа
    bundledPlugin("org.jetbrains.android") // Android plugin API (для флейворов)
    pluginVerifier()                       // Верификатор совместимости
    instrumentationTools()                 // Инструментация тестов
    testFramework(TestFrameworkType.Platform)
}
testImplementation("junit:junit:4.13.2")
testImplementation("org.mockito:mockito-core:5.8.0")
testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
```

---

## 🔮 Следующие шаги (приоритет)

1. **Верифицировать компиляцию** — `./gradlew compileKotlin` должен проходить без ошибок
2. **Тест на реальном проекте с flavors** — открыть в sandboxed Studio проект с `productFlavors { dev {}; prod {} }`
3. **Phase 2 UI** — добавить в `BuildToolWindowPanel` секцию с переключателем Gradle/CLI и dropdown девайсов
4. **FlavorCache invalidation** — подписаться на VFS events для инвалидации при изменении build.gradle
5. **Multi-module support** — `ComboBox` для выбора модуля из списка вместо ручного ввода

---

## 💬 История разработки (краткая)

- **Планирование**: Составлен детальный план с архитектурой, компонентами, UI макетом
- **Реализация Phase 1**: Все 13 файлов написаны за одну сессию
- **Gradle config fix**: `settings.gradle.kts` упрощён — убран settings plugin (вызывал `Unresolved reference: intellijPlatform`)
- **Compilation fixes**: Исправлены nullable String? ошибки из BaseState, переписан Configurable на Swing
- **Build compat fix**: `pluginUntilBuild` расширен до `253.*` под реальный Android Studio пользователя
- **Успешно протестировано**: Plugin загрузился в sandboxed Android Studio, Tool Window "Fast Deploy" появился
