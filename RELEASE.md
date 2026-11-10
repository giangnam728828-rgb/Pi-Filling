# Release Guide — how to build an installable APK

This project has never shipped a downloadable APK. The CI workflow here is the
first that produces one: it cross-compiles proot (Layer 2), bundles the wrapper
(Layer 3), signs the release, and publishes it as a GitHub Release.

## What you need

### A signing key (one-time)

The keystore is **the single most important file** in this project. Losing it
means you can never ship an update to an already-installed APK — Android will
reject it as a different signer.

1. On any machine with JDK 17+ installed:

   ```bash
   ./scripts/generate-keystore.sh
   ```

   This creates `release.jks` and prints four values. Save them somewhere safe
   (password manager + backup).

2. For local builds, write `android/keystore.properties`:

   ```properties
   storeFile=/full/path/to/release.jks
   storePassword=<STORE_PASSWORD>
   keyAlias=pifilling
   keyPassword=<KEY_PASSWORD>
   ```

3. For CI, add these four repository secrets (**Settings → Secrets and
   variables → Actions → New repository secret**):

   - `ANDROID_KEYSTORE_BASE64` — base64-encoded contents of `release.jks`
   - `ANDROID_KEYSTORE_PASSWORD` — the store password
   - `ANDROID_KEY_ALIAS` — usually `pifilling`
   - `ANDROID_KEY_PASSWORD` — the key password

### Android NDK r29 (for the first step)

Cross-compiling proot requires Android NDK r29 (stable, F-Droid-compliant).

- **On Ubuntu:** download from https://developer.android.com/ndk/downloads or
  use SDK Manager if you have Android Studio installed.
- Set `ANDROID_NDK_HOME` to the NDK directory, or put it under
  `$ANDROID_HOME/ndk/29.*`.

## Building locally

```bash
cd android/proot-bootstrap
./build-proot.sh
```

This takes ~15–25 minutes and outputs `.so` files to
`android/proot-bootstrap/.out/<abi>/`.

Then:

```bash
cd android
./gradlew assembleRelease --no-daemon
```

If `.out/` is missing, Gradle will refuse to build the release APK and print a
clear error message.

## Publishing on GitHub

Push a tag like `v0.2.0` and the `Release` workflow runs automatically:

```bash
git tag v0.2.0
git push origin v0.2.0
```

Or run manually from **Actions → Release → Run workflow**. The version is taken
from the tag; if triggered manually without a tag, use the input field.

The workflow:

1. Cross-compiles proot + talloc (requires NDK r29, Python 3).
2. Builds a signed release APK with R8 minification.
3. Verifies the APK contains proot binaries and the wrapper assets.
4. Creates a GitHub Release with the APK and SHA256 checksum.

### Version numbering

The tag determines both `versionName` and `versionCode`:

- `v0.2.0` → `versionName = "0.2.0"`, `versionCode = 200`
- `v1.3.4` → `versionName = "1.3.4"`, `versionCode = 10304`

Pre-release suffixes are preserved in the name (`0.2.0-beta.1`) but ignored for
the code calculation.

## Known limitations

- First release build has ever been produced; R8 behavior is unverified against
  production usage.
- Signing key is a development key unless the maintainer supplied a long-lived
  one — updates may require uninstalling first.
- Sandbox provisioning needs ~350 MB free storage and a network connection.
- Android 14+ limits `dataSync` foreground services to 6 hours per session.
- Aggressive OEM battery managers can kill the foreground service silently.

## Troubleshooting

### "libproot.so missing" on install

The APK was built without proot. Check that `android/proot-bootstrap/.out/`
contains `.so` files before running `assembleRelease`.

### Unsigned APK warning

CI will fail if the four signing secrets are not set. Local builds will emit a
warning and produce an unsigned APK — Android will reject it at install time.

### Build fails with "Kotlin cannot run on Java 25"

Gradle daemon is running on Java 25. Use JDK 17:

```bash
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleRelease
```

Do not set `org.gradle.java.home` in committed files; use shell env vars or
`~/.gradle/gradle.properties`.

---

This guide lives alongside the new `release.yml` workflow so maintainers know
what changed and why.
