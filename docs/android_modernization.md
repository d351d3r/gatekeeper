# Современный Android и выбор базы для форка

Дата: 10.08.2026. Источники: локальные репозитории `Shelter/`, `zindan/`, `CoreGuard/`, `anubis/` + официальная документация Android (ссылки внизу).

---

## 1. Выбор базы: четыре кандидата

| | Shelter | zindan | CoreGuard | anubis |
|---|---|---|---|---|
| Язык | Java | 136 java + 43 kt | **Kotlin, 100%** | Kotlin, 100% |
| LOC исходников | 5 186 | 29 494 | 4 181 | 8 479 |
| Коммитов | **533** | 21 | **4** | 113 |
| История с | 2018-08-19 | 2026-06-11 | 2026-04-16 | 2026-04-13 |
| Последний коммит кода | 2024-11-12 | 2026-06-23 | 2026-04-24 | 2026-05-11 |
| compileSdk / targetSdk | 35 / 35 (master), 34 / 34 (релиз 1.9.1) | 35 / 35 | **36 / 36** | 34 / 34 |
| minSdk | 24 | 24 | 26 | 29 |
| UI | Views + XML | Views + XML | Views + viewBinding | Views |

Уточнения, которые меняют картину:

**zindan -- это Shelter, а не альтернатива ему.** Пакет так и остался `net.typeblog.shelter`. Из 29 494 LOC подавляющая часть -- вендоренная в репозиторий `libs/SetupWizardLibrary` (та же, что в Shelter). Собственного кода мало, котлинизация частичная -- 43 файла из 179. Форк унаследовал и баги: #2062 на Xiaomi 11t -- "такие же глюки как в Шелтере, автозаморозка в том же состоянии".

**anubis -- другой продукт.** Это не менеджер рабочего профиля. Судя по составу (`StealthVpnService`, `StealthOrchestrator`, `ShizukuManager`, `VpnClientManager`, `StealthTileService`) -- это VPN/stealth-утилита на Shizuku. `FreezeSafetyPolicy` там есть, но профиль он не провижинит. Как базу для менеджера профиля брать нечего.

**CoreGuard -- единственный настоящий Kotlin-кандидат.** И у него принципиально другой набор функций.

## 2. Shelter vs CoreGuard: что умеет каждый

| Возможность | Shelter | CoreGuard |
|---|---|---|
| Провижининг рабочего профиля | да | да |
| **Клонирование приложений в профиль** | да, `PackageInstaller` через `DummyActivity` | **нет** -- только `enableSystemApp` для системных |
| **Перенос файлов между профилями** | да, `CrossProfileDocumentsProvider` | **нет** -- ни провайдера, ни SAF |
| Заморозка | `setApplicationHidden` | `setPackagesSuspended` + `setApplicationHidden` |
| Автозаморозка | по блокировке экрана, `AlarmManager` | **движок триггеров**: сеть, состояние, boot |
| Запрет скриншотов в профиле | нет | `setWorkProfileScreenCaptureDisabled` |
| Управление буфером обмена | нет | `setClipboardSharing` |
| Контакты | блокировка поиска | `setContactsSharing` |
| Intent между профилями | фиксированный набор | `setIntentSharing` |
| Пер-аппные разрешения | нет | `AppPermOverride`, `applyPermissionsForNewApp` |
| Скрытие перечисления пакетов | нет | `denyPackageEnumerationToManagedApps` |
| Сброс VPN в профиле | нет | `clearWorkProfileVpn` |
| Заглушка NFC | `PaymentStubService` | нет |
| Ярлыки разморозки | да | нет |

**Это не конкуренты, а половинки.** Shelter умеет то, чего нет в CoreGuard (клонирование, файлы, ярлыки), CoreGuard -- то, чего нет в Shelter (политики DPM, триггеры, per-app разрешения). Форум это нащупал вслепую: #1726 "шелтер гораздо дружелюбнее и автозаморозка удобнее", #2013 "с шелтер поудобнее, но тут есть др фишки".

