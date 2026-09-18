# Архитектура Android Agent

> Канонический источник архитектурных границ, владельцев capability, контрактов, разрешений и инвариантов Deep_Agent_Mobile.

Этот документ описывает неизменяемую форму продукта и связи между его слоями. Порядок реализации, критерии выхода и состояние этапов находятся в дорожной карте. Отложенные идеи и неподтверждённые направления находятся в отдельном файле идей.

## 1. Граница продукта

Deep_Agent_Mobile — одно цельное Android-приложение и одна пользовательская поставка в виде одного APK.

В состав продукта входят:

- нативная Android-оболочка и Agent Console;
- Agent Core;
- внутренний контракт AgentBridge v1;
- DeepSeek provider;
- local lite-runtime;
- Workspace Manager и ToolRouter;
- GitHub connector;
- GitHub Actions connector;
- Session Journal и управление артефактами;
- единая Permission Policy.

DeepSeek и GitHub Actions являются внешними сервисами, но доступ к ним организуется из того же APK. Пользовательский интерфейс не зависит от внутренних endpoint конкретного runtime или провайдера.

Ограничения пользовательской поставки:

- обязательный Termux не допускается как зависимость приложения;
- отдельный DSH APK не допускается как часть поставки;
- вторая пользовательская оболочка не допускается; UI остаётся в одной нативной оболочке;
- второй APK не является частью пользовательской поставки.

Расширения D1–D3 выполняются внутри существующих владельцев capability: Image Pipeline за AgentBridge/DeepSeek/Artifact Manager, расширенная workspace-модель за Workspace Manager/Session Journal, а token/journal/PR flow за Permission Policy/Session Journal/GitHub Connector. Эти этапы не создают вторую оболочку или новый обязательный runtime.

Серверные plugins не устанавливаются, не изменяются и не мутируются приложением. Harness WebView/mobile-adapter остаётся отдельным продуктом и не создаёт runtime-зависимость Deep_Agent_Mobile.

Тяжёлые операции, включая Android/NDK/CMake-сборки и получение build artifacts, выполняются через GitHub Actions. Наличие Android SDK/NDK внутри базового APK не является условием работы local lite-runtime.

## 2. Слои и владельцы capability

| Слой | Владелец | Ответственность | Прямые зависимости UI |
| --- | --- | --- | --- |
| Compose UI / Agent Console | UI layer | ввод задачи, отображение состояния, approval, diff, событий и артефактов | только AgentBridge v1 |
| AgentBridge v1 | boundary layer | стабильная граница UI ↔ Agent Core, state и ordered event stream | не вызывает providers напрямую |
| Agent Core | orchestration layer | lifecycle сессии, bounded planning/evaluation, target binding, маршрутизация, permission gate и нормализация результатов | владеет orchestration |
| Permission Policy | policy layer | проверка уровня разрешения, scope, approval и запрет self-escalation | вызывается через Agent Core |
| ProviderRegistry / LlmProvider | model provider boundary | разрешение только зарегистрированных providers; Responses API, streaming, reasoning, bounded tool rounds и image input | не владеет UI state |
| Local Lite Runtime | local execution layer | ограниченные локальные операции, RuntimeSupervisor lifecycle и readiness/health probe | не является UI API |
| Workspace Manager | workspace layer | источники workspace, canonical paths, identity, fingerprint и checkpoints | не принимает решения о permission |
| ToolRouter | tool layer | allowlisted typed tools, input/output validation, limits и error normalization | не выполняет произвольный shell |
| GitHub Connector | Git provider | repository read, branch, commit, push и Pull Request | только через Core и permission gate |
| GitHub Actions Connector | Actions provider | dispatch, run/job/step state, logs и artifacts | только через Core и permission gate |
| Session Journal | persistence layer | durable SessionRecord в app-private storage | UI не читает журнал напрямую |
| Artifact Manager | artifact layer | provenance, fingerprint, checksum и локальное размещение артефактов | публикует результат через AgentBridge |

Владелец capability отвечает за его контракт, нормализацию ошибок, cancellation, timeout, redaction и связь с sessionId. Один capability не может молча передавать владение другому слою.

### 2.1 RuntimeSupervisor и headless execution boundary

