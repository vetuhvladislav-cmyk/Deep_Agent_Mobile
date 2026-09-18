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

- `app/build.gradle.kts` содержит `versionCode = 6` и `versionName = "0.1.6"`.
- [Live workflow](.github/workflows/android.yml) и [workflow-шаблон](tools/github-workflow-android.yml) синхронизированы байт-в-байт: JDK 17, Android SDK platform 35/build-tools 35.0.0, Gradle cache, Linux build job, отдельный UI runtime job и release gate.
- [Actions run #32](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35320175455) подтверждает прошлый APK baseline: JDK 17, Gradle 8.13, SDK 35 и `testDebugUnitTest → assembleDebugAndroidTest → assembleDebug`; текущий Linux build job сохраняет эту последовательность.
- [Release v0.1.6](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/releases/tag/v0.1.6) собран из текущего commit `8f673c5` и содержит `deep-agent-mobile-v0.1.6-test.apk` размером 16 896 646 байт. Это по-прежнему debug/test artifact; production-подписание в аудите не выполнялось.

### Фактические CI-результаты

- [Run #46](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35373493968), commit `8f673c5`: Linux build job зелёная за 2:18 (`testDebugUnitTest`, `assembleDebugAndroidTest`, `assembleDebug`, checksum/provenance), release job опубликовал APK v0.1.6 за 4:08. Artifact upload снова получил quota warning, поэтому проверяемый APK берётся из Release assets.
- Release v0.1.6 привязан к tag `v0.1.6` на commit `8f673c5`; SHA-256 sidecar содержит `8161a5ae74f5f6b821e61accefc560caa62de5998a9ced4449c4cb42857de6cd` для `deep-agent-mobile-v0.1.6-test.apk`, рядом лежит `deep-agent-provenance.json` с source SHA.
- UI runtime job в run #46 не стартовал (завершился за 4 секунды): `macos-15-large` требует включённого account billing/spending limit. Поэтому `connectedDebugAndroidTest` остаётся не принятым, а P1-C — в статусе `planned`.
- [Run #45](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35361986031), commit `5c5e853`: Linux build job зелёная за 1:48; release skipped при `publish_release=false`.
- [Run #44](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35355722283), commit `fae06cc`: build job успешно выполнил golden vectors, `testDebugUnitTest`, `assembleDebugAndroidTest`, `assembleDebug`, checksum/provenance; UI job дошёл до emulator startup и упал на несовместимости `x86_64` image с ARM64 `macos-15` host.
- Подтверждённые ограничения runner-инфраструктуры: стандартный ARM64 macOS runner — это Apple Silicon M1/M2 без nested virtualization, поэтому Android emulator там не запускается в принципе; Intel-вариант `macos-15-large` не стартует из-за billing/spending limit аккаунта.
- UI runtime job поэтому перенесён на стандартный `ubuntu-24.04` с KVM-ускорением и `x86_64` AVD; зелёный прогон `connectedDebugAndroidTest` ожидается в следующем workflow_dispatch.

Историческая reference-проверка `main`: [run #6](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35158511466) и Release `v0.1.1` относятся к старой ветке и не заменяют текущую проверку.

UI/runtime, rotation/background/process-death, accessibility tree и screenshot fixtures остаются открытыми exit criteria roadmap до успешного device/runtime acceptance.

## Принцип репозитория

Документация разделена по назначению:

- архитектура фиксирует контракты и инварианты;
- roadmap фиксирует порядок и состояние этапов;
- ideas фиксирует отложенные и неподтверждённые направления;
- README служит навигационным фасадом.

При расхождении фактов применяется источник, указанный в соответствующем документе.