**Дью-дилидженс по CoreGuard.** Четыре коммита на всю историю -- значит история сквошена, происхождение кода не проверяемо. Для инструмента, который получает права Profile Owner, это существенно. Отдельно: в `app/build.gradle.kts` в открытом виде лежит пароль релизного keystore (`CoreGuard_store_2026`). Сам `.jks` в репозиторий не закоммичен -- проверил, `git ls-files` пусто -- так что подписать чужую сборку нельзя. Но пароль в VCS означает, что при любой утечке файла ключа подпись скомпрометирована мгновенно. Плюс #1800: тему CoreGuard на 4PDA забанили, дистрибуция только через GitHub.

## 3. Рекомендация

**База -- Shelter. Kotlin -- да, но миграцией, а не переездом на CoreGuard.**

Обоснование:

1. Две главные боли форума -- клонирование приложений и перенос файлов -- в CoreGuard **отсутствуют как функциональность**. Взяв его базой, начинаешь с минуса: надо писать `PackageInstaller`-путь и `DocumentsProvider` с нуля. Это как раз самые нетривиальные куски Shelter.
2. У Shelter 533 коммита и 8 лет истории, у CoreGuard -- 4 коммита. Для кода с правами Profile Owner проверяемое происхождение важнее свежего синтаксиса.
3. Kotlin-интероп с Java полный. Мигрировать 5 186 LOC можно пофайлово, не останавливая работу. zindan это уже начал -- 43 файла -- и его результат можно посмотреть, прежде чем повторять.
4. Ценное в CoreGuard -- не архитектура, а **список DPM-вызовов**, которых нет у Shelter. Их можно перенести по одному, они самодостаточны. Лицензии обоих проверить перед заимствованием (у Shelter -- в `LICENSE`, у CoreGuard -- MIT-подобная, надо читать).

Единственный аргумент за CoreGuard -- `targetSdk 36`. Но это строчка в gradle плюс работа по адаптации, а не причина выбрасывать реализацию.

## 4. Что адаптировать под современный Android

### 4.1 Обязательное при подъеме до targetSdk 36

* **Edge-to-edge без права отказа.** `R.attr#windowOptOutEdgeToEdgeEnforcement` объявлен устаревшим и не действует. Всю верстку надо чинить через insets. У Shelter Views + XML -- значит правки во всех экранах.
* **Predictive back по умолчанию.** `onBackPressed()` больше не вызывается, `KEYCODE_BACK` не доставляется. Временно отключается через `android:enableOnBackInvokedCallback="false"`, но это отсрочка, а не решение.
* **Ориентация игнорируется на экранах от 600dp.** `android:screenOrientation`, `minAspectRatio`, `setRequestedOrientation()` не работают. Планшеты и раскладушки.

### 4.2 Провижининг -- проверить, риск не подтвержден

AOSP пишет: DPC, желающий поддерживать современные схемы, обязан обрабатывать `ACTION_GET_PROVISIONING_MODE` и `ACTION_ADMIN_POLICY_COMPLIANCE`, иначе провижининг падает. Для device owner `ACTION_PROVISION_MANAGED_DEVICE` прямо объявлен устаревшим.

**Ни Shelter, ни CoreGuard, ни zindan этих хендлеров не реализуют** -- проверил grep по манифестам и коду. Shelter идет по пути `ACTION_PROVISIONING_SUCCESSFUL` через `FinalizeActivity` (`AndroidManifest.xml:88-100`), CoreGuard -- голый `ACTION_PROVISION_MANAGED_PROFILE` (`ProfileManager.kt:42`).

При этом эмпирически профиль поднимается на Android 15 и 16 -- по репортам форума. То есть для DPC-first BYOD work profile старый путь пока живой. **Вердикт не выношу**: нужно проверить на Android 16 руками. Если сломается -- это блокер уровня "приложение не запускается вообще".

