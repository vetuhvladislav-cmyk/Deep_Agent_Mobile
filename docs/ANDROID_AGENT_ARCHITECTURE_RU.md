# Архитектура Android Agent

Утверждённая архитектура `Deep_Agent_Mobile`: одно цельное Android-приложение,
один APK, внутренние модули Agent Core и локальные/удалённые исполнители.

Этот репозиторий содержит только Deep Agent. WebView-оболочка и мобильный адаптер
не являются его зависимостью и ведутся отдельно.

## 1. Граница продукта

Пользователь устанавливает только один APK. Внутри него находятся:

- нативная графическая оболочка;
- Agent Core;
- DeepSeek API adapter;
- Local Lite Runner;
- GitHub и GitHub Actions connectors;
- workspace и artifact manager;
- image input pipeline;
- единая политика разрешений;

Termux, отдельный DSH APK и вторая графическая оболочка не являются обязательными
частями продукта. GitHub Actions и DeepSeek API остаются внешними сервисами,
которыми управляет этот же APK.

Серверные plugins не изменяются. Приложение может показывать доступный серверный
список и статусы, но не устанавливает и не модифицирует plugins в рамках
данного прототипа.

Android 16+ — тестовая платформа. Это не означает автоматического повышения
minSdk до 36.

## 2. Текущее состояние прототипа

В текущем прототипе реализован нативный Compose Agent Console. Harness WebView
не входит в этот репозиторий и развивается отдельно в `harness-mobile`.

Добавлено:

- модели задачи, сессии, событий, исполнителя и разрешений;
- внутренняя граница AgentBridge v1;
- Agent Core с маршрутизацией AUTO, LOCAL_LITE и REMOTE_ACTIONS;
- Local Lite Runner с health probe в app-private workspace;
- DeepSeek Responses API streaming через semantic SSE;
- model name deepseek-flash и base URL https://api.deepseek.com;
- input_image из Android Photo Picker через data URL;
- GitHub Actions workflow dispatch;
- нативный Agent Console внутри того же APK;
- отдельная лента SESSION, PLAN, REASONING, OUTPUT, TOOL, BUILD и ERROR;
- токены DeepSeek/GitHub не сохраняются в постоянное хранилище.

## 3. Слои одного APK

| Слой | Ответственность |
| --- | --- |
| Compose UI | Agent Console, подключение, состояние, разрешения, события |
| Agent Console | Нативная рабочая поверхность Deep Agent |
| AgentBridge v1 | Стабильный контракт UI ↔ Agent Core |
| Agent Core | Маршрутизация, жизненный цикл сессии, события, ошибки |
| DeepSeek Adapter | Responses API, streaming, reasoning и image input |
| Local Lite Runner | Минимальный локальный probe и будущие лёгкие операции |
| GitHub Connector | workflow dispatch, репозитории, PR и артефакты |
| Workspace Manager | локальная рабочая директория, импорт/экспорт и история |
| Permission Policy | READ_ONLY, LOCAL_WRITE, GITHUB_WRITE, PR_CREATE, MERGE_RELEASE |

Внутри Gradle может быть несколько пакетов или модулей, но результатом остаётся
один пользовательский APK.

## 4. AgentBridge v1

UI не зависит от внутренних endpoint runtime или серверных plugins. Контракт содержит:

- submit(request);
- cancel();
- clearEvents();
- session state;
- ordered event stream.

Типы событий:

- SESSION — начало и завершение;
- PLAN — выбранный исполнитель и политика;
- REASONING — поток reasoning от модели;
- OUTPUT — ответ модели;
- TOOL — локальный исполнитель и tool call;
- BUILD — запуск и состояние сборки;
- ERROR — ошибка;
- INFO — диагностическое сообщение.

Следующие компоненты подключаются только за AgentBridge:

- DSH headless;
- собственный Kotlin/Java runtime;
- удалённый runner;
- тестовый fake runner.

Это позволяет менять runtime без переписывания Android UI.

## 5. Маршрутизация задач

AUTO выбирает исполнитель по признакам задачи. Упоминание APK, AAB, Gradle,
Android SDK, NDK, CMake, compile или build направляет задачу в REMOTE_ACTIONS.
Остальные задачи идут в LOCAL_LITE.

LOCAL_LITE предназначен для:

- небольшого анализа;
- чтения и поиска файлов;
- документации;
- лёгких патчей;
- локальных проверок;
- health probe runtime.