RuntimeSupervisor является внутренним владельцем lifecycle локального headless runtime. Разрешённая последовательность — EMPTY → INSTALLING → STARTING → READY → STOPPING → EMPTY; ошибки проходят через FAILED/ROLLBACK, а активным становится только runtime с подтверждённым manifest, ABI, checksum и readiness probe. Loopback provider допустим как безопасный reference adapter, пока DP-02 не выберет и не проверит подписанный ARM64 bundle.

Runtime provider не получает GitHub, merge/release или credential permission. PTY/interactive provider, когда он включён, вызывается только через Agent Core с canonical workspace scope, allowlisted executable/arguments, bounded timeout и redacted output. Произвольный shell и sh -c не являются скрытым fallback. Текущий interactive adapter допускает только прямой pipe-backed git status/diff/log; это не объявляется полноценным PTY до отдельного ABI/backend решения. Если timeout/cancel не подтверждает остановку процесса, состояние публикуется как отдельный `CLEANUP_UNKNOWN` и не запускает автоматический retry.

### 2.2 Image Pipeline и visual input

Image Pipeline является внутренним владельцем D1 и вызывается только через AgentBridge. Он копирует выбранный URI в app-private bounded cache, проверяет MIME и file signature, ограничивает byte/pixel budget и создаёт deterministic SHA-256 asset identity. В DeepSeek передаётся только временный data URL для уже проверенного asset; URI источника, raw bytes и data URL не попадают в live/durable journal.

Перед внешней передачей UI показывает user-visible disclosure. Состояние attachment, checksum, размеры, transfer decision и result state публикуются как redacted metadata через AgentBridge. Отмена, недоступный asset или ошибка provider переводят visual state в FAILED/UNKNOWN и не запускают скрытый retry. Отдельный raster-generation provider, OCR, multi-image и screenshot diff пока не входят в закрытый baseline D1.

### 2.3 Workspace Catalog и project rules

Workspace Catalog остаётся частью Workspace Manager и публикуется только через AgentBridge. Он хранит app-private список workspace с устойчивыми workspaceId, последним подтверждённым fingerprint и optional repository/ref/commitSha; выбранный workspace получает bounded tree page без raw provider response.

Tree paging использует canonical path boundary, лимит глубины/элементов и fail-closed обработку symbolic links. Root AGENT_RULES.md читается только allowlisted read_file, ограничивается по размеру и передаётся DeepSeek как дополнительные недоверенные ограничения. Rules не могут повысить permission, изменить global policy, выбрать другой workspace или заменить user approval. Catalog не создаёт отдельный TaskTracker/state owner.

### 2.4 Credentials, Session Journal и PR provenance

EphemeralCredentialVault является единственным владельцем provider secrets в runtime. UI вводит DeepSeek API key и GitHub token в memory-only state; AgentBridge публикует только CredentialState с boolean-признаками configured и timestamp. Vault хранит значения в wipeable char arrays, автоматически очищает их после bounded TTL и очищается при закрытии AgentCore. Legacy credential fields входного AgentRequest санитизируются до запуска сессии; секреты не проходят в события, SessionStore или redacted result. Явное сохранение или экспорт preset — отдельная user-initiated операция: credential fields шифруются AES-GCM ключом Android Keystore, а runtime vault при этом не становится persistent.

Session Journal остаётся владельцем AgentCore/SessionStore: каждая запись ограничена по размеру, число старых session-файлов и общий объём retention ограничены, запись выполняется atomically. Пользовательский export идёт через SAF destination и содержит только PersistedAgentSession.toJson() с redaction; JournalExportResult возвращает sessionId/status/size/error code без URI, содержимого credentials или raw provider response.

Pull Request не создаётся автоматически. Перед ручным PR_CREATE approval Core повторно проверяет выбранный workspace через Git status, требует подтверждённый текущий HEAD SHA и active sessionId, а GitHub response обязан вернуть тот же head SHA. Ошибка сети, mismatch или неполная provenance переводят операцию в UNKNOWN/FAILED и блокируют replay; merge/release остаются отдельными decision gates.

Git, PR и Actions mutation requests несут operation ID, связанный с текущей session. AgentCore сериализует write-вызовы, возвращает сохранённый результат для повторной пары session/operation без нового side effect и блокирует новый Git/Actions side effect после UNKNOWN до re-check. Последний нормализованный Git result входит в versioned SessionStore snapshot; это не заменяет серверную идемпотентность и не разрешает replay после смены session/workspace.

