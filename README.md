# Deep Agent Mobile

Единый Android APK для инженерного агента: код, документация, архитектура, анализ ошибок, рефакторинг, GitHub-операции и удалённая компиляция.

> Это самостоятельный Deep Agent. Репозиторий `harness-mobile` отделён от него и содержит только Harness WebView, подключение к серверу и мобильную адаптацию интерфейса.

## Цель

Пользователь устанавливает одно приложение и через одну графическую оболочку:

- ставит задачу на русском или английском;
- получает план, reasoning и результат от DeepSeek;
- прикладывает фото/скриншот для анализа;
- запускает локальные лёгкие операции;
- запускает тяжёлую Android-сборку через GitHub Actions;
- в следующих этапах читает логи, применяет патчи, создаёт commit/PR и скачивает проверенный APK.

Целевая тестовая платформа — Android 16+. Это не означает автоматического повышения `minSdk`: базовый прототип сохраняет минимальную совместимость текущего Android-проекта.

## Нынешний вариант

Сейчас это вертикальный прототип Agent Core внутри одного APK:

- `AgentBridge v1` отделяет UI от исполнителей;
- маршруты `AUTO`, `LOCAL_LITE` и `REMOTE_ACTIONS`;
- Local Lite Runner выполняет health probe в app-private workspace;
- DeepSeek Responses API поддерживает streaming reasoning/output и `input_image`;
- GitHub Actions connector отправляет workflow dispatch;
- нативный Compose Agent Console показывает единую ленту событий;
- токены DeepSeek и GitHub живут только в памяти текущей сессии;
- серверные plugins не устанавливаются и не изменяются.

Это уже рабочая основа маршрутизации и проверки контрактов, но ещё не полный автономный цикл исправления проекта.

## Что хотим в итоге

Deep Agent должен принимать задачу, сам составлять план, безопасно читать проект, анализировать ошибку, предлагать diff, после подтверждения применять изменения, работать с GitHub и доводить задачу до проверенного результата.

Единый поток:

```text
Задача → план → разрешение → инструменты → diff → тесты/Actions → логи → APK/PR
```

Heavy build остаётся удалённым: GitHub Actions, JDK, Gradle, Android SDK, NDK/CMake, приватные AAR/Maven и artifacts. Локальный runtime нужен для быстрых проверок, документации и лёгких операций.

## Архитектура

| Слой | Назначение |
| --- | --- |
| Compose UI | задача, конфигурация, разрешения, события, результаты |
| AgentBridge v1 | стабильный внутренний контракт UI ↔ Agent Core |
| Agent Core | сессия, маршрутизация, политика разрешений, ошибки |
| DeepSeek adapter | Responses API, SSE, reasoning, image input |
| Local Lite Runner | лёгкие локальные операции и будущий ToolRouter |
| GitHub connector | Actions, репозитории, Git, PR и artifacts |
| RuntimeSupervisor | будущий headless DSH/Node runtime за AgentBridge |

DSH/headless runtime остаётся заменяемым внутренним исполнителем и не становится публичным контрактом UI. Termux и второй APK не обязательны. Серверные plugins находятся вне области этого репозитория; мобильный WebView-adapter также не является зависимостью Deep Agent.

## Сделано

- [x] отдельный репозиторий для Deep Agent;
- [x] один APK и одна нативная оболочка;
- [x] Agent Core и AgentBridge v1;
- [x] базовая маршрутизация local/remote;
- [x] DeepSeek streaming и image input;
- [x] dispatch GitHub Actions;
- [x] GitHub Actions-сборка debug APK;
- [x] документация архитектуры, roadmap и идей.

## Задачи на будущее

1. `P0`: ToolRouter с read-only инструментами, локальная история и восстановление сессий.
2. `P1`: diff/preview, контролируемая запись, Git, commit и ручной PR.
3. `P1`: polling Actions, job logs, retry, проверка SHA и скачивание APK/AAB.
4. `P1`: Android 16 UI-тесты и регрессионная проверка разрешений.
5. `P2`: RuntimeSupervisor и headless DSH/Node ARM64 bundle.
6. `D1`: OCR, сравнение скриншотов, генерация SVG/Compose/HTML и отдельный image provider.
7. `D2`: расширенная рабочая область Deep Agent, визуальный анализ и подключаемые providers.

Подробности:

- [архитектура](docs/ANDROID_AGENT_ARCHITECTURE_RU.md)
- [roadmap](docs/IMPLEMENTATION_ROADMAP_RU.md)
- [идеи и расширения](docs/IDEAS_EXTENSIONS_ANALYSIS_RU.md)
- [границы репозиториев](docs/REPOSITORY_BOUNDARIES_RU.md)

## Сборка

Основной путь — GitHub Actions. Workflow запускается на push/PR или вручную:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Локальная сборка на aarch64/телефоне остаётся дополнительным режимом:

```bash
tools/setup-aarch64-build.sh
tools/build-local.sh
```

APK появляется в `app/build/outputs/apk/debug/`. Для тяжёлых проектов используйте remote Actions runner.

## Политика безопасности

Режимы `READ_ONLY`, `LOCAL_WRITE`, `GITHUB_WRITE`, `PR_CREATE` и `MERGE_RELEASE` разделены. Текст модели сам по себе не даёт права на опасную запись. До реализации безопасного хранилища ключи не сохраняются в постоянное хранилище и не попадают в события.

## Лицензия и статус

Репозиторий находится на стадии прототипа. Формат `AgentBridge v1` и правило «один APK» считаются зафиксированными архитектурными решениями.
## Последняя проверка

Коммит разделения прошёл GitHub Actions: `testDebugUnitTest` и `assembleDebug` успешно завершены. Публикация установочного APK выполняется через workflow release, потому что временное artifact storage аккаунта переполнено.
