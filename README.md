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

- Последний проверенный baseline — commit [aa6b7a3](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/aa6b7a3427259b4b9afdecdf0c4ffa153caec190): `ProviderRegistry`, typed `AgentEvent` с bounded redacted payload, обязательная Actions correlation, ApprovalToken и patch/artifact provenance.
- [GitHub Actions run #27](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35269039901): `testDebugUnitTest`, `assembleDebugAndroidTest` и `assembleDebug` — успешно.
- После baseline в той же ветке добавлены bounded Planner/Evaluator, строгая host-policy для DeepSeek и operation-bound guard для Git/PR/Actions: обязательные operation IDs, сериализация мутаций, in-memory replay в активной сессии и сохранение последнего результата в SessionStore для безопасного восстановления (коммиты [b056589](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/b056589b1b62c30592453da13a667b1b7ca3a0cb), [82b29ba](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/82b29ba00272a04e97b1410ba5b99e8dd1aafec4), [0e751ed](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/0e751eddd2cdcb427918da60566ffaee1f3bbae7), [2ab6f1f](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/2ab6f1f237f8b8bf84198fde276353ef327ee243), [d9744b7](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/d9744b76fb2c50e74331d031fa77666695c30983), [eb15608](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/eb15608ea9abdbcd9a427bd73d3dd59bb76649d1), [5b93dcd](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/5b93dcd0120a51a818963cca06e98b0911c8b9a5), [a184072](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/a18407229046a71ed6b1bb766786a259a2e88682), [3f0dcf5](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/3f0dcf5ad8d9d0e976cf0e3c4caea133fe0bb34e), [8eb357b](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/8eb357bec87f4054a1297d0f5ba3bf79185587b4), [17f926e](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/17f926e781c4d902a10e9ea162f42a89972c66aa), [70629ea](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/70629ea9283311796852d9e7d7d115a109fa725b), [b5d7071](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/b5d7071721b539814eb95da439e84af792d89613), [b9bdf4a](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/b9bdf4a4c008b1f64ac1e7d31af96b997e258e77)); эти последние изменения пока проверены статически, без новой компиляции и запуска тестов.
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
