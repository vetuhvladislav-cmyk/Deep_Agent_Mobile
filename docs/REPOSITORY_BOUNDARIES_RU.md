# Границы репозиториев

## `Deep_Agent_Mobile`

Канонический репозиторий цельного Deep Agent и его единственного APK:

- Agent Core, AgentBridge и нативная Compose-оболочка;
- DeepSeek API и image input;
- Local Lite Runner и будущий RuntimeSupervisor/headless DSH;
- GitHub, GitHub Actions, Git, PR и artifacts;
- политика разрешений, история сессий, ToolRouter и документация агента.

## `harness-mobile`

Отдельный репозиторий мобильной адаптации Harness:

- Harness WebView;
- подключение к серверу и профили URL;
- WebView auth/cookies, bridge и диагностика;
- CSS/JS mobile adapter и DOM/layout verification;
- документация совместимости и мобильной вёрстки.

Агентские исходники, Agent Console и документация Deep Agent не должны возвращаться в mobile-adapter репозиторий без отдельного решения. Упоминание границы здесь не создаёт runtime-зависимости.

## Общие ограничения

- пользовательская поставка Deep Agent остаётся одним APK;
- Android 16+ используется как тестовая платформа;
- тяжёлая сборка выполняется через GitHub Actions;
- Termux и отдельный DSH APK не обязательны;
- серверные plugins не изменяются приложением;
- UI общается с runtime через `AgentBridge v1`, а не через внутренние endpoint DSH/WebView.

При будущей интеграции поверхностей нужно переносить только согласованный контракт и не смешивать два владельца navigation/state.
