# Отложенные идеи, расширения и неподтверждённые направления

Этот файл содержит только направления со статусом deferred и неподтверждённые технические варианты. Он не является источником архитектурных контрактов, permission matrix, порядка этапов или текущего состояния кода.

Принятые расширения D1–D3 перенесены в [IMPLEMENTATION_ROADMAP_RU.md](./IMPLEMENTATION_ROADMAP_RU.md). Архитектурные границы и контракты находятся в [ANDROID_AGENT_ARCHITECTURE_RU.md](./ANDROID_AGENT_ARCHITECTURE_RU.md).

## Собственный приоритетный список

Этот список — практический порядок работы после статического аудита. Он не создаёт новый источник статусов и не переводит capability в `available`.

### P0 — сначала закрыть надёжность

1. **Единый contract/build surface.** Синхронизировать AgentBridge, модели, UI contract, manifest/Gradle и документацию; acceptance начинается с компилируемого минимального APK.
2. **Fail-closed execution boundary.** Одна политика путей и аргументов для workspace, ToolRouter, Patch Engine и Git; запрет symlink escape, чувствительных файлов и неявного shell.
3. **Cancellation и recovery.** Отмена должна доходить до provider/network/process/runtime; неподтверждённая остановка становится `UNKNOWN` или `FAILED`, а не успешным результатом и не replay.
4. **Provenance внешних действий.** Каждая Actions/PR/artifact операция связывается с `sessionId`, repository, ref и ожидаемым SHA; повторная попытка сначала проверяет существующий run.
5. **Negative-path regression suite.** Fake providers и fixtures для path escape, permission, timeout, ZIP/checksum, journal recovery, process death и отсутствия Git.

### P1 — затем масштабировать полезность

6. **Provider/model registry.** Единый типизированный контракт для base URL, модели, capability и восстановления настроек без разъезда между UI, Core и journal.
7. **Ясный формат journal.** Сохранить bounded JSON snapshots как осознанный текущий формат либо отдельно спроектировать append-only JSONL; не смешивать эти обещания в документации.
8. **Capability и policy registry.** Каждая native, MCP и dynamic capability получает owner, version, scope, permission, redaction и rollback; DSL остаётся ограниченным и без native-кода.
9. **Фоновое выполнение.** Scheduler, WorkManager и foreground service вводятся после модели idempotency, quotas, уведомлений и восстановления после process death.
10. **Доверие к артефактам.** После checksum/source SHA добавить подпись или attestation, если APK/AAB будет распространяться за пределы локального инженерного сценария.

### P2 — только после подтверждённой базы

11. **Voice, vision и accessibility.** Сначала матрица устройств, latency/accuracy benchmarks и privacy threat model; затем отдельные capability adapters.
12. **MCP/CapApp/OS-level.** Сначала стабильный ABI, discovery и consent boundary; fork Android, LSPosed и системный launcher — отдельный продуктовый трек, не скрытая зависимость APK.
13. **Remote/multi-device/decentralized.** Добавлять после threat model, device-specific permission isolation, transport authentication и понятной деградации offline режима.

### Как отбирать идеи из большого списка

Список из 300 пунктов полезен как карта возможностей, но смешивает текущие функции, альтернативные архитектуры, маркетинговые утверждения и взаимоисключающие варианты. До включения в roadmap идея должна иметь владельца, data-flow, permission gate, failure/rollback semantics и проверяемый acceptance criterion.

Особенно требуют разведения:

- единый APK против обязательного Termux, DSH APK, второй оболочки, AOSP fork и LSPosed takeover;
- native UI против WebView/DOM skills;
- allowlist, fail-closed и per-call HITL против trust mode, one-click autonomy и постоянного разрешения на commit-действия;
- ограниченный DSL против произвольного scripting, Python meta-tooling и dev tools;
- on-device privacy против remote inference, Telegram/HTTP tunnels и multi-device control;
- разные заявления о MCP (120+ tools, 57 tools, SSE и Streamable HTTP) — нужен один канонический transport/tool inventory.

Числа вроде «$0.01 за действие», «<1 s» и «95% дешевле» не считаются capability до появления воспроизводимого benchmark с устройством, моделью, набором задач и baseline.

## Решения по направлениям, не включённым в базовый план