REMOTE_ACTIONS предназначен для:

- Gradle и Android SDK;
- NDK/CMake;
- больших репозиториев;
- приватных AAR и Maven;
- длительных тестов;
- debug/release APK;
- build artifacts.

## 6. DeepSeek V4.1 Flash

Используется:

- endpoint https://api.deepseek.com;
- model name deepseek-flash;
- Responses API;
- semantic SSE streaming;
- reasoning effort;
- input_image.

DeepSeek API stateless, поэтому Agent Core должен хранить локальную историю и
состояние tool rounds. При использовании tools в thinking mode необходимо
сохранять reasoning_content и передавать его дальше.

Сейчас реализован поток запроса и отображение reasoning/output. Полный цикл
function tool → локальное выполнение → tool result → следующий sub-turn ещё
не включён.

## 7. GitHub Actions

Прототип `Deep_Agent_Mobile` отправляет workflow dispatch из единого APK:

- repository: owner/name;
- workflow: файл workflow;
- ref: ветка или другой ref;
- agent_task: краткое описание задачи.

Для запуска требуется permission GITHUB_WRITE или выше. Токен не выводится в
журнал.

Следующим слоем добавляются:

1. получение run ID после dispatch;
2. polling статуса workflow;
3. получение job logs;
4. повтор failed jobs;
5. скачивание APK/AAB artifact;
6. создание draft PR;
7. проверка commit и head SHA перед merge.

## 8. Локальный runtime и DSH

Headless runtime остаётся внутренним исполнителем одного APK. Он не должен
становиться публичным контрактом UI.

Первый этап не включает:

- полноценный PTY;
- persistent shell;
- PRoot Ubuntu;
- полный Termux bootstrap;
- Android SDK/NDK внутри базового APK.

Сначала фиксируются AgentBridge, lifecycle, health checks и простой local runner.
После этого отдельно проверяется упаковка ARM64 Node.js и совместимость выбранной
версии DSH с Android.

## 9. Фото и изображения

Путь обработки:

1. Android Photo Picker возвращает content URI.
2. APK читает поток через ContentResolver.
3. Изображение помещается во временный объект ImageAttachment.
4. Bytes кодируются в data URL.
5. DeepSeek получает input_image.
6. Временные данные удаляются по политике cache.

Планируются resize, EXIF normalization, OCR, сравнение скриншотов и передача
нескольких изображений. Генерация растровых изображений остаётся отдельным
ImageGenerator provider.

## 10. Политика разрешений

| Режим | Возможности |
| --- | --- |
| READ_ONLY | чтение, анализ, план и диагностика |
| LOCAL_WRITE | изменение локального workspace |
| GITHUB_WRITE | push, workflow и операции GitHub |
| PR_CREATE | создание Pull Request |
| MERGE_RELEASE | merge и release после отдельного подтверждения |

Опасные write-действия не выполняются только на основании текста модели.
Agent Core обязан проверить режим текущей сессии.

## 11. CI и компиляция APK

Один APK собирается GitHub Actions. В workflow используются JDK 17, Gradle,
Android SDK и build artifacts. Локальная сборка на телефоне остаётся
минимальным дополнительным режимом.

Для крупных проектов MSDK 2.5/2.6, Autel, NDK/CMake и приватных зависимостей
основным остаётся удалённый runner. Workflow этого репозитория запускается вручную и собирает debug APK; автоматические build/test triggers отключены.

## 12. Критерии следующего этапа

- Agent Console запускается как основная нативная оболочка Deep Agent.
- Offline local probe работает без ключей.
- DeepSeek streaming отображает reasoning и output.
- Image input не зависит от недоступного локального пути.
- Remote Actions требует GITHUB_WRITE.
- Никакие серверные plugins не изменяются.
- Один APK остаётся единственной пользовательской поставкой.
- Каждое критическое решение, влияющее на runtime или хранение секретов,
  выносится в отдельный вопрос с сохранением текущего прогресса.

## Источники

- [DeepSeek Responses API](https://api-docs.deepseek.com/guides/responses_api/)
- [DeepSeek Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/)
- [GitHub Actions workflow](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/blob/main/.github/workflows/android.yml)
- [Android foreground services](https://developer.android.com/develop/background-work/services/fgs)
- [Android app-specific storage](https://developer.android.com/training/data-storage/app-specific)
