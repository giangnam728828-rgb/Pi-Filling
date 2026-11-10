import java.io.File
import java.util.Properties
import org.gradle.api.provider.Property

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Bundle the Layer 3 wrapper (node-wrapper/) into the APK assets so the sandbox
// can deploy it into the rootfs at first run (LinuxSandboxManager.deployWrapper).
// Emits into a generated build dir (NOT the tracked src tree) and registers it as
// an assets source below. node_modules is NOT bundled — `npm ci --omit=dev` runs
// inside Alpine, against the committed package-lock.json, after apk add nodejs npm.
val wrapperAssetsDir = layout.buildDirectory.dir("generated/wrapperAssets")
val copyWrapperAssets = tasks.register<Copy>("copyWrapperAssets") {
    from(rootProject.layout.projectDirectory.dir("../node-wrapper")) {
        include("src/**", "package.json", "package-lock.json")
        into("wrapper")
    }
    into(wrapperAssetsDir)
}

// ── proot cross-compile inputs ────────────────────────────────────────────────
// Layer 2's cross-compiled proot binaries live in ../proot-bootstrap/.out/ as
// <abi>/lib*.so. These helpers are declared at script level (not inside the
// android block) because both the sourceSets wiring and the release guard task
// need them.
val prootOutDir = rootProject.layout.projectDirectory.dir("proot-bootstrap/.out")
// Plain String so the guard task can use it inside doFirst without capturing
// script objects, which the configuration cache forbids.
val prootOutPath = prootOutDir.asFile.absolutePath
fun prootSoFiles(): List<File> =
    prootOutDir.asFile.takeIf { it.exists() }
        ?.walkTopDown()?.filter { it.extension == "so" }?.toList()
        ?: emptyList()

// ── Release signing ───────────────────────────────────────────────────────────
// A release APK must be signed to install. The keystore is never committed —
// `android/keystore.properties` is gitignored (local builds) and CI writes it
// from repository secrets. See scripts/generate-keystore.sh.
//
// Missing config is not an error: `assembleDebug` and CI's debug job must keep
// working on a fresh clone with no secrets. A release build with no signing
// config produces an unsigned APK, which Gradle allows and Android refuses to
// install — so we say so loudly rather than letting it fail opaquely later.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
}

fun propOrEnv(prop: String, env: String): String? =
    keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val releaseStoreFile = propOrEnv("storeFile", "ANDROID_KEYSTORE_PATH")
val releaseStorePassword = propOrEnv("storePassword", "ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = propOrEnv("keyAlias", "ANDROID_KEY_ALIAS")
val releaseKeyPassword = propOrEnv("keyPassword", "ANDROID_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword,
).all { it != null } && file(releaseStoreFile!!).exists()

// Version comes from the git tag on a release build (v0.2.0 → 0.2.0 / code
// 200), so the APK's version always matches the release it was published under.
// Falls back to the dev defaults outside a tagged build.
val gitVersionName: String? = System.getenv("PIFILLING_VERSION_NAME")?.takeIf { it.isNotBlank() }
val gitVersionCode: Int? = System.getenv("PIFILLING_VERSION_CODE")?.toIntOrNull()

android {
    namespace = "industries.huginn.pifilling"
    compileSdk = 35

    // ── bundled sandbox images ────────────────────────────────────────────────
    // Deliberately no `androidResources { noCompress }` here. It was tried and
    // measured: it does not help. The decompression happens in `mergeAssets`,
    // which runs long before packaging, so a packaging option cannot prevent
    // it. The fix is the `.tgz` suffix instead — see VerifyBundledSandboxTask
    // and scripts/verify-apk-rootfs.sh, which check the built APK rather than
    // trusting any build setting.

    defaultConfig {
        applicationId = "industries.huginn.pifilling"
        minSdk = 26
        targetSdk = 35
        versionCode = gitVersionCode ?: 1
        versionName = gitVersionName ?: "0.1.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // proot is shipped per-ABI as lib*.so under jniLibs (see sourceSets
        // below). Limit to the ABIs build-proot.sh actually produces.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // Layer 2's cross-compiled proot binaries live in ../proot-bootstrap/.out/
    // as <abi>/lib*.so. Run `android/proot-bootstrap/build-proot.sh` before a
    // release build. The dir is wired in only when present so a UI-only debug
    // build still compiles on a machine that hasn't run the cross-compile yet.
    if (prootSoFiles().isNotEmpty()) {
        sourceSets["main"].jniLibs.srcDir(prootOutDir)
    }

    // Shipping an APK with no proot is the exact failure this project kept
    // hitting: everything compiles, the UI runs, and the sandbox dies at
    // `libproot.so missing` on first use. A release must never do that.
    //
    // The guard runs as a task dependency at execution time, so it reports the
    // real state of .out/ rather than what existed when the build was
    // configured. The jniLibs wiring above stays at configuration time, which
    // is why CI restores .out/ before invoking Gradle.
    val prootReleaseGuard = tasks.register<CheckProotForReleaseTask>("checkProotForRelease") {
        group = "verification"
        description = "Fails a release build when proot is not cross-compiled."
        // The path is re-derived here rather than read from the script-level
        // val: a captured script reference is exactly what makes a task
        // unserialisable for the configuration cache, and this build has
        // `org.gradle.configuration-cache=true`.
        prootDir.set(
            rootProject.layout.projectDirectory.dir("proot-bootstrap/.out").asFile.absolutePath,
        )
    }
    // Scope this to the android block's own life; the wiring below lives
    // outside, so declare the dependency here where the task is visible.
    tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }
        .configureEach { dependsOn(prootReleaseGuard) }

    val verifyBundledSandbox = tasks.register<VerifyBundledSandboxTask>("verifyBundledSandbox") {
        group = "verification"
        description = "Fails a release build when the bundled sandbox assets are missing or not gzip."
        assetsDir.set(
            rootProject.layout.projectDirectory.dir("app/src/main/assets").asFile.absolutePath,
        )
    }
    tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }
        .configureEach { dependsOn(verifyBundledSandbox) }

    // The wrapper assets are generated by copyWrapperAssets into a build dir.
    sourceSets["main"].assets.srcDir(wrapperAssetsDir)

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // Both v1 and v2 signatures. v1 (JAR) is what lets the APK
                // install on minSdk 26 devices; v2 is what modern Android
                // prefers and what makes the APK verifiable as a whole.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
            if (!hasReleaseSigning) {
                // Gradle would happily emit an unsigned release APK, and the
                // only symptom is "App not installed" on a phone much later.
                logger.lifecycle(
                    "WARNING: no release signing config. The APK will be UNSIGNED " +
                        "and Android will refuse to install it. See " +
                        "scripts/generate-keystore.sh.",
                )
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // proot ELF binaries are shipped as .so but are NOT real shared libraries;
    // do not let the packager compress or page-align them away.
    // android.util.Log is a stub in JVM unit tests and throws by default, which
    // would fail every test that touches logging code rather than the code under
    // test. Returning defaults keeps WrapperClient and friends unit-testable on
    // the JVM without dragging in Robolectric.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Ensure the wrapper assets are copied before any build/asset-merge work.
tasks.named("preBuild").configure { dependsOn(copyWrapperAssets) }


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.haze)
    implementation(libs.haze.materials)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))

    debugImplementation(libs.androidx.ui.tooling)
}

