# Карта покрытия тестами (D1)

Живая карта: обновляется вместе с кодом. Правило проекта: **новая
бизнес-логика — чистый класс в `util/` + JVM unit-тест; Activity/Fragment/
Service — только wiring, без ветвлений, достойных отдельного теста.**
Если логика появилась в UI-слое — это сигнал вынести её в `util/`
(так вынесены `RelayAuthorization` из `DummyActivity` и решение VPN-гейта
из `AntiSpyLaunchGate`).

Два эшелона:

- **unit** (`app/src/test`) — JVM, гоняются на каждом
  `./gradlew :app:testDebugUnitTest`. Логику поверх настроек проверяют
  через `TestStorage`: `SharedPreferences` в памяти, подставленный в
  `LocalStorageManager` и `SettingsManager` контекстом-заглушкой, без
  Robolectric и устройства. Все, что уходит в систему (DPM, PackageManager,
  startActivity), на заглушке не работает — это эшелон ниже;
- **androidTest** (`app/src/androidTest`) — инструментальные, нужны
  эмулятор/устройство и рабочий профиль (`TestProfiles` проверяет среду,
  стенд — `tools/testbench.sh`).

Покрытие JVM-тестов (JaCoCo):

```bash
./gradlew -Pcoverage :app:createDebugUnitTestCoverageReport
```

Отчет: `app/build/reports/coverage/test/debug/index.html`. Замер 03.11.2026:
899 из 11 153 строк (8,1 %; было 5,2 %). Знаменатель — все приложение вместе
с UI, сервисами и сгенерированным AIDL; по правилу выше это wiring, поэтому
смотреть надо на классы с решениями, а не на общий процент.

## Покрыто unit-тестами

### Безопасность реле и профиля

| Модуль | Тест | Что проверяется |
|---|---|---|
| `RelayAuthorization` | RelayAuthorizationTest | гейт DummyActivity (S4): набор действий без подписи закреплен, привилегированные — только с подписью или токеном, статус установщика — только через псевдоним, FINALIZE_PROVISION в личном — только во время настройки, порядок проверок с побочными эффектами |
| таблица `RELAY_ACTION_FILTERS` | RelayFilterTableTest | одно направление на действие DummyActivity (E-9), действие объявлено в манифесте, публичная разморозка не форвардится (S2), поверхность без подписи на каждом направлении закреплена |
| `WorkProfilePolicy.settingsInputs` | WorkPolicyInputsTest | каждая настройка политики и изоляции меняет отпечаток (F4), порядок правил ссылок — нет |
| `KeyProof`, `AuthenticationUtility` (ключ) | KeyProofTest, AuthKeyLifecycleTest | доказательство ключа (S1): чужой ключ, чужой вызов и подпись реле не проходят; создание, принятие, сброс ключа |
| `AuthPayload` | AuthPayloadTest | формат подписи реле, разделение доменов с доказательством ключа |
| `BinderCallers`, `Hex` | BinderCallersTest, HexTest | проверка вызывающего биндера по appId; формат ключей |
| `ImpostorScan`, `StartActivityProxyPolicy`, `InstallRestrictionLease` | ImpostorScanTest | поиск подделок и queries в манифесте, белый список прокси запуска, аренда ограничений установки (F1) |
| `PolicyFingerprint` | PolicyFingerprintTest | отпечаток и срок свежести набора политик |
| `IsolationPolicies` | IsolationPoliciesTest | пресеты, строки каждой политики в en/ru |
| `PairingCode` | PairingCodeTest | код сверки ключей при перепривязке |
| `SameProcessTokens` | SameProcessTokensTest | одноразовые токены своего процесса |
| `ProfileActions` | ProfileActionsTest | стабильность action-констант между профилями |

### Заморозка и настройки

