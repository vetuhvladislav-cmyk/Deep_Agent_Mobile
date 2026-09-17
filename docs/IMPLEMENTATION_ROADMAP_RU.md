# Дорожная карта реализации Deep Agent

> Единственный источник порядка этапов, capabilityStatus, карточек реализации и критериев выхода.

Архитектурные контракты и permission matrix находятся в [ANDROID_AGENT_ARCHITECTURE_RU.md](./ANDROID_AGENT_ARCHITECTURE_RU.md). Этот документ не переопределяет их и не дублирует их полные определения.

## 0. Зафиксированный статический hardening pass

На ветке `codex/p1-a-controlled-write-git-pr` выполнен сквозной статический проход по текущему Android diff. `main` не изменялся.

В коде закреплены следующие границы и исправления:

- UI использует единый `AgentUiContract`; workspace-entry IDs детерминированы, а lifecycle `AgentCore` закрывается владельцем приложения.
- Проверки permission используют capability semantics, а не порядок enum; локальная запись и GitHub-доступ не смешиваются.
- `WorkspacePathPolicy` является общей fail-closed границей для ToolRouter, Patch Engine, Git и interactive adapter: абсолютные пути, `..`, symlink-компоненты и чувствительные файлы блокируются.
- Commit ограничен явно указанными файлами; полный diff исключает известные секретные паттерны; process output bounded и дочитывается после достижения лимита.
- Отмена распространяется на DeepSeek, GitHub Actions и runtime; неподтверждённая остановка остаётся `FAILED/UNKNOWN`, а не маскируется под успешное завершение.
- Actions связываются с `sessionId`, ref и ожидаемым commit SHA; artifact дополнительно проверяет run/source provenance, checksum и тип.
- Session/workspace/preset persistence использует временный файл, flush/sync и atomic replacement.
- AgentEvent использует schemaVersion=1 и bounded redacted payload; ProviderRegistry разрешает только явно зарегистрированные provider IDs.
- Bounded Planner формирует детерминированный линейный план, а Completion Evaluator принимает завершение только по подтверждённому response evidence; полноценный DAG отложен.
- DeepSeek endpoint ограничен HTTPS host policy (api.deepseek.com, default/443), а redirects отключены.
- Git/PR/Actions mutations несут operation ID; Git и Actions сериализуют повторный запуск, возвращают сохранённый результат для той же session/operation и после UNKNOWN блокируют новый side effect до re-check; последний Git mutation result сохраняется в versioned SessionStore.