GitHub Actions dispatch имеет обязательную корреляцию `agent_session_id`, `operation_id` и expected commit SHA. Оба идентификатора передаются в workflow inputs и run-name; discovery принимает только run с совпадающими session/operation markers и commit SHA. Artifact download дополнительно связывается с конкретными run ID и source SHA, поэтому concurrent run нельзя выбрать только по branch/ref.

## 3. Контракты данных

### 3.1 WorkspaceIdentity

Каждый ToolCall, diff, artifact и SessionRecord обязан содержать WorkspaceIdentity.

```json
{
  "workspaceId": "string",
  "source": "app-private | saf-import | github-snapshot",
  "root": "string",
  "repository": "owner/name | null",
  "ref": "branch-or-tag-or-null",
  "commitSha": "sha-or-null",
  "mode": "read-only | read-write",
  "fingerprint": "sha256-tree:<hash>"
}
```

Правила:

- workspaceId идентифицирует логический workspace, а не только строку пути;
- root нормализуется и является границей всех path-scoped операций;
- repository, ref и commitSha заполняются, когда источник их предоставляет;
- fingerprint строится через Git tree hash либо content-based Merkle tree;
- в fingerprint участвуют относительный путь, тип entry, размер и hash содержимого в детерминированном порядке;
- mtime не используется как основание для применения diff или patch;
- при fingerprint(plan) != fingerprint(apply) применение отменяется и требуется новый preview/re-check.

### 3.2 ToolCall

ToolCall — типизированный запрос к allowlisted tool.

| Поле | Требование |
| --- | --- |
| schemaVersion | версия схемы вызова |
| invocationId | уникален в пределах сессии |
| sessionId | связывает вызов с SessionRecord |
| workspace | полный WorkspaceIdentity |
| toolName | имя из allowlist; произвольная shell-команда не допускается |
| arguments | JSON-аргументы после проверки схемы и лимитов |
| requiredPermission | минимальный уровень для операции |
| deadline | абсолютный deadline или нормализованный timeout |
| idempotencyKey | ключ повторной проверки для provider operation |
| createdAt | время создания для аудита |

ToolCall не может выбрать другой workspace, повысить permission или скрыть внешний эффект.

### 3.3 ToolResult

ToolResult — нормализованный результат исполнения одного ToolCall.

| Поле | Требование |
| --- | --- |
| invocationId | совпадает с вызовом |
| sessionId | связывает результат с сессией |
| workspace | identity, к которому относится результат |
| state | succeeded, failed, cancelled или unknown |
| data | структурированные данные или redacted text |
| errorCode | стабильный код ошибки либо null |
| truncated | признак ограничения результата |
| fingerprintAfter | fingerprint после операции, если применимо |
| completedAt | время завершения либо фиксации неизвестного состояния |

Незавершённый provider call не считается успешным только по отсутствию ошибки транспорта.

### 3.4 AgentEvent

AgentEvent — упорядоченное наблюдаемое событие сессии.

| Поле | Требование |
| --- | --- |
| eventId | уникальный идентификатор события |
| sessionId | идентификатор сессии |
| sequence | монотонный порядок внутри сессии |
| schemaVersion | версия схемы события; текущая версия — 1 |
| type | SESSION, PLAN, REASONING, OUTPUT, TOOL, DIFF, APPROVAL, BUILD, ARTIFACT, ERROR или INFO |
| timestamp | время формирования |
| workspace | identity, если событие относится к workspace |
| payload | bounded redacted payload без секретов; текущий лимит — 12 000 символов |

Live stream может содержать дополнительные диагностические поля, но durable запись сохраняет только нормализованные и redacted данные. В текущем `SessionStore` отсутствующий `schema_version` старых записей читается как версия 1; новые записи сохраняют `schema_version` и очищенный `payload`.

### 3.5 Provider boundary

`LlmProvider` — явный контракт между Agent Core и model provider. `ProviderRegistry` разрешает только заранее зарегистрированные IDs; в текущем APK зарегистрирован `deepseek.responses`. Это не означает поддержку произвольных OpenAI-compatible endpoint. Для DeepSeek Core и transport client принимают только HTTPS host `api.deepseek.com` с default/443 port, без credentials/query/fragment; HTTP redirects отключены, чтобы Authorization не передавался на другой host.

