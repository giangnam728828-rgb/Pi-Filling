package industries.huginn.pifilling.sandbox

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import industries.huginn.pifilling.sandbox.meta.AtomicStore
import industries.huginn.pifilling.sandbox.meta.ProcIdentity
import industries.huginn.pifilling.sandbox.meta.ProcTree
import industries.huginn.pifilling.sandbox.meta.RecycleRecord
import industries.huginn.pifilling.sandbox.meta.EnvironmentGate
import industries.huginn.pifilling.sandbox.meta.GateInputs
import industries.huginn.pifilling.sandbox.meta.GateVerdict
import industries.huginn.pifilling.sandbox.meta.ProcessRegistry
import industries.huginn.pifilling.sandbox.meta.ScanOutcome
import industries.huginn.pifilling.sandbox.meta.evaluateGate
import industries.huginn.pifilling.sandbox.meta.ReservationState
import industries.huginn.pifilling.sandbox.meta.SpawnReservation
import industries.huginn.pifilling.sandbox.meta.SpawnReservationStore
import industries.huginn.pifilling.sandbox.meta.ReadResult
import industries.huginn.pifilling.sandbox.meta.WriteResult
import industries.huginn.pifilling.sandbox.meta.WriteStage
import industries.huginn.pifilling.ui.AppLanguage
import industries.huginn.pifilling.ui.LocaleSettings
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicLong

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
    /** The user's apk-mirror choice; empty means "follow the locale guess". */
    private val apkMirror: String
        get() = context.getSharedPreferences("pifilling_runtime", Context.MODE_PRIVATE)
            .getString("apk_mirror", "") ?: ""

    /** Mirrors to write into etc/apk/repositories, user choice first if set. */
    private fun apkMirrors(): List<String> {
        val choice = apkMirror
        if (choice.isNotBlank()) return downloader.orderMirrors(choice)
        // No explicit choice: order by the language the app is *drawn* in, not
        // the JVM locale, so "automatic" is correct for a user who forced this
        // app into Chinese on an English phone.
        return downloader.orderMirrors(null, appDisplayLanguage())
    }

    /**
     * The BCP language the interface is currently drawn in.
     *
     * This is what "automatic" mirror order should follow, and it is *not* the
     * same as `java.util.Locale.getDefault()`: that is the phone's system
     * locale, which a user can override for this app alone via the language
     * setting. Reading the persisted choice is the only way to know the mirror
     * order actually matches what the user is looking at.
     */
    private fun appDisplayLanguage(): String = when (val lang = LocaleSettings(context).language) {
        AppLanguage.SYSTEM -> java.util.Locale.getDefault().language
        AppLanguage.CHINESE, AppLanguage.CHINESE_TRADITIONAL -> "zh"
        AppLanguage.ENGLISH -> "en"
    }
    private val sandboxDir = File(context.filesDir, "linux-sandbox")
    private val rootfsDir = File(sandboxDir, "rootfs")
    private val tmpDir = File(sandboxDir, "tmp")
    private val homeDir = File(rootfsDir, "root")
    private val markerFile = File(sandboxDir, ".setup-complete")

    /**
     * Crash-safe storage for the marker and, from B2 onwards, the transaction
     * logs. Physically outside [sandboxDir] on purpose: that directory is the
     * one a wipe deletes, and recovery metadata must not be collateral damage.
     * See the 2c-B plan, ruling A.
     */
    private val metaStore = AtomicStore(File(context.filesDir, "sandbox-meta"))

    /**
     * Tracks processes this app started, so a wipe can tell whether anything
     * still holds the environment.
     *
     * Shares [metaStore] so the registry and the wipe sentinel live under the
     * same root — and, deliberately, outside `linux-sandbox/`, which is the
     * directory a wipe deletes.
     */
    private val registry = ProcessRegistry(metaStore)

    /** Durable record of an in-flight spawn; see [spawnRegistrar]. */
    private val reservationStore = SpawnReservationStore(metaStore)

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
        if (markerFile.exists()) SandboxState.Ready(verifiedAt = 0L)
        else SandboxState.NotInstalled,
    )
    val state: StateFlow<SandboxState> = _state.asStateFlow()

    val isReady: Boolean get() = _state.value is SandboxState.Ready

    // ---- single-writer gate ------------------------------------------------
    // Only one sandbox-modifying operation may run at a time. Before this,
    // provision() launched a coroutine per tap with no coordination at all: two
    // taps meant two coroutines extracting into the same directory, and either
    // could publish state last. The second request is now refused outright
    // rather than queued — queueing would look like "tapping twice installs
    // twice", which is worse than a clear "already installing".
    /**
     * The single coordinator for environment access.
     *
     * Everything that spawns a process, or decides whether one may be spawned,
     * goes through this instance. Two lock sets — this one and a manager-local
     * pair — would not exclude each other even if each were individually
     * correct, which is why there is exactly one.
     */
    private val gate = EnvironmentGate(
        reservationState = { reservationStore.state() },
        scanRegistry = { registry.scan() },
    )

    /** True while a recycle or maintenance hazard is unresolved. */
    private val _maintenanceBlocked = MutableStateFlow(false)
    val maintenanceBlocked: StateFlow<Boolean> = _maintenanceBlocked.asStateFlow()

    /**
     * True when the last attempt to persist the recycle record failed.
     *
     * Kept apart from [maintenanceBlocked] because they answer different
     * questions: one is "is it safe to proceed", the other is "is our record of
     * that safety durable". A flow update succeeding means nothing about the
     * disk — conflating the two is how a block would silently evaporate on the
     * next launch.
     */
    private val _recyclePersistenceFailed = MutableStateFlow(false)
    val recyclePersistenceFailed: StateFlow<Boolean> = _recyclePersistenceFailed.asStateFlow()

    /**
     * Update both gate flags together.
     *
     * They must move together: a state where "persistence failed" is visible
     * without "maintenance blocked" invites a caller to proceed on a record it
     * could not write. Both the flow and the gate see the same value, so a
     * spawn attempt cannot slip past on a stale flag.
     */
    /**
     * Abandon a reservation that provably never started a process.
     *
     * A reservation with no pid and an empty registry describes a spawn that
     * died before `Process.start()` returned — there is nothing running to
     * account for. Without this exit such a reservation would lock the
     * environment permanently, and the only recovery would be clearing app
     * data, which destroys the sandbox and the conversation history with it.
     *
     * Deliberately narrow. It refuses when:
     *  - the reservation records a pid (a process may exist), or
     *  - the registry is unreadable (cannot rule one out), or
     *  - the registry has entries (something is recorded).
     *
     * In those cases the reservation must stay and the gate must remain shut.
     */
    suspend fun abandonStalledReservation(): String {
        val result: String? = gate.withRecovery {
            val state = reservationStore.state()
            when (state) {
                ReservationState.None -> "没有待处理的 reservation"
                is ReservationState.Unreadable -> "reservation 无法读取，不能放弃"
                is ReservationState.Pending -> abandonPending(state.reservation)
            }
        }
        return result ?: "正在执行其他操作，请稍候"
    }

    /**
     * Reconcile the registry with reality, and clear it when everything is gone.
     *
     * The registry is the gate's memory of what it started. A process that died
     * without being released — the app crashed, or the wrapper was killed by the
     * OS — leaves an entry behind that keeps the gate shut forever. That is the
     * correct default (better a stuck gate than deleting under a live process),
     * but it needs an exit: this is it, and it only opens when the processes are
     * confirmed gone.
     *
     * Refuses on anything short of certainty: alive, reused, unverifiable or an
     * unreadable /proc all leave the registry untouched.
     */
    suspend fun reconcileRegistry(): String {
        val result: String? = gate.withRecovery {
            when (val outcome = registry.scan()) {
                ScanOutcome.Empty -> "registry 为空，无需处理"
                is ScanOutcome.AllDead -> when (val r = registry.clear()) {
                    is WriteResult.Ok -> "已清理 ${outcome.rounds} 轮后确认退出的登记"
                    is WriteResult.Failed -> "清理失败: ${r.stage}"
                }
                is ScanOutcome.AllAlive ->
                    "仍有 ${outcome.alive.size} 个进程在运行（pid " +
                        outcome.alive.joinToString { it.pid.toString() } + "），不能清理"
                is ScanOutcome.Partial ->
                    "部分进程仍在运行，不能清理"
                is ScanOutcome.HasReused ->
                    "存在 pid 复用，身份无法确认，不能清理"
                is ScanOutcome.Unverifiable ->
                    "无法核验进程状态（${outcome.reason}），不能清理"
            }
        }
        val answer = result ?: "正在执行其他操作，请稍候"
        // Report the state as it stands *after* the attempt, not just the
        // verdict. "No registry" and "cleared the registry" look the same to a
        // user who pressed the button expecting something to happen, and the
        // difference matters when the next message still fails.
        val after = when (val s = registry.state()) {
            ProcessRegistry.RegistryState.Empty -> "空"
            is ProcessRegistry.RegistryState.Present -> "${s.record.entries.size} 条"
            is ProcessRegistry.RegistryState.Unreadable -> "不可读"
        }
        return "$answer（当前 registry：$after）"
    }

    /**
     * Persist a registry record on the registry's behalf.
     *
     * The registry's own encoder is private, and exposing it would let callers
     * write arbitrary rows; the prune path only ever needs to write back what
     * `state()` returned with entries removed, so re-serialising here with the
     * same field order is safe and keeps the encoder sealed.
     */
    /**
     * Write the recovery snapshot automatically, at the moment something fails.
     *
     * Manual export relies on the user pressing a second button while the state
     * is still relevant, and the interesting state is usually gone by then.
     * Failures are rare, so the file cost is negligible.
     */
    private fun autoExport(reason: String) {
        runCatching {
            val json = recoverySnapshot()
            val dir = File("/sdcard/Download/DSH-Go")
            if (!dir.exists() && !dir.mkdirs()) return
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val file = File(dir, "pifilling-auto-$reason-$stamp.json")
            file.writeText(json)
        }
    }

    private fun persistRecord(record: RecycleRecord): WriteResult {
        val json = buildString {
            append("{\"emptyRounds\":").append(record.emptyRounds).append(",\"entries\":[")
            record.entries.forEachIndexed { i, e ->
                if (i > 0) append(',')
                append("{\"pid\":").append(e.identity.pid)
                append(",\"start\":").append(e.identity.startTimeTicks)
                append(",\"treeComplete\":").append(e.treeComplete)
                append(",\"spawnId\":\"").append(e.spawnId).append("\"}")
            }
            append("]}")
        }
        return metaStore.writeText(ProcessRegistry.RECYCLE_NAME, json)
    }

    /** The body of [abandonStalledReservation] once a pending record was found. */
    private fun abandonPending(reservation: SpawnReservation): String {
        if (reservation.hasPid) {
            return "reservation 记录了 pid ${reservation.pid}，进程可能仍存在，不能放弃"
        }
        val entries = when (val reg = registry.state()) {
            is ProcessRegistry.RegistryState.Unreadable ->
                return "registry 无法读取，不能确认没有进程，不能放弃"
            ProcessRegistry.RegistryState.Empty -> 0
            is ProcessRegistry.RegistryState.Present -> reg.record.entries.size
        }
        if (entries > 0) {
            return "registry 仍有 $entries 条记录，不能放弃"
        }
        // No pid and nothing registered: the spawn never produced a process we
        // could have missed. Clearing the reservation is safe.
        return when (val r = reservationStore.clear()) {
            is WriteResult.Ok -> "已清除残留 reservation（${reservation.id}）"
            is WriteResult.Failed -> "清除失败: ${r.stage}"
        }
    }

    /**
     * A snapshot of the recovery state, for on-device acceptance.
     *
     * The app's private directory is not reachable from a host shell on this
     * device, so the only way to verify that a spawn was reserved, registered
     * and released is to write the evidence somewhere the user can retrieve.
     *
     * Contains process identities and gate state only. No API key, no token, no
     * conversation content: the point is to prove *which* process was recorded,
     * not what the user said to it.
     */
    fun recoverySnapshot(): String {
        val reservation = when (val r = reservationStore.state()) {
            ReservationState.None -> "\"state\":\"None\""
            is ReservationState.Unreadable ->
                "\"state\":\"Unreadable\",\"reason\":\"" + r.reason.replace("\"", "") + "\""
            is ReservationState.Pending -> buildString {
                append("\"state\":\"Pending\"")
                append(",\"id\":\"").append(r.reservation.id).append('"')
                append(",\"purpose\":\"").append(r.reservation.purpose).append('"')
                append(",\"pid\":").append(r.reservation.pid)
                append(",\"start\":").append(r.reservation.startTimeTicks)
            }
        }

        val registry = when (val r = registry.state()) {
            ProcessRegistry.RegistryState.Empty -> "\"state\":\"Empty\",\"entries\":[]"
            is ProcessRegistry.RegistryState.Unreadable ->
                "\"state\":\"Unreadable\",\"reason\":\"" + r.reason.replace("\"", "") + "\",\"entries\":[]"
            is ProcessRegistry.RegistryState.Present -> buildString {
                // Probe each entry so the snapshot says whether the process is
                // actually alive, not just that a record exists. Without this a
                // stale row and a live process look identical, which is the
                // difference between "press reconcile" and "do not".
                val live = runCatching { registry.scan() }.getOrNull()
                append("\"state\":\"Present\",\"scan\":\"")
                append(
                    when (live) {
                        is ScanOutcome.AllDead -> "AllDead"
                        is ScanOutcome.AllAlive -> "AllAlive"
                        is ScanOutcome.Partial -> "Partial"
                        is ScanOutcome.HasReused -> "HasReused"
                        is ScanOutcome.Unverifiable -> "Unverifiable"
                        ScanOutcome.Empty -> "Empty"
                        null -> "scan-failed"
                    },
                )
                append("\",\"entries\":[")
                r.record.entries.forEachIndexed { i, e ->
                    if (i > 0) append(',')
                    append("{\"pid\":").append(e.identity.pid)
                    append(",\"start\":").append(e.identity.startTimeTicks)
                    append(",\"treeComplete\":").append(e.treeComplete)
                    append(",\"spawnId\":\"").append(e.spawnId).append("\"}")
                }
                append(']')
            }
        }

        val reason = gate.blockReason()
        return buildString {
            append("{\"capturedAt\":").append(System.currentTimeMillis())
            append(",\"gate\":{\"blockReason\":")
            if (reason == null) append("null") else append('"').append(reason).append('"')
            append(",\"wipeActive\":").append(gate.isWipeActive())
            append(",\"maintenanceBlocked\":").append(_maintenanceBlocked.value)
            append('}')
            append(",\"reservation\":{").append(reservation).append('}')
            append(",\"registry\":{").append(registry).append('}')
            append(",\"locks\":\"").append(gate.lockDiagnostics()).append('"')
            append(",\"lastRefusal\":")
            val refusal = gate.lastRefusal()
            if (refusal == null) append("null") else append('"').append(refusal).append('"')
            append('}')
        }
    }

    /**
     * Write [recoverySnapshot] where the user can retrieve it.
     *
     * Returns the absolute path, or a failure message. Writing to external
     * storage can fail (no permission, no volume), and a silent failure would
     * leave an acceptance run with no evidence at all.
     */
    fun exportRecoveryState(): String {
        val json = recoverySnapshot()
        val dir = File("/sdcard/Download/DSH-Go")
        if (!dir.exists() && !dir.mkdirs()) return "无法创建 $dir"
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val file = File(dir, "pifilling-state-$stamp.json")
        return runCatching {
            file.writeText(json)
            file.absolutePath
        }.getOrElse { "写入失败: ${it.message}" }
    }

    /**
     * Publish a maintenance block from outside the manager.
     *
     * Exposed because the wrapper's teardown lives in the controller and can
     * discover an unconfirmed exit; that fact has to reach the same gate the
     * spawn paths consult, not a second copy of it.
     */
    fun setMaintenanceGate(blocked: Boolean, persistenceFailed: Boolean) {
        updateGate(blocked, persistenceFailed)
    }

    private fun updateGate(blocked: Boolean, persistenceFailed: Boolean) {
        _maintenanceBlocked.value = blocked
        _recyclePersistenceFailed.value = persistenceFailed
        gate.setMaintenanceBlocked(blocked)
    }

    /**
     * Monotonic spawn identity, unique within this process.
     *
     * Combined with the wall clock so ids from a previous launch cannot collide
     * with this one's. The registry stores it to prove a row belongs to a
     * particular spawn; a bare counter would repeat after a restart.
     */
    private val spawnSeq = AtomicLong(0)
    private fun nextSpawnId(): String =
        "spawn#${System.currentTimeMillis().toString(36)}-${spawnSeq.incrementAndGet()}"

    /**
     * Publish state only if this operation is still the current one.
     *
     * Without this a slow operation can overwrite the state of a newer one, and
     * the UI ends up showing progress for something that already finished.
     */
    private fun emit(opId: Long, next: SandboxState) {
        if (gate.currentOpId() == opId) _state.value = next
    }

    /**
     * Which optional toolchains are present.
     *
     * Probed with one `apk info -e` call per toolchain rather than trusting a
     * stored flag: a user can add a package from inside the shell, and a flag
     * would then disagree with reality.
     */
    suspend fun toolchainStates(): Map<String, ToolchainState> {
        val executor = createExecutor()
        return Toolchain.ALL.associate { tc ->
            val result = runCatching {
                executor.execute(
                    "apk info -e ${tc.packages.first()}",
                    timeoutSeconds = 20,
                )
            }.getOrNull()
            val present = result?.success == true && result.stdout.isNotBlank()
            tc.id to if (present) ToolchainState.PRESENT else ToolchainState.ABSENT
        }
    }

    /**
     * Install one toolchain. Returns null on success, or a reason.
     *
     * The sandbox needs network for this; that is stated on the screen rather
     * than discovered halfway through a download.
     */
    suspend fun installToolchain(tc: Toolchain): String? {
        val executor = createExecutor()
        val update = runCatching {
            executor.execute("apk update", timeoutSeconds = 120)
        }.getOrNull()
        if (update == null || !update.success) {
            return "apk update 失败：${update?.stderr?.take(200) ?: "无法启动"}"
        }
        val add = runCatching {
            executor.execute(
                "apk add --no-cache ${tc.packages.joinToString(" ")}",
                timeoutSeconds = 1200,
            )
        }.getOrNull()
        return when {
            add == null -> "无法启动安装进程"
            add.success -> null
            else -> add.stderr.take(300).ifBlank { "apk add 失败" }
        }
    }

    /** Remove a toolchain again; space is the reason this exists. */
    suspend fun removeToolchain(tc: Toolchain): String? {
        val executor = createExecutor()
        val del = runCatching {
            executor.execute(
                "apk del ${tc.packages.joinToString(" ")}",
                timeoutSeconds = 300,
            )
        }.getOrNull()
        return when {
            del == null -> "无法启动卸载进程"
            del.success -> null
            else -> del.stderr.take(300).ifBlank { "apk del 失败" }
        }
    }

    fun createExecutor(): ProotExecutor = ProotExecutor(
        prootPath = prootPath,
        libDir = "${stageNativeLibs().absolutePath}:$nativeLibDir",
        rootfsPath = rootfsDir.absolutePath,
        homePath = homeDir.absolutePath,
        tmpPath = tmpDir.absolutePath,
    )

    /**
     * Opens a reservation before a process exists, records it afterwards, and
     * clears it only when both succeeded.
     *
     * The ordering is the point: a reservation written after the process starts
     * cannot cover a crash in between, and a reservation cleared before the
     * registry write cannot cover a failed write.
     */
    private val spawnRegistrar = object : ProotExecutor.SpawnRegistrar {

        override suspend fun reserve(purpose: String): ProotExecutor.ReservationResult {
            // withSpawnPermit already holds the gate, and evaluateGate is what
            // decides. Re-asking here would duplicate the decision in a second
            // place that could drift from the first.
            gate.blockReason()?.let {
                return ProotExecutor.ReservationResult.Blocked(it)
            }
            val reservation = SpawnReservation(
                id = nextSpawnId(),
                format = SpawnReservation.CURRENT_FORMAT,
                purpose = purpose,
                seq = spawnSeq.incrementAndGet(),
                startedAt = System.currentTimeMillis(),
                pid = -1,
                startTimeTicks = -1,
            )
            return when (val r = reservationStore.write(reservation)) {
                is WriteResult.Ok -> ProotExecutor.ReservationResult.Ok(reservation)
                is WriteResult.Failed ->
                    ProotExecutor.ReservationResult.Failed(r.stage, r.cause)
            }
        }

        override suspend fun register(
            reservation: SpawnReservation,
            tree: ProcTree,
        ): WriteResult {
            val written = registry.record(tree, reservation.id)
            if (written is WriteResult.Failed) return written
            // Recording returning Ok only means the bytes were persisted. It
            // does not prove the row that landed is the row this spawn created:
            // an identity clash or a concurrent rewrite could have left
            // something else under that pid. Read it back and compare all three
            // fields before the reservation is allowed to go.
            return verifyHandshake(reservation, tree)
        }

        /**
         * Confirm the persisted row matches this reservation on every field.
         *
         * Comparing only the pid would pass for a recycled pid; comparing only
         * pid+start time would pass for a row another spawn wrote. All three are
         * needed, and a row whose `spawnId` is blank (written before the field
         * existed) never matches — an unowned row cannot prove ownership.
         */
        private fun verifyHandshake(reservation: SpawnReservation, tree: ProcTree): WriteResult {
            val state = registry.state()
            val record = when (state) {
                is ProcessRegistry.RegistryState.Present -> state.record
                is ProcessRegistry.RegistryState.Unreadable -> return WriteResult.Failed(
                    WriteStage.TEMP_WRITE,
                    IllegalStateException("registry unreadable after write: ${state.reason}"),
                )
                ProcessRegistry.RegistryState.Empty -> return WriteResult.Failed(
                    WriteStage.TEMP_WRITE,
                    IllegalStateException("registry empty right after a successful write"),
                )
            }
            val root = tree.members.firstOrNull() ?: return WriteResult.Failed(
                WriteStage.TEMP_WRITE,
                IllegalStateException("recorded tree has no root member"),
            )
            val entry = record.entries.firstOrNull { it.identity == root }
                ?: return WriteResult.Failed(
                    WriteStage.TEMP_WRITE,
                    IllegalStateException(
                        "no registry row for pid ${root.pid} after recording",
                    ),
                )
            if (entry.spawnId != reservation.id) {
                return WriteResult.Failed(
                    WriteStage.TEMP_WRITE,
                    IllegalStateException(
                        "registry row belongs to '${entry.spawnId}', " +
                            "expected '${reservation.id}'",
                    ),
                )
            }
            if (entry.identity.startTimeTicks != root.startTimeTicks) {
                return WriteResult.Failed(
                    WriteStage.TEMP_WRITE,
                    IllegalStateException(
                        "start time mismatch: registry ${entry.identity.startTimeTicks}, " +
                            "tree ${root.startTimeTicks}",
                    ),
                )
            }
            return WriteResult.Ok
        }

        override suspend fun release(reservation: SpawnReservation): WriteResult =
            reservationStore.clear()

        override suspend fun prune(
            members: List<ProcIdentity>,
            spawnId: String,
        ): WriteResult {
            // Only rows this spawn wrote may go. A row recorded by another spawn
            // belongs to a process still being tracked, and deleting it would
            // make the gate open while that process may still be alive.
            val state = registry.state()
            if (state !is ProcessRegistry.RegistryState.Present) return WriteResult.Ok
            val mine = state.record.entries.filter { it.spawnId == spawnId }
            if (mine.isEmpty()) return WriteResult.Ok
            val ids = mine.map { it.identity }
            val kept = state.record.entries.filterNot { it.spawnId == spawnId }
            val write = persistRecord(RecycleRecord(kept, state.record.emptyRounds))
            if (write is WriteResult.Failed) return write
            return if (ids.size == state.record.entries.size) WriteResult.Ok
            else WriteResult.Ok
        }
    }

    /**
     * Provision the sandbox end-to-end. Idempotent: if the marker exists it
     * short-circuits to [SandboxState.Ready]. Safe to cancel — a cancelled
     * setup leaves no marker, so the next call re-provisions cleanly.
     */
    suspend fun setup() {
        val ran = gate.withOperation { opId -> setupLocked(opId) }
        if (ran == null) {
            // Another operation holds the gate. Say so instead of racing it.
            Log.i(TAG, "setup() ignored: another sandbox operation is running")
        }
    }

    private suspend fun setupLocked(opId: Long) {
        if (markerFile.exists()) {
            ensureCurrent()
            emit(opId, SandboxState.Ready(verifiedAt = System.currentTimeMillis()))
            return
        }
        var stage = SandboxState.Stage.CHECK
        try {
            emit(opId, SandboxState.Checking(SandboxState.CheckStep.ABI))
            check(File(prootPath).exists()) {
                "libproot.so missing from $nativeLibDir — run proot-bootstrap/build-proot.sh " +
                    "and bundle the .so files into jniLibs before building the app."
            }

            sandboxDir.mkdirs()
            tmpDir.mkdirs()
            homeDir.mkdirs()

            // The base system is bundled in the APK, so first run needs no
            // network at all for it. It used to be a ~3.7 MB download that the
            // UI mislabelled as 350 MB, and on a flaky carrier that download is
            // the difference between "opens and works" and "cannot be set up".
            // The downloader stays as the fallback for an ABI we did not bundle
            // (or a corrupted asset).
            val archive = File(sandboxDir, "rootfs.tar.gz")
            // ABI the baked image was built for. It ships node, npm, git, bash,
            // python3 and the usual CLI tools already installed, so a first run
            // needs no network at all: extracting it *is* the install.
            val baked = deviceAbi == "arm64-v8a"
            val arch = downloader.alpineArch(deviceAbi)
            val base = if (baked) {
                "rootfs/alpine-baked-aarch64"
            } else {
                "rootfs/alpine-${RootfsDownloader.ALPINE_VERSION}-$arch"
            }
            // Three names, because the build decides what actually lands in the
            // APK. AGP decompresses any asset whose name ends in `.gz` and drops
            // the suffix at mergeAssets time, so `*.tar.gz` would arrive as a
            // plain `*.tar` under a different name. Assets are therefore baked
            // as `*.tgz`, which survives untouched; the other two are kept so a
            // build made before that change still provisions instead of
            // silently downloading.
            val candidates = listOf("$base.tgz", "$base.tar.gz", "$base.tar")
            val stagedFrom = stageAsset(candidates, archive, minBytes = 1_000_000)

            if (stagedFrom != null) {
                Log.i(TAG, "using bundled rootfs ($stagedFrom)")
                // No download happened. Publishing a download progress bar here
                // is what made the UI claim "downloading" for an operation that
                // never touched the network; Extracting carries it instead.
            } else if (baked) {
                // arm64 ships a complete image; a build without one cannot
                // provision at all. Downloading a different one would replace a
                // broken release with a silent slow path that hides the defect,
                // so say what is actually wrong.
                throw IllegalStateException(
                    "this build is missing its bundled sandbox image for $deviceAbi " +
                        "(looked for ${candidates.joinToString()}). " +
                        "Run scripts/bake-rootfs.sh before building the APK.",
                )
            } else {
                stage = SandboxState.Stage.DOWNLOAD
                emit(
                    opId,
                    SandboxState.Downloading(
                        mirrorIndex = 1,
                        mirrorCount = 1,
                        mirrorHost = "",
                        bytesRead = 0,
                        bytesTotal = null,
                        bytesPerSecond = null,
                    ),
                )
                downloader.download(deviceAbi, archive) { p ->
                    emit(
                        opId,
                        SandboxState.Downloading(
                            mirrorIndex = p.mirrorIndex,
                            mirrorCount = p.mirrorCount,
                            mirrorHost = p.mirrorHost,
                            bytesRead = p.bytesRead,
                            bytesTotal = p.bytesTotal,
                            bytesPerSecond = p.bytesPerSecond,
                        ),
                    )
                }
            }

            stage = SandboxState.Stage.EXTRACT
            val archiveBytes = archive.length().takeIf { it > 0 }
            emit(
                opId,
                SandboxState.Extracting(
                    fromBundled = stagedFrom != null,
                    bytesRead = 0,
                    bytesTotal = archiveBytes,
                    currentEntry = null,
                ),
            )
            downloader.extract(archive, rootfsDir) { read, total, entry ->
                emit(
                    opId,
                    SandboxState.Extracting(
                        fromBundled = stagedFrom != null,
                        bytesRead = read,
                        bytesTotal = total,
                        currentEntry = entry,
                    ),
                )
            }
            archive.delete()
            downloader.writeResolvConf(rootfsDir)
            downloader.writeRepositories(rootfsDir, apkMirrors())

            val executor = createExecutor()

            if (!baked) {
                stage = SandboxState.Stage.INSTALL
                emit(opId, SandboxState.Installing(SandboxState.InstallStep.APK_UPDATE, null, 0, 1))
                val update = executor.execute("apk update", timeoutSeconds = 300)
                check(update.success) { "apk update failed: ${update.stderr.take(500)}" }

                val packageCount = APK_PACKAGES.split(' ').count { it.isNotBlank() }
                emit(
                    opId,
                    SandboxState.Installing(SandboxState.InstallStep.APK_ADD, null, 0, packageCount),
                )
                val add = executor.execute(
                    "apk add --no-cache $APK_PACKAGES",
                    // Downloading a dozen packages on mobile data easily outlives a
                    // few minutes; the old 180s ceiling killed the install halfway
                    // and then reported the sandbox as ready anyway.
                    timeoutSeconds = 900,
                )
                check(add.success) { "apk add failed: ${add.stderr.take(500)}" }
            }

            configureGitIdentity(executor)
        configureGitCredentialHelper(executor)

            stage = SandboxState.Stage.DEPLOY
            emit(opId, SandboxState.Deploying(SandboxState.DeployStep.RUNNING, "wrapper"))
            deployWrapper(executor)
            emit(opId, SandboxState.Deploying(SandboxState.DeployStep.DONE, "wrapper"))

            stage = SandboxState.Stage.VERIFY
            emit(opId, SandboxState.Verifying(SandboxState.VerifyItem.ROOTFS))

            val marker = writeMarker()
            if (!marker.isDurable()) {
                // The environment itself is fine, but its own record of that is
                // not durable. Reporting Ready here would be a claim storage
                // refused to make; report the write failure instead so the next
                // launch does not silently re-provision.
                val stage = (marker as WriteResult.Failed).stage
                emit(
                    opId,
                    SandboxState.Error(
                        SandboxState.Stage.VERIFY,
                        SandboxState.ErrorCode.EXEC_FAILED,
                        "环境已就绪，但安装标记未能持久化（$stage）",
                        retryable = true,
                    ),
                )
                return
            }
            emit(opId, SandboxState.Ready(verifiedAt = System.currentTimeMillis()))
            Log.i(TAG, "sandbox ready ($deviceAbi, Alpine ${RootfsDownloader.ALPINE_VERSION})")
        } catch (e: CancellationException) {
            // Cancelled: clean up only what this operation created, then say so.
            val (cleaned, failed) = withContext(NonCancellable) { cleanOwnArtifacts(opId) }
            emit(opId, SandboxState.Cancelled(stage, cleaned, failed))
            Log.i(TAG, "sandbox setup cancelled at $stage")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "sandbox setup failed", e)
            emit(opId, classify(e, stage))
            throw e
        }
    }

    /**
     * Turn an exception into a structured failure.
     *
     * The stage is known from the call site; the code is inferred from the
     * exception type and message, because that is all we have. Guessing wrong
     * here costs a slightly-off label, while reporting everything as UNKNOWN
     * costs the user any idea of what to do next.
     */
    private fun classify(e: Exception, stage: SandboxState.Stage): SandboxState.Error {
        val message = e.message ?: e.javaClass.simpleName
        val lower = message.lowercase()
        val code = when {
            e is java.net.UnknownHostException -> SandboxState.ErrorCode.NETWORK
            e is java.net.SocketTimeoutException -> SandboxState.ErrorCode.NETWORK
            e is java.io.InterruptedIOException -> SandboxState.ErrorCode.NETWORK
            lower.contains("http ") -> SandboxState.ErrorCode.HTTP
            lower.contains("missing its bundled") -> SandboxState.ErrorCode.MISSING_ASSET
            lower.contains("no space") || lower.contains("enospc") -> SandboxState.ErrorCode.DISK_FULL
            stage == SandboxState.Stage.EXTRACT -> SandboxState.ErrorCode.CORRUPT_ARCHIVE
            stage == SandboxState.Stage.INSTALL ||
                stage == SandboxState.Stage.DEPLOY -> SandboxState.ErrorCode.EXEC_FAILED
            else -> SandboxState.ErrorCode.UNKNOWN
        }
        // A missing asset or a full disk will not fix itself by retrying.
        val retryable = code !in setOf(
            SandboxState.ErrorCode.MISSING_ASSET,
            SandboxState.ErrorCode.DISK_FULL,
        )
        return SandboxState.Error(stage, code, message, retryable)
    }

    /**
     * Remove the artifacts this operation created.
     *
     * Deliberately narrow: only files whose names carry this operation's id.
     * Anything else on disk belongs to a previous run or to the user, and a
     * cancellation is not a licence to delete it.
     */
    private fun cleanOwnArtifacts(opId: Long): Pair<List<String>, List<String>> {
        val cleaned = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val candidates = listOf(
            File(sandboxDir, "rootfs.tar.gz"),
            File(sandboxDir, "staging-$opId"),
        )
        for (file in candidates) {
            if (!file.exists()) continue
            if (file.deleteRecursively()) cleaned += file.name else failed += file.name
        }
        return cleaned to failed
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
        // Uses the recovery gate, not the operation gate. `ensureCurrent` is the
        // call that *repairs* a stale environment, and a stale registry row is
        // one of the things it repairs — asking the gate first would refuse it
        // precisely when it is needed. It still takes both locks, so it cannot
        // race a wipe or a spawn.
        gate.withRecovery { opId -> ensureCurrentLocked(opId) }
    }

    private suspend fun ensureCurrentLocked(opId: Long) {
        if (!markerFile.exists()) return

        val have = readSetupVersion()
        // The wrapper source is refreshed on every pass, so only the recipe
        // version decides whether there is anything to do at all.
        if (have >= SETUP_VERSION && nodeModulesPresent(createExecutor())) return
        // Show the top-up in the UI. This used to be invisible: the sandbox
        // stayed "Ready" while an `apk add` ran, so a user who was told new
        // tools were bundled had no way to tell whether they had landed.
        if (have < SETUP_VERSION) {
            emit(opId, SandboxState.Upgrading(SandboxState.UpgradeStage.CONFIG))
        }
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
                val marker = writeMarker()
                if (marker.isDurable()) {
                    Log.i(TAG, "backfilled sandbox setup v$have -> v$SETUP_VERSION")
                } else {
                    // Do not log this as a completed backfill: the next launch
                    // will repeat it, because the marker that says it happened
                    // did not make it to disk.
                    Log.e(
                        TAG,
                        "backfilled setup but the marker is not durable " +
                            "(${(marker as WriteResult.Failed).stage}); will retry next launch",
                        marker.cause,
                    )
                }
            }
            .onFailure { Log.w(TAG, "sandbox backfill failed; the agent may hit it", it) }
        // Ready either way: a failed top-up leaves a usable sandbox, and the
        // per-session ensureCurrent() retries. Parking the UI on Upgrading
        // forever would be worse than the missing package.
        emit(opId, SandboxState.Ready(verifiedAt = System.currentTimeMillis()))
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
    /**
     * Ensure the guest's packages are present.
     *
     * Probes first and installs only what is actually missing, so the common
     * case — the bundled image already has everything — costs one offline
     * query instead of a 167 MB download that fails on a bad network.
     */
    private suspend fun installPackages(executor: ProotExecutor) {
        val wanted = APK_PACKAGES.split(' ').filter { it.isNotBlank() }
        val missing = missingPackages(executor, wanted)
        if (missing.isEmpty()) {
            Log.i(TAG, "all ${wanted.size} packages already present; no download needed")
            return
        }
        Log.i(TAG, "installing ${missing.size} missing package(s): ${missing.joinToString(" ")}")
        val result = executor.execute(
            "apk add --no-cache ${missing.joinToString(" ")}",
            timeoutSeconds = 900,
        )
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

    /**
     * Read one `key=value` field out of the marker, or "" when absent.
     *
     * Goes through [AtomicStore] so a marker that exists but cannot be read is
     * distinguishable from one that is not there at all. The old version wrapped
     * everything in runCatching and returned "", which made an unreadable marker
     * look identical to a fresh install — the caller would then re-provision
     * over a sandbox that was actually fine.
     */
    private fun readMarkerField(key: String): String {
        val text = when (val r = metaStore.read(MARKER_NAME)) {
            is ReadResult.Found -> String(r.bytes, Charsets.UTF_8)

            // Only a genuinely absent authoritative copy falls back to the
            // legacy file. This is the case where the store has never been
            // written — a sandbox provisioned by an older build — and the
            // legacy marker is the only record that exists.
            ReadResult.Absent -> runCatching { markerFile.readText() }.getOrDefault("")

            // Unreadable is NOT absent. Falling back here would let a stale
            // legacy copy silently outvote a corrupt authoritative one, which
            // is the wrong direction for the only file that decides whether the
            // sandbox counts as installed. Report nothing and let the caller
            // treat it as unknown.
            is ReadResult.Unknown -> {
                Log.e(TAG, "authoritative marker unreadable: ${r.reason}", r.cause)
                return ""
            }
        }
        val pattern = Regex("(?:^|\\s)" + Regex.escape(key) + "=([^\\s]+)")
        return pattern.find(text)?.groupValues?.get(1).orEmpty()
    }

    /**
     * Write the marker atomically and report what happened.
     *
     * Returns the [WriteResult] of the *authoritative* copy rather than logging
     * and carrying on. A marker write that did not fully complete means the
     * sandbox's own record of being installed is not durable, and a caller that
     * cannot see that will publish [SandboxState.Ready] over it — the user is
     * then told the sandbox is ready by an app that would forget it after the
     * next power loss.
     *
     * The legacy copy at [markerFile] is written afterwards and its failure is
     * logged but not returned: it exists only so builds from before this change
     * can still read the marker, and by construction it is never the copy that
     * decides anything once the store has one.
     */
    private fun writeMarker(): WriteResult {
        val body = "alpine=${RootfsDownloader.ALPINE_VERSION} abi=$deviceAbi setup=$SETUP_VERSION " +
            "wrapper=${bundledSourceDigest()} deps=${bundledDepsDigest()}\n"
        val result = metaStore.writeText(MARKER_NAME, body)
        if (result is WriteResult.Failed) {
            Log.e(TAG, "could not write the authoritative marker (${result.stage})", result.cause)
        }
        // Written even when the authoritative write failed: the legacy file is
        // what an older build reads, and leaving it stale would be worse than
        // leaving it merely imperfect.
        runCatching { markerFile.writeText(body) }
            .onFailure { Log.e(TAG, "could not write the legacy marker", it) }
        return result
    }

    /**
     * Whether [result] means the marker is safely on disk.
     *
     * [WriteStage.DIR_FSYNC] is deliberately *not* treated as success. At that
     * point the rename has happened, so a process reading the file now sees the
     * new contents — but the directory entry was never flushed, so a power loss
     * can still revert it. Publishing Ready on that is a claim the storage layer
     * explicitly refused to make.
     */
    private fun WriteResult.isDurable(): Boolean = this is WriteResult.Ok

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

        // The dependency tree travels with the APK as one tarball (see
        // wrapper-modules.tar.gz). Copy it next to the guest's home so the
        // extract step below can reach it through the /root bind.
        //
        // Fatal if absent, not a warning. This used to be wrapped in runCatching
        // + Log.w, so a build that shipped the wrong asset name installed
        // "successfully", reported Ready, and then failed at the first prompt
        // because the agent had no dependencies — with nothing on screen
        // explaining why.
        val modulesTar = File(homeDir, ".wrapper-modules.tar.gz")
        if (!modulesTar.exists()) {
            val stagedModules = stageAsset(
                listOf(
                    "rootfs/wrapper-modules.tgz",
                    "rootfs/wrapper-modules.tar.gz",
                    "rootfs/wrapper-modules.tar",
                ),
                modulesTar,
                minBytes = 1_000_000,
            ) ?: throw IllegalStateException(
                "this build is missing the bundled agent dependencies " +
                    "(looked for rootfs/wrapper-modules.tgz, .tar.gz and .tar). " +
                    "Run scripts/bake-rootfs.sh before building the APK.",
            )
            Log.i(TAG, "staged wrapper dependencies from $stagedModules")
        }
        // The same build-time decompression can turn the tarball into a plain
        // tar, so pick the flag by content rather than by file name.
        val modulesGzipped = downloader.isGzip(modulesTar)

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

        // Extract the dependency tree shipped inside the APK first. That is the
        // whole point of the bundle: `npm ci` used to be a ~130 MB network
        // fetch, and on a phone network that was the step that failed.
        val bundledModules = runCatching {
            executor.execute(
                "cd /root/wrapper && mkdir -p /tmp/wm && rm -rf /tmp/wm/* && " +
                    "tar x${if (modulesGzipped) "z" else ""}f /root/.wrapper-modules.tar.gz -C /tmp/wm && " +
                    "rm -rf node_modules && mv /tmp/wm/node_modules node_modules && " +
                    "test -d node_modules/@earendil-works/pi-agent-core",
                timeoutSeconds = 600,
            ).success
        }.getOrDefault(false)
        if (bundledModules && nodeModulesPresent(executor)) {
            Log.i(TAG, "wrapper dependencies extracted from the bundled tree")
            return
        }

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
        // Runs twelve probe commands, so it spawns. The gate has to cover it:
        // a probe starting after a wipe scanned would be a process the wipe
        // never saw.
        // Bounded: an inspect that hangs would hold the environment lock and
        // starve every other spawn, which is indistinguishable from a deadlock
        // from the user's side.
        return gate.withSpawnPermit {
            withTimeoutOrNull(INSPECT_TIMEOUT_MS) { inspectLocked() }
        } ?: ("无法查询：" + (gate.lastRefusal() ?: "查询超时"))
    }

    private suspend fun inspectLocked(): String {
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
        // The executor's registrar handles the lifecycle; a per-call callback
        // would override it with a bare record under a fresh spawn id.
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
        // createExecutor() stages native libs into linux-sandbox/nativelib, which
        // is inside the tree a wipe renames and deletes. Building it outside the
        // gate would let it write into a directory that is being moved.
        val executor = runCatching { createExecutor() }.getOrElse {
            return "沙盒不可用: " + (it.message ?: it.javaClass.simpleName)
        }
        val ran: Pair<Boolean, String>? = gate.withOperation { opId ->
            installToolsLocked(executor, opId, onLine)
        }
        if (ran == null) return "正在安装中，请稍候"
        // On failure the second element carries the apk ERROR: lines, so the
        // sheet names the offending package instead of "something failed".
        return if (ran.first) "工具安装完成" else ran.second
    }

    private suspend fun installToolsLocked(
        executor: ProotExecutor,
        opId: Long,
        onLine: ((String) -> Unit)?,
    ): Pair<Boolean, String> {
        var stage = SandboxState.Stage.UPGRADE
        return try {
            // Ask the guest what it is actually missing before touching the
            // network. The bundled arm64 image already ships every package in
            // APK_PACKAGES — the build bakes them in precisely so a first run
            // needs no network — yet this button used to run `apk add` for the
            // whole list regardless. On a phone that cannot reach the Alpine
            // mirrors that meant minutes of "fetch ... IO ERROR" followed by a
            // failure, for packages that were already installed. Probe first:
            // if nothing is missing there is nothing to download, and the
            // operation finishes immediately instead of failing slowly.
            val wanted = APK_PACKAGES.split(' ').filter { it.isNotBlank() }
            val missing = missingPackages(executor, wanted)
            if (missing.isEmpty()) {
                onLine?.invoke("工具已齐全（${wanted.size} 个包已内置），无需下载")
                emit(opId, SandboxState.Ready(verifiedAt = System.currentTimeMillis()))
                return true to ""
            }
            onLine?.invoke("缺少 ${missing.size} 个包：${missing.joinToString(" ")}")

            // Refresh the repositories list first: an install that outlived a
            // single-mirror provisioning would otherwise keep hitting the one
            // CDN that originally 404'd. The user's apk-source choice leads.
            downloader.writeRepositories(rootfsDir, apkMirrors())
            val total = missing.size
            emit(opId, SandboxState.Upgrading(SandboxState.UpgradeStage.TOOLS))
            onLine?.invoke("$ apk update")
            emit(opId, SandboxState.Installing(SandboxState.InstallStep.APK_UPDATE, null, 0, total))
            val update = executor.execute("apk update", timeoutSeconds = 300, onLine = onLine)
            val toInstall = missing.joinToString(" ")
            onLine?.invoke("$ apk add $toInstall")
            emit(opId, SandboxState.Installing(SandboxState.InstallStep.APK_ADD, null, 0, total))
            var add = executor.execute(
                "apk add --no-cache $toInstall",
                timeoutSeconds = 900,
                onLine = { line -> handleApkProgressLine(opId, line, onLine, total) },
            )
            // A single flaky mirror file used to end the whole install. The
            // repositories file now lists every mirror, so one more pass — with
            // a fresh index — recovers the transient case before we call it.
            if (!add.success && !add.timedOut) {
                onLine?.invoke("（一次瞬时失败，换源重试）apk add 再次")
                emit(opId, SandboxState.Installing(SandboxState.InstallStep.APK_UPDATE, null, 0, total))
                executor.execute("apk update", timeoutSeconds = 300, onLine = onLine)
                emit(opId, SandboxState.Installing(SandboxState.InstallStep.APK_ADD, null, 0, total))
                add = executor.execute(
                    "apk add --no-cache $toInstall",
                    timeoutSeconds = 900,
                    onLine = { line -> handleApkProgressLine(opId, line, onLine, total) },
                )
            }
            val marker = writeMarker()
            if (add.success && !marker.isDurable()) {
                val st = (marker as WriteResult.Failed).stage
                emit(opId, SandboxState.Error(
                    stage, SandboxState.ErrorCode.EXEC_FAILED,
                    "工具已安装，但标记未能持久化（$st）", true))
                return false to "工具安装完成，但标记未能持久化（$st）"
            }
            if (add.success) {
                emit(opId, SandboxState.Ready(verifiedAt = System.currentTimeMillis()))
                true to ""
            } else {
                emit(opId, SandboxState.Error(
                    stage, SandboxState.ErrorCode.EXEC_FAILED, "apk add failed", true))
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
                false to "工具安装失败（退出码 ${add.exitCode}）:\n$detail"
            }
        } catch (e: CancellationException) {
            val (cleaned, failed) = withContext(NonCancellable) { cleanOwnArtifacts(opId) }
            emit(opId, SandboxState.Cancelled(stage, cleaned, failed))
            throw e
        } catch (e: Exception) {
            emit(opId, classify(e, stage))
            throw e
        }
    }

    /**
     * apk's own progress lines, e.g. "(12/24) Installing openssl".
     * group 1 = done count, 2 = total, 3 = package name.
     */
    /**
     * Which of [packages] the guest does not have installed.
     *
     * Uses `apk info -e` (installed?) rather than probing for binaries: the
     * question is about packages, and a package that is present but whose
     * binary has a different name would otherwise look missing forever. Runs
     * entirely offline — it reads the guest's installed database, so it works
     * on a phone with no network at all, which is the whole point.
     */
    private suspend fun missingPackages(
        executor: ProotExecutor,
        packages: List<String>,
    ): List<String> {
        val script = packages.joinToString("; ") {
            "apk info -e '$it' >/dev/null 2>&1 || echo '$it'"
        }
        val r = executor.execute(script, timeoutSeconds = 60)
        if (!r.success && r.stdout.isBlank()) {
            // A probe that cannot run must not be read as "nothing is missing":
            // that would silently skip a real install. Fall back to the full
            // list, i.e. behave as before.
            return packages
        }
        val missingSet = r.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return packages.filter { it in missingSet }
    }

    /**
     * Parse one apk output line into an [SandboxState.Installing] update.
     *
     * Forwards the line to [forward] first (so the log panel keeps streaming),
     * then, when the line is apk's own progress counter, publishes the package
     * progress. apk's own total (group 2) is preferred over [fallbackTotal]:
     * it includes the transitive dependencies, so it is the figure the user
     * actually wants the bar to approach.
     */
    private fun handleApkProgressLine(
        opId: Long,
        line: String,
        forward: ((String) -> Unit)?,
        fallbackTotal: Int,
    ) {
        forward?.invoke(line)
        val m = APK_PROGRESS_REGEX.find(line) ?: return
        val done = m.groupValues[1].toIntOrNull() ?: 0
        val total = m.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: fallbackTotal
        val pkg = m.groupValues[3].removeSuffix(".apk")
        emit(opId, SandboxState.Installing(SandboxState.InstallStep.APK_ADD, pkg, done, total))
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
        // This used to run entirely unlocked: it is a long `npm ci`, and nothing
        // stopped it from racing a wipe. It is a modifying operation, so it takes
        // the operation gate first and the environment gate second — the fixed
        // order, to avoid a deadlock against withWipeExclusive.
        val ran = gate.withOperation { opId -> redeployLocked(installDeps, opId, onLine) }
        return ran ?: "正在执行其他操作，请稍候"
    }

    private suspend fun redeployLocked(
        installDeps: Boolean,
        opId: Long,
        onLine: ((String) -> Unit)?,
    ): String {
        val executor = runCatching { createExecutor() }.getOrElse {
            return "沙盒不可用: " + (it.message ?: it.javaClass.simpleName)
        }
        return runCatching {
            downloader.writeRepositories(rootfsDir, apkMirrors())
            val wrapperDir = File(homeDir, "wrapper")
            wrapperDir.mkdirs()
            copyAsset("wrapper", wrapperDir)
            if (!installDeps) {
                val marker = writeMarker()
                return@runCatching if (marker.isDurable()) {
                    "wrapper 源码已重新部署"
                } else {
                    "wrapper 源码已重新部署，但标记未能持久化" +
                        "（${(marker as WriteResult.Failed).stage}）"
                }
            }
            onLine?.invoke("$ npm ci (仅依赖)")
            val r = executor.execute(
                "cd /root/wrapper && npm ci --omit=dev --no-audit --no-fund " +
                    "--registry=https://registry.npmmirror.com",
                timeoutSeconds = 1500,
                onLine = onLine,
            )
            val marker = writeMarker()
            if (r.success && !marker.isDurable()) {
                return@runCatching "依赖安装完成，但标记未能持久化" +
                    "（${(marker as WriteResult.Failed).stage}）"
            }
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

    /**
     * Copy the first bundled asset that actually exists to [dest].
     *
     * Returns the asset name that worked, or null. Tries several names because
     * the build, not this code, decides whether an asset keeps its `.gz` — AGP
     * decompresses `*.tar.gz` assets on the way into the APK and drops the
     * suffix, which is exactly how a bundled image ends up unreachable while
     * looking present in `assets/`.
     */
    private fun stageAsset(candidates: List<String>, dest: File, minBytes: Long = 0L): String? {
        for (name in candidates) {
            val ok = runCatching {
                context.assets.open(name).use { input ->
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                dest.length() > minBytes
            }.getOrDefault(false)
            if (ok) return name
        }
        return null
    }

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
    suspend fun startWrapper(
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
        // The wrapper is long-lived, but only the *launch* needs the gate: once
        // registered, the registry tracks it and a wipe blocks on that entry.
        // Holding the gate for the session's lifetime would deadlock every
        // other operation.
        val process = gate.withSpawnPermit {
            startWrapperLocked(
                apiKey, repoGuestPath, provider, model, logLevel,
                gitHubToken, baseUrl, apiShape, reasoning,
            )
        }
        return process ?: throw IllegalStateException(
            "无法启动会话：" + (gate.lastRefusal() ?: "未知原因"),
        )
    }

    private suspend fun startWrapperLocked(
        apiKey: String,
        repoGuestPath: String,
        provider: AgentProvider,
        model: String?,
        logLevel: String,
        gitHubToken: String?,
        baseUrl: String?,
        apiShape: String,
        reasoning: Boolean,
    ): Process {
        // Snapshot before the wrapper starts: if the launch is refused, the
        // reason has to be captured here, because by the time the user presses
        // export the gate state may already have moved on.
        autoExport("startWrapper-entered")
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
            // No per-call onStarted: see the note in ensureRepoDirLocked.
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
        val done = gate.withSpawnPermit { ensureRepoDirLocked(repoGuestPath) }
        check(done != null) {
            "无法准备目录：" + (gate.lastRefusal() ?: "未知原因")
        }
    }

    private suspend fun ensureRepoDirLocked(repoGuestPath: String) {
        // Auto-export the full state at the moment of failure. The user should
        // not have to press a second button for the evidence to exist, and a
        // snapshot taken after the fact can no longer show why this call was
        // refused.
        autoExport("ensureRepoDir-entered")
        val path = shellQuote(repoGuestPath)
        // No per-call onStarted: the executor's registrar already runs the full
        // lifecycle (reserve -> register -> prune -> release). Passing one here
        // overrode it with a bare `registry.record` under a fresh spawn id, which
        // wrote a row that nothing ever pruned — and that row then refused the
        // very next spawn.
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
        // Nothing was running under the gate at this point in the old design
        // either; 2c-A keeps reset() as-is and leaves the transactional version
        // to 2c-B.
        _state.value = SandboxState.NotInstalled
    }

    internal companion object {
        const val TAG = "LinuxSandboxManager"

        /** Ceiling for a status probe; it runs a dozen guest commands. */
        const val INSPECT_TIMEOUT_MS = 60_000L

        /** File name of the marker inside [metaStore]. */
        const val MARKER_NAME = "setup.marker"

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
