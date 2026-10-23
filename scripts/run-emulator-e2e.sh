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
  git push origin "HEAD:refs/heads/diagnostics/e2e-${GITHUB_RUN_ID:-local}" --force >/dev/null 2>&1 || true
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

: > e2e-logs/connected-test.log
run_connected_test
RC=$?
if [ $RC -ne 0 ] && grep -q "No compatible devices connected" e2e-logs/connected-test.log; then
  # Infrastructure, not the build: the emulator was gone when Gradle looked.
  # Wait for it to come back and run the same task once more, so a flaky
  # disconnect does not report a green build as a failed one.
  echo "no device visible to Gradle; waiting for it and retrying once" >> e2e-logs/phases.log
  : > e2e-logs/connected-test-retry.log
  if wait_for_device_again; then
    run_connected_test
    RC=$?
    cp e2e-logs/connected-test.log e2e-logs/connected-test-retry.log 2>/dev/null || true
    echo "retry exit=$RC" >> e2e-logs/phases.log
  fi
fi
echo "$E2E_TEST_TASK exit=$RC" | tee -a e2e-logs/phases.log
"$ADB" shell cat /sdcard/Android/data/io.mcyber12.codingharness/files/setup-e2e-report.txt > e2e-logs/device-report.log 2>&1 || true
"$ADB" pull /sdcard/Android/data/io.mcyber12.codingharness/files/setup-e2e-report.txt ./setup-e2e-report.txt >> e2e-logs/phases.log 2>&1 || true
"$ADB" shell run-as io.mcyber12.codingharness cat files/coding-harness/bootstrap-error.log > e2e-logs/bootstrap-error.log 2>&1 || true
"$ADB" shell run-as io.mcyber12.codingharness cat files/coding-harness/setup-result.txt > e2e-logs/setup-result.log 2>&1 || true
"$ADB" shell run-as io.mcyber12.codingharness cat files/coding-harness/rootfs/data/data/com.termux/files/usr/var/log/harness-command.log > e2e-logs/harness-command.log 2>&1 || true
"$ADB" logcat -d > e2e-logs/logcat.log 2>&1 || true

# ---------------------------------------------------------------------------
# The pack, delivered the way Play delivers it.
#
# An on-demand feature module is not part of `:app:assembleSealed` — Gradle
# builds the base module and the feature module as separate APKs, and only
# bundletool fuses them (that is what `dist:fusing include="true"` is for). So
# the pack install/remove claim is proved the way a store build gets it: build
# the App Bundle, ask bundletool for a universal APK, install that, and run the
# one test that installs the pack, runs its tool, removes it and checks that the
# core runtime still works. No Play Store is involved, which is the point: it is
# the same artefact shape Play would hand the device.
# ---------------------------------------------------------------------------
if [ "${E2E_PACK_UNIVERSAL:-0}" = "1" ]; then
  phase "pack-universal"
  BUNDLETOOL_VERSION="${E2E_BUNDLETOOL_VERSION:-1.17.2}"
  BUNDLETOOL="e2e-logs/bundletool-all-${BUNDLETOOL_VERSION}.jar"
  PACK_RC=0
  : > e2e-logs/pack-universal.log
  if [ ! -f "$BUNDLETOOL" ]; then
    curl -fsSL --retry 3 -o "$BUNDLETOOL" \
      "https://github.com/google/bundletool/releases/download/${BUNDLETOOL_VERSION}/bundletool-all-${BUNDLETOOL_VERSION}.jar" \
      >> e2e-logs/pack-universal.log 2>&1 || PACK_RC=1
  fi
  AAB=$(ls app/build/outputs/bundle/sealed/*.aab 2>/dev/null | head -1)
  if [ -z "${AAB:-}" ]; then
    echo "no sealed bundle to fuse" >> e2e-logs/pack-universal.log
    PACK_RC=1
  fi
  if [ "$PACK_RC" = "0" ]; then
    # The debug key signs both the universal APK and the test APK: instrumentation
    # needs the two to match, and the sealed variant is debuggable by design.
    java -jar "$BUNDLETOOL" build-apks \
      --bundle="$AAB" \
      --output=e2e-logs/universal.apks \
      --mode=universal \
      --ks="$HOME/.android/debug.keystore" \
      --ks-pass=pass:android --key-pass=pass:android \
      --ks-key-alias=androiddebugkey >> e2e-logs/pack-universal.log 2>&1 || PACK_RC=1
  fi
  if [ "$PACK_RC" = "0" ]; then
    unzip -o -q e2e-logs/universal.apks universal.apk -d e2e-logs >> e2e-logs/pack-universal.log 2>&1 || PACK_RC=1
  fi
  if [ "$PACK_RC" = "0" ]; then
    # A clean install, so the core payload and the pack are installed in the
    # same run and the test proves the pair, not a leftover.
    "$ADB" uninstall io.mcyber12.codingharness >> e2e-logs/pack-universal.log 2>&1 || true
    "$ADB" install -r e2e-logs/universal.apk >> e2e-logs/pack-universal.log 2>&1 || PACK_RC=1
  fi
  TEST_APK=$(ls app/build/outputs/apk/androidTest/sealed/*.apk 2>/dev/null | head -1)
  if [ "$PACK_RC" = "0" ] && [ -n "${TEST_APK:-}" ]; then
    "$ADB" install -r "$TEST_APK" >> e2e-logs/pack-universal.log 2>&1 || PACK_RC=1
  fi
  if [ "$PACK_RC" = "0" ]; then
    "$ADB" shell am instrument -w \
      -e class "io.mcyber12.codingharness.SealedShellTest#packsComeFromTheStoreAndNeverFromApt" \
      io.mcyber12.codingharness.test/androidx.test.runner.AndroidJUnitRunner \
      >> e2e-logs/pack-universal.log 2>&1 || true
    if ! grep -q "OK (1 test)" e2e-logs/pack-universal.log; then
      echo "the pack test did not pass on the universal APK" >> e2e-logs/pack-universal.log
      PACK_RC=1
    fi
  fi
  # What the device kept, so a failure can be read without a rerun.
  {
    echo "--- pack state on the device ---"
    "$ADB" shell run-as io.mcyber12.codingharness \
      ls -l files/coding-harness/rootfs/data/data/com.termux/files/usr/bin 2>&1 | head -20
    "$ADB" shell run-as io.mcyber12.codingharness \
      cat files/coding-harness/pack-build-tools-installed.txt 2>&1 || true
    "$ADB" shell run-as io.mcyber12.codingharness \
      cat files/coding-harness/sealed-payload-pack-buildtools.txt 2>&1 || true
  } >> e2e-logs/pack-universal.log 2>&1
  echo "pack-universal exit=$PACK_RC" >> e2e-logs/phases.log
  [ "$PACK_RC" = "0" ] || RC=1
fi

kill "$PUBLISHER_PID" >/dev/null 2>&1
PHASE="done rc=$RC"
exit $RC