Новый provider допускается к регистрации только после отдельного контракта, credential policy, timeout/cancellation и host/redirect policy.

### 3.6 SessionRecord

SessionRecord — durable состояние сессии в app-private storage.

| Поле | Требование |
| --- | --- |
| schemaVersion | версия записи |
| sessionId | стабильный идентификатор сессии |
| workspace | identity текущего target |
| permission | разрешение текущей сессии |
| state | IDLE, RUNNING, WAITING_APPROVAL, PAUSED, FAILED, UNKNOWN, COMPLETED или CANCELLED |
| requestSummary | redacted описание запроса |
| invocations | состояния вызовов и их correlation IDs |
| decisions | approval и критические решения без секретов |
| actionsState | последний подтверждённый или UNKNOWN Actions outcome с operation ID |
| gitOperationResult | последний нормализованный Git/PR outcome с operation ID |
| eventCursor | последний durable sequence |
| updatedAt | время последнего изменения |

SessionRecord не содержит DeepSeek/GitHub tokens, cookies, Authorization headers или необработанные секретные tool outputs.

### 3.6 AgentBridge v1

AgentBridge v1 — единственная граница между UI и Agent Core.

```kotlin
interface AgentBridge {
    fun submit(request: AgentRequest): SessionId
    fun cancel(sessionId: SessionId)
    fun clearEvents(sessionId: SessionId)
    fun observeState(sessionId: SessionId): StateFlow<AgentSessionState>
    fun observeEvents(sessionId: SessionId): StateFlow<List<AgentEvent>>
    fun restore(sessionId: SessionId): AgentSessionState
}
```

Требования:

- UI вызывает только AgentBridge;
- внутренние классы DeepSeek, GitHub, Actions и runtime не становятся UI-контрактом;
- новые capability добавляют типизированные события и provider interfaces за Bridge;
- sessionId сохраняется в каждом результате, событии и durable record.
- В MVP AgentBridge также отдаёт redacted workspace snapshot и pending approval; импорт workspace, approval и lifecycle вызываются через Bridge, а сырые tool arguments не выходят в UI.

## 4. Permission model

Permission presets являются capability bundles, а не линейной ordinal-иерархией:

| Preset | Capability bundle |
| --- | --- |
| READ_ONLY | чтение, анализ, планирование и diff |
| LOCAL_WRITE | READ_ONLY + локальная запись workspace; GitHub-доступ не добавляется |
| GITHUB_WRITE | READ_ONLY + branch, commit, push и workflow dispatch; локальная запись не добавляется |
| PR_CREATE | READ_ONLY + GITHUB_WRITE + создание Pull Request; локальная запись не добавляется |
| MERGE_RELEASE | полный набор предыдущих capabilities + merge/release |

Permission preset является атрибутом сессии и не может быть повышен текстом модели. Операции с внешним эффектом дополнительно требуют явного пользовательского approval, target binding и актуального WorkspaceIdentity.

### 4.1 Матрица операция × capability

| Операция | READ_ONLY | LOCAL_WRITE | GITHUB_WRITE | PR_CREATE | MERGE_RELEASE |
| --- | --- | --- | --- | --- | --- |
| чтение, анализ, поиск, планирование, diff | да | да | да | да | да |
| локальная запись workspace | нет | approval | нет | нет | approval |
| branch / commit / push | нет | нет | approval | approval | approval |
| workflow dispatch | нет | нет | approval | approval | approval |
| создание Pull Request | нет | нет | нет | approval | approval |
| merge / release / финальное внешнее действие | нет | нет | нет | нет | approval |

Capability проверяется непосредственно перед действием. Разрешение, указанное в ToolCall, не заменяет capabilities текущей сессии; model output не является permission grant.

### 4.2 ApprovalToken для controlled write

Для model-generated `apply_patch` явное подтверждение оформляется непрозрачным in-memory `ApprovalToken`. Он связывает operation, sessionId, workspaceId, preview fingerprint, path, old/new SHA, digest исходных аргументов, время выдачи и expiry. Core проверяет token и все связанные поля непосредственно перед apply; mismatch или истёкший TTL не дают выполнить запись. Истёкший token переводит сессию в `UNKNOWN` и требует нового preview, а token не сохраняется в journal и не replay-ится после process death. После token gate ToolRouter повторно проверяет актуальный workspace fingerprint и SHA.

