#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_FILE="${INKA_RELEASE_CONFIG:-${ROOT_DIR}/.release-signing.properties}"
RUN_TESTS=1

usage() {
  cat <<'EOF'
Usage: scripts/build-release.sh [--skip-tests]

Builds signed release artifacts using local signing values from:
  .release-signing.properties

Required values:
  INKA_RELEASE_STORE_FILE
  INKA_RELEASE_STORE_PASSWORD
  INKA_RELEASE_KEY_ALIAS
  INKA_RELEASE_KEY_PASSWORD

The signing file and keystore are ignored by git.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-tests)
      RUN_TESTS=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -f "$CONFIG_FILE" ]]; then
  while IFS= read -r raw_line || [[ -n "$raw_line" ]]; do
    line="${raw_line%$'\r'}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"

    [[ -z "$line" || "${line:0:1}" == "#" ]] && continue
    [[ "$line" == *"="* ]] || continue

    key="${line%%=*}"
    value="${line#*=}"
    key="${key#"${key%%[![:space:]]*}"}"
    key="${key%"${key##*[![:space:]]}"}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"

    if [[ "${#value}" -ge 2 ]]; then
      first="${value:0:1}"
      last="${value: -1}"
      if [[ "$first" == "$last" && ( "$first" == "\"" || "$first" == "'" ) ]]; then
        value="${value:1:${#value}-2}"
      fi
    fi

    case "$key" in
      INKA_RELEASE_STORE_FILE|INKA_RELEASE_STORE_PASSWORD|INKA_RELEASE_KEY_ALIAS|INKA_RELEASE_KEY_PASSWORD)
        printf -v "$key" '%s' "$value"
        export "$key"
        ;;
    esac
  done < "$CONFIG_FILE"
fi

missing=0
for name in \
  INKA_RELEASE_STORE_FILE \
  INKA_RELEASE_STORE_PASSWORD \
  INKA_RELEASE_KEY_ALIAS \
  INKA_RELEASE_KEY_PASSWORD
do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required signing value: ${name}" >&2
    missing=1
  fi
done

if [[ "$missing" == "1" ]]; then
  echo "" >&2
  echo "Create ${CONFIG_FILE} from .release-signing.properties.example, or export the values in your shell." >&2
  exit 1
fi

if [[ "${INKA_RELEASE_STORE_FILE}" != /* ]]; then
  INKA_RELEASE_STORE_FILE="${ROOT_DIR}/${INKA_RELEASE_STORE_FILE}"
fi

if [[ ! -f "$INKA_RELEASE_STORE_FILE" ]]; then
  echo "Keystore not found: ${INKA_RELEASE_STORE_FILE}" >&2
  exit 1
fi

export ORG_GRADLE_PROJECT_INKA_RELEASE_STORE_FILE="$INKA_RELEASE_STORE_FILE"
export ORG_GRADLE_PROJECT_INKA_RELEASE_STORE_PASSWORD="$INKA_RELEASE_STORE_PASSWORD"
export ORG_GRADLE_PROJECT_INKA_RELEASE_KEY_ALIAS="$INKA_RELEASE_KEY_ALIAS"
export ORG_GRADLE_PROJECT_INKA_RELEASE_KEY_PASSWORD="$INKA_RELEASE_KEY_PASSWORD"

cd "$ROOT_DIR"

tasks=(assembleRelease bundleRelease)
if [[ "$RUN_TESTS" == "1" ]]; then
  tasks=(test "${tasks[@]}")
fi

./gradlew "${tasks[@]}"

cat <<EOF

Release artifacts:
  APK: app/build/outputs/apk/release/app-release.apk
  AAB: app/build/outputs/bundle/release/app-release.aab
EOF
