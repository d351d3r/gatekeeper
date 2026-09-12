#!/usr/bin/env bash
#
# Стенд для инструментальных тестов Gatekeeper.
#
# По умолчанию эмулятор emulator-5556; серия и порт переопределяются переменными
# TESTBENCH_SERIAL / TESTBENCH_PORT. Любое другое устройство в списке adb --
# аварийный выход: к adb пользователя может быть подключен боевой телефон с рабочим
# профилем, снос которого необратим. Скрипт никогда не вызывает adb connect/disconnect/
# kill-server; единственный adb без -s -- "adb devices" в самой проверке.
#
# Штатный провижининг рабочего профиля на образе google_apis не проходит: ManagedProvisioning
# требует сетевого обновления role holder, а приложение не передает
# EXTRA_PROVISIONING_ALLOW_OFFLINE. Профиль поднимается обходным путем (см. cmd profile),
# протокол -- .ai/crossprofile-fixes/verdict-phase1.md, раздел 1.
#
set -euo pipefail

SERIAL="${TESTBENCH_SERIAL:-emulator-5556}"
AVD="gatekeeper_a16"
PORT="${TESTBENCH_PORT:-5556}"
PKG="io.gatekeeper"
TEST_PKG="${PKG}.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
CODE_PKG="io.gatekeeper"
ADMIN="${PKG}/${CODE_PKG}.receivers.GatekeeperDeviceAdminReceiver"
DUMMY="${PKG}/${CODE_PKG}.ui.DummyActivity"
ACTION_FINALIZE="io.gatekeeper.action.FINALIZE_PROVISION"
ACTION_SHUTTLE="io.gatekeeper.action.START_FILE_SHUTTLE"

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
: "${ANDROID_HOME:=/opt/homebrew/share/android-commandlinetools}"
export JAVA_HOME ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

ADB="$ANDROID_HOME/platform-tools/adb"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

die() { echo "testbench: $*" >&2; exit 1; }
say() { echo "== $*"; }

# Единственная проверка безопасности, от которой зависит все остальное.
guard_no_foreign() {
    [ -x "$ADB" ] || die "нет adb: $ADB"
    local foreign
    foreign="$("$ADB" devices | tr -d '\r' | awk -v s="$SERIAL" 'NR>1 && NF>=2 && $1!=s {print $1}')"
    [ -z "$foreign" ] || die "к adb подключено постороннее устройство: $foreign
Отключать его сам не буду. Спросите пользователя."
}

guard() {
    guard_no_foreign
    "$ADB" devices | tr -d '\r' | grep -q "^${SERIAL}[[:space:]][[:space:]]*device$" \
        || die "$SERIAL не подключен или не в состоянии device. Запустите: $0 boot"
}

a() { "$ADB" -s "$SERIAL" "$@"; }

gradlew() { (cd "$ROOT" && ./gradlew "$@"); }

# Id рабочего профиля по флагу FLAG_MANAGED_PROFILE (0x20); пусто, если профиля нет.
work_user() {
    local line id flags
    while IFS= read -r line; do
        case "$line" in *UserInfo\{*) ;; *) continue ;; esac
        id="${line#*UserInfo\{}"; id="${id%%:*}"
        flags="${line##*:}"; flags="${flags%%\}*}"
        case "$flags" in *[!0-9a-fA-F]*|"") continue ;; esac
        if [ $(( 0x$flags & 0x20 )) -ne 0 ]; then echo "$id"; return 0; fi
    done < <(a shell pm list users | tr -d '\r')
    return 1
}

require_work_user() {
    local wu
    wu="$(work_user || true)"
    [ -n "$wu" ] || die "рабочего профиля нет. Запустите: $0 profile"
    echo "$wu"
}

latest_apk() { (cd "$ROOT" && ls -t Gatekeeper-*-debug.apk 2>/dev/null | head -1); }

