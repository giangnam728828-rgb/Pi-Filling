#!/usr/bin/env bash
#
# build-apk.sh — build an installable, signed APK on this machine.
#
# Does the three things that are easy to get wrong by hand:
#
#   1. aapt2 on arm64. AGP ships an x86_64 aapt2; on an arm64 host it fails
#      with "Daemon startup failed". This routes it through the qemu shim
#      from setup-aapt2-qemu.sh when needed.
#   2. Version. versionCode must be >= the installed build or Android refuses
#      the update as a downgrade. Default comes from `git describe`, which
#      tracks the v* tags the release workflow uses.
#   3. Signing. Reads android/keystore.properties (gitignored) or the
#      ANDROID_KEYSTORE_* env vars. Without them the APK is unsigned and will
#      not install — so this refuses instead of producing a dead file.
#
# Usage:
#   ./scripts/build-apk.sh                 # version from git tags
#   ./scripts/build-apk.sh 0.15.0          # explicit version
#   ./scripts/build-apk.sh 0.15.0 1500     # explicit name + code
#   ./scripts/build-apk.sh --debug 0.15.0  # debug variant (debug-signed)

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
ANDROID_DIR="$PWD/android"

VARIANT="release"
if [[ "${1:-}" == "--debug" ]]; then
    VARIANT="debug"
    shift
fi

# ── Version ─────────────────────────────────────────────────────────────────
# Tags are the source of truth for the *display* name. The install code is
# derived from it, but the derivation is only a convenience: it is monotonic
# within a minor line and NOT across one. `0.20.0` computes to 2000, which is
# below `0.19.29`'s 2190, and Android refuses to install a lower code — the
# upgrade silently fails with "App not installed".
#
# So the code is allowed to be given explicitly as the second argument, and this
# script refuses to build when the result would not be an upgrade over what the
# tag history says came before. A wrong code costs a round trip through the
# phone; a rejected build costs nothing.
version_to_code() {
    local v="${1#v}" major minor patch
    IFS=. read -r major minor patch <<<"$v"
    patch="${patch:-0}"
    echo $(( major * 1000 + minor * 100 + patch * 10 ))
}

# Highest code any earlier release could have had, so an explicit code can be
# checked against it.
# Highest code any earlier build produced.
#
# Reads a local ledger rather than git tags: this project has never tagged a
# release, so a tag-based check always compared against 0 and passed everything —
# including `0.20.0`, whose code is below `0.19.29`'s and which Android refused to
# install. The ledger is written after every successful build, so the next build
# is checked against what actually shipped.
VERSION_LEDGER="$(cd "$(dirname "$0")/.." && pwd)/.last-version-code"

previous_version_code() {
    local from_ledger=""
    if [[ -f "$VERSION_LEDGER" ]]; then
        from_ledger="$(tr -dc '0-9' < "$VERSION_LEDGER")"
    fi
    local from_tag=""
    local tag
    tag="$(git tag --sort=-v:refname | head -1 2>/dev/null || true)"
    if [[ -n "$tag" ]]; then
        from_tag="$(version_to_code "$tag")"
    fi
    # Whichever is higher: a tag from another machine must not lower the bar.
    if [[ -n "$from_ledger" && -n "$from_tag" ]]; then
        if (( from_ledger > from_tag )); then echo "$from_ledger"; else echo "$from_tag"; fi
    elif [[ -n "$from_ledger" ]]; then
        echo "$from_ledger"
    elif [[ -n "$from_tag" ]]; then
        echo "$from_tag"
    else
        echo 0
    fi
}

record_version_code() {
    printf '%s\n' "$1" > "$VERSION_LEDGER"
}

