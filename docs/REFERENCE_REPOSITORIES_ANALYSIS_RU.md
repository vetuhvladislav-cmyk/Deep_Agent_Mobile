# Анализ документации Deep Agent Mobile и внешних ориентиров

> Статус: аналитический документ, не разрешение на реализацию
>
> Дата анализа: 2026-09-16
>
> Область: только документация и README; исходный код Deep_Agent_Mobile в этом проходе не оценивался, поскольку репозиторий активно изменяется.

## 1. Краткий вывод

По документам Deep_Agent_Mobile выбран правильный вектор для поставленной задачи:

- одно цельное Android-приложение и один APK;
- нативный Compose-интерфейс;
- собственные Agent Core и AgentBridge v1;
- локальный лёгкий исполнитель для небольших операций;
- GitHub Actions как основной путь тяжёлой сборки;
- явная модель разрешений;
- DSH/headless runtime как будущий заменяемый исполнитель, а не как внешний обязательный продукт;
- чёткое отделение Deep Agent от Harness WebView/mobile adapter.

Главный риск сейчас не в общей архитектуре, а в деталях контракта. Документы хорошо описывают направление и приоритеты, но пока недостаточно фиксируют точные границы ToolRouter, workspace, журнала сессий, восстановления, разрешений, GitHub Actions и артефактов. Второй риск — расхождение статусов между README, архитектурным документом, roadmap и backlog.

Рекомендуемый порядок остаётся таким:

1. синхронизировать документацию и acceptance criteria;
2. зафиксировать read-only ToolRouter и модель workspace;
3. зафиксировать журнал событий и восстановление сессии;
4. затем добавлять контролируемые записи, Git/PR и наблюдаемость Actions;
5. только после этого углубляться в RuntimeSupervisor, headless DSH и PTY.

Ниже приведены идеи, которые стоит занести в план. В этом документе ничего не реализуется.

## 2. Что зафиксировано текущими документами

Сводка составлена по:

- [README.md](../README.md);
- [ANDROID_AGENT_ARCHITECTURE_RU.md](./ANDROID_AGENT_ARCHITECTURE_RU.md);
- [IDEAS_EXTENSIONS_ANALYSIS_RU.md](./IDEAS_EXTENSIONS_ANALYSIS_RU.md);
- [IMPLEMENTATION_ROADMAP_RU.md](./IMPLEMENTATION_ROADMAP_RU.md);
- [REPOSITORY_BOUNDARIES_RU.md](./REPOSITORY_BOUNDARIES_RU.md).

| Область | Зафиксированное решение по документам |
|---|---|
| Продукт | Одно цельное приложение, один Android APK |
| Android 16 | Целевая платформа для тестов; это не означает обязательный minSdk 36 |
| UI | Нативная Compose-оболочка и Agent Console |
| Контракт | AgentBridge v1 между UI и Agent Core |
| Модель | DeepSeek Responses API, streaming, reasoning и image input |
| Исполнение | Local Lite для малых задач, GitHub Actions для тяжёлых задач |
| GitHub | В текущем контуре документирован dispatch workflow; расширение до статусов, логов, артефактов и PR запланировано |
| Безопасность | READ_ONLY по умолчанию, разрешения разделены по уровням, токены в текущем прототипе только в памяти сессии |
| WebView | Harness/mobile adapter находится за пределами Deep_Agent_Mobile и не должен становиться обязательной зависимостью |
| Runtime | Headless DSH, RuntimeSupervisor и PTY отложены; Termux не является обязательной зависимостью |
| Изображения | Базовый image input есть; OCR, сравнение скриншотов и генерация изображений отложены |

Документы отдельно и правильно указывают, что полноценный цикл «план → tool call → результат → проверка → исправление» ещё не следует считать готовым. Также не следует считать реализованными только по названию слоя следующие возможности:

- произвольный ToolRouter;
- чтение и поиск файлов через агентский цикл;
- diff и apply_patch;
- Git commit/push и создание PR;
- polling Actions, job logs и проверка APK;
- durable journal и восстановление после смерти процесса;
- headless DSH и PTY.

## 3. Недостатки и пробелы документации

Это именно пробелы спецификации, а не утверждения о дефектах текущего исходного кода.

