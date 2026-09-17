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

Текущая визуальная адаптация и статический аудит выполнены на ветке `codex/p1-a-controlled-write-git-pr`, HEAD `d13117031d85690c0ee6736bc0049984e109f80f`; `main` не изменялся. Для текущего рабочего diff Android build/test не запускались по согласованному ограничению.

Ниже приведена историческая проверка базовой ветки `main`, а не результат текущего diff.

Проверка выполнена на `main`, commit [35bfd00](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/commit/35bfd006342930ee7e58015578aa6e312c1f6f2a).

- [GitHub Actions run #6](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35158511466): `testDebugUnitTest` и `assembleDebug` — успешно.
- [APK v0.1.1](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/releases/download/v0.1.1/deep-agent-mobile-v0.1.1-test.apk) — 9 670 540 байт.
- SHA-256: `049fbad5f3e030d478260e60dabd0aa9fb7fc72e8c59134da86c5da0451ad215`.
- Обычный Actions artifact не сохранён из-за квоты GitHub; APK доступен через Release.

## Принцип репозитория

Документация разделена по назначению:

- архитектура фиксирует контракты и инварианты;
- roadmap фиксирует порядок и состояние этапов;
- ideas фиксирует отложенные и неподтверждённые направления;
- README служит навигационным фасадом.

При расхождении фактов применяется источник, указанный в соответствующем документе.
