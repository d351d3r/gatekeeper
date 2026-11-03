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
1072 из 11 163 строк (9,6 %; до работы над покрытием было 5,2 %). Знаменатель —
все приложение вместе с UI, сервисами и сгенерированным AIDL; по правилу выше
это wiring, поэтому смотреть надо на классы с решениями, а не на общий процент.

Системные вызовы классов с логикой спрятаны за узкими интерфейсами, которые
JVM-тест подменяет записью вызовов: `SettingsManager.Effects` (письма в соседний
профиль, сторож VPN, компоненты), `CalendarRouting.Platform` (права календаря,
провайдер, JobScheduler). Боевые реализации — тонкие адаптеры без ветвлений.

## Покрыто unit-тестами

### Безопасность реле и профиля

| Модуль | Тест | Что проверяется |
|---|---|---|
| `RelayAuthorization` | RelayAuthorizationTest | гейт DummyActivity (S4): набор действий без подписи закреплен, привилегированные — только с подписью или токеном, статус установщика — только через псевдоним, FINALIZE_PROVISION в личном — только во время настройки, порядок проверок с побочными эффектами |
| таблица `RELAY_ACTION_FILTERS` | RelayFilterTableTest | одно направление на действие DummyActivity (E-9), действие объявлено в манифесте, публичная разморозка не форвардится (S2), поверхность без подписи на каждом направлении закреплена |
| `WorkProfilePolicy.settingsInputs` | WorkPolicyInputsTest | каждая настройка политики и изоляции меняет отпечаток (F4), порядок правил ссылок — нет |
| `KeyProof`, `AuthenticationUtility` (ключ) | KeyProofTest, AuthKeyLifecycleTest | доказательство ключа (S1): чужой ключ, чужой вызов и подпись реле не проходят; создание, принятие, сброс ключа |
| `AuthPayload` | AuthPayloadTest, SignatureWindowTest | формат подписи реле, разделение доменов с доказательством ключа; окно свежести −2 с … 30 с на границах |
| `AuthenticationUtility.encodeSignedValue` | SignedValueEncodingTest | каноническая запись extras: тип входит в запись, неподписываемые типы не подписываются |
| обработчики реле (`ui/*Flow`, `WorkRelayHandlers`, `DummyActivity`) | RelayExtrasSignedTest | каждый читаемый extra подписан или явно неподписываем с причиной (сканирует исходники; так найден неподписанный `isolation_keys`) |
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
| `SettingsManager` (сеттеры) | SettingsSyncTest | каждый сеттер пишет локально и отправляет в соседний профиль то же значение; FreezeService и сторож VPN будятся; изоляция уезжает всем набором; итог миграции доезжает до профиля |
| `CalendarRouting` (включение, перенос) | CalendarRoutingConfigureTest, CalendarRoutingTest | откат при отказе прав, без календаря и при сбое JobScheduler; выключение помнит выбор; перенос только в доступный для записи календарь, удаленные события не трогает |
| `VpnVerdict` | VpnVerdictTest | сложение сигналов VPN: любой «да» решает, упавший сигнал не гасит остальные, отказ всех — «VPN нет» (решение закреплено) |
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
| `MediaMirror` (разбор записи), `MediaMirrorSelection` | MediaMirrorRowTest, MediaMirrorSelectionTest | записи листинга: каталоги, записи без типа и без пути отброшены; отбор медиа для автопереноса |
| `ShuttleEntry` (колонки) | ShuttleEntryColumnsTest | запись шаттла -> колонки курсора провайдера, нейтральный отказ оставляет колонки пустыми |
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
| `Utility` (чистые помощники) | UtilityTest | расширение файла (точка в каталоге, регистр для MimeTypeMap до Android 10), коэффициент уменьшения картинки (в том числе нулевой размер), копирование потока, нормализация и чистка списков |
| `WorkServiceBindFailure` | WorkServiceBindFailureTest | классификация сбоев бинда work-сервиса |
| (сборный) | SmokeTest | дымовая проверка окружения тестов |

## Покрыто androidTest-ами

