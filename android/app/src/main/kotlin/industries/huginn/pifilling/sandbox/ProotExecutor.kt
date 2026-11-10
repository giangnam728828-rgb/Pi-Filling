package industries.huginn.pifilling.sandbox

import android.util.Log
import industries.huginn.pifilling.sandbox.meta.KillOutcome
import industries.huginn.pifilling.sandbox.meta.ProcIdentity
import industries.huginn.pifilling.sandbox.meta.SpawnReservation
import industries.huginn.pifilling.sandbox.meta.ProcTree
import industries.huginn.pifilling.sandbox.meta.ProcessRegistry
import industries.huginn.pifilling.sandbox.meta.WriteResult
import industries.huginn.pifilling.sandbox.meta.WriteStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs commands inside the Alpine rootfs via proot. Ported from Kai's
 * ProotExecutor: the proot ELF is shipped as `libproot.so` under jniLibs and
 * executed from the native library dir (files there are exempt from Android's
 * W^X restriction on app data), with its loader and talloc beside it.
 *
 * Two entry points:
 *  - [execute] runs a single command to completion and returns a [CommandResult].
 *  - [start] launches a long-lived process (e.g. `node wrapper.mjs`) and returns
 *    the raw [Process] for the caller to drive over stdio (see WrapperClient).
 */
