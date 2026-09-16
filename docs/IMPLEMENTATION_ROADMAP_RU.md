# Дорожная карта реализации Deep Agent

> Единственный источник порядка этапов, capabilityStatus, карточек реализации и критериев выхода.

Архитектурные контракты и permission matrix находятся в [ANDROID_AGENT_ARCHITECTURE_RU.md](./ANDROID_AGENT_ARCHITECTURE_RU.md). Этот документ не переопределяет их и не дублирует их полные определения.

## 1. Правила статусов и этапов

Допустимые значения capabilityStatus:

- available — capability прошла свой exit criterion и явно отмечена как доступная;
- planned — capability утверждена для реализации, но exit criterion не закрыт;
- deferred — направление отложено и хранится в ideas-файле;
- out-of-scope — capability исключена архитектурой.

Существующий код не переводит capability в available автоматически. Для перехода требуется выполнение exit criterion, статическая проверка соответствующего результата и явная запись изменения в этом файле.

Порядок этапов:

```text
P0-0 → P0-A → P0-B → P0-C → P1-A → P1-B → P1-C → P2-A → P2-B
```

Один этап не получает второй самостоятельный статус. Если часть широкого этапа закрывается раньше, это фиксируется в его карточке и не создаёт новый Markdown-документ или новый статусный источник.

## 2. Сводка этапов

| Этап | capabilityStatus | Назначение |
| --- | --- | --- |
| P0-0 | planned | очистка Deep Agent и границ репозитория |
| P0-A | planned | workspace и read-only ToolRouter |
| P0-B | planned | durable session journal и recovery |
| P0-C | planned | MVP setup и понятный Agent Console |
| P1-A | planned | diff, controlled write, Git и ручной PR |
| P1-B | planned | Actions observability и проверенные APK/AAB |
| P1-C | planned | Android 16 UI и regression contract |
| P2-A | planned | RuntimeSupervisor и headless DSH |
| P2-B | planned | PTY и интерактивные команды |

## 3. Карточки реализации

### P0-0 — Очистка Deep Agent и границ репозитория

- **Статус:** capabilityStatus: planned
- **Владелец:** repository boundary / Agent Core integration
- **Входной контракт:** утверждённая архитектура, исходный repository snapshot и перечень файлов; контракты не переопределяются в этапе.
- **Выходной контракт:** в репозитории остаются только собственный Deep Agent, один APK-контур, согласованные source-пакеты и четыре канонических Markdown-файла; Harness/mobile-adapter и серверные plugin mutations не становятся зависимостями.
- **Permission gate:** WORKSPACE_WRITE для локальных изменений; GIT_WRITE для branch/commit/push; внешние финальные действия не входят в этап.
- **Session ID:** обязателен для инвентаризации, каждой записи и commit correlation.
- **Redacted audit trail:** SESSION, TOOL, DIFF, APPROVAL и OUTPUT events с путями, SHA и session ID; secrets, tokens и cookies redacted.
- **Cancellation / timeout:** отмена до commit оставляет исходный snapshot без применения; статические операции имеют bounded timeout и не запускают build/test.
- **Recovery rule:** после interruption повторно считать repository fingerprint; неизвестную запись не повторять без нового diff и approval.
- **Exit criterion:** запрещённые Harness/mobile-adapter runtime-зависимости и лишние документационные файлы удалены из целевого scope; четыре Markdown-файла проходят ссылочную и структурную проверку; code/build/test gate остаётся отдельным.

### P0-A — Workspace и read-only ToolRouter

- **Статус:** capabilityStatus: planned
- **Владелец:** Workspace Manager и ToolRouter
- **Входной контракт:** WorkspaceIdentity, canonical path boundary, allowlisted tool names и лимиты из архитектуры.
- **Выходной контракт:** read-only list_files, read_file, search_code, git_status и git_diff с нормализованными ToolCall/ToolResult, scope, timeout, truncation и error codes.
- **Permission gate:** READ_ONLY; tool не может изменить workspace, выбрать другой target или повысить permission.
- **Session ID:** обязателен в каждом вызове, результате и UI event.
- **Redacted audit trail:** invocation ID, tool name, workspace ID, input summary, output size, truncation, fingerprint и redacted error.
- **Cancellation / timeout:** отдельный deadline для каждого tool; Git timeout и cancellation возвращают нормализованный результат; generic shell не добавляется.
- **Recovery rule:** running без подтверждённого результата переводится в UNKNOWN; автоматический replay запрещён, выполняется re-check.
- **Exit criterion:** все пять tools работают через единый router contract; path escape, symlink escape, sensitive files, output overflow и отсутствие git обрабатываются fail-closed; workspace не изменяется.