| Модуль | Тест | Что проверяется |
|---|---|---|
| `AutoFreezeDefaults`, `AutoFreezePolicy` | AutoFreezeRulesTest | снежинка без дублей, отказ уважается и снимается только явно, VPN-гейт, разовая миграция старых заморозок и повтор после сбоя |
| `ScreenLockFreezeMigration` | ScreenLockFreezeMigrationTest | перенос старого триггера блокировки, один раз |
| `LocalStorageManager` | LocalStorageManagerTest | списки в строке (вхождение целиком, пустые элементы), синхронные записи, снимок бэкапа по белому списку |
| `SettingsManager` (умолчания) | SettingsDefaultsTest | свежая установка: кросс-профильные дырки и сторож VPN выключены |
| `AntiSpyLaunchGate` (решение) | AntiSpyLaunchGateTest | итог гашения VPN → запуск/причина блока, неизвестный итог — блок, тост не поверх запроса разрешения |
| `AntiSpyWatchPolicy` | AntiSpyWatchPolicyTest | условия автозаморозки сторожем VPN |
| `NewPackagePolicy`, `PackageSequence` | NewPackagePolicyTest, PackageSequenceTest | новые пакеты профиля (F3) |
| `LegacyFreezeMigration` | LegacyFreezeMigrationTest | миграция списков заморозки старых версий |
| `BackupPayload` | BackupPayloadTest | round-trip JSON бэкапа, отказ чужим форматам, защита секретов (C4) |
| `PendingIntents` (режим) | PendingIntentsModeTest | режим фонового запуска для PendingIntent по версии Android |

### Файлы, ссылки, прочее

| Модуль | Тест | Что проверяется |
|---|---|---|
| `FileShuttlePathResolver` | FileShuttlePathResolverTest | пути шаттла, отказ относительным (E-7) |
| `MediaMirror` (разбор строки), `MediaMirrorSelection` | MediaMirrorRowTest, MediaMirrorSelectionTest | строки листинга: каталоги и строки без типа отброшены; отбор медиа для автопереноса |
| `ShuttleSession` | ShuttleSessionTest | жизненный цикл сессии File Shuttle |
| `ForwardedUriRegistry` | ForwardedUriRegistryTest | реестр пересылаемых URI |
| `CrossProfileLinkRules` | CrossProfileLinkRulesTest | разбор доменных правил ссылок (C2) |
| `CalendarRouting` (кодек) | CalendarRoutingTest | кодек выбранного календаря |
| `CaCertificates` | CaCertificatesTest | сериализация/валидация корневых CA |
| `CloneOutcome` | CloneOutcomeTest | расшифровка кодов установки/клонирования |
| `ProfileForwarder` (выбор) | ForwarderSelectionTest | выбор форвардера/реле |
| `StoreCloneHint` | StoreCloneHintTest | пороги подсказки магазина, выбор кандидата (C1) |
| `InstallWarnPolicy`, `MiuiDetector` | InstallWarnPolicyTest, MiuiDetectorTest | предупреждение об установке на MIUI (A1) |
| `PendingOperationRegistry` | PendingOperationRegistryTest | дедупликация висящих операций |
| `PowerDiagnostics` | PowerDiagnosticsTest | диагностика фоновых ограничений |
| `VpnRoutingAdvice` | VpnRoutingAdviceTest | советы по маршрутизации VPN |
| `VpnTunnelDetector` (имена) | VpnTunnelDetectorTest | имена туннельных интерфейсов |
| `WorkServiceBindFailure` | WorkServiceBindFailureTest | классификация сбоев бинда work-сервиса |
| (сборный) | SmokeTest | дымовая проверка окружения тестов |

## Покрыто androidTest-ами

