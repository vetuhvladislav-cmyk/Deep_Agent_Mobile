# Сборка и релиз Deep Agent Mobile

## Источник истины

Workflow `.github/workflows/android.yml` сам подготавливает JDK, Android SDK platform 35, build-tools 35.0.0 и Gradle cache. Локальный поиск SDK/build-tools/Kotlin для обычного CI не требуется.

Ветка текущего аудита: `codex/p1-a-controlled-write-git-pr`; `main` не изменяется.

## Состояние ветки на момент последней проверки

- На GitHub в ветке `codex/p1-a-controlled-write-git-pr` опубликован commit `8f673c5` (Release v0.1.6, run #46).
- Правка UI runtime gate (перенос на `ubuntu-24.04` + KVM) и синхронизация README/roadmap/BUILD_AND_RELEASE закоммичены локально, но ещё **не запушены**: текущий GitHub-токен имеет scopes `gist`, `read:org`, `repo` и не содержит `workflow`, поэтому push файла `.github/workflows/android.yml` отклоняется.
- Пока эта правка не попала в ветку, live workflow остаётся на `macos-15-large`, и новый прогон `connectedDebugAndroidTest` не запускается. После разрешения scope нужно выполнить push и затем `workflow_dispatch` с `publish_release=false`.

## Триггеры

- `release.published` — автоматическая сборка по опубликованному GitHub Release; тег берётся из события релиза.
- `workflow_dispatch` с `publish_release=false` — build + UI acceptance без публикации.
- `workflow_dispatch` с `publish_release=true` — build + UI acceptance, затем release job публикует APK после успешных обоих gates.

Публикация намеренно не выполнялась в аудитных прогонах.

## Jobs

| Job | Runner | Назначение |
| --- | --- | --- |
| `build` | `ubuntu-24.04` | CanonicalArgs vectors, unit tests, instrumentation compile, debug APK, checksum/provenance, best-effort Actions artifact |
| `ui-runtime` | `ubuntu-24.04` + KVM | `x86_64` Android 35 AVD и `connectedDebugAndroidTest` |
| `release` | `ubuntu-24.04` | ждёт `build`, собирает APK из того же commit и публикует Release assets |

`ui-runtime` использует стандартный Linux runner и аппаратное ускорение через `/dev/kvm`. Это осознанный выбор, а не SDK-настройка:

- стандартный ARM64 macOS runner — Apple Silicon M1/M2: nested virtualization недоступен, Android emulator там не запускается в принципе;
- Intel-вариант `macos-15-large` требует включённого account billing/spending limit и в аудите не стартовал;
- стандартный Linux runner даёт `x86_64` host, совпадающий с `x86_64` гостевым образом, и KVM-ускорение.

Перед созданием AVD job явно проверяет `uname -m == x86_64`, доступность `/dev/kvm` на чтение и запись, а также наличие установленного `system-images;android-35;google_apis;x86_64`.

## Что выполняет build job

1. Устанавливает JDK 17.
2. Устанавливает Android SDK platform 35 и build-tools 35.0.0.
3. Проверяет golden vectors для `CanonicalArgs`.
4. Запускает `testDebugUnitTest`.
5. Компилирует instrumentation tests через `assembleDebugAndroidTest`.
6. Собирает debug APK через `assembleDebug`.
7. Создаёт SHA-256 и `deep-agent-provenance.json`.
8. Публикует проверяемый Actions artifact; если quota artifact storage исчерпана, build остаётся диагностируемым, а release job пересобирает APK из того же commit.

## Сверка с предыдущей успешной сборкой APK

Исторический baseline подтверждён непосредственно по [Actions run #32](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35320175455) на commit `5c14bbb`: job завершился за 2 минуты, job summary зафиксировал Gradle 8.13, JDK 17 и следующие задачи:

1. `./gradlew --no-daemon testDebugUnitTest`
2. `./gradlew --no-daemon assembleDebugAndroidTest`
3. `./gradlew --no-daemon assembleDebug`

До Gradle-шагов workflow устанавливал Android SDK platform 35 и build-tools 35.0.0. Связанный [Release v0.1.5](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/releases/tag/v0.1.5) содержит APK `deep-agent-mobile-v0.1.5-test.apk` размером 16.1 MB с SHA-256 `6a71892cb1faac2ac60728644ad56a5c8e6809a9e827dcd01cd26631c48ca3c9`. Upload Actions artifact в run #32 упёрся в quota, но сама APK-сборка и публикация Release завершились.

Текущий `build` job сохраняет этот порядок и SDK/JDK baseline; отличия — фиксированный `ubuntu-24.04`, актуальные `checkout/setup-java` и дополнительный checksum/provenance. Поэтому текущая APK-компиляция воспроизводит проверенный прошлый способ, а UI runtime вынесен в отдельный gate.

## UI runtime acceptance

UI job устанавливает emulator и `system-images;android-35;google_apis;x86_64`, включает KVM, создаёт AVD `deep-agent-api-35`, дожидается ADB/boot и запускает `connectedDebugAndroidTest`. Используется абсолютный путь к emulator, поэтому workflow не зависит от PATH конкретного runner. Шаг `emulator -accel-check` печатает состояние ускорения в лог, а при отсутствии `/dev/kvm` job падает на явной проверке, а не на таймауте загрузки.

Полный UI lifecycle acceptance — rotation/background/process death, accessibility tree и screenshot fixtures — остаётся отдельным roadmap gate.

## Артефакты Release

Для релиза `vX.Y.Z` имена файлов:

- `deep-agent-mobile-vX.Y.Z-test.apk`
- `deep-agent-mobile-vX.Y.Z-test.apk.sha256`
- `deep-agent-provenance.json`

Provenance связывает APK с исходным `GITHUB_SHA` и именем файла. APK отладочный и предназначен для тестирования, а не для production-подписания.

Опубликованный `v0.1.6` собран из commit `8f673c5`; APK — 16 896 646 байт, SHA-256 `8161a5ae74f5f6b821e61accefc560caa62de5998a9ced4449c4cb42857de6cd`.

## Фактическая проверка аудита

- [Run #46](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35373493968), commit `8f673c5`: build job зелёная за 2:18, release job опубликовал APK v0.1.6 за 4:08. UI job завершился за 4 секунды без запуска — `macos-15-large` требует включённого account billing/spending limit. Artifact upload снова получил quota warning.
- [Run #45](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35361986031), commit `5c5e853`: Linux build job зелёная за 1:48; release skipped при `publish_release=false`.
- [Run #44](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35355722283), commit `fae06cc`: build job успешно завершён; UI job дошёл до emulator startup, но ARM64 `macos-15` runner не может запустить `x86_64` image. Release job был skipped, так как `publish_release=false`.
- [Run #42](https://github.com/vetuhvladislav-cmyk/Deep_Agent_Mobile/actions/runs/35355121189) подтвердил, что Intel `macos-15-large` job требует включённого account billing/spending limit.
- Вывод аудита: macOS-hosted варианты для этого gate непригодны — Apple Silicon M1/M2 не поддерживает nested virtualization, а Intel larger runner заблокирован billing. Поэтому `ui-runtime` перенесён на `ubuntu-24.04` + KVM; после этого нужно повторить `workflow_dispatch` без публикации и приложить зелёный `connectedDebugAndroidTest` как доказательство P1-C.

## Локальная проверка APK

```bash
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebugAndroidTest
./gradlew --no-daemon assembleDebug
sha256sum -c deep-agent-mobile-vX.Y.Z-test.apk.sha256
adb install -r deep-agent-mobile-vX.Y.Z-test.apk
```

На ARM64 Linux `google`-сборка `aapt2` из build-tools не запускается (x86_64 ELF), поэтому локальная сборка требует либо x86_64 host, либо обёртки через статический `qemu-x86_64`:

```bash
./gradlew --no-daemon -Pandroid.aapt2FromMavenOverride=/path/to/qemu-wrapped/aapt2 assembleDebug
```

Локальный прогон не заменяет CI gate: `connectedDebugAndroidTest` требует устройства/эмулятора, а release provenance ссылается на commit CI.
