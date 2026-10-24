#!/usr/bin/env bash
# Boots an Android emulator manually (no third-party runner action) and runs
# the connected E2E test. Every phase is logged to e2e-logs/ and published to
# refs/heads/diagnostics/e2e on exit (trap), so progress and the exact
# failing phase are visible even when CI logs/artifacts are unreachable.
# Environment knobs (used by the exec-model gate, which needs no repository
# access and runs a single measurement test):
#   E2E_TEST_TASK   gradle task to run (default :app:connectedDebugAndroidTest)
#   E2E_TEST_CLASS  optional instrumentation class filter
#   E2E_LEAN=1      skip the connectivity wait (no package downloads needed)
cd "$(dirname "$0")/.." || exit 1

E2E_TEST_TASK="${E2E_TEST_TASK:-:app:connectedDebugAndroidTest}"
E2E_TEST_CLASS="${E2E_TEST_CLASS:-}"
# Extra Gradle properties the variant needs (e.g. -PharnessTargetSdk=34), applied
# to the test invocation as well: a second Gradle call must resolve the same
# test build type, or the variant's connected task does not exist.
E2E_GRADLE_PROPS="${E2E_GRADLE_PROPS:-}"

mkdir -p e2e-logs diagnostics
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
EMU_BIN="$SDK/emulator/emulator"
SDKMANAGER="$(command -v sdkmanager || echo "$SDK/cmdline-tools/latest/bin/sdkmanager")"
AVDMANAGER="$(command -v avdmanager || echo "$SDK/cmdline-tools/latest/bin/avdmanager")"
ADB="$(command -v adb || echo "$SDK/platform-tools/adb")"
PHASE="init"
EMU_PID=""

