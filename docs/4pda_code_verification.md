# Сверка репортов 4PDA с кодом Shelter

Проверка топ-приоритетов из [4pda_compat_matrix.md](4pda_compat_matrix.md) по исходникам `Shelter/`. Дата: 10.08.2026.
Все ссылки -- `file:line` в репозитории `Shelter/`, номера постов -- в `shelter_4pda_posts.json`.

## 0. Состояние репозитория

| Что | Значение |
|---|---|
| Последний коммит с кодом | `831c375`, **2024-11-12** |
| HEAD | `672560f`, 2026-06-02 -- правка URL F-Droid, кода не трогает |
| Тег `1.9.1` (то, что у людей на руках) | `compileSdk 34`, `targetSdkVersion 34` |
| master | `compileSdk 35`, `targetSdkVersion 35`, `minSdkVersion 24` |

Ни релиз, ни master не таргетят Android 16 (SDK 36). Форумное "не обновлялся с 2023" неточно: релиза правда не было с 18.12.2023, но код правился до ноября 2024. `app/build.gradle`.

---

## 1. Установка на MIUI -- ПОДТВЕРЖДЕНО. Известно апстриму, не чинится намеренно

Детект вендора -- `util/Utility.java:249`, метод `isMIUI()`: читает системное свойство `ro.miui.ui.version.name` через `getprop` в дочернем процессе, непустой ответ считает признаком MIUI.

Блокировка клонирования -- `ui/AppListFragment.java:352`:

```java
if (Utility.isMIUI() && !mSelectedApp.isSystem()) {
    // Cannot clone non-system apps on MIUI
```

Текст диалога, `values-ru-rRU/strings.xml:55`:

> Клонирование несистемных приложений в другой профиль в данный момент невозможно на MIUI. Пожалуйста, клонируйте магазин приложений вашей системы (например, Play Маркет) в ваш другой профиль и установите приложения оттуда.

**Обход, который форум "нашел" сам, -- это официальная рекомендация приложения, написанная в том самом диалоге, который пользователи закрывают.** #2122 (клонировать GetApps), #2069 (клонированный Google Play), #2105 (через ГП все ставится) -- три независимых переоткрытия строки 55.

Кнопка "Продолжить в любом случае" ведет в `installOrUninstall(selectedApp, true)` -- ровно тот путь, что виснет. #1312: "нажимаю всё равно продолжить, начинается установка, но она зависает".

Первый запуск, `values-ru-rRU/strings.xml:5`, прямым текстом советует MIUI-пользователям **ВЫЙТИ** и **НЕ ИСПОЛЬЗОВАТЬ** приложение.

**Дыра в защите.** Гард стоит только на клонировании из списка приложений Shelter (`installApp`, `services/ShelterService.java:128`). Установка APK-файла внутрь профиля идет другим путем -- `installApk`, `services/ShelterService.java:168` -> `DummyActivity` -> системный установщик. **Там проверки на MIUI нет вообще.** Это и есть #2105: "любая установка APK внутри рабочего профиля зависает на этапе Установка, либо Установщик пакетов не отвечает". Пользователь не видит ни предупреждения, ни совета -- просто висяк.

**Риск детекта.** `isMIUI()` опирается на `ro.miui.ui.version.name`. Если сборка HyperOS это свойство не выставляет, гард молча выключается и пользователь получает висяк вместо диалога. Выставляет ли его HyperOS 3.x -- **не проверял**, устройства нет.

## 2. Перенос файлов -- ПОДТВЕРЖДЕНО. Не баг, а границы дизайна

Описание фичи, `values-ru-rRU/strings.xml:80` (капс авторский):

> ...вы сможете искать/просматривать/выбирать/копировать файлы в Shelter из рабочего профиля и обратно **ТОЛЬКО через системный менеджер файлов** (он называется "Файлы" или "Документы" на вашем устройстве) **или через приложения, поддерживающие Documents UI**...