test_apk() {
    ls -t "$ROOT"/app/build/outputs/apk/androidTest/debug/*.apk 2>/dev/null | head -1
}

cmd_check() {
    guard
    say "adb видит только $SERIAL"
    a shell getprop ro.build.version.release
    a shell pm list features | tr -d '\r' | grep -q android.software.managed_users \
        || die "образ без android.software.managed_users -- рабочий профиль не поднять"
    say "образ пригоден (managed_users есть)"
}

cmd_boot() {
    guard_no_foreign
    if "$ADB" devices | tr -d '\r' | grep -q "^${SERIAL}[[:space:]][[:space:]]*device$"; then
        say "$SERIAL уже запущен"
        return
    fi
    say "запуск AVD $AVD на порту $PORT"
    nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -port "$PORT" \
        -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
        > "/tmp/emulator-$PORT.log" 2>&1 &
    a wait-for-device
    a shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'
    for s in window_animation_scale transition_animation_scale animator_duration_scale; do
        a shell settings put global "$s" 0
    done
    guard
    say "загружен"
}

cmd_build() {
    say "assembleDebug (инкрементит VERSION_CODE в version.properties)"
    gradlew assembleDebug
    say "APK: $(latest_apk)"
}

cmd_install() {
    guard
    local apk="${1:-}"
    [ -n "$apk" ] || apk="$ROOT/$(latest_apk)"
    [ -f "$apk" ] || die "нет APK: $apk. Запустите: $0 build"
    say "установка $apk"
    a install -r "$apk"
}

# Обходной провижининг. Идемпотентен: повторный запуск на поднятом профиле только
# перерегистрирует кросс-профильные фильтры.
cmd_profile() {
    guard
    local wu
    wu="$(work_user || true)"
    if [ -z "$wu" ]; then
        say "создание рабочего профиля"
        a shell pm create-user --profileOf 0 --managed work
        wu="$(require_work_user)"
    fi
    say "рабочий профиль: user $wu"
    a shell am start-user "$wu" > /dev/null
    a shell pm install-existing --user "$wu" "$PKG" > /dev/null
    a shell dpm set-profile-owner --user "$wu" "$ADMIN" > /dev/null 2>&1 \
        || say "владелец профиля уже установлен"
    say "регистрация кросс-профильных фильтров"
    a shell am start --user "$wu" -a "$ACTION_FINALIZE" -n "$DUMMY" > /dev/null
    a shell am start --user 0 -a "$ACTION_FINALIZE" -n "$DUMMY" > /dev/null
    sleep 2
    cmd_forwarders "$ACTION_SHUTTLE"
}

cmd_perms() {
    guard
    local wu
    wu="$(require_work_user)"
    for u in 0 "$wu"; do
        a shell appops set --user "$u" "$PKG" MANAGE_EXTERNAL_STORAGE allow
        a shell appops set --user "$u" "$PKG" SYSTEM_ALERT_WINDOW allow
    done
    say "MANAGE_EXTERNAL_STORAGE и SYSTEM_ALERT_WINDOW выданы в user 0 и user $wu"
    say "галка File Shuttle включается через UI: меню -> Settings -> File Shuttle"
}

# Маркерные файлы для проверок File Shuttle. /storage/emulated/<work> недоступен
# даже из root-шелла (FUSE, чужой namespace), поэтому пишем в /data/media/<work>.
cmd_files() {
    guard
    local wu
    wu="$(require_work_user)"
    a root > /dev/null
    a wait-for-device
    a shell "mkdir -p /data/media/$wu/Download; \
        echo 'hello from work profile' > /data/media/$wu/Download/work_marker.txt; \
        chown -R media_rw:media_rw /data/media/$wu/Download"
    a shell "echo 'hello from personal profile' > /data/media/0/Download/personal_marker.txt"
    a unroot > /dev/null
    a wait-for-device
    say "маркеры: /data/media/$wu/Download/work_marker.txt, /data/media/0/Download/personal_marker.txt"
}

cmd_up() {
    cmd_boot
    cmd_check
    cmd_build
    cmd_install
    cmd_profile
    cmd_perms
    cmd_files
    cmd_status
}

# Проверка календаря рабочего профиля (сценарий: приложение профиля добавляет событие).
# Шелл без root в work profile не пускает (дефолтный рестрикшэн no_debugging_features,
# см. .ai/crossprofile-fixes/plan.md, фаза 5), поэтому шаг работает под adb root.
#
# Что проверяет:
#   1. CalendarProvider профиля доступен на запись как обычному приложению с
#      WRITE_CALENDAR: создается тестовый календарь-приемник, в него пишется
#      событие, событие читается обратно.
#   2. Изоляция: события профиля не видны из user 0.
#   3. Резолв ACTION_INSERT vnd.android.cursor.item/event внутри профиля
#      (куда попадет intent от приложения профиля).
#
# Google-аккаунта на стенде нет, поэтому UI-путь Google Calendar ("No calendars
# have been synchronized with this device yet") здесь не проверяется: он зависит
# от аккаунта в профиле, а не от Gatekeeper. Артефакты проверки 2026-09-12 --
# скриншоты .ai/crossprofile-fixes/screenshots/gk-*.png.
CAL_ACCOUNT="gk.testbench@local"
CAL_TYPE="gk.testbench.local"
# Глобальные, а не локальные: RETURN-трап в bash 3.2 (штатный на macOS) срабатывает
# уже после разбора локального скоупа функции -- с локалами он видел бы unbound.
CAL_WU=""
CAL_TITLE=""
CAL_BID=""

cleanup() {
    trap - RETURN
    [ -n "$CAL_BID" ] || return 0
    a shell "content delete --user $CAL_WU --uri \
        \"content://com.android.calendar/events?caller_is_syncadapter=true&account_name=$CAL_ACCOUNT&account_type=$CAL_TYPE\" \
        --where \"title='$CAL_TITLE'\"" > /dev/null 2>&1 || true
    a shell "content delete --user $CAL_WU --uri \
        \"content://com.android.calendar/calendars/$CAL_BID?caller_is_syncadapter=true&account_name=$CAL_ACCOUNT&account_type=$CAL_TYPE\"" \
        > /dev/null 2>&1 || true
    CAL_BID=""
    # unroot только здесь: уборка выше требует root (no_debugging_features),
    # а RETURN-трап срабатывает после тела cmd_calendar, включая ее бывший unroot.
    a unroot > /dev/null 2>&1 || true
    a wait-for-device > /dev/null 2>&1 || true
}

cmd_calendar() {
    guard
    local rc=0
    CAL_WU="$(require_work_user)"
    CAL_TITLE="GK TestBench $(date +%s)"
    local title="$CAL_TITLE" wu="$CAL_WU"
    say "рабочий профиль: user $wu, маркер события: $title"
    a root > /dev/null
    a wait-for-device
    for u in 0 "$wu"; do
        a shell pm grant --user "$u" com.android.shell android.permission.WRITE_CALENDAR > /dev/null
        a shell pm grant --user "$u" com.android.shell android.permission.READ_CALENDAR > /dev/null
    done

    # Провайдер требует caller_is_syncadapter в query и аккаунт ОДНОВРЕМЕННО
    # в query-параметрах и в values (иначе "Sync adapters must specify an account").
    a shell "content insert --user $wu --uri \
        \"content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=$CAL_ACCOUNT&account_type=$CAL_TYPE\" \
        --bind account_name:s:$CAL_ACCOUNT --bind account_type:s:$CAL_TYPE \
        --bind name:s:GK_TestBench --bind calendar_displayName:s:GK_TestBench \
        --bind calendar_color:i:3046706 --bind calendar_access_level:i:700 \
        --bind ownerAccount:s:$CAL_ACCOUNT --bind sync_events:i:1 --bind visible:i:1" \
        > /dev/null || { a unroot > /dev/null 2>&1; die "не удалось создать тестовый календарь в user $wu"; }
    CAL_BID="$(a shell "content query --user $wu --uri content://com.android.calendar/calendars \
        --projection _id --where \"calendar_displayName='GK_TestBench'\"" | tr -d '\r' \
        | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | head -1)"
    [ -n "$CAL_BID" ] || { a unroot > /dev/null 2>&1; die "тестовый календарь не читается из user $wu"; }
    say "тестовый календарь: _id=$CAL_BID"

    # Гарантированная уборка тестовых данных даже при падении проверок ниже.
    trap cleanup RETURN

    fail() { say "FAIL: $*"; rc=1; }

    # 1. Запись события от имени приложения профиля (WRITE_CALENDAR, без sync-adapter).
    a shell "B=\$(date +%s); content insert --user $wu --uri content://com.android.calendar/events \
        --bind calendar_id:i:$CAL_BID --bind title:s:\"$title\" \
        --bind dtstart:l:\${B}000 --bind dtend:l:\$((B+3600))000 --bind eventTimezone:s:UTC" \
        > /dev/null || fail "вставка события в user $wu не удалась"

    # 2. Чтение обратно из профиля.
    a shell "content query --user $wu --uri content://com.android.calendar/events \
        --projection _id:title --where \"title='$title'\"" | tr -d '\r' | grep -q "$title" \
        || fail "событие '$title' не читается из user $wu"
    say "событие записано и прочитано из user $wu"

    # 3. Изоляция: в user 0 маркера быть не должно.
    if a shell "content query --user 0 --uri content://com.android.calendar/events \
        --projection _id:title --where \"title='$title'\"" 2>/dev/null | tr -d '\r' | grep -q "$title"; then
        fail "событие профиля видно из user 0 (утечка данных!)"
    else
        say "изоляция соблюдена: в user 0 события нет"
    fi

    # 4. Куда резолвится ACTION_INSERT события внутри профиля.
    if a shell "cmd package query-activities --user $wu \
        -a android.intent.action.INSERT -t vnd.android.cursor.item/event" | tr -d '\r' | grep -q "packageName="; then
        say "ACTION_INSERT .../event резолвится в user $wu:"
        a shell "cmd package query-activities --user $wu \
            -a android.intent.action.INSERT -t vnd.android.cursor.item/event" | tr -d '\r' | grep -E "packageName=" | head -3
    else
        fail "ACTION_INSERT .../event не резолвится ни в одно приложение user $wu"
    fi

    [ "$rc" -eq 0 ] && say "calendar: OK" || { cleanup; die "calendar: есть падения"; }
}

cmd_unit() {
    say "JVM-тесты"
    gradlew testDebugUnitTest
}

# Инструментальные тесты в личном профиле (user 0).
# ANDROID_SERIAL обязателен: без него gradle возьмет первое попавшееся устройство.
cmd_instr() {
    guard
    cmd_profile
    say "инструментальные тесты, user 0"
    if [ $# -gt 0 ]; then
        ANDROID_SERIAL="$SERIAL" gradlew connectedDebugAndroidTest \
            "-Pandroid.testInstrumentationRunnerArguments.class=$1"
    else
        ANDROID_SERIAL="$SERIAL" gradlew connectedDebugAndroidTest
    fi
}

# Инструментальные тесты внутри рабочего профиля. Gradle туда ставить не умеет,
# поэтому APK ставятся через adb, а прогон запускается am instrument --user.
cmd_instr_work() {
    guard
    local wu apk tapk
    wu="$(require_work_user)"
    gradlew assembleDebug assembleDebugAndroidTest
    apk="$ROOT/$(latest_apk)"
    tapk="$(test_apk)"
    [ -f "$tapk" ] || die "нет androidTest APK"
    a install -r "$apk"
    a install -r "$tapk"
    a shell pm install-existing --user "$wu" "$PKG" > /dev/null
    a shell pm install-existing --user "$wu" "$TEST_PKG" > /dev/null
    cmd_profile
    say "инструментальные тесты, user $wu"
    local result
    set +e
    if [ $# -gt 0 ]; then
        a shell am instrument --user "$wu" -w -e class "$1" "$TEST_PKG/$RUNNER"
    else
        a shell am instrument --user "$wu" -w "$TEST_PKG/$RUNNER"
    fi
    result=$?
    set -e
    # androidTest добавляет тестовый пакет с намеренным перехватчиком START_SERVICE.
    # Он нужен только на время проверки: оставленный в профиле, он вызывает системный chooser.
    a shell pm uninstall --user 0 "$TEST_PKG" > /dev/null || true
    a shell pm uninstall --user "$wu" "$TEST_PKG" > /dev/null || true
    return "$result"
}

# Сценарий "обновление поверх", шаг 1: базовая сборка и поднятый на ней профиль.
# -d разрешает откат версии: version.properties инкрементится на каждом assemble.
cmd_upgrade_base() {
    guard
    local apk="${1:-}"
    [ -f "$apk" ] || die "укажите APK базовой сборки: $0 upgrade-base <apk>"
    say "установка базовой сборки $apk"
    a install -r -d "$apk"
    cmd_profile
    cmd_status
    say "теперь выполните на базовой сборке ручные шаги сценария (ярлыки, настройки),"
    say "затем: $0 upgrade-over <новый apk>"
}

# Шаг 2: новая сборка поверх, без сноса данных и без переподнятия профиля.
cmd_upgrade_over() {
    guard
    local apk="${1:-}"
    [ -f "$apk" ] || die "укажите APK новой сборки: $0 upgrade-over <apk>"
    say "версия до обновления"
    a shell dumpsys package "$PKG" | tr -d '\r' | grep -E "versionCode=|versionName=" | head -2
    a install -r "$apk"
    say "версия после обновления"
    a shell dumpsys package "$PKG" | tr -d '\r' | grep -E "versionCode=|versionName=" | head -2
    cmd_status
}

cmd_forwarders() {
    guard
    local action="${1:-$ACTION_SHUTTLE}"
    say "кандидаты на $action (user 0)"
    a shell cmd package query-activities --user 0 -a "$action" | tr -d '\r' \
        | grep -E "packageName=|name=" | head -10
}

cmd_status() {
    guard
    say "пользователи"
    a shell pm list users | tr -d '\r'
    say "пакет"
    a shell dumpsys package "$PKG" | tr -d '\r' | grep -E "versionCode=|versionName=" | head -2
    say "владелец профиля"
    a shell dumpsys device_policy | tr -d '\r' | grep -iA2 "Profile Owner" | head -8
}

cmd_reset() {
    guard
    local wu
    wu="$(work_user || echo "")"
    a shell input keyevent KEYCODE_HOME
    a shell am force-stop --user 0 "$PKG"
    [ -z "$wu" ] || a shell am force-stop --user "$wu" "$PKG"
    a shell am force-stop --user 0 com.google.android.documentsui
    say "процессы остановлены"
}

usage() {
    cat <<EOF
$0 <команда>

  check              проверить, что подключен только $SERIAL и образ пригоден
  boot               запустить AVD $AVD на порту $PORT и дождаться загрузки
  build              ./gradlew assembleDebug
  install [apk]      adb -s $SERIAL install -r (по умолчанию свежий Gatekeeper-*-debug.apk)
  profile            поднять рабочий профиль обходным путем, зарегистрировать фильтры
  perms              выдать MANAGE_EXTERNAL_STORAGE и SYSTEM_ALERT_WINDOW в обоих профилях
  files              создать маркерные файлы для проверок File Shuttle
  calendar           проверка календаря профиля: запись события, изоляция, резолв INSERT
  up                 boot + check + build + install + profile + perms + files + status
  unit               ./gradlew testDebugUnitTest
  instr [class]      инструментальные тесты в личном профиле (ANDROID_SERIAL=$SERIAL)
  instr-work [class] инструментальные тесты внутри рабочего профиля (am instrument --user)
  upgrade-base <apk> сценарий "обновление поверх", шаг 1: базовая сборка + профиль
  upgrade-over <apk> шаг 2: новая сборка поверх, с печатью версий до и после
  forwarders [action] показать кандидатов кросс-профильного реле
  status             пользователи, версия пакета, владелец профиля
  reset              остановить приложение в обоих профилях и DocumentsUI

Запрещено и в скрипте отсутствует: adb connect, adb disconnect, adb kill-server,
./gradlew installDebug, connectedAndroidTest без ANDROID_SERIAL. Единственный adb без -s --
"adb devices" в проверке безопасности, он ничего не меняет.
EOF
}

main() {
    local cmd="${1:-}"
    [ $# -gt 0 ] && shift || true
    case "$cmd" in
        check) cmd_check ;;
        boot) cmd_boot ;;
        build) cmd_build ;;
        install) cmd_install "$@" ;;
        profile) cmd_profile ;;
        perms) cmd_perms ;;
        files) cmd_files ;;
        calendar) cmd_calendar ;;
        up) cmd_up ;;
        unit) cmd_unit ;;
        instr) cmd_instr "$@" ;;
        instr-work) cmd_instr_work "$@" ;;
        upgrade-base) cmd_upgrade_base "$@" ;;
        upgrade-over) cmd_upgrade_over "$@" ;;
        forwarders) cmd_forwarders "$@" ;;
        status) cmd_status ;;
        reset) cmd_reset ;;
        ""|-h|--help|help) usage ;;
        *) usage; exit 1 ;;
    esac
}

main "$@"