| Приоритет | Пробел | Риск | Что стоит добавить в документацию |
|---|---|---|---|
| P0 | Нет единого источника истины для статусов | README, roadmap и идеи могут описывать разные стадии | Сделать roadmap каноническим статусом, а остальные документы — ссылками и краткими сводками |
| P0 | ToolRouter описан на уровне названий инструментов | Несовместимые входы, неясные ошибки, неконтролируемый объём вывода | Для каждого инструмента зафиксировать JSON input/output schema, версию, permission, scope, timeout, лимит ответа, idempotency и коды ошибок |
| P0 | Не определена идентичность workspace | Нельзя надёжно понять, к какому состоянию репозитория относится diff или tool result | Ввести WorkspaceIdentity: источник, root, repository, ref, commit SHA, режим read-only/read-write и fingerprint |
| P0 | Не завершена семантика восстановления | После смерти процесса можно повторить запись, workflow или tool call | Зафиксировать состояния invocation: pending, running, succeeded, failed, cancelled, unknown; завершённые операции не повторять, unknown перепроверять |
| P0 | Матрица разрешений не описывает условия выдачи | READ_ONLY может быть формально установлен, но действие всё равно иметь внешний эффект | Описать target binding, срок approval, одноразовость, требуемый scope токена, запрет self-escalation и защиту от prompt injection |
| P1 | Не определён общий контракт исполнителей | UI и Core могут начать зависеть от Local Lite или DSH | Ввести ExecutionProvider с capability discovery, readiness, cancellation, timeout и нормализованными событиями |
| P1 | Actions описан прежде всего как dispatch | Потеряется связь request → run → job → log → artifact | Зафиксировать RunLedger, correlation IDs, состояние workflow, retry policy, лимиты скачивания, checksum и срок жизни артефакта |
| P1 | Git/PR lifecycle пока неполный | Неясны ветки, конфликты, повторный push и граница ручного/автоматического PR | Описать branch policy, base SHA, commit preview, conflict state, push gate и отдельный PR_CREATE approval |
| P1 | Android 16 UI-тесты не имеют тестового контракта | Тесты могут проверять текст, но не реальные пользовательские сценарии | Добавить test IDs, device/configuration matrix, fixtures, rotation/insets/keyboard cases и screenshot policy |
| P1 | Не зафиксирована политика сети | SSE/WebSocket/API могут по-разному обрабатывать timeout, retry и cancel | Описать offline state, exponential backoff, cancellation, duplicate request handling и redaction сетевых ошибок |
| P1 | Image pipeline описан только до передачи input_image | Неясны размер, сжатие, EXIF, временное хранение и PII | Указать лимиты, TTL cache, удаление временных копий, EXIF policy и отображение пользователю факта передачи изображения |
| P2 | Runtime bundle описан концептуально | Обновление runtime может сломать сессию или оставить несовместимый ABI | Добавить manifest версии, ABI, размер, checksum/signature, compatibility range, staged update и rollback |
| P2 | Нет бюджета ресурсов | Локальный агент может занять память/диск/CPU телефона | Зафиксировать лимиты файлов, вывода, времени, памяти, диска, параллельных процессов и токенов |
| P2 | Нет единого диагностического идентификатора | Сложно связать экран, журнал, API-запрос, Actions run и artifact | Использовать sessionId, invocationId, providerRunId, workflowRunId и artifactId во всех событиях и UI-карточках |

### 3.1. Несогласованности между уже существующими документами

1. В roadmap есть отдельный P0-C для MVP setup и валидации, а в кратком списке приоритетов ideas этот шаг почти не виден. Его лучше явно синхронизировать во всех сводках.

2. Слово «реализовано» иногда относится к архитектурному слою или интерфейсу, а иногда — к готовой функции. Например, GitHub Connector может быть целевым владельцем PR и artifacts, хотя конкретные операции ещё перечислены как будущие. Полезно разделить термины:
   - ownership — какой слой будет владеть capability;
   - available — доступно пользователю сейчас;
   - planned — утверждено, но не реализовано;
   - deferred — оставлено до подходящего этапа.

3. AgentBridge уже имеет поток событий, но durable session journal — будущая возможность. Документы должны явно разделять live event stream и durable event log, чтобы не создать ложное ожидание восстановления.

4. Текущая документация правильно отделяет Harness WebView, но в будущем разделе D2 желательно закрепить, что объединение пользовательских поверхностей не означает возврат зависимости от WebView. Единым должен быть state owner/AgentBridge, а не DOM или внутренний RPC DSH.

