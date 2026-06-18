#!/usr/bin/env bash
set -euo pipefail

# User-run helper. Repository AGENTS.md forbids agents from running Gradle build tasks.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PHONE_SERIAL="${PHONE_SERIAL:-192.168.1.139:39183}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ANDROID_SDK="${ANDROID_SDK:-$ANDROID_HOME}"
GRADLE_TASK="${GRADLE_TASK:-:OsmAnd:assembleGplayFreeLegacyFatDebug}"
APK_PATH="$ROOT_DIR/OsmAnd/build/outputs/apk/gplayFreeLegacyFat/debug/OsmAnd-gplayFree-legacy-fat-debug.apk"
ARTIFACT_PATH="$ROOT_DIR/build-artifacts/FlockFree-gplayFree-legacy-fat-debug.apk"
BUILD_INFO_PATH="$ROOT_DIR/build-artifacts/FlockFree-build-info.txt"
PACKAGE_NAME="com.yetiwurks.flockfree"

usage() {
	cat <<EOF
Usage: $(basename "$0") [--serial HOST:PORT] [--no-install] [--no-launch]

Builds the current FlockFree debug APK, optionally installs it on the Moto over ADB,
launches it, and collects the standard no-Gradle diagnostics snapshot.

Environment:
  PHONE_SERIAL   ADB serial, default: $PHONE_SERIAL
  ANDROID_HOME   Android SDK path, default: $ANDROID_HOME
  GRADLE_TASK    Gradle task, default: $GRADLE_TASK

Note: this script runs Gradle. It is for Eric/manual use, not agent execution.
EOF
}

find_apksigner() {
	if command -v apksigner >/dev/null 2>&1; then
		command -v apksigner
		return 0
	fi
	if [[ -d "$ANDROID_HOME/build-tools" ]]; then
		find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n 1
	fi
}

INSTALL=1
LAUNCH=1
while [[ $# -gt 0 ]]; do
	case "$1" in
		--serial)
			PHONE_SERIAL="$2"
			shift 2
			;;
		--no-install)
			INSTALL=0
			shift
			;;
		--no-launch)
			LAUNCH=0
			shift
			;;
		-h|--help)
			usage
			exit 0
			;;
		*)
			echo "Unknown argument: $1" >&2
			usage >&2
			exit 2
			;;
	esac
done

cd "$ROOT_DIR"
mkdir -p "$(dirname "$ARTIFACT_PATH")"

SOURCE_COMMIT="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
if git diff --quiet && git diff --cached --quiet; then
	SOURCE_STATE="clean"
else
	SOURCE_STATE="dirty"
	echo "Warning: source tree has uncommitted changes; build info will record dirty state." >&2
fi

ANDROID_HOME="$ANDROID_HOME" ANDROID_SDK="$ANDROID_SDK" \
	./gradlew "$GRADLE_TASK" -x test --no-daemon --max-workers=1

if [[ ! -f "$APK_PATH" ]]; then
	echo "APK not found: $APK_PATH" >&2
	exit 1
fi

cp "$APK_PATH" "$ARTIFACT_PATH"
APK_SHA256="$(sha256sum "$ARTIFACT_PATH" | awk '{print $1}')"
echo "$APK_SHA256  $ARTIFACT_PATH"

APK_SIGNER="$(find_apksigner)"
SIGNATURE_STATUS="not checked"
if [[ -n "$APK_SIGNER" && -x "$APK_SIGNER" ]]; then
	"$APK_SIGNER" verify --verbose "$ARTIFACT_PATH"
	SIGNATURE_STATUS="verified by $APK_SIGNER"
fi

{
	echo "Built: $(date -Is)"
	echo "Source commit: $SOURCE_COMMIT"
	echo "Source state: $SOURCE_STATE"
	echo "Gradle task: $GRADLE_TASK"
	echo "Package: $PACKAGE_NAME"
	echo "APK: $ARTIFACT_PATH"
	echo "SHA-256: $APK_SHA256"
	echo "Signature: $SIGNATURE_STATUS"
} > "$BUILD_INFO_PATH"
echo "Wrote build info: $BUILD_INFO_PATH"

if [[ "$INSTALL" -eq 1 ]]; then
	adb connect "$PHONE_SERIAL" || true
	adb -s "$PHONE_SERIAL" install -r "$ARTIFACT_PATH"
fi

if [[ "$LAUNCH" -eq 1 ]]; then
	adb -s "$PHONE_SERIAL" shell monkey -p "$PACKAGE_NAME" 1
fi

"$ROOT_DIR/scripts/flockfree-moto-diagnostics.sh" --serial "$PHONE_SERIAL" --logcat-lines 1000
