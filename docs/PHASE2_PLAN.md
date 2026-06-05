# Phase 2 — Android CLI режим, выбор устройства, единое место настроек

> Рабочий план (утверждён). Если прервёмся — продолжаем отсюда.
> Статус прогресса см. в конце файла.

## Context

Плагин «Fast Deploy» сейчас умеет только Gradle-режим, а заготовки под Android CLI
(`AndroidCliExecutor`) и список устройств (`DeviceResolver`) написаны, но не подключены
к UI (настройки `useAndroidCli`, `targetDevice` не используются). Параллельно у плагина
два места для конфигурации — тул-окно «Fast Deploy» и страница Preferences → Tools →
Android Efficiency (`PluginSettingsConfigurable`).

Цель: (1) добавить переключатель **Gradle ↔ Android CLI** и **выбор устройства** в тул-окне;
(2) убрать дублирующую страницу настроек, чтобы вся конфигурация была в одном месте.

`android` CLI установлен; реальные команды: `android run [--device=<serial>] [--debug]
[--activity=...]`, `android emulator list/start/stop`.

## Решения (согласовано)

- **CLI-режим:** Gradle-секции (Build Target, Flavor, Gradle Flags, Custom Flags, Post-Build)
  **дизейблятся** (grey out), не прячутся.
- **Источник устройств:** только подключённые через `DeviceResolver.listDevices()` (adb).
- **Устройство в Gradle-режиме:** префикс `ANDROID_SERIAL=<serial>` перед `./gradlew …`;
  в `am start` — `adb -s <serial>` явно.
- **CLI-режим:** `android run --device=<serial>`.
- **Страница настроек** `PluginSettingsConfigurable` удаляется целиком.

## Изменения по файлам

- **`settings/PluginSettingsConfigurable.kt`** — удалить файл (настройки остаются в `PluginSettings`).
- **`resources/META-INF/plugin.xml`** — удалить `<projectConfigurable … PluginSettingsConfigurable …/>` (projectService оставить).
- **`execution/BuildCommandComposer.kt`** — ветка по `useAndroidCli`:
  - CLI: `android run` + ` --device=<serial>` если задан.
  - Gradle: префикс `ANDROID_SERIAL='<serial>' ` перед `./gradlew`; в `appendLaunchActivity` — `adb -s '<serial>' shell am start`.
  - preview/terminal/marker — обе формы.
- **`toolwindow/BuildToolWindowPanel.kt`**:
  - Секция «Run via»: радио Gradle / Android CLI (CLI задизейблено + тултип, если `isCliAvailable()`==false).
  - Секция «Device»: editable-combo (пусто = default/all) + refresh; async из `DeviceResolver.listDevices()`; хранит serial в `targetDevice`.
  - `setGradleControlsEnabled(false)` для CLI-режима.
  - `persistSettings()` сохраняет новые поля.
- **`execution/AndroidCliExecutor.kt`** — используем `isCliAvailable()`; остальное не трогаем.
- **Тесты `BuildCommandComposerTest.kt`** — CLI с/без device; Gradle с ANDROID_SERIAL; am start с `adb -s`.

## Verification

1. `./gradlew test buildPlugin --no-configuration-cache`.
2. Install from Disk: страница настроек исчезла; device dropdown работает; preview Gradle vs CLI; grey-out секций; нотификация в обоих режимах.

## Ограничения

- Исполнение в IDE-терминале (строки). Marker-нотификация POSIX-only. AVD/эмуляторы — вне scope.

---

## Прогресс

- [ ] Удалить PluginSettingsConfigurable + extension в plugin.xml
- [ ] BuildCommandComposer: ветка CLI/Gradle + device
- [ ] BuildToolWindowPanel: Run via + Device + grey-out
- [ ] Тесты BuildCommandComposerTest
- [ ] Сборка/тесты зелёные (на CI/второй машине)