5. Для каждого пункта roadmap стоит добавить один и тот же набор полей: входной контракт, выходной контракт, permission gate, session ID, redacted audit trail, cancellation/timeout, recovery rule и exit criterion.

## 4. Что полезно взять из похожих проектов

Ниже использованы только README и документация указанных проектов. Код этих проектов не переносится автоматически.

| Источник | Сильная идея | Что адаптировать для Deep Agent | Ограничение или проблема источника |
|---|---|---|---|
| [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) и его [architecture](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/architecture.md) | Capability seams, typed events, append-only session log, отдельные этапы pre/execute/post для tools, заменяемые providers | ToolCapabilityRegistry, durable/live event split, единый tool pipeline и провайдерные интерфейсы | DSH — developer preview с возможными breaking changes; Cordis/plugin-tree и Node/TypeScript-окружение слишком тяжёлы для базового Android APK |
| [DeepSeek Harness SAFETY.md](https://github.com/deepseek-ai/deepseek-harness/blob/master/SAFETY.md) | Явное предупреждение о выполнении модельного кода, credentials, files и plugins; least privilege и disposable environment | Включить threat model, минимальные права, предупреждение о границе sandbox и обязательную проверку внешних эффектов | Сам DSH прямо говорит, что approval/sandbox не гарантируют полной изоляции и не должны быть единственным security control |
| [Aider](https://github.com/Aider-AI/aider) | Repo map, Git integration, diff/undo/checkpoints, работа с изображениями, цикл lint/test после изменений | Read-only repository map, checkpoint до записи, diff preview, commit summary и явный запуск проверок после approval | Aider — Python/CLI-инструмент с desktop filesystem assumptions; автоматические commit/test нельзя делать дефолтом в текущей модели разрешений |
| [Cline](https://github.com/cline/cline) | Plan/Act, human-in-the-loop для edits/commands, checkpoints/undo, project rules, headless/session events | Разделить планирование и выполнение, подтверждать каждую мутацию, добавить checkpoint и файл правил проекта вроде AGENT_RULES.md | Основной UX рассчитан на desktop/IDE/CLI; auto-approve и длительные процессы требуют более строгой Android policy |
| [OpenHands](https://github.com/All-Hands-AI/OpenHands) и [SDK](https://github.com/OpenHands/software-agent-sdk) | Отделение control plane от executor, сменные backends, workspace/event/task abstractions | ExecutionProvider, WorkspaceProvider, TaskTracker и сохранение сессии при смене исполнителя | Полный стек Python/TypeScript/server/Docker не нужен для одного APK; документация сама подчёркивает риск запуска без sandbox с полным filesystem access |
| [Termux](https://github.com/termux/termux-app) | Bootstrap, ABI/runtime packaging, process lifecycle и отдельное управление терминалом | Только идеи RuntimeSupervisor: readiness, heartbeat, versioned bundle, ABI check, rollback | Termux — отдельная Linux/Android-среда; документация предупреждает о нестабильности фоновых процессов на Android 12+ и большом размере bootstrap. Это не должна быть обязательная зависимость |
| [harness-mobile](https://github.com/vetuhvladislav-cmyk/harness-mobile) и его [connection docs](https://github.com/vetuhvladislav-cmyk/harness-mobile/blob/main/docs/connection.md) / [DOM map](https://github.com/vetuhvladislav-cmyk/harness-mobile/blob/main/docs/gui-dom-map.md) | Чёткая граница repository ownership, диагностируемое подключение, семантические data-* markers вместо одних hash-классов | Сохранить boundary docs и диагностические контракты; если когда-либо понадобится WebView-поверхность — опираться на семантические markers и отдельный adapter layer | WebView зависит от сервера, auth/cookie и DOM-структуры; hash-классы хрупки. Нельзя делать DOM, WebView или внутренний DSH RPC контрактом Agent Core |

## 5. Идеи, которые стоит занести в план

Это кандидаты на будущую реализацию, а не согласование на немедленную разработку.

### 5.1. Contract-first ToolRouter

Каждый инструмент должен иметь описатель примерно такого состава:

- name и semantic version;
- category: read, workspace, git, github, actions, image;
- inputSchema и outputSchema;
- required permission;
- workspace scope;
- timeout и max output bytes;
- mutating flag;
- idempotency/recheck strategy;
- redaction policy;
- стабильные error codes.

Минимальный read-only контур следует начать с list_files, read_file, search_code, git_status и git_diff. Для каждого нужен workspace fingerprint и явная отметка truncation. Инструмент не должен сам выбирать другой workspace или повышать permission.

### 5.2. WorkspaceIdentity и снимки состояния

Вместо передачи только пути следует передавать объект состояния:

- источник: local, imported, remote или GitHub;
- логический workspace ID;
- root/scope;
- repository и ref;
- base commit SHA;
- read-only/read-write mode;
- fingerprint;
- createdAt/updatedAt.

Тогда diff, tool result, Actions run и PR смогут ссылаться на конкретное состояние, а не на изменяемую строку пути.

### 5.3. Два потока событий

Полезно разделить:

- durable session events — то, что восстанавливает контекст и состояние;
- live events — поток прогресса UI, heartbeat и временных логов.

Рекомендуемые общие поля:

sessionId, eventId, sequence, timestamp, kind, invocationId, providerRunId, redactionState, schemaVersion.

Для незавершённой операции после process death нужен статус unknown, а не автоматическое повторение. Для workflow — повторная проверка run ID; для записи файла — проверка workspace fingerprint и diff.

### 5.4. ExecutionProvider вместо прямых вызовов исполнителей

Ввести единый концептуальный контракт для Local Lite, GitHub Actions и будущего headless DSH:

- capabilities;
- readiness;
- start;
- streamEvents;
- cancel;
- recover;
- collectArtifacts.

UI знает только AgentBridge. Agent Core выбирает provider по capability и policy. Это позволит позже заменить DSH, не меняя Compose и публичный протокол.

### 5.5. Plan/Act и checkpoints

Заимствовать идею Cline/Aider:

- Plan — анализ и список действий без мутаций;
- Act — выполнение после approval;
- checkpoint — сохранённая точка до каждой группы записей;
- diff preview — обязательный экран перед commit/push;
- undo/revert — отдельная операция, тоже с permission gate.

Автоматический commit допустимо рассматривать только как генерацию подготовленного commit message и набора изменений. Сам commit, push и PR должны оставаться отдельными переходами.

### 5.6. Repository map

Заимствовать у Aider компактную карту репозитория:

- строится только из разрешённого workspace;
- использует ignore rules;
- ограничивает размер и глубину;
- отделяет структуру от полного содержимого файлов;
- помечает устаревший fingerprint;
- не отправляет секретные или игнорируемые файлы модели.

Это даст модели контекст больших проектов без загрузки всего дерева. Реализацию следует планировать после базового read-only ToolRouter.

### 5.7. Actions RunLedger

Состояния удалённой сборки лучше документировать как последовательность:

dispatch → workflow run → jobs → steps/logs → artifacts → verified result.

Для каждой сущности нужны:

- correlation ID;
- repository/ref/SHA;
- workflow и input parameters;
- current state;
- start/end time;
- cancellation/retry information;
- artifact name, size, SHA-256, expiry и download status.

APK нельзя показывать как готовый результат только потому, что workflow завершился успешно: нужна проверка существования, типа, размера и checksum.

### 5.8. TaskTracker и project rules

Из OpenHands полезна отдельная структурированная модель задачи, а не только текст в чате:

- цель;
- подзадачи;
- текущий шаг;
- блокер;
- approval required;
- итог;
- ссылки на diff/run/artifact.

Из Cline полезен проектный файл правил. В Deep Agent его можно рассматривать как будущий AGENT_RULES.md, который читается явно и не может переопределять глобальную permission policy приложения.

## 6. Что не следует переносить целиком

- Не встраивать весь Cordis/DSH plugin tree в контракт приложения. Использовать его как источник архитектурных паттернов; DSH остаётся заменяемым provider.
- Не переносить весь Aider CLI и не включать безусловные auto-commit/lint/test.
- Не переносить desktop/IDE-ориентированный стек Cline; брать Plan/Act, approvals и checkpoints.
- Не превращать Deep Agent в OpenHands-подобную распределённую серверную платформу. Нужны лёгкие адаптеры provider/workspace.
- Не включать полный Termux, package manager, PRoot и PTY до доказанной необходимости и отдельного решения.
- Не смешивать Agent Core с DOM/WebView Harness. Общий пользовательский APK не требует общего runtime-контракта.
- Не хранить в durable journal токены, cookies, Authorization headers или необработанные секретные tool outputs.

## 7. Приоритеты после синхронизации документов

| Этап | Рекомендуемое содержание | Критерий готовности |
|---|---|---|
| P0-0 | Устранить расхождения README/roadmap/ideas и зафиксировать статусную модель | Для каждого capability есть один статус и одна ссылка на источник истины |
| P0-A | WorkspaceIdentity и read-only ToolRouter | Все read-only tools имеют schema, scope, limits, errors и тестовые fixtures |
| P0-B | Durable session journal и recovery | Процесс можно остановить между шагами и восстановить без повторения завершённых side effects |
| P0-C | MVP setup, TaskTracker и нормализованный event UI | Пользователь видит plan, current step, approval, error и final result |
| P1-A | diff/apply_patch, checkpoints, Git и ручной PR | Каждая мутация имеет approval, diff, base SHA и recovery path |
| P1-B | Actions RunLedger, jobs/logs и verified APK artifacts | По session ID можно восстановить весь путь от dispatch до скачанного файла |
| P1-C | Android 16 UI/regression contract | Основные сценарии имеют stable test IDs, fixtures и проверки rotation/insets/keyboard |
| P2-A | RuntimeSupervisor/headless DSH provider | Есть state machine, readiness, heartbeat, version/ABI check, cancellation и rollback |
| P2-B | PTY | Добавляется только после стабильного headless lifecycle и доказанной потребности |
| D1/D2/D3 | OCR/сравнение изображений, расширенный workspace, token/change-log/auto-PR policy | Отдельные решения с privacy, storage, approval и rollback criteria |

## 8. Открытые решения, которые лучше не угадывать

До перехода от документации к соответствующему этапу стоит отдельно согласовать:

1. Какой workspace является каноническим: app-private копия, импортированный каталог, remote snapshot или Git checkout.

2. Нужно ли сохранять токены только в памяти, использовать Android Keystore или вводить proxy/backend. Любой вариант требует redaction и явного поведения после перезапуска.

3. Что означает автоматический PR: только подготовка черновика, создание PR после approval или полностью автоматическая публикация. Последний вариант не должен появляться как скрытое следствие GitHub_WRITE.

4. Какие GitHub scopes разрешены для read, push, Actions, PR и release; как приложение проверяет, что target repository/ref совпадает с подтверждённым.

5. Какой именно набор Android 16 устройств/эмуляторов и системных ограничений считается обязательным для UI-контракта.

6. Какая версия/ABI DSH допустима внутри одного APK и что считается безопасным rollback.

## 9. Итог

Документированная архитектура Deep Agent Mobile выглядит пригодной для поставленной задачи. Её сильные стороны — цельный APK, собственный Agent Core, явные границы репозитория, permission model и вынос тяжёлой сборки в GitHub Actions.

Главное улучшение — перейти от описания модулей к строгим контрактам состояния и операций. Наиболее ценные заимствования:

- из DSH — capability seams, event log и tool lifecycle;
- из Aider — repository map, diff/checkpoint и undo;
- из Cline — Plan/Act и approval на каждую мутацию;
- из OpenHands — сменные execution/workspace providers и TaskTracker;
- из Termux — supervisor, ABI/versioning и rollback;
- из harness-mobile — дисциплина границ и диагностируемые adapter-контракты.

При этом проблемы источников нужно считать частью требований: DSH остаётся developer preview, sandbox не является абсолютной изоляцией, Termux несёт Android process/size ограничения, а WebView и DOM-селекторы создают хрупкую зависимость от внешнего интерфейса.

На текущем этапе разумно документировать и согласовывать именно эти контракты. Реализацию расширений выполнять по утверждённому приоритету отдельными этапами.

## 10. Использованные внешние источники

- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
- [DeepSeek Harness architecture](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/architecture.md)
- [DeepSeek Harness safety notice](https://github.com/deepseek-ai/deepseek-harness/blob/master/SAFETY.md)
- [Aider](https://github.com/Aider-AI/aider)
- [Cline](https://github.com/cline/cline)
- [OpenHands](https://github.com/All-Hands-AI/OpenHands)
- [OpenHands Software Agent SDK](https://github.com/OpenHands/software-agent-sdk)
- [Termux app](https://github.com/termux/termux-app)
- [harness-mobile](https://github.com/vetuhvladislav-cmyk/harness-mobile)
- [harness-mobile connection documentation](https://github.com/vetuhvladislav-cmyk/harness-mobile/blob/main/docs/connection.md)
- [harness-mobile DOM map](https://github.com/vetuhvladislav-cmyk/harness-mobile/blob/main/docs/gui-dom-map.md)
