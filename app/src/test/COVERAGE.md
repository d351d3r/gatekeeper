# Карта покрытия тестами (D1)

Живая карта: обновляется вместе с кодом. Правило проекта: **новая
бизнес-логика — чистый класс в `util/` + JVM unit-тест; Activity/Fragment/
Service — только wiring, без ветвлений, достойных отдельного теста.**
Если логика появилась в UI-слое — это сигнал вынести её в `util/`.

Два эшелона:

- **unit** (`app/src/test`) — чистая JVM-логика, гоняются на каждом
  `./gradlew :app:testDebugUnitTest`;
- **androidTest** (`app/src/androidTest`) — инструментальные, нужны
  эмулятор/устройство и рабочий профиль (`TestProfiles` проверяет среду).

## Покрыто unit-тестами (20 классов)

| Модуль | Тест | Что проверяется |
|---|---|---|
| `AntiSpyWatchPolicy` | AntiSpyWatchPolicyTest | условия автозаморозки сторожем VPN |
| `AuthPayload` | AuthPayloadTest | формат/парсинг auth-полезной нагрузки |
| `BackupPayload` | BackupPayloadTest | round-trip JSON бэкапа, отказ чужим форматам, защита секретов (C4) |
| `CaCertificates` | CaCertificatesTest | сериализация/валидация корневых CA |
| `CloneOutcome` | CloneOutcomeTest | расшифровка кодов установки/клонирования |
| `ForwardedUriRegistry` | ForwardedUriRegistryTest | реестр пересылаемых URI |
| `ProfileForwarder` (выбор) | ForwarderSelectionTest | выбор форвардера/реле |
| `InstallWarnPolicy` | InstallWarnPolicyTest | предупреждение об установке на MIUI (A1) |
| `LegacyFreezeMigration` | LegacyFreezeMigrationTest | миграция списков заморозки старых версий |
| `MediaMirrorSelection` | MediaMirrorSelectionTest | отбор медиа для автопереноса |
| `MiuiDetector` | MiuiDetectorTest | определение MIUI/HyperOS |
| `PendingOperationRegistry` | PendingOperationRegistryTest | дедупликация висящих операций |
| `PowerDiagnostics` | PowerDiagnosticsTest | диагностика фоновых ограничений |
| `ProfileActions` | ProfileActionsTest | стабильность action-констант между профилями |
| `SameProcessTokens` | SameProcessTokensTest | токены same-process запросов |
| `ShuttleSession` | ShuttleSessionTest | жизненный цикл сессии File Shuttle |
| `StoreCloneHint` | StoreCloneHintTest | пороги подсказки магазина, выбор кандидата (C1) |
| `VpnRoutingAdvice` | VpnRoutingAdviceTest | советы по маршрутизации VPN |
| `WorkServiceBindFailure` | WorkServiceBindFailureTest | классификация сбоев бинда work-сервиса |
| (сборный) | SmokeTest | дымовая проверка окружения тестов |

## Покрыто androidTest-ами

| Модуль | Тест | Что проверяется |
|---|---|---|
| `CrossProfileDocumentsProvider` | DocumentsProviderTest | A3: корни/листинг/чтение через шаттл |
| `FileShuttleService` | FileShuttleForegroundTest | foreground-сессия, сроки жизни |
| `AntiSpyVpnWatchService` | AntiSpyVpnWatchTest | сторож VPN в отдельном процессе |
| `AuthenticationUtility` | AuthenticationBootstrapTest | bootstrap авторизации на устройстве |
| `Utility.createLauncherShortcut` | BatchShortcutIconTest | A2: иконка ярлыка — растр, без падения на requestPinShortcut |
| `ProfileForwarder` (разрешение) | ForwarderResolutionTest | разрешение реле на устройстве |
| `ServiceLiveness` | ServiceLivenessTest | живость binder-ов обоих сервисов |
| среда | EnvironmentTest, TestProfiles, ImpostorRelayActivity | предусловия и фикстуры профиля |

## Сознательно не покрыто (тонкий слой / граница фреймворка)

| Модуль | Почему | Риск |
|---|---|---|
| `MainActivity`, `AppListFragment`, `DummyActivity`, `SettingsFragment`, `SetupWizardActivity` | чистый wiring: binder-вызовы + UI; ветвления выносим в `util/` (см. C1/C3/C4) | регрессии wiring ловятся ручным прогоном сценариев USER_GUIDE |
| `GatekeeperService`, `KillerService`, `BatchFreezeService`, `FreezeService` | шеллы над AIDL/DPM; решения принимают вызывающие (покрыты) | низкий |
| `AntiSpyManager`, `AntiSpyVpnGuard`, `VpnTunnelDetector`, `AntiSpyLaunchGate` | завязаны на системные VPN/activity-сервисы; ядро политик вынесено в `AntiSpyWatchPolicy` (покрыто) | средний — цель будущего эшелона |
| `WorkProfileBatchFreeze` | DPM-вызовы; решение «морозить ли» — `AutoFreezePolicy`/defaults | низкий |
| `LocalStorageManager` | типизированная обёртка SharedPreferences; опасные места (типы) покрыты через round-trip `BackupPayloadTest` | низкий |
| `GatekeeperToast`, `PendingIntents` | UI/системные helper-ы | низкий |

## Пробелы-цели (по приоритету)

1. `AntiSpyLaunchGate` — выделить решающую функцию в `util/` и покрыть
   (сейчас логика в развязке VPN-гейта и запуска).
2. `AutoFreezePolicy` / `AutoFreezeDefaults` — чистая политика, достойна
   unit-тестов (правила opt-out, первичного заполнения списка).
3. `MediaMirror` — оркестрация; отбор покрыт (`MediaMirrorSelectionTest`),
   кламп путей — нет (см. E-7 в plan.md).
