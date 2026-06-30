#!/usr/bin/env bash
set -euo pipefail

# Build/install helper for local FlockFree test APKs.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PHONE_SERIAL="${PHONE_SERIAL:-192.168.1.139:39183}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ANDROID_SDK="${ANDROID_SDK:-$ANDROID_HOME}"
# Production package: com.yetiwurks.flockfree (gplayFree flavor)
# NEVER use nightlyFree flavor for releases or test installs — it uses
# com.yetiwurks.flockfree.dev which installs as a separate app and causes
# duplicate installations, map loss, and update checker confusion.
GRADLE_TASK="${GRADLE_TASK:-:OsmAnd:assembleGplayFreeOpenglFatDebug}"
APK_PATH="$ROOT_DIR/OsmAnd/build/outputs/apk/gplayFreeOpenglFat/debug/OsmAnd-gplayFree-opengl-fat-debug.apk"
FLOCKFREE_ARTIFACT_VERSION="${FLOCKFREE_ARTIFACT_VERSION:-local}"
ARTIFACT_NAME="${ARTIFACT_NAME:-FlockFree-Navigation-${FLOCKFREE_ARTIFACT_VERSION}-sideload.apk}"
ARTIFACT_PATH="$ROOT_DIR/build-artifacts/$ARTIFACT_NAME"
BUILD_INFO_PATH="$ROOT_DIR/build-artifacts/FlockFree-build-info.txt"
PACKAGE_NAME="com.yetiwurks.flockfree"
FIELD_DURATION="${FIELD_DURATION:-900}"

usage() {
	cat <<EOF
Usage: $(basename "$0") [--serial HOST:PORT] [--no-install] [--no-launch] [--skip-readiness] [--field-session]

Builds the current FlockFree debug APK, optionally installs it on the Moto over ADB,
launches it, and runs the standard no-Gradle readiness snapshot. With
--field-session it also starts the timed manual evidence collector.

Environment:
  PHONE_SERIAL   ADB serial, default: $PHONE_SERIAL
  ANDROID_HOME   Android SDK path, default: $ANDROID_HOME
  GRADLE_TASK    Gradle task, default: $GRADLE_TASK
  FLOCKFREE_ARTIFACT_VERSION
                 Sideload artifact version label, default: $FLOCKFREE_ARTIFACT_VERSION
  ARTIFACT_NAME  Output APK filename, default: $ARTIFACT_NAME
  FIELD_DURATION Timed field-session seconds, default: $FIELD_DURATION

Note: this script builds the OpenGL flavor so 2D/3D map mode and 3D buildings are available.
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
RUN_READINESS=1
RUN_FIELD_SESSION=0
while [[ $# -gt 0 ]]; do
	case "$1" in
		--serial)
			[[ $# -ge 2 ]] || {
				echo "Missing value for --serial" >&2
				exit 64
			}
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
		--skip-readiness)
			RUN_READINESS=0
			shift
			;;
		--field-session)
			RUN_FIELD_SESSION=1
			shift
			;;
		--field-duration)
			[[ $# -ge 2 ]] || {
				echo "Missing value for --field-duration" >&2
				exit 64
			}
			FIELD_DURATION="$2"
			shift 2
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

case "$FIELD_DURATION" in
	''|*[!0-9]*)
		echo "--field-duration must be a positive integer" >&2
		exit 64
		;;
esac

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

if [[ "$RUN_READINESS" -eq 1 && "$INSTALL" -eq 1 && "$RUN_FIELD_SESSION" -eq 0 ]]; then
	readiness_args=(--serial "$PHONE_SERIAL" --logcat-lines 2000)
	if [[ "$LAUNCH" -eq 0 ]]; then
		readiness_args+=(--no-launch)
	fi
	echo
	echo "Running no-Gradle readiness gate for the installed APK..."
	"$ROOT_DIR/scripts/flockfree-morning-readiness.sh" "${readiness_args[@]}"
elif [[ "$RUN_READINESS" -eq 1 && "$INSTALL" -eq 1 ]]; then
	echo
	echo "Readiness gate will run at the start of the timed field-test session."
elif [[ "$RUN_READINESS" -eq 1 ]]; then
	echo
	echo "Skipped readiness gate because --no-install was used. Run scripts/flockfree-morning-readiness.sh after installing."
else
	echo
	echo "Skipped readiness gate. Run scripts/flockfree-morning-readiness.sh when ready."
fi

if [[ "$RUN_FIELD_SESSION" -eq 1 && "$INSTALL" -eq 1 ]]; then
	field_args=(--serial "$PHONE_SERIAL" --duration "$FIELD_DURATION")
	if [[ "$LAUNCH" -eq 0 ]]; then
		field_args+=(--no-launch)
	fi
	if [[ "$RUN_READINESS" -eq 0 ]]; then
		field_args+=(--skip-readiness)
	fi
	echo
	echo "Starting timed FlockFree field-test evidence session..."
	"$ROOT_DIR/scripts/flockfree-field-test-session.sh" "${field_args[@]}"
elif [[ "$RUN_FIELD_SESSION" -eq 1 ]]; then
	echo
	echo "Skipped field-test session because --no-install was used. Install the APK, then run scripts/flockfree-field-test-session.sh."
else
	echo
	echo "For the manual field-test evidence window, run:"
	printf '  scripts/flockfree-field-test-session.sh --serial %q\n' "$PHONE_SERIAL"
fi