## 5. Live event stream и durable journal

Live и durable состояния разделены.

Live event stream:

- публикуется через AgentBridge;
- имеет форму StateFlow<List<AgentEvent>>;
- предназначен для текущего UI и временного наблюдения;
- может быть очищен или пересоздан после восстановления.

Durable event journal:

- хранится как SessionRecord в app-private storage;
- содержит только versioned и redacted записи;
- является источником восстановления Agent Core;
- не является прямым источником данных для UI.

Инварианты:

1. UI не читает journal напрямую.
2. Agent Core читает journal и публикует нормализованное состояние в live stream.
3. Незавершённая операция получает UNKNOWN, требует re-check и не повторяется автоматически.
4. Завершённая операция не запускается повторно только потому, что Activity или процесс были восстановлены.
5. Секреты не попадают ни в live payload, ни в durable journal.

### 5.1 Формат durable journal и recovery

Текущая реализация `SessionStore` использует versioned bounded JSON snapshot v7 на сессию: массив событий, Actions state и последний Git/PR result сохраняются в `session-<id>.json`, а указатель `latest` обновляется атомарно. Это устойчивый journal-подобный формат для текущего APK, но не append-only JSONL; переход к JSONL потребует отдельной миграции и проверки recovery. События имеют `schemaVersion=1` и bounded redacted `payload`; прежние записи без версии читаются совместимо как версия 1. Сохранённый success/UNKNOWN outcome используется только для той же session/operation; незавершённая операция после process death переводится в UNKNOWN и не replay-ится.

Запись выполняется через временный файл с flush/sync и atomic replacement с безопасным fallback. Размер одной записи, число session-файлов, общий retention и число событий ограничены. Восстановление нормализует повреждённые идентификаторы, не запускает повторно write/external actions и публикует неполное состояние вместо молчаливого replay.

## 6. Критические точки согласования

Каждая критическая развилка имеет отдельное поле decisionState. Допустимые значения: open, resolved или deferred. Это поле не является состоянием capability и не заменяет permission или результат операции.

| ID | Вопрос | decisionState |
| --- | --- | --- |
| DP-01 | Постоянное хранение provider tokens: только сессия, Android Keystore или внешний proxy? | deferred |
| DP-02 | Какой ARM64 Node.js/DSH bundle и ABI-контракт допустим внутри одного APK? | open |
| DP-03 | Нужны ли persistent shell и PTY после стабилизации local lite-runtime? | deferred |
| DP-04 | Какой отдельный provider отвечает за растровую генерацию изображений? | deferred |
| DP-05 | Какая минимальная версия Android фиксируется для публичной поставки? | open |
| DP-06 | Допускается ли автоматическое создание PR и при каких approval gates? | deferred |

Новое решение не считается принятым, пока его decisionState не изменён явно и не сохранено обоснование в соответствующем документе.

## 7. Out-of-scope

Следующие направления не являются частью границы продукта:

- установка или изменение серверных plugins;
- прямой UI-доступ к DSH/WebView/DOM/RPC endpoint;
- выдача модели права на самостоятельное повышение permission;
- неограниченный shell-инструмент, скрытый за обычным tool call;
- постоянное хранение секретов без отдельного решения;
- применение patch на основании mtime или неподтверждённого fingerprint;
- включение Android SDK/NDK в базовый APK как обязательное условие тяжёлой сборки.

Out-of-scope не может быть добавлен в roadmap без отдельного изменения этого архитектурного документа.

## 8. Общие инварианты

1. Пользовательская поставка — один APK.
2. UI общается с Agent Core только через AgentBridge v1.
3. Каждый tool call, diff, artifact и session record имеет WorkspaceIdentity.
4. Изменение fingerprint между plan/preview и apply отменяет применение.
5. Model output не является permission grant.
6. Неизвестная операция получает UNKNOWN и не повторяется автоматически.
7. Live stream и durable journal не смешиваются.
8. Durable данные redacted и не содержат секретов.
9. Каждый provider возвращает нормализованный результат с timeout, cancellation и error state.
10. Один факт имеет одного владельца.
11. Все внешние и локальные mutation paths используют operation/source binding; локальные write paths сериализуются общей guard-мьютексом, а несовместимый или неполный binding блокирует replay.
12. P1/P2 code pass не является acceptance: capability становится available только после per-stage behavior/device evidence.