publish() {
  "$ADB" pull /sdcard/Android/data/io.mcyber12.codingharness/files/setup-e2e-report.txt ./setup-e2e-report.txt >/dev/null 2>&1 || true
  "$ADB" shell run-as io.mcyber12.codingharness cat files/coding-harness/rootfs/data/data/com.termux/files/usr/var/log/harness-command.log > ./harness-command-live.log 2>/dev/null || true
  tail -c 20000 ./harness-command-live.log > diagnostics/harness-command-tail.log 2>/dev/null || true
  cp ./setup-e2e-report.txt diagnostics/setup-e2e-report.txt 2>/dev/null || echo "no setup-e2e-report.txt yet" > diagnostics/setup-e2e-report.txt
  rm -rf diagnostics/e2e-logs
  cp -r e2e-logs diagnostics/e2e-logs 2>/dev/null || true
  for x in app/build/outputs/androidTest-results/connected/*.xml; do
    [ -f "$x" ] && cp "$x" diagnostics/
  done
  date -u +"e2e phase=$PHASE %Y-%m-%dT%H:%M:%SZ run=${GITHUB_RUN_ID:-local}" > diagnostics/e2e-status.txt
  git add diagnostics/ >/dev/null 2>&1 || return 0
  git diff --cached --quiet >/dev/null 2>&1 && return 0
  git -c user.email=ci@invalid -c user.name=ci-e2e commit -qm "e2e $PHASE ${GITHUB_RUN_ID:-local} [ci]" >/dev/null 2>&1 || return 0
  git push origin "HEAD:refs/heads/diagnostics/e2e-${GITHUB_RUN_ID:-local}-sdk${E2E_TARGET_SDK:-any}" --force >/dev/null 2>&1 || true
  git reset --soft HEAD~1 >/dev/null 2>&1 || true
  git restore --staged diagnostics >/dev/null 2>&1 || true
}

phase() {
  PHASE="$1"
  echo "[$(date -u +%H:%M:%S)] phase=$PHASE" | tee -a e2e-logs/phases.log
  publish
}

cleanup() {
  [ -n "$EMU_PID" ] && kill "$EMU_PID" >/dev/null 2>&1
  publish
}
trap cleanup EXIT

( while true; do sleep 300; publish; done ) &
PUBLISHER_PID=$!

phase "install-system-image"
echo "SDK=$SDK"; ls "$SDK" >> e2e-logs/phases.log 2>&1
# The image API is chosen by the caller: the sealed build is measured both on
# the API level it targets and on a newer one, because the exec policy is a
# property of the device, not of the manifest alone.
SYSTEM_IMAGE="${E2E_SYSTEM_IMAGE:-system-images;android-34;aosp_atd;x86_64}"
yes | "$SDKMANAGER" --licenses > e2e-logs/licenses.log 2>&1 || true
"$SDKMANAGER" "emulator" "$SYSTEM_IMAGE" > e2e-logs/sysimg.log 2>&1
if [ $? -ne 0 ]; then
  # Not every API level publishes an aosp_atd image; google_atd is the sibling
  # flavour. Record the substitution so the result cannot be misread.
  FALLBACK="${SYSTEM_IMAGE/;aosp_atd;/;google_atd;}"
  if [ "$FALLBACK" != "$SYSTEM_IMAGE" ]; then
    echo "falling back to $FALLBACK" >> e2e-logs/sysimg.log
    "$SDKMANAGER" "emulator" "$FALLBACK" >> e2e-logs/sysimg.log 2>&1 \
      && SYSTEM_IMAGE="$FALLBACK" && echo "E2E_SYSTEM_IMAGE=$SYSTEM_IMAGE" >> e2e-logs/phases.log
  fi
fi
if [ ! -d "$SDK/system-images" ] || ! "$SDKMANAGER" --list_installed 2>/dev/null | grep -q "${SYSTEM_IMAGE#system-images;}"; then
  phase "system-image-failed"; tail -50 e2e-logs/sysimg.log; exit 1
fi

phase "create-avd"
# avdmanager and emulator must agree on the AVD home; newer cmdline-tools
# otherwise create under a location emulator does not search
# ("Unknown AVD name [e2e]").
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
mkdir -p "$ANDROID_AVD_HOME"
echo "ANDROID_AVD_HOME=$ANDROID_AVD_HOME" >> e2e-logs/phases.log
echo no | "$AVDMANAGER" create avd -n e2e -k "$SYSTEM_IMAGE" -d pixel_6 --force > e2e-logs/avd.log 2>&1
# The full coding toolchain (clang/llvm, rust-free) unpacks several GB;
# the pixel_6 default data partition can fill up mid-install (ENOSPC
# leaves dpkg half-configured and straggler binaries missing).
sed -i 's/^disk.dataPartition.size=.*/disk.dataPartition.size=16384M/' "$ANDROID_AVD_HOME/e2e.avd/config.ini" 2>/dev/null || true
grep -q '^disk.dataPartition.size=' "$ANDROID_AVD_HOME/e2e.avd/config.ini" 2>/dev/null || echo 'disk.dataPartition.size=16384M' >> "$ANDROID_AVD_HOME/e2e.avd/config.ini"
echo "dataPartition: $(grep '^disk.dataPartition.size=' "$ANDROID_AVD_HOME/e2e.avd/config.ini" 2>/dev/null)" >> e2e-logs/phases.log
if [ $? -ne 0 ]; then phase "create-avd-failed"; tail -50 e2e-logs/avd.log; exit 1; fi
ls -la "$ANDROID_AVD_HOME" >> e2e-logs/phases.log 2>&1
if ! "$EMU_BIN" -list-avds 2>/dev/null | grep -qx e2e; then
  phase "create-avd-invisible"
  "$EMU_BIN" -list-avds >> e2e-logs/avd.log 2>&1 || true
  exit 1
fi

phase "launch-emulator"
ACCEL_ARGS=""
if [ "$(uname)" = "Linux" ]; then
  if [ -e /dev/KVM ] && python3 -c 'import os,fcntl; fd=os.open("/dev/KVM", os.O_RDWR); assert fcntl.ioctl(fd, 0xAE00, 0) > 0' 2>/dev/null; then
    echo "KVM usable; hardware acceleration on" >> e2e-logs/phases.log
  else
    # GitHub-hosted runners here expose no /dev/KVM at all, and macOS
    # runners are nested (HVF unsupported). Software emulation (TCG) is
    # slow but works; budgets below are sized for it.
    echo "KVM unavailable; using -no-accel (software emulation)" >> e2e-logs/phases.log
    ACCEL_ARGS="-no-accel"
  fi
fi
# Explicit DNS servers: the emulator's default DNS proxy forwards to the
# runner host's nameservers, which failed here with apt reporting
# "No address associated with hostname" for the Termux repository.
"$EMU_BIN" -avd e2e -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim -dns-server 8.8.8.8,1.1.1.1 $ACCEL_ARGS -memory 4096 -cores 4 -partition-size 8192 > e2e-logs/emulator.log 2>&1 &
EMU_PID=$!
# No wait-for-device here (GNU timeout is unavailable on macOS and an
# unbounded wait hangs forever if the emulator dies); the poll loop below
# is bounded and also watches the emulator process itself.
booted=""
for i in $(seq 1 180); do
  if ! kill -0 "$EMU_PID" >/dev/null 2>&1; then
    echo "emulator process died during boot" >> e2e-logs/phases.log
    break
  fi
  if [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then booted=1; break; fi
  sleep 10
done
if [ -z "$booted" ]; then
  phase "boot-failed"
  tail -100 e2e-logs/emulator.log || true
  "$ADB" logcat -d > e2e-logs/logcat.log 2>&1 || true
  exit 1
fi
"$ADB" shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
"$ADB" shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
"$ADB" shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true

phase "network-check"
# The guest had no working DNS at all in earlier runs (even Java-side
# InetAddress failed), which makes every apt fetch fail resolving the
# repository hostname. Log the guest network state, try to enable data,
# and wait briefly for connectivity before spending the test budget.
"$ADB" shell cmd connectivity airplane-mode disable >> e2e-logs/network.log 2>&1 || true
"$ADB" shell svc data enable >> e2e-logs/network.log 2>&1 || true
{
  echo "--- ip addr ---"; "$ADB" shell ip addr 2>&1 | head -40
  echo "--- net props ---"; "$ADB" shell getprop 2>&1 | grep -i -E "dns|net\." | head -20
  echo "--- connectivity ---"; "$ADB" shell dumpsys connectivity 2>&1 | head -60
} >> e2e-logs/network.log 2>&1
for i in $(seq 1 6); do
  if "$ADB" shell ping -c 1 -W 3 8.8.8.8 >> e2e-logs/network.log 2>&1; then
    echo "guest ping 8.8.8.8 OK (attempt $i)" >> e2e-logs/network.log
    break
  fi
  echo "guest ping attempt $i failed" >> e2e-logs/network.log
  sleep 10
done
# If no network came up by itself, try the emulator's virtual WiFi AP,
# which is the data path on recent google_apis images when cellular is idle.
{
  echo "--- enabling wifi + VirtWifi ---"
  "$ADB" root 2>&1 || true
  sleep 3
  "$ADB" shell svc wifi enable 2>&1
  "$ADB" shell cmd wifi set-wifi-enabled enabled 2>&1
  sleep 5
  "$ADB" shell cmd wifi connect-network VirtWifi open 2>&1
  "$ADB" shell cmd wifi connect-network VirtWifi open -x 2>&1
} >> e2e-logs/network.log 2>&1
# Under software emulation the guest gets very little CPU, so give the
# framework a long real-time window to associate and get DHCP, polling
# for an actual default network instead of fixed short sleeps.
net_ok=""
if [ -n "${E2E_LEAN:-}" ]; then
  echo "lean mode: skipping the connectivity wait (this gate needs no network)" >> e2e-logs/network.log
  net_ok=lean
else
for i in $(seq 1 45); do
  default_net="$("$ADB" shell dumpsys connectivity 2>/dev/null | grep -m1 'Active default network:' || true)"
  wlan_ip="$("$ADB" shell ip addr show wlan0 2>/dev/null | grep -m1 'inet ' || true)"
  echo "net poll $i: $default_net | wlan: $wlan_ip" >> e2e-logs/network.log
  case "$default_net" in
    ""|*"none"*) ;;
    *) net_ok=1; break ;;
  esac
  if "$ADB" shell ping -c 1 -W 3 8.8.8.8 >> e2e-logs/network.log 2>&1; then
    echo "guest ping 8.8.8.8 OK (net poll $i)" >> e2e-logs/network.log
    net_ok=1; break
  fi
  sleep 20
