# Сборка и релиз Deep Agent Mobile

## Источник истины

Workflow `.github/workflows/android.yml` сам подготавливает Java, Android SDK и Gradle-кэш. Локальный поиск SDK, build-tools и Kotlin для обычного CI не требуется.

Запуски:

- `release.published` — автоматическая сборка по опубликованному GitHub Release; тег берётся из события релиза.
- `workflow_dispatch` — ручной запуск для проверки ветки или выпуска APK. Для публикации нужно включить `publish_release` и указать SemVer-тег вида `v0.1.5`.

Ветка разработки проекта: `codex/p1-a-controlled-write-git-pr`. Основная ветка не изменяется в рамках этого аудита.

## Что выполняет CI

1. Устанавливает JDK 17.
2. Устанавливает Android SDK, platform 35 и build-tools 35.0.0.
3. Проверяет golden vectors для `CanonicalArgs`.
4. Запускает `testDebugUnitTest`.
5. Компилирует instrumentation tests через `assembleDebugAndroidTest`.
6. Собирает отладочный APK через `assembleDebug`.
7. Создаёт SHA-256 и `deep-agent-provenance.json`.
8. При публикации релиза прикладывает APK, checksum и provenance к тому же GitHub Release.

Команды, которые повторяет workflow:

```bash
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebugAndroidTest
./gradlew --no-daemon assembleDebug
```

## Артефакты

Для релиза `vX.Y.Z` имена файлов:

- `deep-agent-mobile-vX.Y.Z-test.apk`
- `deep-agent-mobile-vX.Y.Z-test.apk.sha256`
- `deep-agent-provenance.json`

Provenance связывает APK с исходным `GITHUB_SHA` и именем файла. APK отладочный и предназначен для тестирования, а не для production-подписания.

Actions artifact хранится семь дней и является дополнительным каналом. Если GitHub временно исчерпал квоту artifact storage, workflow не блокирует публикацию: Release assets остаются источником APK и checksum.

## Ручная проверка APK

После загрузки APK можно проверить checksum и установить debug-сборку:

```bash
sha256sum -c deep-agent-mobile-vX.Y.Z-test.apk.sha256
adb install -r deep-agent-mobile-vX.Y.Z-test.apk
```
