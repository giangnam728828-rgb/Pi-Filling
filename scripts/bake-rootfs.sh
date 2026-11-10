#!/bin/sh
# Rebuild the two binaries the APK ships so a first run needs no network:
#
#   assets/rootfs/alpine-baked-aarch64.tgz   Alpine + node, npm, git, bash,
#                                               python3 and the usual CLI tools
#                                               pre-installed (no `apk add`)
#   assets/rootfs/wrapper-modules.tgz        the wrapper's node_modules
#                                               (no `npm ci`)
#
# Why: every network step in provisioning was a way for setup to fail on a
# phone network — "1 error; 167 MiB in 120 packages" was the apk add, and
# `npm ci` was another 130 MB fetch. Both now arrive with the app.
#
# Requires network once, here, and a POSIX tar/gzip. Alpine packages are plain
# tarballs, so "installing" them is extraction plus a CA bundle rebuild — no
# proot and no root needed, which is why this runs anywhere.
set -eu
ALPINE=3.21.3
BRANCH=v3.21
ARCH=aarch64
MIRROR=${MIRROR:-https://mirrors.tuna.tsinghua.edu.cn/alpine}
PKGS="nodejs npm git bash coreutils grep sed gawk findutils curl
      ca-certificates make python3 py3-pip tar gzip xz diffutils patch
      util-linux tree jq openssh-client"
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ASSETS="$ROOT/android/app/src/main/assets/rootfs"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
echo "== minirootfs"
curl -fsSL "$MIRROR/$BRANCH/releases/$ARCH/alpine-minirootfs-$ALPINE-$ARCH.tar.gz" \
  | tar xz -C "$tmp"
mkdir -p "$tmp/etc/apk"
printf '%s\n' "$MIRROR/$BRANCH/main" "$MIRROR/$BRANCH/community" > "$tmp/etc/apk/repositories"

echo "== resolving packages (needs python3)"
python3 - "$tmp" "$MIRROR" "$BRANCH" "$ARCH" $PKGS <<'PY'
import io, json, os, re, sys, tarfile, urllib.request
root, mirror, branch, arch = sys.argv[1:5]
want = sys.argv[5:]
idx = {}
for repo in ("main", "community"):
    url = f"{mirror}/{branch}/{repo}/{arch}/APKINDEX.tar.gz"
    t = tarfile.open(fileobj=io.BytesIO(urllib.request.urlopen(url, timeout=180).read()))
    cur = {}
    for line in t.extractfile("APKINDEX").read().decode("utf-8", "replace").splitlines():
        if not line:
            if cur.get("P"): idx[cur["P"]] = cur
            cur = {}
            continue
        k, _, v = line.partition(":")
        if k in "PVDTS": cur[k] = v
    if cur.get("P"): idx[cur["P"]] = cur
prov = {}
for name, m in idx.items():
    for p in m.get("p", "").split():
        prov.setdefault(re.split(r"[<>=~]", p)[0], name)
def deps(p):
    return [re.split(r"[<>=~]", x)[0] for x in idx[p].get("D", "").split()
            if x and not x.startswith("!")]
seen, order, queue = set(), [], list(want)
while queue:
    d = queue.pop()
    p = d if d in idx else prov.get(d)
    if p is None or p in seen: continue
    seen.add(p); order.append(p); queue.extend(deps(p))
print(f"== {len(order)} packages")
def target(name, link):
    return link if link.startswith("/") else "/" + os.path.normpath(os.path.join(os.path.dirname(name), link))
files = 0
for p in order:
    apk = f"{idx[p]['V']}"
    data = None
    for repo in ("main", "community"):
        try:
            data = urllib.request.urlopen(f"{mirror}/{branch}/{repo}/{arch}/{p}-{apk}.apk", timeout=180).read()
            break
        except Exception:
            continue
    if data is None:
        sys.exit(f"missing package: {p}")
    with tarfile.open(fileobj=io.BytesIO(data)) as t:
        for m in t.getmembers():
            base = os.path.basename(m.name)
            if base.startswith((".PKGINFO", ".SIGN")) or m.name.startswith("."):
                continue
            name = m.name.lstrip("./")
            dest = os.path.join(root, name)
            if m.isdir():
                os.makedirs(dest, exist_ok=True); continue
            os.makedirs(os.path.dirname(dest) or root, exist_ok=True)
            if os.path.lexists(dest): os.remove(dest)
            if m.issym():
                os.symlink(target(name, m.linkname), dest); continue
            if m.islnk():
                os.link(os.path.join(root, m.linkname.lstrip("./")), dest); continue
            f = t.extractfile(m)
            if f is None: continue
            with open(dest, "wb") as o: o.write(f.read())
            os.chmod(dest, m.mode & 0o7777)
            files += 1
print(f"== {files} files extracted")

# update-ca-certificates never ran (no scripts in this flow), and without it
# every HTTPS call inside the sandbox fails.
certs = []
for dirpath, _, names in os.walk(os.path.join(root, "usr/share/ca-certificates")):
    certs += [os.path.join(dirpath, n) for n in names if n.endswith(".crt")]
bundle = "\n".join(open(c, encoding="utf-8", errors="replace").read() for c in sorted(certs))
for rel in ("etc/ssl/certs/ca-certificates.crt", "etc/ssl/cert.pem"):
    p = os.path.join(root, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    if os.path.lexists(p): os.remove(p)
    open(p, "w").write(bundle)
print(f"== CA bundle: {bundle.count('BEGIN CERTIFICATE')} certs")
PY

echo "== apk database"
mkdir -p "$tmp/lib/apk/db" "$tmp/etc/apk"
echo "$PKGS" | tr ' ' '\n' | sed '/^$/d' > "$tmp/etc/apk/world"

echo "== rootfs tarball"
mkdir -p "$ASSETS"
# .tgz, not .tar.gz: AGP decompresses any asset whose name ends in .gz and
# drops the suffix at mergeAssets time, so a .tar.gz asset arrives as a
# plain .tar under a different name and the app cannot find it. A .tgz
# suffix survives untouched (verified by probe; see 0.19.1 changelog).
tar czf "$ASSETS/alpine-baked-$ARCH.tgz" -C "$tmp" .

echo "== wrapper dependencies"
cd "$ROOT/node-wrapper"
[ -d node_modules ] || npm ci --omit=dev --no-audit --no-fund --registry=https://registry.npmmirror.com
# Source maps and docs are 60+ MB of the tree and are never loaded at runtime.
find node_modules -name '*.map' -delete
rm -rf node_modules/@earendil-works/*/docs node_modules/@earendil-works/*/examples
tar czf "$ASSETS/wrapper-modules.tgz" node_modules

ls -lh "$ASSETS"