if [[ $# -ge 1 && -n "${1:-}" ]]; then
    VERSION_NAME="${1#v}"
else
    VERSION_NAME="$(git describe --tags --abbrev=0 2>/dev/null | sed 's/^v//')"
    VERSION_NAME="${VERSION_NAME:-0.1.0}"
fi
VERSION_CODE="${2:-$(version_to_code "$VERSION_NAME")}"

# Refuse a code that would not install over the previous release. See the note
# above: this is the check that the `0.20.0` → 2000 regression would have hit.
PREV_CODE="$(previous_version_code)"
if (( VERSION_CODE <= PREV_CODE )) && [[ -z "${ALLOW_VERSION_DOWNGRADE:-}" ]]; then
    echo "error: versionCode $VERSION_CODE ($VERSION_NAME) is not above the" >&2
    echo "       previous release's $PREV_CODE; Android would refuse the install." >&2
    echo "       Pass an explicit code:  ./scripts/build-apk.sh $VERSION_NAME <code>" >&2
    echo "       (or set ALLOW_VERSION_DOWNGRADE=1 if this is intentional)" >&2
    exit 1
fi

# Consume the version arguments; anything left over goes to Gradle verbatim.
if [[ $# -ge 2 ]]; then shift 2; elif [[ $# -eq 1 ]]; then shift; fi

# ── aapt2 ───────────────────────────────────────────────────────────────────
GRADLE_ARGS=(--console=plain)
HOST_ARCH="$(uname -m)"
if [[ "$HOST_ARCH" != "x86_64" ]]; then
    # Only the arm64 path needs the shim; a native x86_64 host runs aapt2 itself.
    if "$ANDROID_DIR/../scripts/setup-aapt2-qemu.sh" --check 2>/dev/null; then
        :
    else
        echo "→ configuring the aapt2 qemu shim (arm64 host)"
        "$ANDROID_DIR/../scripts/setup-aapt2-qemu.sh"
    fi
    GRADLE_ARGS+=(-Pandroid.aapt2FromMavenOverride="${AAPT2_SHIM_DIR:-/root/tools/aapt2-bin}/aapt2")
fi

# ── Signing ─────────────────────────────────────────────────────────────────
if [[ "$VARIANT" == "release" ]]; then
    if [[ -f "$ANDROID_DIR/keystore.properties" ]] ||
       [[ -n "${ANDROID_KEYSTORE_PATH:-}" ]]; then
        :
    else
        cat >&2 <<'EOF'
error: no release signing configured, so the APK would not install.

Android only accepts an update signed by the same key as the installed app.
Point this build at that keystore, either in android/keystore.properties:

    storeFile=/absolute/path/to/release.p12
    storePassword=<STORE_PASSWORD>
    keyAlias=pifilling
    keyPassword=<KEY_PASSWORD>

…or via ANDROID_KEYSTORE_PATH / ANDROID_KEYSTORE_PASSWORD /
ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD.

If the original key is gone, no local build can upgrade an installed app —
the only path left is uninstall + install, which discards app data.
EOF
        exit 1
    fi
    if [[ ! -d "$ANDROID_DIR/proot-bootstrap/.out" ]]; then
        echo "warning: android/proot-bootstrap/.out/ is missing — the release" >&2
        echo "         guard will fail. Run build-proot.sh, or restore the .so" >&2
        echo "         files from a previously built APK." >&2
    fi
fi

# ── Bundled sandbox assets ──────────────────────────────────────────────────
# Checked before the build (so a missing image fails fast) and again on the
# finished APK (so an AGP setting that silently stopped applying is caught).
if [[ "$VARIANT" == "release" ]]; then
    echo "→ verifying bundled sandbox assets"
    "$ROOT/scripts/verify-rootfs-assets.sh"
fi

# ── Build ───────────────────────────────────────────────────────────────────
echo "→ building :app:assemble${VARIANT^}  $VERSION_NAME ($VERSION_CODE)"
cd "$ANDROID_DIR"
PIFILLING_VERSION_NAME="$VERSION_NAME" \
PIFILLING_VERSION_CODE="$VERSION_CODE" \
    ./gradlew ":app:assemble${VARIANT^}" "${GRADLE_ARGS[@]}" "$@"

APK="$ANDROID_DIR/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
[[ -f "$APK" ]] || { echo "error: expected $APK" >&2; exit 1; }

if [[ "$VARIANT" == "release" ]]; then
    echo
    "$ROOT/scripts/verify-apk-rootfs.sh" "$APK"
fi

# The build produced an artifact; remember its code so the next one is checked
# against it. Written here, not earlier, so a failed build never advances the bar.
record_version_code "$VERSION_CODE"

echo
echo "→ $APK"
du -h "$APK" | awk '{print "  size: "$1}'
if [[ -f "$ANDROID_DIR/keystore.properties" ]] || [[ -n "${ANDROID_KEYSTORE_PATH:-}" ]]; then
    APKSIGNER="$(find "${ANDROID_SDK_ROOT:-/root/android-sdk}/build-tools" -name apksigner 2>/dev/null | sort | tail -1)"
    [[ -n "$APKSIGNER" ]] && "$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | head -2
fi