class ProotExecutor(
    private val prootPath: String,
    /** `LD_LIBRARY_PATH` for proot: one or more dirs, colon-separated. */
    private val libDir: String,
    private val rootfsPath: String,
    private val homePath: String,
    private val tmpPath: String,
    /**
     * Registry hook applied to every process this executor starts.
     *
     * Attached at construction rather than passed to each call so that no call
     * site can forget it. There are seventeen `execute()` sites in the manager;
     * relying on each to pass `onStarted` is how the one that mattered — the
     * `nodeModulesPresent` probe — went unnoticed.
     *
     * Null disables registration, which only tests do.
     */
    private val registrar: SpawnRegistrar? = null,
) {
    /**
     * Coordinates the whole spawn lifecycle, not just the registration.
     *
     * The reservation has to be written *before* the process exists, and the
     * spawn id must be chosen at that moment — so a callback invoked after the
     * process is up cannot do the job. This interface gives the executor a
     * single place to open the reservation, obtain the id, and close it once
     * registration is confirmed.
     */
    interface SpawnRegistrar {
        /**
         * Persist a reservation for an about-to-start process.
         *
         * Returns the reservation, or a failure that must prevent the start.
         */
        suspend fun reserve(purpose: String): ReservationResult

        /** Record the started tree under the id from [reserve]. */
        suspend fun register(reservation: SpawnReservation, tree: ProcTree): WriteResult

        /** Clear the reservation. Only called once [register] returned Ok. */
        suspend fun release(reservation: SpawnReservation): WriteResult

        /**
         * Remove the registry rows for [members], whose process has exited.
         *
         * Only rows whose `spawnId` matches the spawning reservation may be
         * pruned, so one spawn can never delete another spawn's evidence.
         */
        suspend fun prune(members: List<ProcIdentity>, spawnId: String): WriteResult
    }

    /**
     * Test seam: launches the process. Production uses [ProcessBuilder];
     * tests substitute a fake so the lifecycle can be driven without proot.
     */
    internal var launcher: ((List<String>, Map<String, String>) -> Process)? = null

    /**
     * Test seam: decides whether a started tree actually exited.
     *
     * Production supplies the registry probe; without one the outcome cannot
     * claim the tree exited, which keeps the failure conservative.
     */
    internal var killOutcomeProbe: ((ProcTree, Boolean) -> KillOutcome)? = null

    /**
     * The reservation and tree from the most recent [start], for the caller to
     * settle once the process has exited. A single slot is safe because
     * `start` -> `execute` is strictly sequential on one coroutine.
     *
     * Both must be settled: the reservation says a spawn was authorised, the
     * registry row says a process was recorded. Leaving either behind shuts the
     * gate for every subsequent spawn.
     */
    internal var lastReservation: SpawnReservation? = null
    internal var lastTree: ProcTree? = null

    sealed interface ReservationResult {
        data class Ok(val reservation: SpawnReservation) : ReservationResult
        /** The environment gate is shut; no process may be started. */
        data class Blocked(val reason: String) : ReservationResult
        data class Failed(val stage: WriteStage, val cause: Throwable) : ReservationResult
    }
    /**
     * Build the proot argv. [command] is run via `/bin/sh -c`. `-0` makes the
     * guest believe it is root (proot's uid/gid emulation), which Alpine's apk
     * and most tooling expect.
     */
    private fun buildArgs(command: String, workingDir: String): List<String> = listOf(
        prootPath,
        "--rootfs=$rootfsPath",
        "--bind=/dev",
        "--bind=/proc",
        "--bind=/sys",
        "--bind=$homePath:/root",
        "--bind=$tmpPath:/tmp",
        "-0",
        "-w", workingDir,
        "/bin/sh", "-c", command,
    )

    private fun buildEnv(extraEnv: Map<String, String>): Map<String, String> {
        val loaderPath = File(prootPath).parent?.let { "$it/libproot-loader.so" }.orEmpty()
        val base = linkedMapOf(
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "LD_LIBRARY_PATH" to libDir,
            "PROOT_TMP_DIR" to tmpPath,
            "PROOT_LOADER" to loaderPath,
        )
        base.putAll(extraEnv)
        return base
    }

    /**
     * Start a long-lived process inside the sandbox. The returned [Process] has
     * stdin/stdout/stderr pipes wired (stderr NOT merged into stdout, because
     * the wrapper's stdout is structured JSONL while stderr is free-form logs).
     *
     * **Blocking.** Do not call this on the main thread: it spawns a process and,
     * when [onStarted] is supplied, walks `/proc` for up to
     * [TREE_WALK_TIMEOUT_MS]. Both current call sites already run on
     * `Dispatchers.IO` (`execute` below, and `AgentController.startSession`),
     * which is why the scheduler is *not* switched here — doing so would let
     * `start()` return before the tree was recorded, which is the registration
     * race this parameter exists to avoid.
     *
     * [onStarted] is invoked synchronously, before this function returns, on the
     * caller's thread. Its exceptions are swallowed: a failure to record a
     * process must not prevent the process from having been started, or a
     * sandbox that works would appear to fail at launch.
     */
    fun start(
        command: String,
        workingDir: String = "/root",
        extraEnv: Map<String, String> = emptyMap(),
    ): Process {
        val pb = ProcessBuilder(buildArgs(command, workingDir))
        pb.directory(File(rootfsPath).parentFile)
        pb.environment().clear()
        pb.environment().putAll(buildEnv(extraEnv))
        pb.redirectErrorStream(false)
        return pb.start()
    }

    /**
     * Thrown when a process was started but its registration failed.
     *
     * [exitConfirmed] is the only field that may be used to decide the process is
     * gone. [killRequested] merely says the request was issued — destroyForcibly
     * returns immediately and does not wait. Treating a request as a result is
     * how a live process becomes invisible to the next wipe.
     */
    class ProcessRegistrationException(
        message: String,
        cause: Throwable?,
        val outcome: KillOutcome,
    ) : IllegalStateException(message, cause) {
        /** True only when the process tree is confirmed gone. */
        val safeToClear: Boolean get() = outcome.safeToClear
    }

    /**
     * The OS pid of [process], or -1 when the platform will not say.
     *
     * `Process.pid()` is API 26+, but this project targets Java 8 bytecode
     * without desugaring for it, so it is called reflectively. A -1 result is
     * handled by the caller as an incomplete tree rather than a crash: not being
     * able to record a process is a bookkeeping failure, not a launch failure.
     */
    /**
     * Whether [processPid] can return a usable value on this platform.
     *
     * Probed by starting nothing: the reflection targets are inspected for
     * presence. If neither exists, no spawn could ever be registered, and the
     * caller must fail before creating one.
     */
    private fun pidAvailable(): Boolean {
        val hasMethod = runCatching {
            Process::class.java.getMethod("pid")
        }.isSuccess
        if (hasMethod) return true
        // Older Android exposes the field on the concrete class; without an
        // instance we can only check that some subclass declares it, which is
        // what the process builder will hand back.
        return runCatching {
            Class.forName("java.lang.UNIXProcess").getDeclaredField("pid")
        }.isSuccess || runCatching {
            Class.forName("java.lang.ProcessImpl").getDeclaredField("pid")
        }.isSuccess
    }

    private fun processPid(process: Process): Int {
        // Process.pid() is API 26+ but this module compiles to Java 8 bytecode
        // without desugaring for it, so it is called reflectively. If that
        // fails there is no other supported way to learn the pid, and the
        // caller must treat the tree as uncapturable rather than inventing one.
        val viaReflection = runCatching {
            val method = Process::class.java.getMethod("pid")
            (method.invoke(process) as? Long)?.toInt()
        }.getOrNull()
        if (viaReflection != null && viaReflection > 0) return viaReflection

        // Fallback: the implementation class usually exposes the same value.
        // `java.lang.UNIXProcess` on older Android, `ProcessImpl` on newer.
        val viaField = runCatching {
            val f = process.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            (f.get(process) as? Int)
        }.getOrNull()
        if (viaField != null && viaField > 0) return viaField

        return -1
    }

    /**
     * Walk `/proc` to collect the process and everything it spawned.
     *
     * Returns [ProcTree.treeComplete] false when the deadline is hit or `/proc`
     * is partly unreadable, rather than returning a short list that looks
     * complete. The distinction matters downstream: an incomplete tree makes a
     * later recycle block while anything is alive, because we cannot know what
     * else belongs to it.
     */
    internal fun captureTree(rootPid: Int): ProcTree {
        if (rootPid <= 0) {
            // The platform refused to give us a pid. Record nothing and say the
            // tree is incomplete, so a later recycle blocks instead of assuming
            // this process never existed.
            return ProcTree(rootPid, emptyList(), treeComplete = false)
        }
        val deadline = System.nanoTime() + TREE_WALK_TIMEOUT_MS * 1_000_000L
        val rootStat = readStatIdentity(rootPid)
            ?: return ProcTree(rootPid, emptyList(), treeComplete = false)

        val collected = linkedMapOf(rootPid to rootStat)
        var complete = true

        while (true) {
            if (System.nanoTime() > deadline) {
                complete = false
                break
            }
            val frontier = collected.keys.toSet()
            val children = runCatching {
                File("/proc").listFiles()
                    ?.asSequence()
                    ?.mapNotNull { it.name.toIntOrNull() }
                    ?.filter { it !in collected }
                    ?.mapNotNull { pid ->
                        val stat = readStatIdentity(pid) ?: return@mapNotNull null
                        if (stat.first in frontier) pid to stat else null
                    }
                    ?.toList()
                    .orEmpty()
            }.getOrElse {
                complete = false
                emptyList()
            }
            if (children.isEmpty()) break
            for ((pid, stat) in children) collected[pid] = stat
        }

        return ProcTree(
            rootPid = rootPid,
            members = collected.map { (pid, stat) ->
                ProcIdentity(pid, stat.second)
            },
            treeComplete = complete,
        )
    }

    /** `(ppid, startTimeTicks)` for one pid, or null when it cannot be read. */
    private fun readStatIdentity(pid: Int): Pair<Int, Long>? {
        val file = File("/proc/$pid/stat")
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        return ProcessRegistry.parseStat(raw)
    }

    /**
     * Run [command] to completion. Output is bounded at [MAX_OUTPUT_CHARS] each
     * for stdout/stderr (matching Kai), and the call is force-killed after
     * [timeoutSeconds] (clamped to 1..180).
     */
    suspend fun execute(
        command: String,
        workingDir: String = "/root",
        timeoutSeconds: Long = 30,
        extraEnv: Map<String, String> = emptyMap(),
        /**
         * Called for every output line as it arrives, on the drain thread.
         *
         * Installs (`apk add`, `npm ci`) take minutes on phone hardware and used
         * to be completely silent: the UI showed a disabled button and nothing
         * else, which is indistinguishable from a hang. The callback lets the
         * caller stream that output into the UI as proof of progress. It must be
         * cheap and must not block — it runs while the command is still going.
         */
        onLine: ((String) -> Unit)? = null,
        /**
         * Called with the process tree before this function waits for exit.
         *
         * Forwarded to [start]. Without it, every command run through
         * [execute] — `apk add`, `npm ci`, the inspect probes — would launch a
         * process that the registry never learns about, and a wipe scanning
         * mid-install would see an empty registry and conclude AllDead.
         */
    ): CommandResult = withContext(Dispatchers.IO) {
        // The upper bound used to be 180s, which silently capped every long
        // operation at three minutes. `npm ci` for the wrapper's dependency tree
        // needs far longer than that on a phone, and because it deletes
        // node_modules before fetching, each timed-out attempt left the sandbox
        // with fewer packages than before. The wrapper then died with exit code
        // 1 on every prompt and nothing in the UI said why.
        val clamped = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val process = start(command, workingDir, extraEnv)

        // Drain both streams concurrently to avoid pipe-buffer deadlock.
        val stdoutThread = drainAsync(process.inputStream.bufferedReader(), onLine)
        val stderrThread = drainAsync(process.errorStream.bufferedReader(), onLine)

        val finished = runInterruptible {
            process.waitFor(clamped, TimeUnit.SECONDS)
        }

        if (!finished) {
            process.destroyForcibly()
            stdoutThread.join(DRAIN_JOIN_MS)
            stderrThread.join(DRAIN_JOIN_MS)
            return@withContext CommandResult(
                success = false,
                stdout = stdoutThread.result(),
                stderr = stderrThread.result(),
                exitCode = -1,
                timedOut = true,
            )
        }

        stdoutThread.join(DRAIN_JOIN_MS)
        stderrThread.join(DRAIN_JOIN_MS)
        val code = process.exitValue()
        CommandResult(
            success = code == 0,
            stdout = stdoutThread.result(),
            stderr = stderrThread.result(),
            exitCode = code,
            timedOut = false,
        )
    }

    private fun drainAsync(reader: java.io.BufferedReader, onLine: ((String) -> Unit)?): DrainThread {
        val t = DrainThread(reader, onLine)
        // Daemon so a proot grandchild stuck holding the pipe open can never
        // wedge the JVM/worker thread past the join timeout.
        t.isDaemon = true
        t.start()
        return t
    }

    private class DrainThread(
        private val reader: java.io.BufferedReader,
        private val onLine: ((String) -> Unit)?,
    ) : Thread() {
        private val sb = StringBuilder()
        override fun run() {
            try {
                reader.useLines { lines ->
                    for (line in lines) {
                        if (sb.length < MAX_OUTPUT_CHARS) {
                            sb.append(line).append('\n')
                        }
                        // A throwing listener must never kill the drain: losing
                        // the remaining output would hang the caller's timeout.
                        try {
                            onLine?.invoke(line)
                        } catch (e: Exception) {
                            Log.d("ProotExecutor", "progress listener failed: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("ProotExecutor", "drain ended: ${e.message}")
            }
        }

        fun result(): String =
            if (sb.length > MAX_OUTPUT_CHARS) sb.substring(0, MAX_OUTPUT_CHARS) else sb.toString()
    }

    private companion object {
        /** Upper bound on the /proc walk; a phone has a few hundred processes. */
        const val TREE_WALK_TIMEOUT_MS = 200L

        /** How long to wait for a killed process to actually exit. */
        const val KILL_CONFIRM_TIMEOUT_MS = 2_000L

        /** Ceiling for a single guest command; installs are slow on phone hardware. */
        const val MAX_TIMEOUT_SECONDS = 1800L
        const val MAX_OUTPUT_CHARS = 15_000
        const val DRAIN_JOIN_MS = 2_000L
    }
}