| Модуль | Тест | Что проверяется |
|---|---|---|
| `DummyActivity` (гейт) | RelayGateTest | activity спрашивает `RelayAuthorization`: неподписанная команда не исполняется, подписанная — исполняется |
| `ImpostorScan`, `InstallRestrictionLease` | ProfileGuardsTest | тестовый APK с действием реле найден как подделка (S1); ограничение установки снимается на операцию и возвращается (F1, только в рабочем профиле) |
| `LocalEvents` | LocalEventsTest | доставка на главном потоке, снятый до доставки получатель молчит |
| `CalendarRouting` (провайдер) | CalendarRoutingDeviceTest | событие локального календаря уезжает в Google-календарь, удаленное — нет (только в рабочем профиле) |
| `CrossProfileDocumentsProvider`, `FileShuttleExternalBrowser` | DocumentsProviderTest | A3: корни/листинг/чтение через шаттл; временная выдача корня внешнему проводнику |
| `FileShuttleService` | FileShuttleForegroundTest | foreground-сессия, сроки жизни |
| `ShuttleEntry` (Parcel) | ShuttleEntryParcelTest | запись и типизированный список проходят через Parcel без потерь, пустые поля остаются пустыми |
| `AntiSpyVpnWatchService` | AntiSpyVpnWatchTest | сторож VPN в отдельном процессе |
| `AuthenticationUtility` | AuthenticationBootstrapTest | bootstrap авторизации, ключ не уходит подтвердившей стороне, доказательство ключа |
| `Utility.createLauncherShortcut` | BatchShortcutIconTest | A2: иконка ярлыка — растр, без падения на requestPinShortcut |
| `ProfileForwarder` (разрешение) | ForwarderResolutionTest | разрешение реле, доступа к контенту и создания события в личном календаре |
| `ServiceLiveness` | ServiceLivenessTest | живость binder-ов обоих сервисов |
| передача владения | TransferOwnershipTest | F1: передача владения профилем другому приложению |
| среда | EnvironmentTest, TestProfiles, ImpostorRelayActivity | предусловия и фикстуры профиля |

RelayGateTest, ProfileGuardsTest, LocalEventsTest, CalendarRoutingDeviceTest и
ShuttleEntryParcelTest добавлены 03.11.2026 и на момент записи только
компилируются: в среде, где они написаны, нет аппаратной виртуализации для
эмулятора. Первый прогон — на стенде.

## Сознательно не покрыто (тонкий слой / граница фреймворка)

| Модуль | Почему | Риск |
|---|---|---|
| `MainActivity`, `AppListFragment`, `SettingsFragment`, `SetupWizardActivity`, остальной `DummyActivity` | wiring: binder-вызовы + UI; ветвления выносим в `util/` (C1/C3/C4, гейт реле) | регрессии wiring ловятся ручным прогоном сценариев USER_GUIDE |
| `GatekeeperService`, `KillerService`, `BatchFreezeService`, `FreezeService` | шеллы над AIDL/DPM; решения принимают вызывающие (покрыты) | низкий |
| `AntiSpyManager`, `AntiSpyDummyVpnDisconnector` | системные VPN/activity-сервисы; ядро политик — `AntiSpyWatchPolicy`, решение гейта — `AntiSpyLaunchGate` (покрыты) | средний |
| `WorkProfileBatchFreeze` | DPM-вызовы; решение «морозить ли» — `AutoFreezePolicy`/defaults (покрыты) | низкий |
| `GatekeeperToast`, `Notifications` | UI/системные helper-ы | низкий |
| `Utility` (остальное) | ярлыки, растры, AppOps, пересылка интентов в профиль — обертки над системными сервисами; пересылку проверяет ForwarderResolutionTest, ярлык — BatchShortcutIconTest | низкий |
| `VpnTunnelDetector` (сигналы) | сами сигналы спрашивают NetworkInterface и ConnectivityManager; решение по ним — `VpnVerdict` (покрыт) | низкий |

## Пробелы-цели (по приоритету)

Пять целей прошлого замера закрыты (VPN-сигналы, окно подписи, маршрутизация
календаря, синхронизация настроек, чистые части `Utility`). Остается:

1. Первый прогон новых инструментальных тестов на стенде (см. выше): решения
   под ними покрыты JVM-тестами, но связка с системой ни разу не исполнялась.
2. Повтор подписанного интента в окне 30 с: подпись не одноразовая, и
   подделка, перехватившая интент в «Открыть с помощью», может отправить его
   повторно без изменений. Подмену extras закрывает RelayExtrasSignedTest,
   сам повтор — нет; кандидат — одноразовый номер в подписи.