### 8.1 Правило одного факта

| Факт | Единственный источник |
| --- | --- |
| граница продукта, слои, контракты, permission matrix, out-of-scope и инварианты | этот документ |
| порядок этапов, состояние этапов, карточки реализации и exit criteria | IMPLEMENTATION_ROADMAP_RU.md |
| deferred, неподтверждённые идеи и проекции out-of-scope | IDEAS_EXTENSIONS_ANALYSIS_RU.md |
| навигация и краткая сводка | README.md |

Другие документы используют ссылку на источник вместо копирования архитектурного факта. При расхождении применяется этот принцип владения источником.


## 9. Визуальная система Agent Console

Agent Console использует собственную визуальную систему, вдохновлённую только общими правилами из предоставленного PDF: сдержанная тёмная палитра, контрастная моно-линейная иконографика, тонкие границы и явное кодирование состояния. Компоновка, top bar, left rail, sensor split, telemetry и другие элементы интерфейса Autel в продукт не переносятся.

Основные токены тёмной темы:

| Роль | Цвет | Использование |
| --- | --- | --- |
| near black | `#030609` | фон приложения |
| graphite | `#080D12` | поверхности и карточки |
| white | `#F2F5F4` | основной текст и нейтральные иконки |
| violet | `#912BFF` | идентичность Agent Core и основной акцент |
| cyan | `#00DFFF` | выполнение и информационные состояния |
| lime | `#91F000` | активное/завершённое состояние |
| amber | `#F5C542` | approval, read-only и ограниченные действия |
| red | `#FF5C5C` | ошибка и блокирующее состояние |

Правила применения:

1. Цвет не заменяет текст: каждое состояние дополнительно описывается подписью или контекстом.
2. Иконки остаются моно-линейными, строятся на общей 24 dp сетке и получают tint по состоянию.
3. Фиолетовый и циан используются как идентичность и информационный акцент, а не как универсальный сигнал ошибки.
4. Поверхности используют небольшое количество уровней глубины и тонкую границу; постоянное свечение и тяжёлая вложенность карточек не применяются.
5. Текст пользовательского интерфейса — русский; технические имена протоколов, permission и Git-команд сохраняются без перевода, когда это нужно для точности.
6. Тема применяется через существующий Compose `DeepAgentTheme`; Agent Core, AgentBridge v1, permission gates и владельцы состояния не изменяются.


## 13. MVP hardening: ADR и security boundary

Эта секция фиксирует стабильные решения hardening-среза. Порядок реализации и exit criteria находятся в дорожной карте.

### ADR-001 — Durable Operation Ledger

- **Дата:** 2026-09-17.
- **Последнее уточнение:** 2026-09-18.
- **Статус:** accepted.
- Side-effect операция проходит `PREPARED + fsync → STARTED + fsync → effect → terminal + fsync`.
- Ledger использует framing, длину payload, checksum, sequence, boot ID, operation ID, integrity mode и key version.
- Базовый integrity profile — CRC32C.
- HMAC-SHA-256 через Android Keystore используется для privileged/remote/exported trace profile; потеря или invalidation ключа переводит состояние в `KEY_UNAVAILABLE/CORRUPT/UNKNOWN`.
- `FULL` и `BATCHED` durability не смешиваются с resolution. `BATCHED` запрещён для side-effect операций.
- `QUERYABLE`, `IDEMPOTENT` и `BLIND` имеют разные recovery actions; только `IDEMPOTENT` допускает явный retry с тем же operation ID после re-check.
- Старые `PREPARED/STARTED/RUNNING/PENDING` не продолжаются автоматически и переводятся в `UNKNOWN`.
- Runtime projection пересчитывает UNKNOWN/recovery metadata после каждой append; неподтверждённая terminal запись не может быть опубликована как успешный side effect.
- Повреждение trailing frame обрезается. Повреждение середины ledger блокирует автоматическое продолжение.
- Downgrade формата ledger запрещён; перенос выполняется только через export/import.

