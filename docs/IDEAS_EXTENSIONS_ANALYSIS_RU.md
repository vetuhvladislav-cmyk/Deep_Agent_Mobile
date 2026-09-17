# Отложенные идеи, расширения и неподтверждённые направления

Этот файл содержит только направления со статусом deferred и неподтверждённые технические варианты. Он не является источником архитектурных контрактов, permission matrix, порядка этапов или текущего состояния кода.

Принятые расширения D1–D3 перенесены в [IMPLEMENTATION_ROADMAP_RU.md](./IMPLEMENTATION_ROADMAP_RU.md). Архитектурные границы и контракты находятся в [ANDROID_AGENT_ARCHITECTURE_RU.md](./ANDROID_AGENT_ARCHITECTURE_RU.md).

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
