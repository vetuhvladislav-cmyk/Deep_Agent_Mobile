# Deep Agent Mobile

Единый Android APK для инженерного агента: постановка задач, анализ кода и документации, работа с изображениями, локальные лёгкие операции и удалённые GitHub Actions.

Deep Agent использует одну нативную оболочку, Agent Core, AgentBridge v1, DeepSeek и local lite-runtime. Обязательный Termux, отдельный DSH APK и вторая пользовательская оболочка находятся вне границ поставки.

## Документация

- [Архитектура и контракты](docs/ANDROID_AGENT_ARCHITECTURE_RU.md)
- [Дорожная карта реализации](docs/IMPLEMENTATION_ROADMAP_RU.md)
- [Идеи и отложенные направления](docs/IDEAS_EXTENSIONS_ANALYSIS_RU.md)

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

Последнее подтверждение выполнено на ветке `codex/p1-a-controlled-write-git-pr`; `main` не изменялся.

- Последний проверенный baseline — commit [aa6b7a3](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/aa6b7a3427259b4afdecdf0c4ffa153caec190): `ProviderRegistry`, typed `AgentEvent` с bounded redacted payload, обязательная Actions correlation, ApprovalToken и patch/artifact provenance.
- [GitHub Actions run #27](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35269039901): `testDebugUnitTest`, `assembleDebugAndroidTest` и `assembleDebug` — успешно.
- После baseline в той же ветке добавлены bounded Planner/Evaluator и строгая host-policy для DeepSeek (коммиты [b056589](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/b056589b1b62c30592453da13a667b1b7ca3a0cb), [82b29ba](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/82b29ba00272a04e97b1410ba5b99e8dd1aafec4), [0e751ed](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/0e751eddd2cdcb427918da60566ffaee1f3bbae7), [2ab6f1f](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/2ab6f1f237f8b8bf84198fde276353ef327ee243)); эти последние изменения пока проверены статически, без новой компиляции и запуска тестов.
- [Release v0.1.4](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/releases/tag/v0.1.4) опубликован из проверенного commit [84ca54b](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/84ca54b36c6ffe1ac3fb45ffbd0903275bc99ba7).
- [APK v0.1.4](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/releases/download/v0.1.4/deep-agent-mobile-v0.1.4-test.apk) — 9 998 464 байта.
- SHA-256: `c6b20d9db54e5350fbacff14a8fb7cc5cde96fd09684175c366833855c0be762`.
- Android instrumentation APK ранее компилировался, но instrumentation/device execution, реальный runtime и device lifecycle acceptance ещё не запускались; поэтому P0-A/P0-B/P0-C остаются `planned` до соответствующих exit criteria.

Для сравнения остаётся историческая проверка базовой ветки `main`.

Проверка выполнена на `main`, commit [35bfd00](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/35bfd006342930ee7e58015578aa6e312c1f6f2a).

- [GitHub Actions run #6](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35158511466): `testDebugUnitTest` и `assembleDebug` — успешно.
- [APK v0.1.1](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/releases/download/v0.1.1/deep-agent-mobile-v0.1.1-test.apk) — 9 670 540 байт.
- SHA-256: `049fbad5f3e030d478260e60dabd0aa9fb7fc72e8c59134da86c5da0451ad215`.

## Принцип репозитория

Документация разделена по назначению:

- архитектура фиксирует контракты и инварианты;
- roadmap фиксирует порядок и состояние этапов;
- ideas фиксирует отложенные и неподтверждённые направления;
- README служит навигационным фасадом.

При расхождении фактов применяется источник, указанный в соответствующем документе.