### ADR-002 — CanonicalArgs и approval binding

- **Дата:** 2026-09-17.
- **Статус:** accepted.
- Approval связывает session ID, tool name, operation ID, canonical args SHA-256, workspace ID, workspace fingerprint, target SHA, old/new content SHA и expiry; изменение любого binding-поля инвалидирует approval.
- Raw JSON с duplicate keys, invalid numbers или невалидной структурой отклоняется.
- Golden vectors находятся в `app/src/test/resources/canonical_args_v1_vectors.json`.
- Kotlin-реализация и независимый reference находятся в `tools/canonical_args_reference.py`.

### ADR-003 — Typed tool envelope и capability isolation

- **Дата:** 2026-09-17.
- **Статус:** accepted.
- Tool output передаётся модели только как typed envelope с `schema_version`, capability, trust, `content_is_data`, `instructions_are_data`, session ID, operation ID и canonical args SHA-256.
- Workspace/provider output считается untrusted content и не может расширить capability set.
- Tool definitions фильтруются до передачи модели по текущему permission.
- Router отвергает неизвестный или запрещённый tool даже при прямом сфабрикованном вызове.
- UI и Agent Core не получают capability через текстовый XML/Markdown-маркер; такие маркеры являются только данными.
- Structural seams выделены без изменения AgentBridge v1: `AgentTransaction`, `ToolRegistry`, `ToolInvoker`, `ToolVerifier`, `EnvelopePolicy`, `ApprovalBinding`, `AuditTraceStore` и `ProcessCleanupController`.
- UNKNOWN отображается отдельной карточкой с ledger health, числом операций, operation ID, типом resolution, последним подтверждённым состоянием, diagnostic reason и запретом automatic retry; export journal включает структурированный redacted ledger diagnostic и audit trace.

### ADR-004 — Threat model

- **Дата:** 2026-09-17.
- **Статус:** accepted for MVP hardening.
- **Assets:** исходный workspace, локальные изменения, Git/PR state, Actions credentials, provider credentials, session journal, operation ledger, APK/CI artifacts и diagnostic exports.
- **Trust boundaries:** пользовательский UI → AgentBridge; AgentBridge → Agent Core; Agent Core → ToolRouter; ToolRouter → workspace/Git/Actions/provider; untrusted workspace/tool/provider output → model context; app-private persistence → exported diagnostic bundle.
- **Malicious inputs:** prompt injection в workspace rules, Markdown/XML, source comments, tool output, CI logs, provider response и forged function call.
- **Data flow control:** canonical args и typed envelope до model context; permission/capability check до invocation; approval binding до side effect; redaction до journal, snapshot, trace и UI; provenance/checksum до artifact acceptance.
- **P0 controls:** fail-closed permission policy, unavailable tools omitted from model context, CanonicalArgs rejection before dispatch, direct forged tool rejection, fingerprint/target SHA/operation binding, durable ledger, UNKNOWN recovery, no automatic replay, secret redaction и HMAC profile.
- **Automatic tests:** CanonicalArgs golden/negative vectors, ledger torn-write/middle-corruption/key-loss/replay tests, capability filtering, forged-tool rejection, typed-envelope injection fixture и approval binding tests.
- **Residual risks:** Android process death между effect и terminal frame, compromise of the host OS/Keystore, malicious content that is not recognized as a secret, и correctness of external GitHub/Actions state until re-check.
- **Privileged mode:** отдельная capability profile с теми же approval, ledger, provenance и redaction invariants; privileged mode не может быть получен моделью самостоятельно.

### ADR-005 — P1/P2 provenance и cleanup boundary

- **Дата:** 2026-09-18.
- **Статус:** accepted for static implementation pass; runtime/device acceptance open.
- Все локальные mutation paths `apply_patch`, rollback и сохранение проверенного artifact сериализуются вместе с Git/Actions mutation paths через общую Core guard; fingerprint/approval re-check выполняется в этой сериализованной границе.
- Actions dispatch не допускается без expected source commit SHA. Idempotency cache hit требует session ID, operation ID, repository, workflow, ref, expected SHA, canonical request SHA-256 и совместимого terminal head SHA; старое состояние без полного binding не считается cache hit.
- UI selectors и accessibility labels принадлежат каноническому `AgentUiContract`; локализованный видимый текст не является test/security boundary.
- RuntimeSupervisor проверяет bounded manifest/version/ABI fields и checksum до provider lifecycle. Loopback provider остаётся reference adapter до DP-02.
- Pipe-backed interactive adapter не объявляется PTY. Неподтверждённое завершение процесса публикуется как `CLEANUP_UNKNOWN`, не восстанавливается автоматически и требует отдельной проверки.
- **Validation:** статический source audit выполнен; compile, tests, CI, Android device/runtime и PTY process-tree acceptance не выполнялись.

