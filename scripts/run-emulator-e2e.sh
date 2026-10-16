#!/usr/bin/env bash
# Boots an Android emulator manually (no third-party runner action) and runs
# the connected E2E test. Every phase is logged to e2e-logs/ and published to
# refs/heads/diagnostics/e2e on exit (trap), so progress and the exact
# failing phase are visible even when CI logs/artifacts are unreachable.
cd "$(dirname "$0")/.." || exit 1

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
  "$ADB" shell run-as io.mcyber12.codingharness cat files/coding-harness/usr/var/log/harness-command.log > ./harness-command-live.log 2>/dev/null || true
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
yes | "$SDKMANAGER" --licenses > e2e-logs/licenses.log 2>&1 || true
"$SDKMANAGER" "emulator" "system-images;android-34;aosp_atd;x86_64" > e2e-logs/sysimg.log 2>&1
if [ $? -ne 0 ]; then phase "system-image-failed"; tail -50 e2e-logs/sysimg.log; exit 1; fi

phase "create-avd"
# avdmanager and emulator must agree on the AVD home; newer cmdline-tools
# otherwise create under a location emulator does not search
# ("Unknown AVD name [e2e]").
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
mkdir -p "$ANDROID_AVD_HOME"
echo "ANDROID_AVD_HOME=$ANDROID_AVD_HOME" >> e2e-logs/phases.log
echo no | "$AVDMANAGER" create avd -n e2e -k "system-images;android-34;aosp_atd;x86_64" -d pixel_6 --force > e2e-logs/avd.log 2>&1
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
{
  echo "--- ip addr after wifi wait (net_ok=$net_ok) ---"; "$ADB" shell ip addr 2>&1 | grep -A2 -E "eth0|wlan0"
  echo "--- wifi status ---"; "$ADB" shell cmd wifi status 2>&1 | head -20
} >> e2e-logs/network.log 2>&1
"$ADB" shell ping -c 1 -W 5 packages-cf.termux.dev >> e2e-logs/network.log 2>&1 || true
publish

phase "connected-test"
./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest > e2e-logs/connected-test.log 2>&1
RC=$?
echo "connectedDebugAndroidTest exit=$RC" | tee -a e2e-logs/phases.log
"$ADB" shell cat /sdcard/Android/data/io.mcyber12.codingharness/files/setup-e2e-report.txt > e2e-logs/device-report.log 2>&1 || true
"$ADB" pull /sdcard/Android/data/io.mcyber12.codingharness/files/setup-e2e-report.txt ./setup-e2e-report.txt >> e2e-logs/phases.log 2>&1 || true
"$ADB" shell run-as io.mcyber12.codingharness cat files/coding-harness/bootstrap-error.log > e2e-logs/bootstrap-error.log 2>&1 || true
"$ADB" shell run-as io.mcyber12.codingharness cat files/coding-harness/usr/var/log/harness-command.log > e2e-logs/harness-command.log 2>&1 || true
"$ADB" logcat -d > e2e-logs/logcat.log 2>&1 || true

kill "$PUBLISHER_PID" >/dev/null 2>&1
PHASE="done rc=$RC"
exit $RC
