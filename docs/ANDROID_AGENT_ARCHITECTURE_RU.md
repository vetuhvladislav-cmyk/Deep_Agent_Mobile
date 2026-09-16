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

Не являются обязательными частями продукта:

- Termux;
- отдельный DSH APK;
- второй APK;
- вторая пользовательская графическая оболочка.

Серверные plugins не устанавливаются, не изменяются и не мутируются приложением. Harness WebView/mobile-adapter остаётся отдельным продуктом и не создаёт runtime-зависимость Deep_Agent_Mobile.

Тяжёлые операции, включая Android/NDK/CMake-сборки и получение build artifacts, выполняются через GitHub Actions. Наличие Android SDK/NDK внутри базового APK не является условием работы local lite-runtime.

## 2. Слои и владельцы capability

| Слой | Владелец | Ответственность | Прямые зависимости UI |
| --- | --- | --- | --- |
| Compose UI / Agent Console | UI layer | ввод задачи, отображение состояния, approval, diff, событий и артефактов | только AgentBridge v1 |
| AgentBridge v1 | boundary layer | стабильная граница UI ↔ Agent Core, state и ordered event stream | не вызывает providers напрямую |
| Agent Core | orchestration layer | lifecycle сессии, target binding, маршрутизация, permission gate и нормализация результатов | владеет orchestration |
| Permission Policy | policy layer | проверка уровня разрешения, scope, approval и запрет self-escalation | вызывается через Agent Core |
| DeepSeek provider | model provider | Responses API, streaming, reasoning, tool rounds и image input | не владеет UI state |
| Local Lite Runtime | local execution layer | ограниченные локальные операции и readiness/health probe | не является UI API |
| Workspace Manager | workspace layer | источники workspace, canonical paths, identity, fingerprint и checkpoints | не принимает решения о permission |
| ToolRouter | tool layer | allowlisted typed tools, input/output validation, limits и error normalization | не выполняет произвольный shell |
| GitHub Connector | Git provider | repository read, branch, commit, push и Pull Request | только через Core и permission gate |
| GitHub Actions Connector | Actions provider | dispatch, run/job/step state, logs и artifacts | только через Core и permission gate |
| Session Journal | persistence layer | durable SessionRecord в app-private storage | UI не читает журнал напрямую |
| Artifact Manager | artifact layer | provenance, fingerprint, checksum и локальное размещение артефактов | публикует результат через AgentBridge |

Владелец capability отвечает за его контракт, нормализацию ошибок, cancellation, timeout, redaction и связь с sessionId. Один capability не может молча передавать владение другому слою.

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
| type | SESSION, PLAN, REASONING, OUTPUT, TOOL, DIFF, APPROVAL, BUILD, ARTIFACT, ERROR или INFO |
| timestamp | время формирования |
| workspace | identity, если событие относится к workspace |
| payload | redacted payload без секретов |

Live stream может содержать дополнительные диагностические поля, но durable запись сохраняет только нормализованные и redacted данные.

### 3.5 SessionRecord

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

## 4. Permission model

Уровни разрешений:

| Уровень | Разрешённая область |
| --- | --- |
| READ_ONLY | чтение, анализ, планирование, diff |
| PLAN | формирование плана без write-исполнения |
| WORKSPACE_WRITE | изменение локального workspace |
| GIT_WRITE | branch, commit, push, workflow dispatch, PR |
| REMOTE_ACTION | merge, release и финальные внешние операции |

Permission level является атрибутом сессии и не может быть повышен текстом модели. Операции с внешним эффектом дополнительно требуют явного пользовательского approval, target binding и актуального WorkspaceIdentity.

### 4.1 Матрица операция × уровень

| Операция | READ_ONLY | PLAN | WORKSPACE_WRITE | GIT_WRITE | REMOTE_ACTION |
| --- | --- | --- | --- | --- | --- |
| чтение, анализ, поиск, планирование, diff | да | да | да | да | да |
| локальная запись workspace | нет | нет | approval | approval | approval |
| branch / commit / push | нет | нет | нет | approval | approval |
| workflow dispatch | нет | нет | нет | approval | approval |
| создание Pull Request | нет | нет | нет | approval | approval |
| merge / release / финальное внешнее действие | нет | нет | нет | нет | approval |

Permission проверяется непосредственно перед действием. Разрешение, указанное в ToolCall, не заменяет разрешение текущей сессии.

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
- обязательная установка Termux;
- обязательный отдельный DSH APK;
- второй пользовательский APK или параллельная графическая оболочка;
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

### 8.1 Правило одного факта

| Факт | Единственный источник |
| --- | --- |
| граница продукта, слои, контракты, permission matrix, out-of-scope и инварианты | этот документ |
| порядок этапов, состояние этапов, карточки реализации и exit criteria | IMPLEMENTATION_ROADMAP_RU.md |
| deferred, неподтверждённые идеи и проекции out-of-scope | IDEAS_EXTENSIONS_ANALYSIS_RU.md |
| навигация и краткая сводка | README.md |

Другие документы используют ссылку на источник вместо копирования архитектурного факта. При расхождении применяется этот принцип владения источником.