### P0-B — Durable session journal и recovery

- **Статус:** capabilityStatus: planned
- **Владелец:** Agent Core и Session Journal
- **Входной контракт:** AgentBridge v1, AgentEvent, SessionRecord, session/invocation IDs и app-private storage.
- **Выходной контракт:** versioned durable journal, восстановление состояния после rotation/background/process death и пользовательское summary без секретов.
- **Permission gate:** READ_ONLY для journal/recovery; восстановление не даёт права на повторную write или external action.
- **Session ID:** является первичным ключом durable записи и не меняется при восстановлении.
- **Redacted audit trail:** journal сохраняет состояние вызовов, event sequence, decisions и provider references без tokens, cookies и Authorization headers.
- **Cancellation / timeout:** journal write атомарен; recovery имеет bounded timeout и сообщает неполное состояние вместо зависания.
- **Recovery rule:** завершённые side effects не повторяются; незавершённые операции получают UNKNOWN и требуют re-check.
- **Результат текущей реализации:** кодовая часть P0-B внесена: versioned journal v2 с чтением v1, атомарная запись session/latest, сохранение events/invocations/decisions, redaction и recovery без автоматического replay; статическая согласованность изменённых файлов проверена.
- **Acceptance gate:** capabilityStatus остаётся `planned` до поведенческой Android-проверки exit criterion; build, tests и APK в этой итерации не запускались.
- **Exit criterion:** сессия восстанавливается без повторения завершённых tool/build/write операций, сохраняет correlation IDs и объясняет неизвестное состояние.

### P0-C — MVP setup и понятный Agent Console

- **Статус:** capabilityStatus: planned
- **Владелец:** Compose UI и AgentBridge integration
- **Входной контракт:** AgentBridge state/event stream, provider configuration reference, target selection и permission model.
- **Выходной контракт:** один понятный экран с task input, target, permission, provider state, attachment preview, events, error, cancel и recovery affordances.
- **Permission gate:** READ_ONLY по умолчанию; PLAN разрешает только формирование плана; write controls отображают gate и не выполняют действие без отдельного approval.
- **Session ID:** отображается через события и связывает task, provider calls, approvals и final result.
- **Redacted audit trail:** UI events содержат event type, state transitions, provider name и correlation IDs; секретные значения маскируются.
- **Cancellation / timeout:** cancel доступен из UI; network/provider timeout переводится в понятное состояние без скрытого retry.
- **Recovery rule:** после rotation/background UI подписывается на AgentBridge, а не читает journal; при UNKNOWN предлагает re-check.
- **Exit criterion:** новый пользователь из одного экрана понимает, что настроено, что отсутствует, какой permission требуется и почему операция остановилась.

### P1-A — Diff, controlled write, Git и ручной PR

- **Статус:** capabilityStatus: planned
- **Владелец:** Patch Engine, Workspace Manager и GitHub Connector
- **Входной контракт:** read-only workspace snapshot, ToolCall для patch, WorkspaceIdentity, base fingerprint, explicit approval и target repository/ref.
- **Выходной контракт:** preview/diff, checkpoint и controlled local apply; branch/commit/push и ручной Pull Request с проверяемой provenance.
- **Permission gate:** WORKSPACE_WRITE для локальной записи; GIT_WRITE для branch/commit/push/PR; REMOTE_ACTION для merge/release не входит в этап.
- **Session ID:** обязателен в preview, approval, checkpoint, commit, PR и каждом связанном event.
- **Redacted audit trail:** base file SHA, plan/apply fingerprints, changed paths, approval actor, checkpoint reference, commit SHA и PR number; secrets redacted.
- **Cancellation / timeout:** preview можно отменить без записи; apply и Git operations имеют timeout, cancellation и fail-closed conflict handling.
- **Recovery rule:** fingerprint mismatch или unknown apply останавливает операцию; checkpoint используется для re-check/rollback, автоматический повтор запрещён.
- **Exit criterion:** каждая локальная мутация имеет preview, base SHA, актуальный fingerprint, approval, checkpoint и recovery path; каждый commit/PR связан с session ID, repository, ref и проверяемым SHA.

### P1-B — Actions observability и проверенные APK/AAB