Механизм -- `DocumentsProvider`, `util/CrossProfileDocumentsProvider.java:32`, authority `net.typeblog.shelter.documents`. В манифесте он **выключен по умолчанию**: `AndroidManifest.xml:135`, `android:enabled="false"`. Включается только галкой File Shuttle -- `util/SettingsManager.java:54-60`, через `setComponentEnabledSetting`.

Отсюда следствия, которые снимают часть вопросов темы:

* **#2124, #2126 -- "поделиться видит рабочий профиль, но он пустой" -- это ожидаемое поведение, не баг.** Share sheet не поддерживается by design: механизм SAF, а не share-интент. Пользователь неделю искал то, чего нет.
* **#2137 "встроенный проводник в Shelter"** -- это и есть единственный поддерживаемый путь, а не хитрый обход.
* Нужны разрешения **в обоих профилях сразу**: All Files (`values/strings.xml:111`) и Draw over Other Apps (`values/strings.xml:112`). Прозевал один профиль -- фича молча не работает. Это объясняет #2135, где галки стоят "везде", а перенос не идет.

**Чего код не объясняет.** Ошибка Samsung `данное действие запрещено политикой безопасности` (#2135) -- источник **не установлен**. Стектрейса в теме нет, устройства нет. Гипотеза форума про конфликт с Knox (#2136) кодом **не подтверждается и не опровергается**. Вердикт не выношу.

## 2-бис. ANR провайдера при переносе файлов -- ВОСПРОИЗВЕДЕНО НА УСТРОЙСТВЕ

Добавлено 10.08.2026 по результатам прогона на Oppo Find X9 Pro / ColorOS 16 / Android 16, Shelter 1.9.1 (445).

**Симптом:** файлы рабочего профиля не видны в личном. DocumentsUI показывает пусто.

**Что зафиксировано на устройстве.**

Запись ANR в crashbox ColorOS:

```
crashbox_event_type: "anr"
crashbox_exception_name: "ContentProvider Timeout"
crashbox_exception_msg:  "ContentProvider not responding"
crashbox_package_name:   "net.typeblog.shelter"     version 445
crashbox_file_path:      "/data/anr/anr_22954_2026-08-10-12-31-58-888"
```

Реакция DocumentsUI в тот же момент:

```
W DocumentAccess: Couldn't create DocumentInfo for uri:
    content://net.typeblog.shelter.documents/document/%2Fshelter_storage_root%2F
E GetDocumentTask: Cannot find document info for authority:
    net.typeblog.shelter.documents, documentId: /shelter_storage_root/, userId: 0
```

`dumpsys activity services net.typeblog.shelter` в этот момент показывает только `u0 KillerService` и `u12 ShelterService`. **`FileShuttleService` не поднят ни в одном профиле.**

**Причина в коде.** `util/CrossProfileDocumentsProvider.java:56-90`, метод `doBindService()`:

```java
getContext().startActivity(intent);   // просим DummyActivity в другом профиле поднять шаттл

// A hack to convert the asynchronous process of starting service to synchronous
synchronized (mLock) {
    try {
        mLock.wait();                 // без таймаута
    } catch (InterruptedException e) {
        // ???
    }
}
```

Провайдер просит **другой профиль** запустить `FileShuttleService` через `startActivity` и затем блокирует поток на `mLock.wait()` **без таймаута**. Колбэк приходит в `IFileShuttleServiceCallback.Stub` и только он делает `notifyAll()`. Если activity в другом профиле не стартовала или не вызвала колбэк -- поток висит бесконечно. Вызовы `ContentProvider` идут на биндер-потоках, поэтому система бьет ANR.

Хуже: `ensureServiceBound()` вызывается из **всех** методов провайдера -- `:139, 153, 173, 183, 194, 207, 219`. То есть любое обращение DocumentsUI к любому документу уходит в ту же ловушку.

**Причина установлена: Background Activity Launch.** Второй прогон, запуск проводника из самого Shelter (то есть с переднего плана), дал в `logcat -b events`:

```
12:38:40.429 am_wtf: ActivityTaskManager, Background activity launch blocked! goo.gle/android-bal
  callingPackage: net.typeblog.shelter;  callingPackageTargetSdk: 34;  callingUid: 10365
  callingUidHasVisibleActivity: false
  callingUidHasNonAppVisibleWindow: false
  callingUidProcState: BOUND_TOP
  intent: act=net.typeblog.shelter.action.START_FILE_SHUTTLE
          cmp=android/com.android.internal.app.ForwardIntentToManagedProfile
  callerStartMode: MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED
  resultIfPiCreatorAllowsBal: BAL_BLOCK

12:39:00.445 am_anr:       [0,26099,net.typeblog.shelter,ContentProvider not responding]
12:39:00.651 am_kill:      [0,26099,net.typeblog.shelter,0,bg anr,170268]
12:39:00.711 am_proc_died: [0,26099,net.typeblog.shelter]
```

Ровно 20 секунд между блокировкой и ANR -- системный таймаут ContentProvider. Процесс затем убит, поэтому корень Shelter "исчезает" из DocumentsUI.

Провайдер живет в процессе без видимой activity (`callingUidHasVisibleActivity: false`), хотя и привязан к DocumentsUI (`procState: BOUND_TOP`). Запуск activity в другом профиле через `ForwardIntentToManagedProfile` для него -- фоновый, и Android его блокирует.

**Заложенный автором обход больше не работает.** `values/strings.xml:112` объясняет, что разрешение "Поверх других приложений" нужно именно "для запуска служб переноса файлов в фоне". Разрешение выдано в обоих профилях (проверено через `appops`), но в логе `callingUidHasNonAppVisibleWindow: false`: после ужесточения BAL в Android 14-16 исключение требует **реально видимого окна оверлея**, а не факта выданного разрешения. Путь закрыт системно, не прошивкой.

**Усугубляющий дефект кода.** Ожидание без таймаута на биндер-потоке превращает блокировку в гарантированный ANR и убийство процесса вместо внятной ошибки. Минимальная правка -- `mLock.wait(timeout)` с проверкой результата. Полная -- перестать поднимать шаттл из провайдера: устанавливать связь, пока UI Shelter на переднем плане, и держать ее, а провайдеру возвращать ошибку с подсказкой открыть Shelter.

**Рабочая замена есть, и она в самом Shelter.** `Utility.java:191-203` вешает кросс-профильный фильтр на `ACTION_SEND` и `ACTION_SEND_MULTIPLE` с типом `*/*` и флагом `FLAG_PARENT_CAN_ACCESS_MANAGED`.

Внимание на семантику флагов -- она обратна названиям ([коммит AOSP](https://android.googlesource.com/platform/frameworks/base/+/29fae7b39447977c4247bb901721df86d8a92ef8%5E2..29fae7b39447977c4247bb901721df86d8a92ef8/)):

| Флаг | Старое имя | Интент отправлен в | Резолвится в |
|---|---|---|---|
| `FLAG_PARENT_CAN_ACCESS_MANAGED` | `FLAG_TO_PRIMARY_USER` | рабочем | личном |
| `FLAG_MANAGED_CAN_ACCESS_PARENT` | `FLAG_TO_MANAGED_PROFILE` | личном | рабочем |

То есть на `ACTION_SEND` открыто направление **рабочий -> личный**. **Проверено на устройстве 10.08.2026: работает.** Внутри рабочего профиля открыть файл любым приложением, "Поделиться", в списке появляются приложения личного профиля, файл уходит наружу.

Это объясняет и форумную асимметрию: #2124 и #2126 пытались шарить из личного в рабочий и видели пустую вкладку -- для произвольных файлов это направление Shelter не открывает.

**Практический вывод.** Для сценария "вытащить файл из профиля" File Shuttle не нужен вовсе, достаточно share sheet. Сломанный SAF-путь -- отдельная задача, и он единственный, кто дает ANR.

## 3. Краш ярлыка общей заморозки на Android 16 -- ГИПОТЕЗА, не доказано

Оба типа ярлыка идут через одну функцию `util/Utility.java:302 createLauncherShortcut`. Единственная структурная разница -- иконка:

| Ярлык | Иконка | Поведение на A16 (#1706) |
|---|---|---|
| Отдельного приложения | `Icon.createWithBitmap(icon)` -- `ui/AppListFragment.java:523` | создается |
| Общей заморозки | `Icon.createWithResource(this, R.mipmap.ic_freeze)` -- `ui/MainActivity.java:411` | краш |

`mipmap-anydpi-v26/ic_freeze.xml` -- это `<adaptive-icon>` с background и foreground, без monochrome-слоя. То есть в `ShortcutInfo` уходит ресурс adaptive-icon, а в рабочем случае -- готовый растр.

Это единственное отличие двух путей, и оно совпадает с симптомом. **Но это гипотеза.** Стектрейса в теме нет, на A16 не воспроизводил. Для вердикта нужен logcat момента краша. Пока -- кандидат, не причина.

## 4. Побочно: заглушка NFC -- работает не тем, чем ее считают

`services/PaymentStubService.java` -- это `HostApduService`, который на любую команду отвечает `notifyUnhandled()` и `null`. Пустышка. В манифесте `enabled="false"` (`AndroidManifest.xml:164`), включается галкой.

CHANGELOG 1.9 объясняет назначение:

> Added a fake NFC payment service to workaround a bug in Android that prevents payment apps inside the work profile from being used if none is present in the main profile.

То есть это **обход одного конкретного бага Android** -- когда в основном профиле нет ни одного платежного сервиса. Это не общее решение NFC в рабочем профиле. Отсюда разброс в теме: помогает там, где проблема именно в этом (#2076 Honor Magic8 Pro), и не помогает нигде больше (#1555 Redmi Note 11 -- "что с заглушкой, что без", #1766 Asus, #1847 ЮMoney).

## 5. Побочно: потеря профиля (#2067)

`ui/MainActivity.java:142-152`: если `Utility.transferIntentToProfile` кидает `IllegalStateException`, код трактует это как "профиля не существует вовсе", ставит `PREF_HAS_SETUP = false`, показывает `work_profile_not_found` и вызывает `finish()`.

Пути восстановления, сохраняющего существующий профиль, в коде нет -- только пересоздание. Это соответствует #2067, где Shelter требует удалить рабочий профиль и без этого не стартует, а вместе с профилем уезжают все данные приложений.

## 6. Побочно: самозаселение профиля на HyperOS (#1970)

`values/strings.xml:64`, текст предупреждения "Показать все":

> ...this feature can be useful when **faulty vendor-customized ROMs does not enable all necessary system apps in work profile by default**.

Апстрим знает, что вендорские прошивки сами решают, какие системные приложения включить в профиле. #1970 (GetApps и Безопасность появились в профиле сами) -- проявление того же, со стороны вендора, не Shelter.

---

## Итог

| # | Репорт форума | Вердикт по коду |
|---|---|---|
| 1 | Установка на MIUI не работает | **Подтверждено.** Известное ограничение, гард + рекомендация в коде. Гарда нет на пути установки APK внутри профиля -- дыра. |
| 2 | Файлы между профилями не переносятся | **Подтверждено как дизайн.** Только через Documents UI, провайдер выключен по умолчанию, разрешения нужны в обоих профилях. Share sheet не поддерживается вообще. |
| 3 | Краш ярлыка общей заморозки на A16 | **Не доказано.** Есть единственный кандидат -- adaptive-icon через `createWithResource`. Нужен logcat. |
| 4 | NFC-заглушка не помогает | **Объяснено.** Это обход одного частного бага Android, а не решение NFC в профиле. |
| 5 | Профиль в невосстановимом состоянии | **Подтверждено.** Пути восстановления без пересоздания нет. |

Что из этого следует для продукта: пункты 1 и 2 -- не про возможности, а про то, что приложение знает ответ и не доносит его. Пункт 3 -- единственный из топа, где нужен реальный дебаг на устройстве с Android 16.
