import java.io.File
import java.util.Properties

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
    val prootReleaseGuard = tasks.register("checkProotForRelease") {
        group = "verification"
        description = "Fails a release build when proot is not cross-compiled."
        doFirst {
            val soFiles = prootSoFiles()
            if (soFiles.isEmpty()) {
                throw GradleException(
                    "Refusing to build a release APK without proot: " +
                        "${prootOutDir.asFile} contains no .so files, so the sandbox " +
                        "could not start and every user would hit " +
                        "\"libproot.so missing\". Run " +
                        "android/proot-bootstrap/build-proot.sh first (needs Android " +
                        "NDK r29, Python 3, git, curl, make).",
                )
            }
            logger.lifecycle("proot present: ${soFiles.size} .so files")
        }
    }
    // Scope this to the android block's own life; the wiring below lives
    // outside, so declare the dependency here where the task is visible.
    tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }
        .configureEach { dependsOn(prootReleaseGuard) }

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

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))

    debugImplementation(libs.androidx.ui.tooling)
}