- отдельный raster-generation provider — deferred; D1 использует существующий DeepSeek provider для анализа;
- TaskTracker как отдельный state owner — отклонено; задача представляется через SessionRecord, AgentEvent и AgentBridge;
- произвольные дополнительные workspace providers — deferred до подтверждённой потребности;
- внешний proxy для хранения provider tokens — deferred и не входит в базовый D3 scope;
- автоматический PR после успешного Actions run — отклонено для текущего плана; базовый flow остаётся ручным и approval-gated;
- автоматический merge/release — deferred и требует отдельного REMOTE_ACTION решения.

## Неподтверждённые технические направления

- ToolCapabilityRegistry с версионированием и обнаружением capability;
- native Git/libgit2 provider, если на Android-устройстве отсутствует исполняемый git;
- дополнительные provider adapters при сохранении единого AgentBridge.

Для каждого направления потребуется отдельное обоснование, владелец, влияние на storage/runtime и решение до включения в roadmap.

## Идеи, выявленные статическим аудитом

### Детерминированная корреляция Actions run и idempotency key

- **Статус:** deferred.
- **Проблема:** после `workflow_dispatch` один только timestamp/ID может выбрать соседний параллельный run; timeout также не должен приводить к повторному dispatch вслепую.
- **Идея:** передавать уникальный correlation/idempotency key, проверять workflow/ref/source SHA и сохранять связь dispatch → run до разрешения повторной попытки.
- **Зависимости:** контракт workflow input, Session Journal, GitHub Actions API.
- **Критерий приёмки:** параллельные runs не смешиваются, а после неизвестного результата UI предлагает только re-check существующего run.

### No-follow и потоковое чтение workspace

- **Статус:** deferred.
- **Проблема:** bounded read снижает риск переполнения памяти, но последовательность `length/canonical/read` всё ещё оставляет TOCTOU-окно.
- **Идея:** использовать bounded streaming и NIO-операции с `NOFOLLOW_LINKS` там, где это доступно, с единым безопасным file-access helper.
- **Зависимости:** Workspace Manager, ToolRouter, Patch Engine, Android API 26+.
- **Критерий приёмки:** symlink/race fixtures не позволяют прочитать или заменить файл вне подтверждённого snapshot; память ограничена размером операции.

### Sandbox для Git config и remote

- **Статус:** deferred.
- **Проблема:** hooks, global config и fsmonitor уже блокируются, но repository-local config/filters/remote могут влиять на поведение внешнего Git процесса.
- **Идея:** отдельный Git execution profile с разрешённым набором config, безопасным remote-host policy и запретом process filters/неподтверждённых SSH-команд.
- **Зависимости:** Git provider или libgit2, GitHub/enterprise host policy, GIT_WRITE approval.
- **Критерий приёмки:** вредоносный `.git/config` не запускает сторонний процесс и не меняет target remote; разрешённый push остаётся проверяемым по ref, HEAD и fingerprint.

### Супервизор дерева процессов и output watchdog

- **Статус:** deferred.
- **Проблема:** прямой `ProcessBuilder` ограничивает родительский процесс, но полноценный backend должен гарантировать остановку дочерних процессов и ограничивать idle/output abuse.
- **Идея:** process-group/tree termination, idle deadline, bounded stdout/stderr streaming и orphan scan как часть P2-B backend.
- **Зависимости:** RuntimeSupervisor, Android process API/ABI, отдельное решение по PTY.
- **Критерий приёмки:** timeout/cancel не оставляет дочерних процессов, output не превышает budget, состояние после interruption — UNKNOWN без replay.

### Подписанная provenance для APK/AAB

- **Статус:** deferred.
- **Проблема:** текущие checksum и source SHA подтверждают целостность полученных bytes и связь с commit, но JSON sidecar сам по себе не является подписью производителя.
- **Идея:** проверять подписанную provenance/attestation и ограничивать допустимые redirect hosts перед сохранением artifact.
- **Зависимости:** CI signing/attestation policy, key distribution, Artifact Manager.
- **Критерий приёмки:** artifact принимается только при валидной подписи, ожидаемом source SHA и разрешённом источнике; отказ не допускает сохранение.

### Контрактные fixtures для acceptance gates

- **Статус:** deferred.
- **Проблема:** без `src/test` и `src/androidTest` нельзя воспроизводимо закрыть проверки пяти tools, cancellation, ZIP limits, journal recovery, permission gates и отсутствие replay.
- **Идея:** добавить fake providers и deterministic fixtures для workspace, GitHub Actions, runtime, SAF и image cache.
- **Зависимости:** test seams в AgentBridge/provider interfaces, CI emulator/device policy.
- **Критерий приёмки:** каждый planned этап имеет автоматический negative-path тест; тесты не используют реальные tokens, сети или production workspace.
