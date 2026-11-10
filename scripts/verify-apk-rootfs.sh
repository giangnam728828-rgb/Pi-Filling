#!/usr/bin/env bash
#
# verify-apk-rootfs.sh — check what the APK actually contains, not what the
# build was configured to do.
#
# `androidResources.noCompress` is a request. This script is the evidence: it
# opens the finished APK and asserts that the two bundled sandbox assets are
# present under exactly the names the Kotlin reads, and that their first bytes
# are still the gzip magic. A build option that silently stops applying is
# precisely the failure this whole check exists to catch.
#
# Usage: ./scripts/verify-apk-rootfs.sh <apk>
set -euo pipefail

APK="${1:-}"
[[ -n "$APK" ]] || { echo "usage: $0 <path-to.apk>" >&2; exit 2; }
[[ -f "$APK" ]] || { echo "no such APK: $APK" >&2; exit 2; }

fail=0
probe="$(mktemp -d)"
trap 'rm -rf "$probe"' EXIT

# Name -> what the Kotlin opens (must match LinuxSandboxManager).
declare -A REQUIRED=(
    ["assets/rootfs/alpine-baked-aarch64.tgz"]="the ARM64 sandbox image"
    ["assets/rootfs/wrapper-modules.tgz"]="the agent's bundled dependencies"
)

echo "APK: $APK"
echo "--- bundled sandbox assets ---"

for path in "${!REQUIRED[@]}"; do
    what="${REQUIRED[$path]}"
    base="${path%.tgz}"         # the names an older/other build could produce
    if unzip -l "$APK" "$path" >/dev/null 2>&1; then
        # Extract to a file instead of piping into `head`: closing the pipe early
        # makes unzip die of SIGPIPE, and `set -o pipefail` then reports 141 and
        # kills this script mid-report.
        rm -rf "$probe"
        mkdir -p "$probe"
        unzip -o -q "$APK" "$path" -d "$probe" >/dev/null 2>&1 || true
        magic=$(od -An -tx1 -N2 "$probe/$path" 2>/dev/null | tr -d ' \n')
        if [[ "$magic" == "1f8b" ]]; then
            printf '  ✓ %s (gzip)\n' "$path"
        else
            printf '  ✗ %s is present but magic=%s, not gzip\n' "$path" "$magic"
            fail=1
        fi
    elif unzip -l "$APK" "$base.tar.gz" >/dev/null 2>&1 ||
         unzip -l "$APK" "$base.tar" >/dev/null 2>&1; then
        printf '  ✗ %s is MISSING; the APK has a legacy name instead\n' "$path"
        printf '      → %s was baked with the old .tar.gz name and got mangled.\n' "$what"
        printf '      → Re-run scripts/bake-rootfs.sh so it is produced as .tgz.\n'
        fail=1
    else
        printf '  ✗ %s is MISSING entirely (%s)\n' "$path" "$what"
        fail=1
    fi
done

echo "--- apk contents sanity ---"
# Stale, no-longer-read variants would only waste space; warn, don't fail.
for stale in \
    "assets/rootfs/alpine-baked-aarch64.tar" \
    "assets/rootfs/alpine-baked-aarch64.tar.gz" \
    "assets/rootfs/wrapper-modules.tar" \
    "assets/rootfs/wrapper-modules.tar.gz"; do
    if unzip -l "$APK" "$stale" >/dev/null 2>&1; then
        printf '  ! %s is also present (unused; wastes space)\n' "$stale"
    fi
done

# The Kotlin must be asking for the names the APK actually ships.
src="$PWD/android/app/src/main/kotlin/industries/huginn/pifilling/sandbox/LinuxSandboxManager.kt"
if [[ -f "$src" ]]; then
    for want in "alpine-baked-aarch64" "wrapper-modules"; do
        if grep -q "$want" "$src"; then
            printf '  ✓ Kotlin references "%s"\n' "$want"
        else
            printf '  ✗ Kotlin does not reference "%s" — asset and code disagree\n' "$want"
            fail=1
        fi
    done
fi

echo
if (( fail )); then
    echo "FAILED: the APK cannot provide its bundled sandbox." >&2
    exit 1
fi
echo "OK: both assets ship as gzip under the names the app reads."
