# Сборка и релиз Deep Agent Mobile

## Источник истины

Workflow `.github/workflows/android.yml` сам подготавливает JDK, Android SDK platform 35, build-tools 35.0.0 и Gradle cache. Локальный поиск SDK/build-tools/Kotlin для обычного CI не требуется.

Ветка текущего аудита: `codex/p1-a-controlled-write-git-pr`; `main` не изменяется.

## Триггеры

- `release.published` — автоматическая сборка по опубликованному GitHub Release; тег берётся из события релиза.
- `workflow_dispatch` с `publish_release=false` — build + UI acceptance без публикации.
- `workflow_dispatch` с `publish_release=true` — build + UI acceptance, затем release job публикует APK после успешных обоих gates.

Публикация намеренно не выполнялась в аудитных прогонах.

## Jobs

| Job | Runner | Назначение |
| --- | --- | --- |
| `build` | `ubuntu-24.04` | CanonicalArgs vectors, unit tests, instrumentation compile, debug APK, checksum/provenance, best-effort Actions artifact |
| `ui-runtime` | `macos-15-large` (Intel) | `x86_64` Android 35 AVD и `connectedDebugAndroidTest` |
| `release` | `ubuntu-24.04` | ждёт `build` и `ui-runtime`, собирает APK из того же commit и публикует Release assets |

`macos-15-large` — larger runner. Для него в GitHub account должны быть разрешены billing/spending limit; без этого UI job не стартует. Это инфраструктурное требование, не SDK-настройка.

## Что выполняет build job

1. Устанавливает JDK 17.
2. Устанавливает Android SDK platform 35 и build-tools 35.0.0.
3. Проверяет golden vectors для `CanonicalArgs`.
4. Запускает `testDebugUnitTest`.
5. Компилирует instrumentation tests через `assembleDebugAndroidTest`.
6. Собирает debug APK через `assembleDebug`.
7. Создаёт SHA-256 и `deep-agent-provenance.json`.
8. Публикует проверяемый Actions artifact; если quota artifact storage исчерпана, build остаётся диагностируемым, а release job пересобирает APK из того же commit.

## UI runtime acceptance

UI job устанавливает emulator и `system-images;android-35;google_apis;x86_64`, создаёт AVD `deep-agent-api-35`, дожидается ADB/boot и запускает `connectedDebugAndroidTest`. Используется абсолютный путь к emulator, поэтому workflow не зависит от PATH конкретного runner.

Полный UI lifecycle acceptance — rotation/background/process death, accessibility tree и screenshot fixtures — остаётся отдельным roadmap gate.

## Артефакты Release

Для релиза `vX.Y.Z` имена файлов:

- `deep-agent-mobile-vX.Y.Z-test.apk`
- `deep-agent-mobile-vX.Y.Z-test.apk.sha256`
- `deep-agent-provenance.json`

Provenance связывает APK с исходным `GITHUB_SHA` и именем файла. APK отладочный и предназначен для тестирования, а не для production-подписания.

## Фактическая проверка аудита

- [Run #44](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35355722283), commit `fae06cc`: build job успешно завершён; UI job дошёл до emulator startup, но текущий стандартный ARM64 `macos-15` runner не может запустить `x86_64` image. Release job был skipped, так как `publish_release=false`.
- [Run #42](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35355121189) подтвердил, что корректный Intel `macos-15-large` job требует включённого account billing/spending limit.
- Финальный workflow уже указывает совместимую пару `macos-15-large` + `x86_64`; после разрешения runner billing нужно повторить `workflow_dispatch` без публикации, затем отдельным запуском включить `publish_release`.

## Локальная проверка APK

```bash
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebugAndroidTest
./gradlew --no-daemon assembleDebug
sha256sum -c deep-agent-mobile-vX.Y.Z-test.apk.sha256
adb install -r deep-agent-mobile-vX.Y.Z-test.apk
```
