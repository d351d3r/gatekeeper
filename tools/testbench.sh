#!/usr/bin/env bash
#
# Стенд для инструментальных тестов zindan.
#
# Работает ТОЛЬКО с эмулятором emulator-5556. Любое другое устройство в списке adb --
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

SERIAL="emulator-5556"
AVD="zindan_a16"
PORT="5556"
PKG="io.gatekeeper"
TEST_PKG="${PKG}.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
CODE_PKG="io.gatekeeper"
ADMIN="${PKG}/${CODE_PKG}.receivers.ShelterDeviceAdminReceiver"
DUMMY="${PKG}/${CODE_PKG}.ui.DummyActivity"
ACTION_FINALIZE="net.typeblog.gatekeeper.action.FINALIZE_PROVISION"
ACTION_SHUTTLE="net.typeblog.gatekeeper.action.START_FILE_SHUTTLE"

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

latest_apk() { (cd "$ROOT" && ls -t Zindan-*-debug.apk 2>/dev/null | head -1); }

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
    if [ $# -gt 0 ]; then
        a shell am instrument --user "$wu" -w -e class "$1" "$TEST_PKG/$RUNNER"
    else
        a shell am instrument --user "$wu" -w "$TEST_PKG/$RUNNER"
    fi
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
  install [apk]      adb -s $SERIAL install -r (по умолчанию свежий Zindan-*-debug.apk)
  profile            поднять рабочий профиль обходным путем, зарегистрировать фильтры
  perms              выдать MANAGE_EXTERNAL_STORAGE и SYSTEM_ALERT_WINDOW в обоих профилях
  files              создать маркерные файлы для проверок File Shuttle
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
