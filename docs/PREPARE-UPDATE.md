# Подготовка обновления приложения (runbook для агента)

Когда пользователь просит **«подготовить обновление»**, **«сделай релиз»**, **«выложи новую версию»** — выполнять этот чеклист. Не спрашивать лишнего, если не блокер.

## Контекст проекта

| Что | Значение |
|-----|----------|
| Репозиторий | `deniman23/lctr_app` |
| Package | `com.example.lctr_app` |
| Версия (единственный источник) | `app/version.properties` |
| CI workflow | `.github/workflows/android-release.yml` |
| Сервер API | `http://178.172.235.51:8080` |
| APK на сервере | `/var/www/locator/static/releases/` |
| URL раздачи | `/static/releases/locator-{versionName}-{versionCode}.apk` |
| Симлинк latest | `locator-latest.apk` |
| Подпись | release keystore (`keystore/lctr-release.jks`, секреты в GitHub) |
| OTA на устройстве | poll-команда `app_update` (тип в `device/PollCommand.kt`) |

**Текущая базовая версия в репо** (обновлять строку после каждого релиза): см. `app/version.properties`.

## Что сделать агенту (по порядку)

### 1. Прочитать текущую версию

```bash
cat app/version.properties
# versionCode=N
# versionName=X.Y.Z
```

### 2. Поднять версию

- `versionCode` — **обязательно +1** (целое, монотонно растёт).
- `versionName` — semver patch (+1 в последнем сегменте), если пользователь не указал иное.

Пример: `5 / 1.0.3` → `6 / 1.0.4`.

Редактировать **только** `app/version.properties` (не дублировать в `build.gradle.kts`).

### 3. Закоммитить и запушить в `main`

Коммит только если пользователь просил коммит/push; иначе — подготовить diff и спросить.

Сообщение коммита (стиль репо):

```
Release X.Y.Z (versionCode N)
```

Push в `main` запускает CI, если изменились `app/**` (в т.ч. `version.properties`).

Альтернатива без push: пользователь сам — **Actions → Android Release → Run workflow**.

### 4. Дождаться CI

- Workflow: **Android Release**
- Успех: job зелёный, артефакт `apk-{versionName}-{versionCode}`
- **Summary** job: имя APK, SHA256, готовый JSON `app_update`

Если CI упал — см. раздел «Типичные ошибки CI» ниже.

### 5. Проверить деплой на сервер (по возможности)

```bash
# с машины с SSH или curl
curl -I "http://178.172.235.51:8080/static/releases/locator-{versionName}-{versionCode}.apk"
```

Ожидается `200` и размер > 1 MB.

### 6. Сформировать payload для устройств

Из Summary CI или вручную:

```json
{
  "command": {
    "type": "app_update",
    "id": "release-{versionCode}",
    "payload": {
      "url": "/static/releases/locator-{versionName}-{versionCode}.apk",
      "version": "{versionName}",
      "version_code": {versionCode},
      "sha256": "{sha256 из CI}"
    }
  }
}
```

**Правила OTA:**

- `version_code` в payload **строго равен** `versionCode` внутри APK (из `version.properties` на момент сборки).
- `version_code` на сервере **должен быть больше**, чем на устройстве, иначе `already_up_to_date`.
- `sha256` — SHA-256 **того же** файла, что лежит на сервере.
- Подпись APK должна совпадать с установленным приложением (release → release). Debug поверх release — нельзя без переустановки.

Опциональные поля (если сервер поддерживает): `wifi_only`, `install_when_idle`.

### 7. Отправить обновление на устройства

Через админку / API poll сервера `locator_go` — команда `app_update` на нужные `user_id` / все устройства.

Если есть `LOCATOR_ADMIN_API_KEY` и эндпоинт:

```bash
./scripts/ci/register-release-on-server.sh {versionName} {versionCode} \
  "/static/releases/locator-{versionName}-{versionCode}.apk" {sha256}
```

### 8. Проверка на устройстве

```bash
adb shell dumpsys package com.example.lctr_app | grep -E versionCode|versionName
```

Ожидается новая версия после poll + скачивания. С Device Owner установка тихая.

SharedPreferences (отладка OTA): `app_update_state`, `app_update_error`.

## GitHub Secrets (уже в репо, не коммитить значения)

| Secret | Назначение |
|--------|------------|
| `ANDROID_KEYSTORE_BASE64` | base64 `lctr-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | |
| `ANDROID_KEY_ALIAS` | обычно `lctr` |
| `ANDROID_KEY_PASSWORD` | |
| `DEPLOY_SSH_HOST` | `178.172.235.51` |
| `DEPLOY_SSH_USER` | |
| `DEPLOY_SSH_KEY` | приватный ключ |
| `DEPLOY_REMOTE_DIR` | `/var/www/locator/static/releases` |
| `LOCATOR_ADMIN_API_KEY` | опционально |

Локальная сборка без CI:

```bash
export ANDROID_KEYSTORE_PATH="$PWD/keystore/lctr-release.jks"
export ANDROID_KEYSTORE_PASSWORD='...'
export ANDROID_KEY_ALIAS=lctr
export ANDROID_KEY_PASSWORD='...'
./gradlew :app:assembleRelease
```

## Типичные ошибки CI

| Симптом | Причина | Фикс |
|---------|---------|------|
| `grep: .../app/build.gradle.kts` при Read version | `source` без `BASH_SOURCE` | уже исправлено в `scripts/ci/extract-version.sh` |
| `workflow file issue` | `if: secrets.*` в workflow | не использовать secrets в `if` |
| Summary: `No such file` | был `$GITHUB_SUMMARY` | использовать `$GITHUB_STEP_SUMMARY` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` на телефоне | другая подпись | переустановка release + заново DO |
| `already_up_to_date` | `version_code` ≤ на устройстве | поднять `versionCode` и пересобрать |

## Первая установка / смена подписи (не OTA)

1. Снять Device Owner (debug: `adb shell am start -n com.example.lctr_app/.MainActivity --ez clear_device_owner true` только в DEBUG-сборке).
2. `adb uninstall com.example.lctr_app`
3. Установить release APK
4. `adb shell dpm set-device-owner com.example.lctr_app/.corporate.CorporateDeviceAdminReceiver`
5. QR для `user_id` / `api_key`

## После успешного релиза

Обновить в этом файле строку «текущая базовая версия» **не обязательно** — источник правды `app/version.properties`.

## Краткий чеклист для ответа пользователю

1. [ ] `versionCode` / `versionName` подняты в `app/version.properties`
2. [ ] Push / Run workflow
3. [ ] CI зелёный, APK и SHA256 из Summary
4. [ ] Файл доступен по HTTP
5. [ ] `app_update` с корректным `version_code` и `sha256`
6. [ ] На пилотном устройстве версия обновилась