Отдельно из Android 16: `EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS` объявлен устаревшим и игнорируется, экраны обучения убраны из мастера.

### 4.3 Private Space -- закрытая дверь

Форум спрашивал дважды (#1661, #1785) и не получил ответа. Ответ такой:

* **API для третьих лиц нет.** Приложение не может ни создать Private Space, ни положить туда программу. Единственный поддерживаемый путь -- пользовательский: кнопка "Install Apps" внутри Private Space кидает неявный интент, поймать который может только приложение с `<category android:name="android.intent.category.APP_MARKET" />`.
* Тип профиля -- `android.os.usertype.profile.PRIVATE`, константа `UserManager.USER_TYPE_PROFILE_PRIVATE` (Android 15).
* При блокировке пространства **все процессы внутри убиваются немедленно**, включая foreground-сервисы. API для обнаружения этого события нет, предупредить пользователя нельзя.
* Управлять можно только запретительно и только с правами DPC: `UserManager.DISALLOW_ADD_PRIVATE_PROFILE`.
* **Важное для нас:** в Android 16 QPR2 появился перенос файлов **внутрь** Private Space -- кнопка "Add files", системный файловый пикер. То есть Google закрыл ровно ту боль, что мучает пользователей Shelter, но только для своего механизма, не для work profile.

Вывод: Private Space -- не замена и не платформа для нас. Это конкурент, у которого есть то, чего у нас нет, и нет того, что есть у нас (клонирование, заморозка, автоматизация).

### 4.4 Cross-profile: чего не будет

`INTERACT_ACROSS_PROFILES` -- **restricted permission за одобрением Android Enterprise через Early Access Program**. Критерии включают популярность приложения и категорию. FOSS-форк утилиты изоляции это одобрение не получит.

Что доступно без разрешения: `CrossProfileApps` умеет запустить главную activity **того же приложения** в другом профиле. Проверки -- `canInteractAcrossProfiles()`, `canRequestInteractAcrossProfiles()` (API 30+). `CrossProfileApps.startActivity()` уже требует разрешения.

Connected Apps SDK (`@CrossProfile`, `@CrossProfileProvider`) -- построен поверх того же разрешения. Не наш путь.

**Практический вывод:** `DocumentsProvider` -- не костыль Shelter, а единственный доступный третьей стороне механизм обмена файлами между профилями. Значит вкладываться надо не в поиск обходного пути, а в обнаруживаемость и надежность существующего.

### 4.5 NFC в Android 16 -- надежды не оправдались

Android 16 добавил `NfcAdapter.enable()` / `disable()` и `UserManager.DISALLOW_CHANGE_NEAR_FIELD_COMMUNICATION_RADIO`. Но для кастомных DPC эти API **ограничены Device Owner и Profile Owner корпоративных устройств**. BYOD-профиль, которым и является Shelter, их использовать не может.

То есть 71 пост форума про неработающий NFC новыми API не закрывается. Остается `PaymentStubService` -- обход одного частного бага, и разброс по прошивкам (#2076 Honor работает, #1555 Redmi нет).

### 4.6 Иконки и ярлыки

Моя гипотеза про краш ярлыка общей заморозки на A16 (см. [4pda_code_verification.md](4pda_code_verification.md)) документацией **не подтверждается**. Известная проблема с `Icon.createWithResource()` в динамических ярлыках -- визуальная: лаунчеры OEM применяют свою нормализацию, иконка выходит зажатой или с двойной маской. AOSP рекомендует `Icon.createWithAdaptiveBitmap()`. Про краш ничего нет. Гипотеза ослабла, logcat по-прежнему нужен.

Отдельно: с **Android 16 QPR2 система сама темизирует иконки** приложений, которые не дали свою. У `ic_freeze` monochrome-слоя нет (`mipmap-anydpi-v26/ic_freeze.xml` -- только background и foreground). Стоит добавить.

### 4.7 Прочее из Android 16, что можно взять

* `DISALLOW_THREAD_NETWORK` -- запрет Thread-сетей.
* `DevicePolicyManager.setAppFunctionsPolicy()` -- контроль App Functions, механизма межприложенческой оркестрации. Бета, API меняется. Потенциально важно: это новый канал утечки между приложениями, и его стоит уметь резать в профиле.
* `setAutoTimePolicy()` / `setAutoTimeZonePolicy()` -- только DO и COPE, нам недоступно.
* Квоты фоновых задач пересчитаны по standby bucket -- касается автозаморозки на `AlarmManager`.

### 4.8 Заморозка: скрыть или приостановить

Shelter использует `setApplicationHidden`, CoreGuard -- `setPackagesSuspended`. Семантика разная: скрытое приложение исчезает из лаунчера, приостановленное остается видимым и при запуске показывает системный диалог. Для #2091 (AIMP продолжал играть после заморозки) разница может быть принципиальной -- но без воспроизведения это догадка, не вывод.

## 5. Что из этого закрывает боли форума

| Боль | Число постов | Закрывается? |
|---|---|---|
| VPN виден из профиля (`tun0`) | 129 | Нет. Стена платформы. |
| NFC из профиля | 71 | Нет. Новые API только для DO/COPE. |
| Файлы между профилями | 46 | Частично. Механизм есть и он единственно возможный -- чинить обнаруживаемость и Samsung-кейс. |
| Ярлыки | 45 | Да, после дебага на A16 + monochrome-слой. |
| Установка APK на MIUI | 41 | Частично. Гард на путь `installApk`, внятная подсказка вместо висяка. |
| Автозаморозка | 21 | Да. Движок триггеров из CoreGuard + пересчет квот A16. |
| Скриншот не выходит из профиля | -- | Да, тем же `DocumentsProvider`. Плюс `setWorkProfileScreenCaptureDisabled` из CoreGuard как отдельная фича. |

## 6. Открытые вопросы -- нужны твои ответы

Прежде чем planning, три развилки, где я не могу решить за тебя:

1. **Цель продукта.** Изоляция госсофта от данных (тогда приоритет -- политики DPM, буфер, контакты, перечисление пакетов) или удобная песочница-морозилка (тогда приоритет -- клонирование, ярлыки, файлы, UX)? Матрица болей форума тянет в разные стороны: 129 постов про VPN -- это первая цель, 46 про файлы -- вторая.
2. **Целевой парк.** Xiaomi/HyperOS -- 21 жалоба из 82, это главный источник боли и одновременно единственная платформа, где апстрим официально капитулировал. Вкладываться в обход MIUI или честно объявить неподдерживаемой?
3. **Минимальная версия.** Shelter держит minSdk 24 (Android 7). CoreGuard -- 26, anubis -- 29. Поднятие минимума разом снимает пласт legacy-кода, но отрезает часть парка. У тебя есть данные, на чем реально сидят пользователи?

Отдельно: пункт 4.2 (провижининг на Android 16) стоит проверить до всякого планирования. Если старый путь отвалился -- это меняет объем работ целиком.

---

## Источники

* [What's new for enterprise in Android 16](https://developer.android.com/work/versions/android-16)
* [What's new for enterprise in Android 15](https://developer.android.com/work/versions/android-15)
* [Behavior changes: apps targeting Android 16](https://developer.android.com/about/versions/16/behavior-changes-16)
* [Provision for device management (AOSP)](https://source.android.com/docs/devices/admin/provision)
* [Private space (AOSP)](https://source.android.com/docs/security/features/private-space)
* [Connected work & personal apps](https://developers.google.com/android/work/connected-apps/connected-apps)
* [Work profiles (Android Enterprise)](https://developer.android.com/work/managed-profiles)
* [New in Android 16 for enterprise -- Jason Bayton](https://bayton.org/android/android-16-enterprise-features/)
* [Adaptive icons (AOSP)](https://source.android.com/docs/core/display/adaptive-icons)
