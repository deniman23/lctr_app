# Схема прошивки телефона (Device Owner)

Чеклист обязательных политик, которые приложение применяет при назначении Device Owner.

## Обязательные политики

| # | Политика | Реализация | Проверка |
|---|----------|------------|----------|
| 1 | Device Owner | `adb shell dpm set-device-owner …` | `dpm list-owners` |
| 2 | Геолокация always | `setPermissionGrantState` GRANTED (один раз) | health: `location.permission = always` |
| 3 | **POST_NOTIFICATIONS = DENIED** | `suppressAppNotifications()` | health: `app_notifications_suppressed = true` |
| 4 | **Не перевыдавать geo на wake** | `setPermissionGrantStateIfChanged` | нет спама «В вашей организации…» |
| 5 | Батарея whitelist | `cmd deviceidle whitelist +pkg` | health: `battery_unrestricted = true` |
| 6 | Скрыть из лаунчера | `setApplicationHidden(true)` | health: `hidden_from_launcher = true` |
| 7 | Запрет удаления | `setUninstallBlocked(true)` | health: `uninstall_blocked = true` |

## Что нельзя убрать

- Минимальное уведомление foreground location service (Android требует для `FOREGROUND_SERVICE_LOCATION`).
- Иконка «локация активна» в статус-баре при работе GPS.

## Версия с фиксом

Исправление спама «В вашей организации приложению … разрешено использование геолокации» — **>= 1.0.30 (versionCode 31)**.

Release только через **GitHub Actions** (release keystore), не через `build_android_release.sh` на сервере без keystore.
