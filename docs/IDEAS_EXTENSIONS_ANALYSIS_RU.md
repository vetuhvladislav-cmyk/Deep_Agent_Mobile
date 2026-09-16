# Идеи, расширения и направления анализа

Канонический файл для `Deep_Agent_Mobile`. Внешняя web/mobile-адаптация находится вне области Deep Agent и не входит в его runtime.

> Канонический порядок задач находится в [IMPLEMENTATION_ROADMAP_RU.md](./IMPLEMENTATION_ROADMAP_RU.md).
> Этот файл фиксирует границы, решения и отложенные направления. Реализация выполняется по явной задаче и после отдельного согласования; текущий проход не запускает сборку и тесты.

## Правила статусов

- **Задачи реализации** — утверждённый backlog продукта. Реализуются по приоритету `P0 → P1 → P2` после прохождения критериев выхода.
- **Отложенный план** — направления, которые записаны для будущего этапа, но сейчас не реализуются.
- **Отдельное решение** — архитектурная развилка, которую нельзя закрывать молча изменением кода.

## Граница продукта

- Пользователь получает одно цельное Android-приложение и один APK.
- Внутренние Agent Core, runtime, GitHub/Actions и UI-компоненты не являются отдельными приложениями.
- Termux Activity, отдельный DSH APK и вторая пользовательская оболочка не требуются.
- Android 16+ используется как платформа тестирования; `minSdk` не повышается автоматически до 36.
- GitHub Actions остаётся основным способом тяжёлой Android-сборки.
- Серверные plugins внешнего сервера не изменяются в рамках текущего проекта.
- Запись, push, PR, merge и release разделяются уровнями `READ_ONLY`, `LOCAL_WRITE`, `GITHUB_WRITE`, `PR_CREATE`, `MERGE_RELEASE`.

## Уже реализовано в `v0.2.0-test`

- Agent Core и стабильный внутренний контракт `AgentBridge v1`.
- Маршруты `AUTO`, `LOCAL_LITE` и `REMOTE_ACTIONS`.
- Local Lite Runner с health probe.
- DeepSeek Responses API streaming, reasoning и image input через Android URI → data URL.
- Dispatch GitHub Actions после проверки `GITHUB_WRITE`.
- Нативная Agent Console внутри единственного APK.
- Базовая лента событий и временное хранение API/GitHub-токенов только в памяти текущей сессии.

## Утверждённые задачи реализации

Полный список, зависимости и критерии выхода — в [приоритизированной дорожной карте](./IMPLEMENTATION_ROADMAP_RU.md). Краткий порядок:

1. **P0-A — ToolRouter foundation:** `read_file`, `list_files`, `search_code`, `git_status`, `git_diff`, типизированные tool calls/results, workspace boundary и контрактные тесты.
2. **P0-B — История и восстановление:** versioned event journal, сериализация tool rounds, восстановление незавершённой сессии после Activity/process death.
3. **P1-A — Запись, Git и ручной PR:** preview и `apply_patch`, branch/commit/push, явный `create_pull_request` с `PR_CREATE`.
4. **P1-B — Actions observability:** run/job status, логи, failed-job retry и скачивание проверенных APK/AAB artifacts.
5. **P1-C — Android 16 UI-контракт:** Agent Console, task editor, target/permission controls, configuration, image attachment, event stream, navigation, rotation и inset regression.
6. **P2-A — RuntimeSupervisor и headless DSH:** lifecycle, readiness, heartbeat, versioned ARM64 bundle, rollback и loopback.
7. **P2-B — PTY:** только после стабильного headless runtime; persistent shell, дочерние процессы и интерактивное восстановление.

Важно: `create_pull_request` как явно разрешаемый инструмент относится к задачам реализации. Автоматическое создание PR по политике — отдельное отложенное направление.

## Отложенный план

### D1 — Изображения

- OCR текста и stack trace.
- Сравнение двух UI-скриншотов с подсветкой отличий.
- Выделение области, resize и нормализация EXIF/orientation.
- Генерация SVG/Compose/HTML.
- Отдельный провайдер растровой генерации изображений.

Базовый image input уже есть. Растровая генерация не считается встроенной возможностью DeepSeek API и требует отдельного `ImageGenerator` provider.

### D2 — Расширенная рабочая область Deep Agent

- Объединить Task, Workspace, Builds, Artifacts и события под одним владельцем состояния.
- Добавить импорт workspace, просмотр diff и связывание результата с session ID.
- Подключать новые providers только через `AgentBridge v1`.

До стабилизации P0/P1 нативная Agent Console остаётся единственной пользовательской поверхностью Deep Agent.

### D3 — Политика токенов, записи изменений и автоматического PR

- Выбрать постоянное хранение токенов: только сессия, Android Keystore или внешний proxy.
- Зафиксировать redaction токенов в логах, событиях, crash reports и экспортируемых artifacts.
- Определить журнал write-операций, срок хранения, экспорт и восстановление.
- Утвердить branch-per-task, approval gate, обязательный diff и связь PR с commit SHA.
- Отдельно решить, допускается ли автоматическое создание PR после успешного Actions run.

До утверждения D3 действует текущая схема: токены не сохраняются постоянно, write требует текущего permission level, а PR создаётся только явным действием с `PR_CREATE`.

## Что сознательно не реализуется сейчас

- полный автоматический tool loop DeepSeek;
- произвольное выполнение shell-команд из текста пользователя;
- полноценный PTY/интерактивный терминал и полный Termux bootstrap;
- Android SDK/NDK внутри базового APK;
- изменение серверных plugins;
- отдельный APK или обязательная установка Termux;
- automatic PR policy, постоянное хранение токенов и неявная запись изменений.

## Критические точки согласования

- финальный способ упаковки ARM64 Node.js/DSH и SHA-256 bundle;
- необходимость persistent shell/PTY после стабилизации Lite Runner;
- провайдер растровой генерации;
- политика хранения секретов и экспортируемого журнала изменений;
- критерии opt-in для автоматического PR;
- минимальная версия Android для публичного APK.

Любая развилка, влияющая на runtime, секреты, внешний write или единую оболочку, фиксируется отдельным решением и не закрывается скрытым изменением исходников.