### ADR-006 — Formal capability contract и границы его внедрения

- **Дата:** 2026-09-18.
- **Статус:** accepted как план реализации; Phase 1 в работе, Phase 2–4 запланированы.
- **Контекст:** обсуждение формального security-контракта зафиксировало семь свойств: capability algebra, constrained `SessionPolicy`, отдельный `ReconcilePolicy`, non-reusable grant после UNKNOWN, tamper-evident ledger, user-presence-bound issuance и dependent `OperationChain`.
- **Решение:** внедрять контракт поэтапно, начиная с трёх точечных фиксов, которые закрывают существующие дыры и проверяются unit-тестами, и только затем вводить capability-алгебру. Обоснование: capability-алгебра — самая объёмная часть (затрагивает все resolvers, connectors и tool adapters), но она не закрывает ни одной текущей дыры, поскольку ограничения уже выражены привязками в `ApprovalBinding`. Порядок по зависимостям («capability-модель первым слоем») отклонён в пользу порядка по риску.

Соответствие семи свойств текущему коду:

| Свойство | Состояние | Факт |
| --- | --- | --- |
| Capability algebra | отсутствует | `PermissionMode` — enum из 5 значений с грубым `allows()`; `ToolCapability` — обёртка 1:1; нет `RepositorySelector`, `RefPattern`, `CanonicalPathSet` |
| Constrained SessionPolicy | частично | `ApprovalBinding` связывает session/workspace/fingerprint/path/old-sha/new-sha/canonical-args, но без общего `maxCapabilities` и бюджета сессии |
| ReconcilePolicy | отсутствует как domain | есть только `LedgerRecoveryAction.RECHECK_REQUIRED`; ни один `reconcile.*` endpoint не реализован |
| Non-reusable grant после UNKNOWN | частично | replay блокируется по `operationId` и есть cached result для пары session/operation, но у токена нет состояния `CONSUMED`, а `clearPendingPatch()` лишь обнуляет `StateFlow` |
| Tamper-evident Ledger | почти | есть Keystore HMAC, `bootId`, `sequence` с детектом разрывов, детект middle/prefix corruption; нет chain/Merkle и signed checkpoints, поэтому удаление префикса записей невидимо |
| User-presence-bound issuance | частично | UI approval и единственный PDP есть; нет биометрии, `setUserAuthenticationRequired` и подписи grant |
| Dependent OperationChain | отсутствует | ledger ключуется одним `operationId`: `records[operationId] = record` перезаписывает запись, `retryUnknown()` удаляет предыдущий UNKNOWN, attempt history не хранится |

Отсутствует во всём коде: `executionAttemptId`, `grantId`, `toolSchemaHash`, `SessionBudget`, `OperationBudget` — 0 вхождений.

Что уже соответствует контракту и не переписывается:

- Граница атомарности ledger совпадает с контрактом: `OperationLedger.execute()` при исключении из side effect пишет `UNKNOWN`, а не `FAILED`; автоматический retry запрещён.
- `ToolOutputEnvelope` реализует prompt-injection defense: `content_is_data`, `instructions_are_data`, `ToolTrust`.
- `ADR-004` уже содержит модель угроз (assets, trust boundaries, malicious inputs, residual risks) — она дополняется capability-моделью, а не создаётся заново.
- `CanonicalArgs` и `EnvelopePolicy` остаются неизменными точками проверки аргументов.

Порядок реализации и критерии выхода зафиксированы в [IMPLEMENTATION_ROADMAP_RU.md](./IMPLEMENTATION_ROADMAP_RU.md), раздел «1B. Formal capability contract». Контракт считается внедрённым только при наличии теста на обход PEP, а не happy-path теста.