| Модуль | Тест | Что проверяется |
|---|---|---|
| `DummyActivity` (гейт) | RelayGateTest | activity спрашивает `RelayAuthorization`: неподписанная команда не исполняется, подписанная — исполняется |
| `ImpostorScan`, `InstallRestrictionLease` | ProfileGuardsTest | тестовый APK с действием реле найден как подделка (S1); ограничение установки снимается на операцию и возвращается (F1, только в рабочем профиле) |
| `LocalEvents` | LocalEventsTest | доставка на главном потоке, снятый до доставки получатель молчит |
| `CrossProfileDocumentsProvider`, `FileShuttleExternalBrowser` | DocumentsProviderTest | A3: корни/листинг/чтение через шаттл; временная выдача корня внешнему проводнику |
| `FileShuttleService` | FileShuttleForegroundTest | foreground-сессия, сроки жизни |
| `AntiSpyVpnWatchService` | AntiSpyVpnWatchTest | сторож VPN в отдельном процессе |
| `AuthenticationUtility` | AuthenticationBootstrapTest | bootstrap авторизации, ключ не уходит подтвердившей стороне, доказательство ключа |
| `Utility.createLauncherShortcut` | BatchShortcutIconTest | A2: иконка ярлыка — растр, без падения на requestPinShortcut |
| `ProfileForwarder` (разрешение) | ForwarderResolutionTest | разрешение реле, доступа к контенту и создания события в личном календаре |
| `ServiceLiveness` | ServiceLivenessTest | живость binder-ов обоих сервисов |
| передача владения | TransferOwnershipTest | F1: передача владения профилем другому приложению |
| среда | EnvironmentTest, TestProfiles, ImpostorRelayActivity | предусловия и фикстуры профиля |

RelayGateTest, ProfileGuardsTest и LocalEventsTest добавлены 03.11.2026 и
на момент записи только компилируются: в среде, где они написаны, нет
аппаратной виртуализации для эмулятора. Первый прогон — на стенде.

## Сознательно не покрыто (тонкий слой / граница фреймворка)

| Модуль | Почему | Риск |
|---|---|---|
| `MainActivity`, `AppListFragment`, `SettingsFragment`, `SetupWizardActivity`, остальной `DummyActivity` | wiring: binder-вызовы + UI; ветвления выносим в `util/` (C1/C3/C4, гейт реле) | регрессии wiring ловятся ручным прогоном сценариев USER_GUIDE |
| `GatekeeperService`, `KillerService`, `BatchFreezeService`, `FreezeService` | шеллы над AIDL/DPM; решения принимают вызывающие (покрыты) | низкий |
| `AntiSpyManager`, `AntiSpyDummyVpnDisconnector` | системные VPN/activity-сервисы; ядро политик — `AntiSpyWatchPolicy`, решение гейта — `AntiSpyLaunchGate` (покрыты) | средний |
| `WorkProfileBatchFreeze` | DPM-вызовы; решение «морозить ли» — `AutoFreezePolicy`/defaults (покрыты) | низкий |
| `GatekeeperToast`, `Notifications` | UI/системные helper-ы | низкий |

## Пробелы-цели (по приоритету)

1. `VpnTunnelDetector` (6 % строк) — решение «VPN активен» складывается из
   трех сигналов (tun-интерфейс, VPN-транспорт у любой сети, нет NOT_VPN у
   активной сети); сбой определения дает `false`, то есть гейт пропускает
   запуск (fail-open). Вынести сложение сигналов в чистую функцию, покрыть
   комбинации и явно решить, остается ли сбой открытым. Сейчас покрыт только
   разбор имен интерфейсов.
2. `AuthenticationUtility.checkIntent` — окно времени подписи (−2 с … 30 с)
   и разбор extras покрыты только на устройстве; границы окна достойны
   JVM-теста после выноса проверки времени в чистую функцию.
3. `CalendarRouting.configure` / `moveLocalEvents` (6 %) — DPM и
   ContentResolver: инструментальный тест на календаре, который заводит
   `tools/testbench.sh`.
4. `SettingsManager` (сеттеры) — синхронизация в соседний профиль идет
   через реле, проверяется только сценарием на стенде.
5. `Utility` (3 %) — сборник разнородных помощников; чистые части
   выносить по мере правок, а не отдельным рефакторингом.