Последний подтверждённый baseline прошёл unit-тесты, компиляцию instrumentation APK и debug APK в [GitHub Actions run #27](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35269039901) на commit `aa6b7a3`. После него bounded Planner/Evaluator, DeepSeek host-policy и operation-bound Git/PR/Actions recovery изменены в коммитах `b056589`, `82b29ba`, `0e751ed`, `2ab6f1f`, `d9744b7`, `eb15608`, `5b93dcd`, `a184072`, `3f0dcf5`, `8eb357b` и `17f926e`; по текущему ограничению пользователя новая компиляция и тесты не запускались. Android device execution, реальный runtime и device lifecycle acceptance ещё не запускались, поэтому соответствующие exit criteria остаются открытыми.

## 1. Правила статусов и этапов

Допустимые значения capabilityStatus:

- available — capability прошла свой exit criterion и явно отмечена как доступная;
- planned — capability утверждена для реализации, но exit criterion не закрыт;
- deferred — направление отложено и хранится в ideas-файле;
- out-of-scope — capability исключена архитектурой.

Существующий код не переводит capability в available автоматически. Для перехода требуется выполнение exit criterion, статическая проверка соответствующего результата и явная запись изменения в этом файле.

Порядок этапов:

```text
P0-0 → P0-A → P0-B → P0-C → P1-A → P1-B → P1-C → P2-A → P2-B → D1 → D2 → D3
```

Один этап не получает второй самостоятельный статус. Если часть широкого этапа закрывается раньше, это фиксируется в его карточке и не создаёт новый Markdown-документ или новый статусный источник.

## 2. Сводка этапов

| Этап | capabilityStatus | Назначение |
| --- | --- | --- |
| P0-0 | available | очистка Deep Agent и границ репозитория |
| P0-A | planned | workspace и read-only ToolRouter |
| P0-B | planned | durable session journal и recovery |
| P0-C | planned | MVP setup и понятный Agent Console |
| P1-A | planned | diff, controlled write, Git и ручной PR |
| P1-B | planned | Actions observability и проверенные APK/AAB |
| P1-C | planned | Android 16 UI и regression contract |
| P2-A | planned | RuntimeSupervisor и headless DSH |
| P2-B | planned | PTY и интерактивные команды |
| D1 | planned | изображения и визуальный анализ |
| D2 | planned | расширенная рабочая область |
| D3 | planned | токены, журнал и PR |

## 3. Карточки реализации

### P0-0 — Очистка Deep Agent и границ репозитория

- **Статус:** capabilityStatus: available
- **Владелец:** repository boundary / Agent Core integration
- **Входной контракт:** утверждённая архитектура, исходный repository snapshot и перечень файлов; контракты не переопределяются в этапе.
- **Выходной контракт:** в репозитории остаются только собственный Deep Agent, один APK-контур, согласованные source-пакеты и четыре канонических Markdown-файла; Harness/mobile-adapter и серверные plugin mutations не становятся зависимостями.
- **Permission gate:** LOCAL_WRITE для локальных изменений; GITHUB_WRITE для branch/commit/push; внешние финальные действия не входят в этап.
- **Session ID:** обязателен для инвентаризации, каждой записи и commit correlation.
- **Redacted audit trail:** SESSION, TOOL, DIFF, APPROVAL и OUTPUT events с путями, SHA и session ID; secrets, tokens и cookies redacted.
- **Cancellation / timeout:** отмена до commit оставляет исходный snapshot без применения; статические операции имеют bounded timeout и не запускают build/test.
- **Recovery rule:** после interruption повторно считать repository fingerprint; неизвестную запись не повторять без нового diff и approval.
- **Exit criterion:** запрещённые Harness/mobile-adapter runtime-зависимости и лишние документационные файлы удалены из целевого scope; четыре Markdown-файла проходят ссылочную и структурную проверку; code/build/test gate остаётся отдельным.
- **Историческая приёмка baseline:** на commit `35bfd006` в целевом scope остались ровно четыре канонических Markdown-файла; проверены 9 относительных ссылок, отсутствующих целей нет; запрещённые `harness`/`mobile-adapter` runtime-пути отсутствуют. `testDebugUnitTest` и `assembleDebug` прошли в GitHub Actions run #6; APK опубликован в Release `v0.1.1`. Это не результат текущей ветки.

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
- **Результат текущей реализации:** пять allowlisted read-only entry points и fail-closed guards присутствуют в едином ToolRouter; добавлены JVM-тесты на workspace path policy, fingerprint, пять read-only операций, no-git, unknown arguments и строгие bounded schemas. `testDebugUnitTest` и `assembleDebug` прошли в [run #27](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35269039901).
- **Acceptance gate:** capabilityStatus остаётся `planned` до Android/runtime-проверки пяти tools на реально импортированном workspace, включая path/symlink escape, sensitive files, overflow, no-git и неизменность workspace; instrumentation и SAF import fixture ещё не запускались.
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
- **Результат текущей реализации:** кодовая часть P0-B внесена: versioned bounded journal v6 с чтением версий 1–6, per-session JSON snapshots, атомарная запись session/latest, сохранение events/invocations/decisions и последнего Git mutation result, redaction и recovery без автоматического replay; AgentEvent хранит schema version и bounded redacted payload; после process death завершённый Git result можно вернуть по session/operation ID, а незавершённый Git/Actions side effect остаётся UNKNOWN и блокирует replay; статическая согласованность изменённых файлов проверена.
- **Validation:** добавлены JVM-тесты на versioned atomic journal snapshot, redaction, latest pointer и отбрасывание неподдерживаемых recovery inputs; [run #27](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35269039901) прошёл. Android-проверка background/process death/rotation и отсутствие replay на device ещё не выполнялись.
- **Acceptance gate:** capabilityStatus остаётся `planned` до поведенческой Android-проверки восстановления после background/process death/rotation и подтверждения отсутствия replay; JVM coverage не заменяет instrumentation gate.
- **Exit criterion:** сессия восстанавливается без повторения завершённых tool/build/write операций, сохраняет correlation IDs и объясняет неизвестное состояние.

### P0-C — MVP setup и понятный Agent Console

- **Статус:** capabilityStatus: planned
- **Владелец:** Compose UI и AgentBridge integration
- **Входной контракт:** AgentBridge state/event stream, provider configuration reference, target selection и permission model.
- **Выходной контракт:** один понятный экран с task input, target, permission, provider state, attachment preview, events, error, cancel и recovery affordances.
- **Permission gate:** READ_ONLY по умолчанию; планирование не выполняет write; write controls отображают gate и не выполняют действие без отдельного approval.
- **Session ID:** отображается через события и связывает task, provider calls, approvals и final result.
- **Redacted audit trail:** UI events содержат event type, state transitions, provider name и correlation IDs; секретные значения маскируются.
- **Cancellation / timeout:** cancel доступен из UI; network/provider timeout переводится в понятное состояние без скрытого retry.
- **Recovery rule:** после rotation/background UI подписывается на AgentBridge, а не читает journal; при UNKNOWN предлагает re-check.
- **Результат текущей реализации:** Agent Console подключён только к AgentBridge, workspace import и patch approval выведены из внутренних типов, добавлены provider/session/recovery summary, сохранение несекретной формы, восстановление image URI и единый scrollable mobile layout; статическая проверка пройдена.
- **Validation:** unit-тесты, компиляция `assembleDebugAndroidTest` и debug APK проверены в [run #27](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35269039901); instrumentation smoke test добавлен, но Android UI/lifecycle запуск на device или emulator ещё не выполнялся.
- **Acceptance gate:** capabilityStatus остаётся `planned` до поведенческой Android-проверки читаемости состояния, recovery affordances, rotation/background, keyboard/insets и process death; compilation instrumentation APK не заменяет device execution.
- **Exit criterion:** новый пользователь из одного экрана понимает, что настроено, что отсутствует, какой permission требуется и почему операция остановилась.

### P1-A — Diff, controlled write, Git и ручной PR

- **Статус:** capabilityStatus: planned
- **Владелец:** Patch Engine, Workspace Manager и GitHub Connector
- **Входной контракт:** read-only workspace snapshot, ToolCall для patch, WorkspaceIdentity, base fingerprint, explicit approval и target repository/ref.
- **Выходной контракт:** preview/diff, checkpoint и controlled local apply; branch/commit/push и ручной Pull Request с проверяемой provenance.
- **Результат текущей реализации:** добавлены фиксированные Git-операции `status`, `checkout -b`, `add + commit` и `push` через ToolRouter; write-операции проверяют workspace fingerprint до/после и HEAD SHA, timeout/неподтверждённый результат переводятся в `UNKNOWN`; добавлен GitHub PR connector с redacted-ответом и ручным approval через AgentBridge/UI; для `apply_patch` добавлены атомарный checkpoint manifest, persisted recovery state и ручной rollback с повторной проверкой post-write fingerprint. Для model-generated local write введён operation-bound `ApprovalToken`: opaque token связывает operation, session, workspace fingerprint, old/new SHA, digest аргументов и expiry. Git/PR requests несут operation ID, AgentCore сериализует write-вызовы, возвращает cached result для повторной пары session/operation и блокирует новый Git/PR side effect после `UNKNOWN`; последний mutation result сохраняется в SessionStore. Unit-тесты baseline на binding и TTL, unified patch/checkpoint/rollback и ZIP artifact verifier прошли в run #27; последующие operation-bound изменения проверены только статически.
- **Ограничение текущей реализации:** P1-A не переводится в `available` без поведенческой проверки branch/commit/push/PR, отказов Git, timeout/re-check, permission gates, checkpoint/rollback и проверки отсутствия секретов в событиях/journal.
- **Permission gate:** LOCAL_WRITE для локальной записи; GITHUB_WRITE для branch/commit/push; PR_CREATE для Pull Request; MERGE_RELEASE для merge/release не входит в этап.
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
- **Permission gate:** GITHUB_WRITE для dispatch и retry; LOCAL_WRITE для сохранения скачанного artifact; merge/release остаются за MERGE_RELEASE.
- **Session ID:** связывает dispatch, workflow run, jobs, logs, artifact и UI cards.
- **Redacted audit trail:** run ID, job/step states, durations, redacted logs, artifact name/size/content type/checksum и source SHA.
- **Cancellation / timeout:** polling имеет backoff, deadline и cancel; скачивание ограничено размером, типом и timeout; failed-job retry не выполняется автоматически.
- **Recovery rule:** неизвестный run/job/artifact получает UNKNOWN; повтор dispatch запрещён до re-check исходного run и idempotency key.
- **Результат текущей реализации:** добавлен Actions connector с dispatch/run discovery/polling, jobs/steps, failed-step log retrieval и redacted state; workflow публикует APK вместе с SHA-256 sidecar и provenance source SHA; AgentBridge/Session Journal сохраняют correlation и recovery state; Artifact Manager скачивает ZIP, проверяет APK/AAB, checksum, source SHA и атомарно сохраняет его только после LOCAL_WRITE approval. Correlation сделана обязательной: `agent_session_id`, `operation_id` и expected commit SHA входят в dispatch/run discovery, а state и audit сохраняют operation ID; contract-тесты прошли в run #27.
- **Ограничение текущей реализации:** capabilityStatus остаётся `planned` до runtime-проверки именно app-side пути dispatch → run → job → step/log → artifact на реальном repository, проверки квоты/redirect/timeout/UNKNOWN и подтверждения сохранённого APK/AAB; workflow build/release уже проверен отдельно, но это не заменяет Android connector acceptance.
- **Exit criterion:** приложение показывает status и failed step, позволяет получить redacted logs, проверяет artifact type/size/checksum/commit SHA и сохраняет подтверждённый APK/AAB.

### P1-C — Android 16 UI и regression contract

- **Статус:** capabilityStatus: planned
- **Владелец:** Compose UI и Android validation layer
- **Входной контракт:** AgentBridge v1, UI state model, event types, permission states и согласованные Android 16 scenarios.
- **Выходной контракт:** стабильный UI contract с test IDs, accessibility semantics, rotation/background/insets/keyboard coverage и screenshot fixtures для критических состояний.
- **Permission gate:** LOCAL_WRITE только для изменения UI source; runtime actions в сценариях используют permission текущей сессии.
- **Session ID:** используется в fixtures и event assertions, но не является частью визуального текста.
- **Redacted audit trail:** scenario ID, screen state, event sequence, failure location и screenshot reference без provider secrets.
- **Cancellation / timeout:** каждый UI scenario имеет bounded timeout; зависший provider не блокирует UI test lifecycle.
- **Recovery rule:** потеря Activity не создаёт новую сессию и не повторяет side effect; тест повторно подключается к AgentBridge state.
- **Результат текущей реализации:** добавлен канонический UI contract с независимыми стабильными test IDs и accessibility semantics для корневого экрана, task/workspace controls, patch approval/rollback, Actions artifact, Git, session lifecycle и event cards; существующие `rememberSaveable`, app-private journal и AgentBridge state сохраняют recovery-safe поведение при Activity recreation, keyboard и system insets.
- **Ограничение текущей реализации:** capabilityStatus остаётся `planned` до Android 16 instrumentation-проверки основных сценариев, rotation/background/process death, keyboard/insets, accessibility tree и screenshot fixtures; debug APK собран в CI, но UI lifecycle tests ещё не запускались.
- **Exit criterion:** основные user flows воспроизводимы на Android 16, имеют стабильные test IDs и не теряют session state при rotation/background/insets transitions.

### P2-A — RuntimeSupervisor и headless DSH

- **Статус:** capabilityStatus: planned
- **Владелец:** RuntimeSupervisor и Local Lite Runtime integration
- **Входной контракт:** отдельно согласованный ARM64 runtime bundle, manifest, checksum, ABI/version range и readiness probe.
- **Выходной контракт:** внутренний lifecycle EMPTY → INSTALLING → STARTING → READY → STOPPING → EMPTY с FAILED/ROLLBACK веткой, loopback adapter за AgentBridge и controlled shutdown.
- **Permission gate:** LOCAL_WRITE для app-private install/update; provider runtime не получает право на GitHub или merge/release.
- **Session ID:** связывает runtime start, health probe, invocation, crash и rollback.
- **Redacted audit trail:** bundle version, ABI, checksum, lifecycle states, heartbeat, exit code и error class; environment secrets redacted.
- **Cancellation / timeout:** install/start/stop/readiness/heartbeat имеют bounded timeout; cancellation удаляет только неполный temporary state.
- **Recovery rule:** failed or incompatible bundle не становится active; supervisor выполняет rollback к последней подтверждённой версии или возвращает EMPTY.
- **Результат текущей реализации:** добавлен внутренний RuntimeSupervisor с mutex-serial lifecycle EMPTY → INSTALLING → STARTING → READY → STOPPING → EMPTY, ветками FAILED/ROLLBACK, bounded timeout/cancellation, manifest/checksum/ABI state и loopback provider за AgentBridge; Local Lite probe больше не использует shell.
- **Ограничение текущей реализации:** capabilityStatus остаётся `planned`: loopback provider не является реальным ARM64 DSH bundle; DP-02 остаётся open, поэтому installation/readiness/crash/restart нужно проверить на согласованном runtime manifest и Android 16 device; build и runtime-тесты в этой сессии не запускались.
- **Exit criterion:** runtime запускается внутри одного APK, readiness подтверждается, отказ не повреждает workspace и UI продолжает работать через AgentBridge.

### P2-B — PTY и интерактивные команды

- **Статус:** capabilityStatus: planned
- **Владелец:** RuntimeSupervisor и interactive execution provider
- **Входной контракт:** подтверждённый P2-A runtime, PTY backend для ABI, workspace scope и explicit user action.
- **Выходной контракт:** cancellable PTY session, ограниченное дерево процессов, scoped environment и нормализованные output/exit events.
- **Permission gate:** LOCAL_WRITE для команд, способных изменить workspace; READ_ONLY не запускает mutating process; MERGE_RELEASE не подразумевается.
- **Session ID:** связывает PTY process group, output chunks, cancel, exit и recovery state.
- **Redacted audit trail:** command metadata, scoped cwd, process ID, exit state, duration и redacted output; credentials не журналируются.
- **Cancellation / timeout:** cancel завершает process tree; idle/maximum runtime timeout обязателен; UI не блокируется.
- **Recovery rule:** потерянный процесс получает UNKNOWN, не восстанавливается автоматически и требует явного re-check или controlled termination.
- **Результат текущей реализации:** добавлен AgentBridge/Core interactive contract и bounded direct-process adapter с allowlist только для git status/diff/log, canonical cwd, очищенным scoped environment, input/output limits, timeout, cancel, redacted output и persisted UNKNOWN recovery state; UI показывает нормализованные output/exit/recovery события.
- **Ограничение текущей реализации:** capabilityStatus остаётся `planned`: текущий backend использует pipes, а не полноценный PTY; расширение allowlist и реальный PTY ABI зависят от подтверждённого P2-A runtime/DP-02; process-tree, Android 16 и process-death сценарии требуют runtime-проверки; build и тесты в этой сессии не запускались.
- **Exit criterion:** интерактивная команда не выходит из workspace/permission scope, не блокирует UI, корректно отменяется и оставляет понятное состояние после process death.

## 4. Расширения после базовой линии P0–P2

D1–D3 являются утверждёнными post-core этапами. Они не изменяют порядок и критерии P0–P2, не создают второй APK, вторую пользовательскую оболочку или новый обязательный внешний runtime. Каждый этап получает статус `planned` до выполнения собственного exit criterion.

### D1 — Изображения и визуальный анализ

- **Статус:** capabilityStatus: planned
- **Владелец:** Image Pipeline, DeepSeek provider, AgentBridge и Artifact Manager
- **Зависимости:** P0-C для attachment flow и UI-состояния; P0-B для durable redacted state; действующий AgentBridge v1.
- **Входной контракт:** attachment из AgentBridge, app-private URI, MIME, размер, pixel bounds, source digest, sessionId и явное состояние передачи внешнему provider.
- **Выходной контракт:** нормализованный `ImageAttachment`, redacted visual/OCR result, deterministic screenshot diff и provenance исходных изображений.
- **Порядок внедрения:**
  1. app-private import/copy, MIME validation, byte/pixel limits и deterministic digest;
  2. EXIF orientation, resize/downscale, optional crop и bounded temporary cache;
  3. preview, multiple-image/region model и явное уведомление о передаче изображения DeepSeek;
  4. OCR, stack-trace/screen-text extraction и визуальный анализ через существующий DeepSeek provider;
  5. локальное deterministic-сравнение UI-скриншотов с подсветкой отличий;
  6. экспорт SVG/Compose/HTML как обычных текстовых артефактов без отдельного image runtime.
- **Не входит в базовый exit criterion:** отдельный raster-generation provider; он остаётся отдельным deferred-решением.
- **Permission gate:** READ_ONLY для импорта, анализа и сравнения; LOCAL_WRITE только для явно подтверждённого сохранения результата в workspace; внешний provider требует явного user-visible disclosure.
- **Session ID:** связывает attachment, provider call, OCR/diff result, cache lifecycle и artifact.
- **Redacted audit trail:** attachment ID, MIME, размеры, digest, provider name, transfer decision, result state и artifact reference; raw image bytes и provider secrets не журналируются.
- **Cancellation / timeout:** отдельный deadline на нормализацию, OCR, visual analysis и diff; скрытый retry не выполняется.
- **Recovery rule:** незавершённая передача получает UNKNOWN и требует re-check; временный cache удаляется по TTL/size policy; восстановление Activity не повторяет provider call.
- **Exit criterion:** изображение импортируется и отображается в preview, нормализуется в заданных пределах, передача пользователю понятна, результат восстанавливается через AgentBridge, storage ограничен, OCR/diff имеют проверяемую provenance, а секреты отсутствуют в journal и events.

- **Результат текущей реализации:** добавлен внутренний ImageAnalysisPipeline, который копирует изображение в bounded app-private cache, проверяет MIME/file signature, byte/pixel limits и создаёт SHA-256 asset identity; AgentBridge/UI показывают redacted metadata и disclosure перед временной передачей проверенного изображения в существующий DeepSeek provider; raw bytes и data URL не сохраняются в events/journal.
- **Ограничение текущей реализации:** D1 остаётся planned: OCR, multi-image/region model, EXIF/crop, deterministic screenshot diff и проверяемая visual-result provenance требуют отдельной реализации и Android/provider-проверки; raster-generation provider остаётся deferred.

### D2 — Расширенная рабочая область

- **Статус:** capabilityStatus: planned
- **Владелец:** Workspace Manager, Session Journal, AgentBridge и Artifact Manager
- **Зависимости:** P0-A WorkspaceIdentity/fingerprint; P0-B durable recovery; P1-A checkpoint/diff/Git provenance.
- **Входной контракт:** существующий WorkspaceIdentity, canonical path boundary, fingerprint, repository/ref/commitSha и sessionId.
- **Выходной контракт:** повторно открываемый workspace catalog, immutable snapshot history, paged tree view, project rules и единое состояние Task/Workspace/Builds/Artifacts/events.
- **Порядок внедрения:**
  1. app-private Workspace Catalog со стабильным workspaceId, source, ref, commitSha и последним подтверждённым fingerprint;
  2. immutable snapshot/checkpoint records с визуальной и событийной связью с sessionId;
  3. paging/virtualization дерева, output limits и fail-closed path/symlink handling для больших workspace;
  4. чтение `AGENT_RULES.md` через allowlisted read-only ToolRouter;
  5. применение project rules только как дополнительных ограничений: они не могут изменить глобальную Permission Policy, повысить session permission или заменить user approval;
  6. GitHub snapshot/checkout как специализированный provider за Workspace Manager; новые remote providers добавляются только отдельным решением;
  7. отображение Task, Workspace, Builds, Artifacts и event stream через единый AgentBridge state owner без отдельного TaskTracker runtime.
- **Permission gate:** каталог и чтение metadata — READ_ONLY; импорт, checkout и изменение локального workspace — соответствующий LOCAL_WRITE/GITHUB_WRITE approval.
- **Session ID:** связывает workspace selection, snapshot, checkpoint, task metadata, build и artifact.
- **Redacted audit trail:** workspaceId, source, root summary, repository/ref/commitSha, fingerprints, snapshot IDs и rule decisions; secrets и raw provider responses не сохраняются.
- **Cancellation / timeout:** import, checkout, snapshot и tree paging имеют bounded timeout; отмена незавершённого checkout удаляет только temporary state.
- **Recovery rule:** stale/foreign target или fingerprint mismatch останавливает операцию; восстановление не повторяет checkout, write или Git action автоматически.
- **Exit criterion:** пользователь может повторно открыть ранее импортированный workspace, увидеть подтверждённую provenance, безопасно просмотреть большой tree, применить project rules без permission escalation и восстановить связь результата с session/artifact/build.

- **Результат текущей реализации:** Workspace Manager расширен повторно открываемым app-private catalog: сохраняются стабильные workspaceId и optional repository/ref/commitSha, текущий fingerprint refresh-ится перед публикацией, добавлены bounded tree pages с canonical path/symlink guard, выбор workspace через AgentBridge и чтение корневого AGENT_RULES.md через allowlisted read_file; rules передаются модели только как redacted дополнительные ограничения и не меняют permission policy.
- **Ограничение текущей реализации:** D2 остаётся planned: immutable snapshot history, GitHub snapshot/checkout provider, полноценный lazy paging для очень больших workspace и Android recovery/runtime-проверка требуют отдельного закрытия acceptance gate.


### D3 — Токены, журнал и Pull Request

- **Статус:** capabilityStatus: planned
- **Владелец:** Credential Policy, Session Journal, GitHub Connector и Artifact Manager
- **Зависимости:** AgentBridge v1, P0-B journal/recovery, P1-A controlled write/Git provenance и P1-B Actions observability.
- **Входной контракт:** provider credential reference, sessionId, journal record, repository/ref, base/commit SHA, approval и idempotency key.
- **Выходной контракт:** выбранная token policy, bounded/exportable redacted journal, branch/commit/PR provenance и ручной проверяемый Pull Request flow.
- **Порядок внедрения:**
  1. отдельный decision gate DP-01: базовый вариант — повторный ввод provider token после перезапуска; Android Keystore рассматривается как opt-in; внешний proxy не входит в базовый scope;
  2. retention/size policy для journal и write audit, очистка старых записей и пользовательский export выбранного диапазона с redaction;
  3. связывание branch, commit SHA, repository/ref, sessionId, approval и PR number;
  4. ручное создание draft/обычного PR после отдельного GITHUB_WRITE approval;
  5. re-check перед dispatch, PR и любым повторением; unknown state блокирует автоматический replay;
  6. merge/release остаются отдельным MERGE_RELEASE decision gate.
- **Permission gate:** token configuration не выдаёт permission; branch/commit/push/PR требуют GITHUB_WRITE и явного approval; merge/release не входят в D3 baseline.
- **Session ID:** обязателен в credential decision, journal export, commit, Actions run, artifact и PR correlation.
- **Redacted audit trail:** credential reference без значения token, retention decision, export range, repository/ref, base/commit SHA, approval actor, PR number и run/artifact provenance.
- **Cancellation / timeout:** journal export, GitHub operation и Actions polling имеют deadline, cancellation и bounded download; failed/unknown operation не повторяется автоматически.
- **Recovery rule:** завершённый commit/PR не создаётся повторно после восстановления; fingerprint/SHA mismatch требует нового preview/re-check; неизвестный внешний результат остаётся UNKNOWN.
- **Не входит в базовый exit criterion:** автоматический PR после успешного Actions run и автоматический merge/release.
- **Exit criterion:** секреты не попадают в UI events/journal/diff, journal ограничен и экспортируется redacted, каждый commit/PR связан с sessionId и проверяемым SHA, ручное approval работает, а неизвестные внешние операции останавливаются без replay.

- **Результат текущей реализации:** добавлен memory-only EphemeralCredentialVault с bounded TTL и wipe при очистке/закрытии, AgentBridge credential state без значений secrets, санитизация legacy request fields и UI-кнопка очистки; SessionStore получил retention limit (до 12 session-файлов/32 MiB) и SAF export redacted journal; PR flow перед внешним вызовом повторно проверяет Git HEAD, требует sessionId и expected head SHA, сверяет SHA ответа и сохраняет только provenance metadata.
- **Ограничение текущей реализации:** D3 остаётся planned: Android/provider runtime-проверка, реальный export/recovery сценарий, проверка Keystore-backed preset на этом устройстве, Actions/PR integration tests и server-side idempotency требуют отдельного acceptance gate; runtime vault остаётся memory-only, merge/release и автоматический PR по Actions не добавлялись.



## Исправление импорта и пресеты подключения

- Исправлен импорт ZIP: создание существующей родительской директории теперь не считается ошибкой.
- Исправлен импорт папки через Android Storage Access Framework: tree URI читается через `DocumentFile`, а не трактуется как обычный файл.
- Для выбранных URI сохраняется временное разрешение чтения, а содержимое копируется в app-private workspace.
- Исправлен запуск локального runtime: `LOCAL_WRITE` передаётся из выбранного UI-разрешения до создания сессии.
- Добавлен `AgentPresetStore`: DeepSeek URL/model и GitHub repository/workflow/ref сохраняются и импортируются единым JSON-пресетом.
- API-ключ DeepSeek и токен GitHub в пресете не записываются открытым текстом: используется AES-GCM с ключом Android Keystore. При переносе пресета на другое устройство ключи требуют повторного ввода.
- Добавлены действия UI: «Сохранить», «Загрузить», «Экспорт», «Импорт» пресета.
- Базовые значения проекта: `vetuhvladislav-cmyk/Deep_Agent_Mobile`, `android.yml`, `codex/p1-a-controlled-write-git-pr`, `deepseek-flash`.
