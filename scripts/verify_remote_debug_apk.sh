#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="/root/workspace/video-forge-android"
APK_PATH="${PROJECT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
OUT_DIR="/root/workspace/video-forge-android/remote-apk-evidence"
LOCAL_GRADLE="/root/gradle-8.9/bin/gradle"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

mkdir -p "$OUT_DIR"
TS="$(date +%Y%m%d-%H%M%S)"
EVIDENCE="$OUT_DIR/remote_evidence_${TS}.json"

note(){
  echo "[$(date -Iseconds)] $*"
}

compute_apk_meta() {
  if [ -f "$APK_PATH" ]; then
    apk_size=$(wc -c < "$APK_PATH")
    apk_sha256=$(sha256sum "$APK_PATH" | awk '{print $1}')
    aapt_ok=1
    aapt_bin=""
    for candidate in \
      "$(command -v aapt || true)" \
      "$(command -v aapt2 || true)" \
      "/root/Android/Sdk/build-tools/"*"/aapt" \
      "/opt/android-sdk/build-tools/"*"/aapt" \
      "/root/Android/Sdk/build-tools/"*"/aapt2" \
      "/opt/android-sdk/build-tools/"*"/aapt2"; do
      [ -n "$candidate" ] || continue
      [ -x "$candidate" ] || continue
      aapt_bin="$candidate"
      break
    done
    if [ -n "$aapt_bin" ]; then
      aapt_ok=$("$aapt_bin" dump badging "$APK_PATH" >/dev/null 2>&1; echo $?)
    fi
  else
    apk_size=""
    apk_sha256=""
    aapt_ok=1
  fi
}

build_result=0
build_method="none"

if [ -x "$LOCAL_GRADLE" ]; then
  note "Using local gradle: $LOCAL_GRADLE"
  if (cd "$PROJECT_DIR" && "$LOCAL_GRADLE" --offline -q clean :app:assembleDebug); then
    build_method="local_gradle_offline"
  else
    build_method="local_gradle_offline_failed"
    if (cd "$PROJECT_DIR" && ./gradlew --offline -q clean :app:assembleDebug >/tmp/gradlew_offline.log 2>&1); then
      build_method="gradlew_offline"
    elif (cd "$PROJECT_DIR" && ./gradlew -q clean :app:assembleDebug >/tmp/gradlew_online.log 2>&1); then
      build_method="gradlew_online"
    else
      build_result=1
    fi
  fi
else
  build_method="local_gradle_missing"
  if (cd "$PROJECT_DIR" && ./gradlew --offline -q clean :app:assembleDebug >/tmp/gradlew_offline.log 2>&1); then
    build_method="gradlew_offline"
  elif (cd "$PROJECT_DIR" && ./gradlew -q clean :app:assembleDebug >/tmp/gradlew_online.log 2>&1); then
    build_method="gradlew_online"
  else
    build_result=1
  fi
fi

compute_apk_meta

adb_path="$(command -v adb || true)"
if [ -z "$adb_path" ]; then
  adb_status="not_found"
else
  adb_version="$($adb_path version | sed -n '1,2' | tr -d '\r')"
  adb_status="$adb_version"
fi

cat > "$EVIDENCE" <<JSON
{
  "timestamp": "$(date -Iseconds)",
  "project_dir": "$PROJECT_DIR",
  "apk_exists": $([ -f "$APK_PATH" ] && echo true || echo false),
  "apk_size": ${apk_size:-null},
  "apk_sha256": "${apk_sha256}",
  "aapt_dump_ok": $([ "$aapt_ok" -eq 0 ] && echo true || echo false),
  "aapt_bin": "${aapt_bin:-}",
  "build_method": "$build_method",
  "build_result": $build_result,
  "build_exit": $build_result,
  "adb_status": "$adb_status"
}
JSON

note "evidence: $EVIDENCE"
ls -lh "$EVIDENCE"

if [ "$build_result" -ne 0 ]; then
  note "build failed"
  exit 1
fi
exit 0
