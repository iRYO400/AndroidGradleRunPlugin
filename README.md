# Android Efficiency Plugin — Fast Deploy ⚡

> Плагин для Android Studio, который заменяет медленную кнопку Run ▶ прямым вызовом `./gradlew` с оптимизированными флагами.

## Почему это быстрее?

Стандартный запуск в Android Studio выполняет множество внутренних проверок перед каждым билдом. Прямой вызов через терминал позволяет:
- Использовать `--offline` — пропустить проверку зависимостей в сети
- Использовать `--parallel` — собирать модули параллельно
- Использовать `--configuration-cache` — кэшировать конфигурацию между запусками
- Избежать overhead самого Android Studio

**Типичный выигрыш: 30-60% от времени запуска.**

## Возможности

### Phase 1 — Gradle обёртка ✅
- **Tool Window "Fast Deploy"** — панель справа с полной конфигурацией
- **Чекбоксы для всех Gradle-флагов**: `--offline`, `--parallel`, `--configuration-cache`, `--build-cache`, `--daemon`, `--configure-on-demand`, `--stacktrace`, `--info`, `--debug`, `--dry-run`
- **Автоопределение build flavors** из проекта (через AndroidModuleModel API + fallback regex)
- **Ручной ввод flavor** если автоопределение не сработало
- **Live preview** итоговой команды — обновляется при каждом изменении
- **Встроенная консоль** с ANSI-цветами и кликабельными ссылками
- **Сохранение настроек** между перезапусками IDE (`.idea/AndroidEfficiencyPlugin.xml`)
- **Горячая клавиша** `Ctrl+Shift+F10` (mac: `Cmd+Shift+F10`) для быстрого запуска
- **Копирование команды** в буфер обмена
- **Страница настроек** в IDE Preferences → Tools → Android Efficiency

### Phase 2 — Android CLI ✅
- Интеграция с `android run` (новый Android CLI, май 2026)
- `android describe` для автоопределения APK-артефактов
- Управление эмуляторами через `android emulator list/start/stop`
- Выбор целевого устройства из списка подключенных

## Установка для разработки

### Требования
- JDK 17+
- Gradle 8.11+ (через wrapper, скачивается автоматически)
- Android Studio Meerkat (2024.3.1) или выше

### Запуск в отладочном Android Studio

```bash
./gradlew runIde
```

Откроется отдельный экземпляр Android Studio с установленным плагином.

### Сборка плагина

```bash
./gradlew buildPlugin
```

ZIP-файл плагина появится в `build/distributions/`.

### Установка в Android Studio

1. Android Studio → Settings → Plugins → ⚙️ → Install Plugin from Disk…
2. Выбрать ZIP из `build/distributions/`
3. Перезапустить IDE

## Использование

1. Откройте **Android-проект** в Android Studio
2. Откройте Tool Window **"Fast Deploy"** (правая панель)
3. Настройте флаги и выберите flavor
4. Нажмите **▶ Run Build** или используйте `Ctrl+Shift+F10`

## Структура проекта

```
src/main/kotlin/.../
├── settings/
│   ├── PluginSettings.kt              # Персистентные настройки
│   └── PluginSettingsConfigurable.kt  # Страница в Preferences
├── toolwindow/
│   ├── BuildToolWindowFactory.kt      # Регистрация Tool Window
│   └── BuildToolWindowPanel.kt        # Главный UI
├── execution/
│   ├── BuildCommandComposer.kt        # Формирование команды
│   ├── GradleCommandExecutor.kt       # Запуск через GeneralCommandLine
│   └── AndroidCliExecutor.kt          # Phase 2: Android CLI
├── flavor/
│   ├── FlavorDetector.kt              # Определение build flavors
│   └── FlavorCache.kt                 # Кэш
├── actions/
│   └── QuickBuildAction.kt            # Shortcut action
└── util/
    ├── GradlewResolver.kt             # Поиск gradlew
    └── DeviceResolver.kt              # Список устройств
```

## Пример генерируемых команд

```bash
# Без flavor (Debug)
./gradlew :app:installDebug \
    --offline --parallel \
    --configuration-cache --build-cache

# С flavor (Dev + Release)
./gradlew :app:installDevRelease \
    --offline --parallel \
    --configuration-cache --build-cache \
    --daemon

# Assemble только APK (prod + Release)
./gradlew :app:assembleProdRelease \
    --offline --parallel \
    --configuration-cache
```

## Лицензия

Apache 2.0
