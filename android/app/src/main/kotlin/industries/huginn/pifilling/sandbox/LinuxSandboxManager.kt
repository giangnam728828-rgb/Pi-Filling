package industries.huginn.pifilling.sandbox

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileNotFoundException

/**
 * Owns the Alpine sandbox lifecycle on Android. Provisions the rootfs + Node
 * toolchain, deploys the Layer 3 wrapper into it, and launches the wrapper
 * process for [industries.huginn.pifilling.wrapper.WrapperClient] to drive.
 *
 * Layout (all app-private, under filesDir):
 *   linux-sandbox/
 *     rootfs/            extracted Alpine
 *     tmp/               PROOT_TMP_DIR
 *     rootfs/root/       guest HOME, bound to /root
 *     rootfs/root/wrapper/   the node wrapper (deployed from assets)
 *     .setup-complete    marker written after a successful provision
 */
class LinuxSandboxManager(
    private val context: Context,
    private val downloader: RootfsDownloader = RootfsDownloader(),
) {
    private val sandboxDir = File(context.filesDir, "linux-sandbox")
    private val rootfsDir = File(sandboxDir, "rootfs")
    private val tmpDir = File(sandboxDir, "tmp")
    private val homeDir = File(rootfsDir, "root")
    private val markerFile = File(sandboxDir, ".setup-complete")

    private val nativeLibDir: String get() = context.applicationInfo.nativeLibraryDir
    private val prootPath: String get() = File(nativeLibDir, "libproot.so").absolutePath

    private val deviceAbi: String get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    private val nativeLibStageDir = File(sandboxDir, "nativelib")

    /**
     * Work around a name mismatch between talloc's SONAME and what Android is
     * able to package.
     *
     * `libproot.so` records its dependency under talloc's real SONAME,
     * `libtalloc.so.2`. An APK only extracts entries matching `lib*.so` into
     * the native library dir, so the packaged copy is necessarily named
     * `libtalloc.so` and the dynamic linker cannot satisfy the dependency:
     *
     *     CANNOT LINK EXECUTABLE ".../libproot.so":
     *     library "libtalloc.so.2" not found: needed by main executable
     *
     * Stage a copy under the name the linker actually looks for, in
     * app-private storage, and put that directory on proot's library search
     * path. The fix lives here rather than in `build-proot.sh` because that
     * script is vendored byte-identical from Kai (see
     * `proot-bootstrap/VENDORED.md`) and Android packaging is our constraint,
     * not upstream's.
     *
     * Verified on hardware; see `android/VERIFICATION.md`.
     */
    private fun stageNativeLibs(): File {
        nativeLibStageDir.mkdirs()
        val source = File(nativeLibDir, "libtalloc.so")
        val staged = File(nativeLibStageDir, "libtalloc.so.2")
        // Re-copy when absent or when an app update changed the library.
        if (source.exists() && (!staged.exists() || staged.length() != source.length())) {
            source.inputStream().use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return nativeLibStageDir
    }

    private val _state = MutableStateFlow<SandboxState>(
        if (markerFile.exists()) SandboxState.Ready else SandboxState.NotInstalled,
    )
    val state: StateFlow<SandboxState> = _state.asStateFlow()

    val isReady: Boolean get() = _state.value is SandboxState.Ready

    fun createExecutor(): ProotExecutor = ProotExecutor(
        prootPath = prootPath,
        libDir = "${stageNativeLibs().absolutePath}:$nativeLibDir",
        rootfsPath = rootfsDir.absolutePath,
        homePath = homeDir.absolutePath,
        tmpPath = tmpDir.absolutePath,
    )

    /**
     * Provision the sandbox end-to-end. Idempotent: if the marker exists it
     * short-circuits to [SandboxState.Ready]. Safe to cancel — a cancelled
     * setup leaves no marker, so the next call re-provisions cleanly.
     */
    suspend fun setup() {
        if (markerFile.exists()) {
            ensureCurrent()
            _state.value = SandboxState.Ready
            return
        }
        try {
            check(File(prootPath).exists()) {
                "libproot.so missing from $nativeLibDir — run proot-bootstrap/build-proot.sh " +
                    "and bundle the .so files into jniLibs before building the app."
            }

            sandboxDir.mkdirs()
            tmpDir.mkdirs()
            homeDir.mkdirs()

            val archive = File(sandboxDir, "rootfs.tar.gz")
            _state.value = SandboxState.Downloading(0f)
            downloader.download(deviceAbi, archive) { fraction ->
                _state.value = SandboxState.Downloading(fraction.takeIf { it >= 0f })
            }

            _state.value = SandboxState.Extracting
            downloader.extract(archive, rootfsDir)
            archive.delete()
            downloader.writeResolvConf(rootfsDir)
            downloader.writeRepositories(rootfsDir)

            val executor = createExecutor()

            _state.value = SandboxState.Installing("apk update")
            val update = executor.execute("apk update", timeoutSeconds = 300)
            check(update.success) { "apk update failed: ${update.stderr.take(500)}" }

            _state.value = SandboxState.Installing(APK_PACKAGES)
            val add = executor.execute(
                "apk add --no-cache $APK_PACKAGES",
                // Downloading a dozen packages on mobile data easily outlives a
                // few minutes; the old 180s ceiling killed the install halfway
                // and then reported the sandbox as ready anyway.
                timeoutSeconds = 900,
            )
            check(add.success) { "apk add failed: ${add.stderr.take(500)}" }

            configureGitIdentity(executor)
        configureGitCredentialHelper(executor)

            _state.value = SandboxState.Installing("wrapper")
            deployWrapper(executor)

            writeMarker()
            _state.value = SandboxState.Ready
            Log.i(TAG, "sandbox ready ($deviceAbi, Alpine ${RootfsDownloader.ALPINE_VERSION})")
        } catch (e: Exception) {
            Log.e(TAG, "sandbox setup failed", e)
            _state.value = SandboxState.Error(e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * Copy the bundled wrapper (app assets `wrapper/`) into the rootfs and run a
     * production install. The app build copies `node-wrapper/{src,package.json,
     * package-lock.json}` into `assets/wrapper/` (see android/README.md).
     */
    /**
     * Bring an already-provisioned sandbox up to the current setup version.
     *
     * A sandbox provisioned by an older build keeps its marker, and
     * [SandboxState] starts `Ready` from that marker alone, so [setup] is never
     * called again — a new provisioning step would silently never reach existing
     * installs. Backfilling is far cheaper than forcing a ~350 MB re-provision
     * for what amounts to a couple of config writes.
     *
     * Cheap in the common case: a marker read and an integer compare, no proot
     * exec unless there is actually work to do.
     */
    suspend fun ensureCurrent() {
        if (!markerFile.exists()) return
        val have = readSetupVersion()
        // The wrapper source is refreshed on every pass, so only the recipe
        // version decides whether there is anything to do at all.
        if (have >= SETUP_VERSION && nodeModulesPresent(createExecutor())) return
        // Show the top-up in the UI. This used to be invisible: the sandbox
        // stayed "Ready" while an `apk add` ran, so a user who was told new
        // tools were bundled had no way to tell whether they had landed.
        if (have < SETUP_VERSION) _state.value = SandboxState.Upgrading
        runCatching {
            val executor = createExecutor()
            if (have < 2) configureGitIdentity(executor)
            if (have < 4) configureGitCredentialHelper(executor)

            // The agent goes first. It is the thing that makes chat work at all,
            // and it used to sit behind the package install, so an `apk add` that
            // could not reach a mirror took the agent down with it. Copying a few
            // hundred KB also costs nothing, so there is no reason to gate it on
            // a digest that could be wrong.
            deployWrapper(executor)

            // The guest toolchain is independent: a failure here leaves a
            // working agent that is merely missing a utility, which is far
            // better than a sandbox that cannot start one.
            if (have < SETUP_VERSION) {
                runCatching { installPackages(executor) }
                    .onFailure { Log.w(TAG, "package top-up failed; tools may be missing", it) }
            }
        }
            .onSuccess {
                writeMarker()
                Log.i(TAG, "backfilled sandbox setup v$have -> v$SETUP_VERSION")
            }
            .onFailure { Log.w(TAG, "sandbox backfill failed; the agent may hit it", it) }
        // Ready either way: a failed top-up leaves a usable sandbox, and the
        // per-session ensureCurrent() retries. Parking the UI on Upgrading
        // forever would be worse than the missing package.
        _state.value = SandboxState.Ready
    }

    /**
     * Names of the tools the app promises to have installed, for the settings
     * screen. Kept next to [APK_PACKAGES] so the two cannot drift silently.
     */
    val provisionedPackages: List<String>
        get() = APK_PACKAGES.split(" ").filter { it.isNotBlank() }

    /**
     * Install (or top up) the guest toolchain.
     *
     * `bash` is not incidental. The agent is told it has a "bash tool" and the
     * tool is named `bash`, but Alpine ships only busybox `ash`, so models
     * reasonably emit bash-only syntax and get:
     *
     *     sh: bash: not found
     *     hello.sh: line 12: syntax error: bad substitution
     *
     * Observed on device across two different models. Cheaper to make the
     * environment match what the agent is told it has than to teach every model
     * that the "bash tool" is not bash.
     */
    private suspend fun installPackages(executor: ProotExecutor) {
        val result = executor.execute("apk add --no-cache $APK_PACKAGES", timeoutSeconds = 900)
        check(result.success) { "apk add failed: ${result.stderr.take(300)}" }
    }

    /**
     * Give the sandbox a git identity.
     *
     * Alpine ships none, so `git commit` fails with "Author identity unknown".
     * Observed consequence on hardware: the agent burned several turns on failed
     * commits and then **invented** an identity, writing
     * `Assistant Bot <assistant@example.com>` into the *user's repo* local
     * config. Committing is the product's whole point (V1_SPEC), so the
     * environment should supply this rather than leaving the model to improvise
     * a plausible-looking author.
     *
     * The value is deliberately obviously-an-agent and uses the reserved
     * `.local` TLD: it must not read as a real person. Once Stage 1.5 wires
     * GitHub auth, the operator's own identity should replace it, because
     * commits that get pushed should carry the operator's name, not this.
     */
    private suspend fun configureGitIdentity(executor: ProotExecutor) {
        val result = executor.execute(
            "git config --global user.name '$GIT_USER_NAME' && " +
                "git config --global user.email '$GIT_USER_EMAIL' && " +
                "git config --global --add safe.directory '*'",
            timeoutSeconds = 30,
        )
        check(result.success) { "git config failed: ${result.stderr.take(300)}" }
    }

    /**
     * Teach git in the guest how to authenticate to GitHub, without writing the
     * credential anywhere.
     *
     * The helper is a shell function that reads `$GITHUB_TOKEN` from the
     * environment **at use time**, so the configured value contains no secret —
     * only the name of a variable. `git-credential-store`, the obvious
     * alternative, would persist the token in plaintext at
     * `~/.git-credentials` inside a rootfs that outlives the session.
     *
     * Scoped to `https://github.com` rather than set globally, so the helper is
     * never offered to some other host the agent happens to talk to.
     *
     * The username is literal: GitHub accepts any username when the password is
     * a PAT, and `x-access-token` is its documented convention.
     *
     * This does not hide the token from the agent — the `bash` tool inherits the
     * environment, and the agent is what runs `git push`. Containment is the
     * token's own scope, which is why V1_SPEC specifies a *fine-grained* PAT.
     * See SECURITY.md.
     */
    private suspend fun configureGitCredentialHelper(executor: ProotExecutor) {
        // Single-quoted in the shell and escaped here, so $GITHUB_TOKEN reaches
        // git's config verbatim and expands only when the helper runs.
        val helper = "!f() { echo username=x-access-token; echo password=\$GITHUB_TOKEN; }; f"
        val result = executor.execute(
            "git config --global credential.https://github.com.helper '$helper'",
            timeoutSeconds = 30,
        )
        check(result.success) { "git credential helper config failed: ${result.stderr.take(300)}" }
    }

    /**
     * Content digest of the wrapper bundled in this APK.
     *
     * Deploying the wrapper was originally a provisioning step, which meant a
     * new wrapper only reached a device that had never provisioned. Every
     * wrapper change after the first install was silently invisible — the
     * symptom was an agent missing tools the build had definitely registered.
     * Comparing content is what makes that impossible to get wrong; a
     * hand-bumped version number would only work when someone remembered.
     */
    private fun bundledSourceDigest(): String = bundledDigestOf("wrapper/src", "wrapper/package.json")

    /**
     * Digest of the dependency *inputs* only.
     *
     * Splitting this from [bundledSourceDigest] is what makes upgrades safe. When
     * both lived in one digest, editing a single line of wrapper source counted
     * as a dependency change and re-ran `npm ci` — which deletes node_modules
     * before fetching anything, so a phone that could not reach the npm registry
     * was left with no wrapper at all and died with exit code 1 on every prompt.
     */
    private fun bundledDepsDigest(): String =
        bundledDigestOf("wrapper/package.json", "wrapper/package-lock.json")

    private fun bundledDigestOf(vararg roots: String): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun walk(path: String) {
            val children = context.assets.list(path)
            if (children.isNullOrEmpty()) {
                runCatching {
                    context.assets.open(path).use { input ->
                        digest.update(path.toByteArray())
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            digest.update(buf, 0, n)
                        }
                    }
                }
            } else {
                children.sorted().forEach { walk("$path/$it") }
            }
        }
        roots.forEach { walk(it) }
        digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }.getOrDefault("unknown")

    /** Read one `key=value` field out of the marker, or "" when absent. */
    private fun readMarkerField(key: String): String = runCatching {
        val pattern = Regex("(?:^|\\s)" + Regex.escape(key) + "=([^\\s]+)")
        pattern.find(markerFile.readText())?.groupValues?.get(1).orEmpty()
    }.getOrDefault("")

    private fun writeMarker() {
        markerFile.writeText(
            "alpine=${RootfsDownloader.ALPINE_VERSION} abi=$deviceAbi setup=$SETUP_VERSION " +
                "wrapper=${bundledSourceDigest()} deps=${bundledDepsDigest()}\n",
        )
    }

    private fun readSetupVersion(): Int =
        readMarkerField("setup").takeWhile(Char::isDigit).toIntOrNull() ?: 0

    private suspend fun deployWrapper(executor: ProotExecutor) {
        // Stage through the guest's own /tmp instead of writing the bind target
        // directly from the host side.
        //
        // Writing `<homeDir>/wrapper` looks equivalent — proot binds that
        // directory onto `/root` — but on device the guest kept executing the
        // original `wrapper.mjs` no matter how many times it was "copied": the
        // agent answered `Unknown option '--base-url'` from a build that
        // demonstrably shipped the flag. Staging into the `/tmp` bind and
        // copying from *inside* the guest removes the host/guest path
        // assumption entirely, and the grep afterwards proves it took.
        val stage = File(tmpDir, "wrapper-deploy")
        stage.deleteRecursively()
        stage.mkdirs()
        copyAsset("wrapper", stage)

        val sync = executor.execute(
            "mkdir -p /root/wrapper && cp -rf /tmp/wrapper-deploy/. /root/wrapper/",
            timeoutSeconds = 180,
        )
        if (!sync.success) {
            throw IllegalStateException(
                "could not stage the agent into the sandbox: " + sync.stderr.take(400),
            )
        }
        // Positive check: if this build's wrapper is in place, its newest flag
        // is in it. `--reasoning` was added after `--base-url`, so a wrapper
        // staged in the `--base-url` era (the /tmp/wrapper-deploy fix) passes
        // the old check and then dies with "Unknown option '--reasoning'" the
        // moment the app sends it — exactly the code-1 crash this guard exists
        // to catch.
        val verify = executor.execute(
            "grep -c -- '--reasoning' /root/wrapper/src/wrapper.mjs || true",
            timeoutSeconds = 30,
        )
        if (verify.stdout.trim().toIntOrNull().let { it == null || it < 1 }) {
            throw IllegalStateException(
                "the staged agent looks stale (no --reasoning): " + verify.stdout.take(200),
            )
        }

        // Source is cheap to replace; dependencies are neither cheap nor
        // reliable to fetch. Only touch them when the lockfile actually moved,
        // or when there is nothing usable on disk.
        val depsChanged = readMarkerField("deps") != bundledDepsDigest()
        if (!depsChanged && nodeModulesPresent(executor)) return

        val install = executor.execute(
            // The default registry is frequently unreachable from mainland
            // networks and slow everywhere else; this mirror is a drop-in.
            "cd /root/wrapper && npm ci --omit=dev --no-audit --no-fund " +
                "--registry=https://registry.npmmirror.com",
            timeoutSeconds = 1500,
        )
        if (!install.success) {
            // A stale dependency tree still starts; a deleted one never does.
            // `npm ci` removes node_modules first, so a mid-way failure is the
            // one case that must abort loudly.
            check(nodeModulesPresent(executor)) {
                "npm ci failed: ${install.stderr.take(400)}"
            }
            Log.w(TAG, "npm ci failed, but the existing node_modules is usable")
        }
    }

    /** Cheap probe: is there a resolvable dependency tree in the guest? */
    /**
     * Report what is actually installed in the guest.
     *
     * The app used to claim tools were bundled with no way to check, which meant
     * "did that install?" could only be answered by reading logs the UI never
     * showed. This runs the probes in the guest and hands back the transcript.
     */
    suspend fun inspect(): String {
        if (!markerFile.exists()) return "沙盒尚未安装"
        val executor = runCatching { createExecutor() }.getOrElse {
            return "沙盒不可用: " + (it.message ?: it.javaClass.simpleName)
        }
        val probes = listOf(
            "echo '== 运行时 =='",
            "node -v || echo 'node 缺失'",
            "npm -v || echo 'npm 缺失'",
            "git --version || echo 'git 缺失'",
            "echo '== 工具 =='",
            "for t in bash curl jq make python3 rg tree patch tar xz gawk; do " +
                "if command -v \$t >/dev/null 2>&1; then echo \"ok   \$t\"; " +
                "else echo \"缺   \$t\"; fi; done",
            "echo '== wrapper =='",
            "ls -l /root/wrapper/src/wrapper.mjs 2>&1",
            "grep -c -- '--reasoning' /root/wrapper/src/wrapper.mjs 2>&1 || true",
            "echo '== 依赖 =='",
            "ls /root/wrapper/node_modules/@earendil-works 2>&1 || echo 'node_modules 缺失'",
        ).joinToString("; ")
        val r = executor.execute(probes, timeoutSeconds = 120)
        val text = (r.stdout + "\n" + r.stderr).trim()
        return if (text.isBlank()) "没有输出（退出码 ${r.exitCode}）" else text
    }

    /**
     * Install (or top up) the guest's apk packages on user request.
     *
     * [onLine] receives the guest's own output as it arrives, so the sheet can
     * show that apk is actually downloading instead of an inert spinner.
     */
    suspend fun installTools(onLine: ((String) -> Unit)? = null): String {
        if (!markerFile.exists()) return "沙盒尚未安装"
        val executor = runCatching { createExecutor() }.getOrElse {
            return "沙盒不可用: " + (it.message ?: it.javaClass.simpleName)
        }
        return runCatching {
            // Refresh the repositories list first: an install that outlived a
            // single-mirror provisioning would otherwise keep hitting the one
            // CDN that originally 404'd.
            downloader.writeRepositories(rootfsDir)
            onLine?.invoke("$ apk update")
            val update = executor.execute("apk update", timeoutSeconds = 300, onLine = onLine)
            onLine?.invoke("$ apk add $APK_PACKAGES")
            var add = executor.execute(
                "apk add --no-cache $APK_PACKAGES",
                timeoutSeconds = 900,
                onLine = onLine,
            )
            // A single flaky mirror file used to end the whole install. The
            // repositories file now lists every mirror, so one more pass — with
            // a fresh index — recovers the transient case before we call it.
            if (!add.success && !add.timedOut) {
                onLine?.invoke("（一次瞬时失败，换源重试）apk add 再次")
                executor.execute("apk update", timeoutSeconds = 300, onLine = onLine)
                add = executor.execute(
                    "apk add --no-cache $APK_PACKAGES",
                    timeoutSeconds = 900,
                    onLine = onLine,
                )
            }
            writeMarker()
            when {
                add.success -> "工具安装完成"
                else -> {
                    // "1 error; 167 MiB in 120 packages" names nothing, which is
                    // exactly what the user saw. apk prints the offenders on
                    // ERROR: lines — surface those so the sheet says *which*
                    // package, instead of "something failed".
                    val errors = (add.stdout + "\n" + add.stderr).lineSequence()
                        .filter { it.startsWith("ERROR:") || it.startsWith("WARN:") }
                        .distinct()
                        .take(8)
                        .joinToString("\n")
                    val detail = if (errors.isNotEmpty()) errors
                    else (add.stderr + update.stderr).takeLast(800)
                    "工具安装失败（退出码 ${add.exitCode}）:\n" + detail
                }
            }
        }.getOrElse { "失败: " + (it.message ?: it.javaClass.simpleName) }
    }

    /**
     * Redeploy the wrapper and, optionally, its dependencies.
     *
     * Exposed as a button because the automatic path can fail for reasons only
     * the device knows (registry unreachable, disk full), and the previous
     * behaviour was to log that and carry on pretending the sandbox was ready.
     */
    suspend fun redeploy(installDeps: Boolean, onLine: ((String) -> Unit)? = null): String {
        if (!markerFile.exists()) return "沙盒尚未安装"
        val executor = runCatching { createExecutor() }.getOrElse {
            return "沙盒不可用: " + (it.message ?: it.javaClass.simpleName)
        }
        return runCatching {
            downloader.writeRepositories(rootfsDir)
            val wrapperDir = File(homeDir, "wrapper")
            wrapperDir.mkdirs()
            copyAsset("wrapper", wrapperDir)
            if (!installDeps) {
                writeMarker()
                return@runCatching "wrapper 源码已重新部署"
            }
            onLine?.invoke("$ npm ci (仅依赖)")
            val r = executor.execute(
                "cd /root/wrapper && npm ci --omit=dev --no-audit --no-fund " +
                    "--registry=https://registry.npmmirror.com",
                timeoutSeconds = 1500,
                onLine = onLine,
            )
            writeMarker()
            if (r.success) {
                "依赖安装完成"
            } else {
                "依赖安装失败（退出码 ${r.exitCode}）:\n" + (r.stderr + r.stdout).takeLast(1200)
            }
        }.getOrElse { "失败: " + (it.message ?: it.javaClass.simpleName) }
    }

    private suspend fun nodeModulesPresent(executor: ProotExecutor): Boolean =
        runCatching {
            executor.execute(
                "test -d /root/wrapper/node_modules/@earendil-works/pi-agent-core",
                timeoutSeconds = 20,
            ).success
        }.getOrDefault(false)

    private fun copyAsset(assetPath: String, dest: File) {
        val assets = context.assets
        val children = assets.list(assetPath)
        // AssetManager.list() returns [] for an empty directory AND can return []
        // for some files, so we can't infer file-vs-dir from emptiness. Try to
        // open as a file; if that throws, it's a directory (possibly empty).
        if (children.isNullOrEmpty()) {
            try {
                assets.open(assetPath).use { input ->
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: FileNotFoundException) {
                dest.mkdirs()
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyAsset("$assetPath/$child", File(dest, child))
        }
    }

    /**
     * Launch the wrapper process inside the sandbox, returning the raw [Process].
     * The API key is supplied via env at spawn time (per ARCHITECTURE.md the key
     * never lands on disk); [repoGuestPath] is a path inside the rootfs, e.g.
     * `/root/repo`.
     */
    fun startWrapper(
        apiKey: String,
        repoGuestPath: String,
        provider: AgentProvider = AgentProvider.DEFAULT,
        model: String? = null,
        logLevel: String = "info",
        /**
         * Optional fine-grained GitHub PAT. Reaches git via the credential
         * helper configured at provisioning, which reads it from the
         * environment; it is never written to the guest filesystem. Omitting it
         * simply means `git push` fails to authenticate — everything else works.
         */
        gitHubToken: String? = null,
        /** Only used by the custom provider: endpoint and wire protocol. */
        baseUrl: String? = null,
        apiShape: String = "openai-completions",
        /** Custom provider only: tell the wrapper the endpoint reasons. */
        reasoning: Boolean = false,
    ): Process {
        check(isReady) { "sandbox not ready" }
        // Quote every user-supplied argument: the custom Base URL can contain a
        // query string, and a model id like `gpt-4 (preview)` must stay one arg.
        fun q(v: String) = "'" + v.replace("'", "'\\''") + "'"
        val modelArg = model?.takeIf { it.isNotBlank() }?.let { " --model " + q(it) } ?: ""
        val customArg = if (provider.isCustom) {
            val url = baseUrl?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("custom provider needs a base URL")
            " --base-url " + q(url) + " --api-shape " + q(apiShape) +
                (if (reasoning) " --reasoning" else "")
        } else ""
        val command =
            "node /root/wrapper/src/wrapper.mjs --repo " + q(repoGuestPath) +
                " --provider " + q(provider.id) + modelArg + customArg
        return createExecutor().start(
            command = command,
            workingDir = repoGuestPath,
            extraEnv = buildMap {
                // Each provider reads only its own variable, so this must match
                // the wrapper's PROVIDERS table (see AgentProvider).
                put(provider.keyEnv, apiKey)
                put("WRAPPER_LOG_LEVEL", logLevel)
                // Unlike the provider key, this one must survive into the bash
                // tool's children: git is what consumes it.
                gitHubToken?.takeIf { it.isNotBlank() }?.let { put("GITHUB_TOKEN", it) }
            },
        )
    }

    /**
     * Make sure the directory the wrapper is about to be pointed at exists.
     *
     * `wrapper.mjs` calls `fatal(2, "--repo path does not exist ...")` when
     * `--repo` is missing, and nothing in the codebase ever created the default
     * `/root/repo`. The result was that a freshly provisioned sandbox could
     * never start a session: setup succeeded, then every "Start session" died
     * with `wrapper process exited (code 2)` and no explanation of why.
     *
     * Idempotent: creating the directory is a no-op once it is there, and an
     * existing checkout is left alone (the `git rev-parse` guard means we never
     * re-init a repository that is already one).
     */
    suspend fun ensureRepoDir(repoGuestPath: String) {
        check(isReady) { "sandbox not ready" }
        val path = shellQuote(repoGuestPath)
        val result = createExecutor().execute(
            "mkdir -p $path" +
                " && cd $path" +
                " && { git rev-parse --is-inside-work-tree >/dev/null 2>&1 || git init -q; }",
            workingDir = "/root",
            timeoutSeconds = 60,
        )
        check(result.success) {
            "could not prepare $repoGuestPath: ${result.stderr.take(300)}"
        }
    }

    /**
     * Single-quote for busybox ash. Guest paths come from a text field, so they
     * can contain spaces or quotes; without this a path like `/root/my repo`
     * would split into two arguments and `mkdir` would silently make the wrong
     * thing.
     */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /** Delete the entire sandbox and return to [SandboxState.NotInstalled]. */
    fun reset() {
        sandboxDir.deleteRecursively()
        _state.value = SandboxState.NotInstalled
    }

    private companion object {
        const val TAG = "LinuxSandboxManager"

        /** Bump when setup() gains a step that existing sandboxes need backfilled. */
        const val SETUP_VERSION = 7
            // The agent is handed a "bash tool", so a real shell plus the everyday
    // utilities a coding agent reaches for must actually exist. Alpine's
    // busybox alone lacks bash, grep -P, curl, make, python3 etc., and models
    // otherwise emit commands that fail at runtime rather than doing the task.
    // Kept to a modest set: all of these are small alpine packages, and the
    // rootfs download (~350 MB) dominates provisioning time either way.
    const val APK_PACKAGES =
        "nodejs npm git bash coreutils grep sed gawk findutils " +
            "curl ca-certificates make python3 py3-pip tar gzip xz " +
            "diffutils patch util-linux tree jq openssh-client"
        const val GIT_USER_NAME = "Pi-Filling Agent"
        const val GIT_USER_EMAIL = "agent@pi-filling.local"
    }
}