done
fi
{
  echo "--- ip addr after wifi wait (net_ok=$net_ok) ---"; "$ADB" shell ip addr 2>&1 | grep -A2 -E "eth0|wlan0"
  echo "--- wifi status ---"; "$ADB" shell cmd wifi status 2>&1 | head -20
} >> e2e-logs/network.log 2>&1
"$ADB" shell ping -c 1 -W 5 packages-cf.termux.dev >> e2e-logs/network.log 2>&1 || true

# Regression fixture for the real-device dpkg failure: on a stock phone,
# another app's /data/data/<pkg> answers EACCES (SELinux/DAC), and stock
# dpkg dies on it because its compile-time CONFIGDIR lives in the official
# Termux data directory. Recreate that boundary here: a root-owned,
# unreadable official-prefix config dir must NOT be able to break the
# harness's dpkg (libharness-exec translates the scan to our prefix).
{
  echo "--- foreign-app dpkg config boundary ---"
  "$ADB" shell mkdir -p /data/data/com.termux/files/usr/etc/dpkg/dpkg.cfg.d 2>&1
  # Also mirror the keyring tree: the bootstrap's share/pacman/keyrings
  # symlinks point at /data/data/com.termux/files/usr/share/termux-keyring,
  # and dpkg dereferences them while upgrading termux-keyring. On a real
  # phone that whole tree answers EACCES (not ENOENT); recreate that here
  # so an unrepaired absolute symlink anywhere in the prefix fails loudly.
  "$ADB" shell mkdir -p /data/data/com.termux/files/usr/share/termux-keyring 2>&1
  "$ADB" shell 'echo foreign > /data/data/com.termux/files/usr/share/termux-keyring/2096779623.gpg' 2>&1
  "$ADB" shell chmod -R 000 /data/data/com.termux/files 2>&1
  "$ADB" shell ls -ld /data/data/com.termux/files/usr/etc/dpkg/dpkg.cfg.d 2>&1
} >> e2e-logs/network.log 2>&1
publish

