#!/usr/bin/env bash
#
# verify-rootfs-assets.sh — fail before the APK is built, not after it fails on
# someone's phone.
#
# The defect this exists for: an asset named `*.tar.gz` reaches the APK as a
# plain tar named `*.tar`, because AGP's mergeAssets decompresses anything ending
# in `.gz` and drops that suffix. The app then cannot find its bundled sandbox,
# silently downloads one instead, and on a bad connection never finishes
# installing. Nothing about that failure points at the asset.
#
# Assets are therefore baked as `.tgz`, which mergeAssets leaves alone. This
# script checks the source side; verify-apk-rootfs.sh checks the built APK.
#
# Checks three things per required asset: present, plausible size, gzip magic.
# Run by scripts/build-apk.sh and by the release workflow.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$ROOT/android/app/src/main/assets/rootfs"
fail=0

check() {
    local name="$1" what="$2" min="${3:-1000000}"
    local f="$DIR/$name"
    if [[ ! -f "$f" ]]; then
        printf '  ✗ %s — missing (%s)\n' "$name" "$what"
        fail=1
        return
    fi
    local size
    size=$(stat -c%s "$f" 2>/dev/null || echo 0)
    if (( size < min )); then
        printf '  ✗ %s — only %s bytes, truncated (%s)\n' "$name" "$size" "$what"
        fail=1
        return
    fi
    local magic
    magic=$(od -An -tx1 -N2 "$f" | tr -d ' \n')
    if [[ "$magic" != "1f8b" ]]; then
        printf '  ✗ %s — magic %s, not gzip; %s must be a gzipped tar\n' "$name" "$magic" "$what"
        fail=1
        return
    fi
    printf '  ✓ %s (%s, gzip)\n' "$name" "$(numfmt --to=iec "$size" 2>/dev/null || echo "${size}B")"
}

echo "bundled sandbox assets ($DIR)"
check "alpine-baked-aarch64.tgz" "the ARM64 sandbox image"
check "wrapper-modules.tgz"      "the agent's bundled dependencies"

if (( fail )); then
    echo
    echo "Refusing to build. Run scripts/bake-rootfs.sh to regenerate them." >&2
    exit 1
fi
