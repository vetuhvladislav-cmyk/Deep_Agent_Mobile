# Deep Agent Mobile

Единый Android APK для инженерного агента: постановка задач, анализ кода и документации, работа с изображениями, локальные лёгкие операции и удалённые GitHub Actions.

Deep Agent использует одну нативную оболочку, Agent Core, AgentBridge v1, DeepSeek и local lite-runtime. Обязательный Termux, отдельный DSH APK и вторая пользовательская оболочка находятся вне границ поставки.

## Документация

- [Архитектура и контракты](docs/ANDROID_AGENT_ARCHITECTURE_RU.md)
- [Дорожная карта реализации](docs/IMPLEMENTATION_ROADMAP_RU.md)
- [Идеи и отложенные направления](docs/IDEAS_EXTENSIONS_ANALYSIS_RU.md)
- [Сборка и релиз](docs/BUILD_AND_RELEASE_RU.md)

## Рекомендуемый порядок чтения

1. [Архитектура](docs/ANDROID_AGENT_ARCHITECTURE_RU.md) — границы продукта и устойчивые контракты.
2. [Roadmap](docs/IMPLEMENTATION_ROADMAP_RU.md) — порядок этапов, статусы и критерии выхода.
3. [Ideas](docs/IDEAS_EXTENSIONS_ANALYSIS_RU.md) — отложенные и неподтверждённые направления.

## Сводка дорожной карты

Статусы ниже являются фасадной копией из IMPLEMENTATION_ROADMAP_RU.md. Источником истины остаётся roadmap.

| Этап | capabilityStatus |
| --- | --- |
| P0-0 | available |
| P0-A | planned |
| P0-B | planned |
| P0-C | planned |
| P1-A | planned |
| P1-B | planned |
| P1-C | planned |
| P2-A | planned |
| P2-B | planned |
| D1 | planned |
| D2 | planned |
| D3 | planned |

## Проверки

Аудит, синхронизация документации и CI-проверки выполнены на ветке `codex/p1-a-controlled-write-git-pr`; `main` не изменялся.

### Сверка кода, версии и workflow

- `app/build.gradle.kts` содержит `versionCode = 5` и `versionName = "0.1.5"`.
- [Live workflow](.github/workflows/android.yml) и [workflow-шаблон](tools/github-workflow-android.yml) синхронизированы байт-в-байт: JDK 17, Android SDK platform 35/build-tools 35.0.0, Gradle cache, Linux build job, отдельный UI runtime job и release gate.
- [Actions run #32](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35320175455) подтверждает прошлый APK baseline: JDK 17, Gradle 8.13, SDK 35 и `testDebugUnitTest → assembleDebugAndroidTest → assembleDebug`; текущий Linux build job сохраняет эту последовательность.
- Существующий [Release v0.1.5](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/releases/tag/v0.1.5) собран из более раннего commit `5c14bbb` и не содержит текущих workflow/documentation changes. Production-signed release в этом аудите не публиковался.

### Фактические CI-результаты

- [Run #44](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35355722283), commit `fae06cc`: Linux build job успешно выполнил golden vectors, `testDebugUnitTest`, `assembleDebugAndroidTest`, `assembleDebug`, checksum/provenance; длительность build job — 1:58.
- UI runtime job в run #44 дошёл до emulator startup, но завершился диагностикой: `x86_64` system image нельзя запустить на ARM64 `macos-15` host. Поэтому `connectedDebugAndroidTest` пока не принят, а release job был корректно skipped при `publish_release=false`.
- В ходе аудита отдельно подтверждены ограничения runner-инфраструктуры: ARM64 macOS без доступного HVF не запускает ARM64 AVD, software QEMU для этой связки падает; ARM64 Linux image не содержит пакет `emulator`; совместимый `macos-15-large` не стартовал из-за billing/spending limit аккаунта.
- APK из текущего build job — debug/test artifact; его публикация в Release выполняется только по команде или событию `release.published` после обоих gates.

Историческая reference-проверка `main`: [run #6](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35158511466) и Release `v0.1.1` относятся к старой ветке и не заменяют текущую проверку.

UI/runtime, rotation/background/process-death, accessibility tree и screenshot fixtures остаются открытыми exit criteria roadmap до успешного device/runtime acceptance.

## Принцип репозитория

Документация разделена по назначению:

- архитектура фиксирует контракты и инварианты;
- roadmap фиксирует порядок и состояние этапов;
- ideas фиксирует отложенные и неподтверждённые направления;
- README служит навигационным фасадом.

При расхождении фактов применяется источник, указанный в соответствующем документе.