phase "device-ready"
# Gradle's device provider installs as soon as it sees the device; if adb has
# not finished reading its properties it reports the (bogus) message "Cannot
# install split APKs with API level < 21" and the run dies before a single
# test executes. Wait for the SDK property and one real package-manager call.
for i in $(seq 1 40); do
  sdk_level="$("$ADB" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  if [ -n "$sdk_level" ] && "$ADB" shell pm list packages >/dev/null 2>&1; then
    echo "device ready: sdk=$sdk_level (attempt $i)" >> e2e-logs/phases.log
    break
  fi
  echo "device not ready yet (attempt $i, sdk='$sdk_level')" >> e2e-logs/phases.log
  sleep 5
done

# Package verification is another way an install of a 90 MB APK stalls on a
# KVM-less emulator: nothing is being checked against a store, so turning the
# verifier off removes a wait that measures the runner, not the app.
"$ADB" shell settings put global verifier_verify_adb_installs 0 >/dev/null 2>&1 || true
"$ADB" shell settings put global package_verifier_enable 0 >/dev/null 2>&1 || true

# A booted device is not a responsive one. Under software emulation `adb shell
# getprop` can still take tens of seconds right after boot_completed, and if it
# exceeds ddmlib's timeout Gradle decides the device is API 1 and refuses to
# install anything. Wait for the device to answer *quickly* — three consecutive
# fast reads of the SDK level — before handing it to Gradle.
phase "device-warm"
WARM_OK=0
for attempt in $(seq 1 60); do
  started=$(date +%s)
  read_sdk="$("$ADB" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  took=$(( $(date +%s) - started ))
  if [ -n "$read_sdk" ] && [ "$took" -le 5 ]; then
    WARM_OK=$((WARM_OK + 1))
  else
    WARM_OK=0
  fi
  echo "warm $attempt: sdk='$read_sdk' in ${took}s (streak=$WARM_OK)" >> e2e-logs/phases.log
  [ "$WARM_OK" -ge 3 ] && break
  sleep 5