// ── release guard, as a typed task ────────────────────────────────────────────
// Declared as a class rather than an inline `doFirst { }` lambda: a lambda in a
// .kts file implicitly captures the build script object, which Gradle cannot
// serialize into the configuration cache. With `org.gradle.configuration-cache`
// on, that turned every `assembleRelease` into a build that compiled the APK
// and then failed on the way out. A managed Property crosses the boundary as
// plain data, so the guard is cached like any other task.
abstract class CheckProotForReleaseTask : DefaultTask() {
    @get:Input
    abstract val prootDir: Property<String>

    @TaskAction
    fun check() {
        val path = prootDir.get()
        val soFiles = File(path).takeIf { it.exists() }
            ?.walkTopDown()?.filter { it.extension == "so" }?.toList()
            ?: emptyList()
        if (soFiles.isEmpty()) {
            throw GradleException(
                "Refusing to build a release APK without proot: " +
                    "$path contains no .so files, so the sandbox " +
                    "could not start and every user would hit " +
                    "\"libproot.so missing\". Run " +
                    "android/proot-bootstrap/build-proot.sh first (needs Android " +
                    "NDK r29, Python 3, git, curl, make).",
            )
        }
        logger.lifecycle("proot present: ${soFiles.size} .so files")
    }
}

// ── bundled sandbox assets, verified before they are shipped ──────────────────
// The defect this guards against is silent: an asset that is present but under
// a different name, or decompressed, installs "successfully" and then cannot
// run a single command. Checking here turns that into a failed build.
//
// Two files are required and both must be real gzip tarballs:
//   rootfs/alpine-baked-aarch64.tgz      complete Alpine + node/npm/git/bash
//   rootfs/wrapper-modules.tgz           the agent's node_modules
//
// The `.tgz` suffix is not cosmetic. AGP decompresses any asset whose name ends
// in `.gz` and drops that suffix at mergeAssets time — before any packaging
// option applies — so a `.tar.gz` asset arrives in the APK as a plain `.tar`
// under a different name and the app cannot find it. `.tgz` is left untouched.
// ARMv7 and x86_64 fall back to a network install, so they are not checked.
abstract class VerifyBundledSandboxTask : DefaultTask() {
    @get:Input
    abstract val assetsDir: Property<String>

    @TaskAction
    fun check() {
        val dir = File(assetsDir.get(), "rootfs")
        val required = listOf(
            "alpine-baked-aarch64.tgz" to "the ARM64 sandbox image",
            "wrapper-modules.tgz" to "the agent's bundled dependencies",
        )
        val problems = mutableListOf<String>()

        for ((name, what) in required) {
            val file = File(dir, name)
            when {
                !file.exists() -> problems += "$name is missing — $what. " +
                    "Run scripts/bake-rootfs.sh."
                file.length() < 1_000_000 -> problems +=
                    "$name is only ${file.length()} bytes — truncated $what."
                !file.isGzip() -> problems +=
                    "$name is not gzip (magic ${file.magicHex()}) — $what " +
                        "must be a gzipped tar, not a plain tar or an HTML error page."
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "Refusing to ship a release with an unusable sandbox:\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\n\nSee scripts/verify-rootfs-assets.sh.",
            )
        }
        logger.lifecycle("bundled sandbox: 2 gzip assets present in $dir")
    }

    private fun File.isGzip(): Boolean = runCatching {
        inputStream().use { input ->
            val magic = ByteArray(2)
            input.read(magic) == 2 &&
                magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
        }
    }.getOrDefault(false)

    private fun File.magicHex(): String = runCatching {
        inputStream().use { input ->
            val magic = ByteArray(4)
            val read = input.read(magic)
            magic.take(read).joinToString(" ") { "%02x".format(it) }
        }
    }.getOrDefault("unreadable")
}