- **Статус:** capabilityStatus: planned
- **Владелец:** GitHub Actions Connector и Artifact Manager
- **Входной контракт:** разрешённый workflow dispatch, repository/ref/workflow, session ID и commit provenance.
- **Выходной контракт:** наблюдаемый путь dispatch → run → job → step/log → artifact → verified result.
- **Permission gate:** GIT_WRITE для dispatch и retry; WORKSPACE_WRITE для сохранения скачанного artifact; merge/release остаются за REMOTE_ACTION.
- **Session ID:** связывает dispatch, workflow run, jobs, logs, artifact и UI cards.
- **Redacted audit trail:** run ID, job/step states, durations, redacted logs, artifact name/size/content type/checksum и source SHA.
- **Cancellation / timeout:** polling имеет backoff, deadline и cancel; скачивание ограничено размером, типом и timeout; failed-job retry не выполняется автоматически.
- **Recovery rule:** неизвестный run/job/artifact получает UNKNOWN; повтор dispatch запрещён до re-check исходного run и idempotency key.
- **Exit criterion:** приложение показывает status и failed step, позволяет получить redacted logs, проверяет artifact type/size/checksum/commit SHA и сохраняет подтверждённый APK/AAB.

### P1-C — Android 16 UI и regression contract

- **Статус:** capabilityStatus: planned
- **Владелец:** Compose UI и Android validation layer
- **Входной контракт:** AgentBridge v1, UI state model, event types, permission states и согласованные Android 16 scenarios.
- **Выходной контракт:** стабильный UI contract с test IDs, accessibility semantics, rotation/background/insets/keyboard coverage и screenshot fixtures для критических состояний.
- **Permission gate:** WORKSPACE_WRITE только для изменения UI source; runtime actions в сценариях используют permission текущей сессии.
- **Session ID:** используется в fixtures и event assertions, но не является частью визуального текста.
- **Redacted audit trail:** scenario ID, screen state, event sequence, failure location и screenshot reference без provider secrets.
- **Cancellation / timeout:** каждый UI scenario имеет bounded timeout; зависший provider не блокирует UI test lifecycle.
- **Recovery rule:** потеря Activity не создаёт новую сессию и не повторяет side effect; тест повторно подключается к AgentBridge state.
- **Exit criterion:** основные user flows воспроизводимы на Android 16, имеют стабильные test IDs и не теряют session state при rotation/background/insets transitions.

### P2-A — RuntimeSupervisor и headless DSH

- **Статус:** capabilityStatus: planned
- **Владелец:** RuntimeSupervisor и Local Lite Runtime integration
- **Входной контракт:** отдельно согласованный ARM64 runtime bundle, manifest, checksum, ABI/version range и readiness probe.
- **Выходной контракт:** внутренний lifecycle EMPTY → INSTALLING → STARTING → READY → STOPPING → EMPTY с FAILED/ROLLBACK веткой, loopback adapter за AgentBridge и controlled shutdown.
- **Permission gate:** WORKSPACE_WRITE для app-private install/update; provider runtime не получает право на GitHub или merge/release.
- **Session ID:** связывает runtime start, health probe, invocation, crash и rollback.
- **Redacted audit trail:** bundle version, ABI, checksum, lifecycle states, heartbeat, exit code и error class; environment secrets redacted.
- **Cancellation / timeout:** install/start/stop/readiness/heartbeat имеют bounded timeout; cancellation удаляет только неполный temporary state.
- **Recovery rule:** failed or incompatible bundle не становится active; supervisor выполняет rollback к последней подтверждённой версии или возвращает EMPTY.
- **Exit criterion:** runtime запускается внутри одного APK, readiness подтверждается, отказ не повреждает workspace и UI продолжает работать через AgentBridge.

### P2-B — PTY и интерактивные команды

- **Статус:** capabilityStatus: planned
- **Владелец:** RuntimeSupervisor и interactive execution provider
- **Входной контракт:** подтверждённый P2-A runtime, PTY backend для ABI, workspace scope и explicit user action.
- **Выходной контракт:** cancellable PTY session, ограниченное дерево процессов, scoped environment и нормализованные output/exit events.
- **Permission gate:** WORKSPACE_WRITE для команд, способных изменить workspace; READ_ONLY не запускает mutating process; REMOTE_ACTION не подразумевается.
- **Session ID:** связывает PTY process group, output chunks, cancel, exit и recovery state.
- **Redacted audit trail:** command metadata, scoped cwd, process ID, exit state, duration и redacted output; credentials не журналируются.
- **Cancellation / timeout:** cancel завершает process tree; idle/maximum runtime timeout обязателен; UI не блокируется.
- **Recovery rule:** потерянный процесс получает UNKNOWN, не восстанавливается автоматически и требует явного re-check или controlled termination.
- **Exit criterion:** интерактивная команда не выходит из workspace/permission scope, не блокирует UI, корректно отменяется и оставляет понятное состояние после process death.