done
if [ "$WARM_OK" -lt 3 ]; then
  echo "device never answered getprop quickly; continuing anyway" >> e2e-logs/phases.log
fi

# Install outside Gradle first. Gradle's device layer installs through ddmlib,
# whose shell timeout is what turns a slow emulator into "API level 1, cannot
# install". adb has no such timeout, so the expensive part happens here and
# Gradle's own install becomes an overwrite of an identical package.
PRE_APP=$(ls app/build/outputs/apk/sealed/*.apk 2>/dev/null | head -1)
PRE_TEST=$(ls app/build/outputs/apk/androidTest/sealed/*.apk 2>/dev/null | head -1)
if [ -n "${PRE_APP:-}" ]; then
  started=$(date +%s)
  "$ADB" install -r -t "$PRE_APP" >> e2e-logs/install.log 2>&1 && \
    echo "preinstalled $PRE_APP in $(( $(date +%s) - started ))s" >> e2e-logs/phases.log || \
    echo "preinstall of $PRE_APP failed (Gradle will try)" >> e2e-logs/phases.log
fi
if [ -n "${PRE_TEST:-}" ]; then
  "$ADB" install -r -t "$PRE_TEST" >> e2e-logs/install.log 2>&1 || \
    echo "preinstall of the test APK failed (Gradle will try)" >> e2e-logs/phases.log
fi

phase "connected-test"
TEST_ARGS=""
if [ -n "$E2E_TEST_CLASS" ]; then
  TEST_ARGS="-Pandroid.testInstrumentationRunnerArguments.class=$E2E_TEST_CLASS"
fi
echo "task=$E2E_TEST_TASK class=${E2E_TEST_CLASS:-<all>} props=${E2E_GRADLE_PROPS:-none}" >> e2e-logs/phases.log

# Pin the device for Gradle. With one emulator this looks redundant, but an
# emulator that drops off adb between the readiness check and Gradle's own
# device discovery makes AGP report "No compatible devices connected" - which
# says nothing about the build and wastes a 20-minute software-emulated boot.
SERIAL="$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
if [ -n "$SERIAL" ]; then
  export ANDROID_SERIAL="$SERIAL"
  echo "ANDROID_SERIAL=$ANDROID_SERIAL" >> e2e-logs/phases.log
fi

run_connected_test() {
  # shellcheck disable=SC2086  # word splitting is intended for the property list
  ./gradlew --no-daemon --stacktrace "$E2E_TEST_TASK" $TEST_ARGS $E2E_GRADLE_PROPS \
    >> e2e-logs/connected-test.log 2>&1
}

wait_for_device_again() {
  "$ADB" wait-for-device
  for i in $(seq 1 60); do
    booted="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    packages_ok=""
    [ "$booted" = "1" ] && "$ADB" shell pm list packages >/dev/null 2>&1 && packages_ok=1
    [ -n "$packages_ok" ] && { echo "device back after $i check(s)" >> e2e-logs/phases.log; return 0; }
    sleep 5
  done
  return 1
}

# Infrastructure failures look like test failures.
#
# Without KVM the emulator runs under software emulation, and ddmlib's shell
# timeout fires while `am instrument` is still starting: the log says
# "Starting 0 tests" plus a ShellCommandUnresponsiveException, and Gradle
# reports a failed test task even though no test ran. Those signatures are
# retried; a real assertion failure is not, because retrying it would hide a
# real regression behind a green run.
is_infrastructure_failure() {
  grep -qE "No compatible devices connected|ShellCommandUnresponsiveException|Starting 0 tests|device offline|INSTALL_FAILED_UPDATE_INCOMPATIBLE|Unable to install" "$1"
}

uninstall_packages() {
  "$ADB" shell pm uninstall io.mcyber12.codingharness >/dev/null 2>&1 || true
  "$ADB" shell pm uninstall io.mcyber12.codingharness.test >/dev/null 2>&1 || true
}

RC=0
ATTEMPT=1
while [ "$ATTEMPT" -le 3 ]; do
  : > e2e-logs/connected-test.log
  run_connected_test
  RC=$?
  [ "$RC" -eq 0 ] && break
  if ! is_infrastructure_failure e2e-logs/connected-test.log; then
    echo "attempt $ATTEMPT failed on the test itself; not retrying" >> e2e-logs/phases.log
    break
  fi
  cp e2e-logs/connected-test.log "e2e-logs/connected-test-attempt${ATTEMPT}.log" 2>/dev/null || true
  echo "attempt $ATTEMPT failed for an infrastructure reason; retrying" >> e2e-logs/phases.log
  [ "$ATTEMPT" -lt 3 ] || break
  wait_for_device_again || true
  # A half-installed pair is a common cause of the next attempt failing the
  # same way: start from a clean device.
  uninstall_packages
  ATTEMPT=$((ATTEMPT + 1))
  sleep 20
done
echo "$E2E_TEST_TASK exit=$RC (attempts=$ATTEMPT)" | tee -a e2e-logs/phases.log
"$ADB" shell cat /sdcard/Android/data/io.mcyber12.codingharness/files/setup-e2e-report.txt > e2e-logs/device-report.log 2>&1 || true
"$ADB" pull /sdcard/Android/data/io.mcyber12.codingharness/files/setup-e2e-report.txt ./setup-e2e-report.txt >> e2e-logs/phases.log 2>&1 || true
"$ADB" shell run-as io.mcyber12.codingharness cat files/coding-harness/bootstrap-error.log > e2e-logs/bootstrap-error.log 2>&1 || true
"$ADB" shell run-as io.mcyber12.codingharness cat files/coding-harness/setup-result.txt > e2e-logs/setup-result.log 2>&1 || true
"$ADB" shell run-as io.mcyber12.codingharness cat files/coding-harness/rootfs/data/data/com.termux/files/usr/var/log/harness-command.log > e2e-logs/harness-command.log 2>&1 || true
"$ADB" logcat -d > e2e-logs/logcat.log 2>&1 || true

# ---------------------------------------------------------------------------
# The pack, install → run → remove, on the device.
#
# `:app:assembleSealed` never contains an on-demand module: Gradle builds the
# base and the feature as separate APKs, and only Play (or bundletool) fuses
# them. Fusing here with bundletool made this phase fail on rules about
# `extractNativeLibs` in a bundle — Google's packaging opinions, not our code,
# and the sealed design needs extraction.
#
# So the pack's own path is proved with a build we control: `packdemo` is the
# sealed variant with the pack's payload folded into the base APK. Same classes,
# same payload format, same install/verify/remove code as a Play-delivered
# module; only the packaging differs. The store claim stays where it belongs —
# the AAB gate checks that the module is really in the bundle, on demand, under
# Play's size cap.
# ---------------------------------------------------------------------------
if [ "${E2E_PACK_DEMO:-0}" = "1" ]; then
  phase "pack-demo"
  PACK_RC=0
  : > e2e-logs/pack-demo.log

  # Build only — no device interaction, so nothing here can time out.
  # shellcheck disable=SC2086  # word splitting is intended for the property list
  ./gradlew --no-daemon --stacktrace \
    -PharnessTargetSdk="$E2E_TARGET_SDK" \
    -PharnessCompileSdk="$E2E_COMPILE_SDK" \
    -PharnessTestBuildType=packdemo \
    :app:assemblePackdemo :app:assemblePackdemoAndroidTest \
    >> e2e-logs/pack-demo.log 2>&1 || PACK_RC=1

  APP_APK=$(ls app/build/outputs/apk/packdemo/*.apk 2>/dev/null | head -1)
  TEST_APK=$(ls app/build/outputs/apk/androidTest/packdemo/*.apk 2>/dev/null | head -1)
  echo "app=$APP_APK test=$TEST_APK" >> e2e-logs/pack-demo.log
  if [ -z "${APP_APK:-}" ] || [ -z "${TEST_APK:-}" ]; then
    echo "the packdemo artefacts were not built" >> e2e-logs/pack-demo.log
    PACK_RC=1
  fi

  # No Gradle device layer here, on purpose.
  #
  # `connected*` runs go through UTP/ddmlib, whose shell timeout fires while a
  # 90 MB APK installs on a KVM-less emulator; it then reports the device as API
  # level 1 and refuses to install anything. adb has no such timeout, and
  # `am instrument -w` streams until the test ends, so this single test is run
  # directly. The main gate still uses Gradle, because its JUnit XML is what the
  # assertion step reads.
  if [ "$PACK_RC" = "0" ]; then
    ATTEMPT=1
    while [ "$ATTEMPT" -le 2 ]; do
      PACK_RUN=0
      wait_for_device_again || true
      "$ADB" install -r -t "$APP_APK" >> e2e-logs/pack-demo.log 2>&1 || PACK_RUN=1
      "$ADB" install -r -t "$TEST_APK" >> e2e-logs/pack-demo.log 2>&1 || PACK_RUN=1
      if [ "$PACK_RUN" = "0" ]; then
        "$ADB" shell am instrument -w \
          -e class "io.mcyber12.codingharness.SealedShellTest#packsComeFromTheStoreAndNeverFromApt" \
          io.mcyber12.codingharness.test/androidx.test.runner.AndroidJUnitRunner \
          >> e2e-logs/pack-demo.log 2>&1 || PACK_RUN=1
        grep -q "OK (1 test)" e2e-logs/pack-demo.log || PACK_RUN=1
      fi
      [ "$PACK_RUN" = "0" ] && break
      echo "pack attempt $ATTEMPT failed; retrying on a clean device" \
        >> e2e-logs/pack-demo.log
      [ "$ATTEMPT" -lt 2 ] || break
      ATTEMPT=$((ATTEMPT + 1))
      uninstall_packages
      sleep 20
    done
    PACK_RC=$PACK_RUN
  fi

  # The harness uninstalls the app at the end of a connected run, taking its
  # report file with it; logcat is the one record that survives. TestRunner has
  # the assertion message, SealedShell has the test's own narration.
  "$ADB" logcat -d -s SealedShell:V TestRunner:V AndroidJUnitRunner:V \
    > e2e-logs/pack-demo-logcat.log 2>&1 || true
  grep -E "SealedShell|TestRunner|AssertionError|junit" e2e-logs/pack-demo-logcat.log \
    >> e2e-logs/pack-demo.log 2>/dev/null || true
  {
    echo "--- what the device kept after the run ---"
    "$ADB" shell run-as io.mcyber12.codingharness \
      cat files/coding-harness/pack-build-tools-installed.txt 2>&1 || true
    "$ADB" shell run-as io.mcyber12.codingharness \
      cat files/coding-harness/sealed-payload-pack-buildtools.txt 2>&1 || true
    "$ADB" shell run-as io.mcyber12.codingharness \
      ls -l files/coding-harness/rootfs/data/data/com.termux/files/usr/bin/make 2>&1 || true
  } >> e2e-logs/pack-demo.log 2>&1
  echo "pack-demo exit=$PACK_RC" >> e2e-logs/phases.log
  [ "$PACK_RC" = "0" ] || RC=1
fi

kill "$PUBLISHER_PID" >/dev/null 2>&1
PHASE="done rc=$RC"
exit $RC
