package industries.huginn.pifilling.sandbox.meta

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Which step of a write failed.
 *
 * The stages are ordered the way they run, and the distinction matters to the
 * caller: a failure before [RENAME] leaves the previous contents intact, while a
 * failure at [DIR_FSYNC] means the new bytes are very likely live but their
 * durability is unconfirmed. Neither is a success, and a caller that cannot tell
 * them apart cannot decide whether to retry or to recover.
 */
enum class WriteStage {
    /** The name was rejected before any I/O (see [AtomicStore.resolveSafe]). */
    PATH_REJECTED,

    /** Writing the temporary file failed. Nothing at the target changed. */
    TEMP_WRITE,

    /** The temporary file's contents could not be flushed to the device. */
    FILE_FSYNC,

    /** The rename failed. Nothing at the target changed. */
    RENAME,

    /**
     * The rename succeeded but the directory entry could not be flushed.
     *
     * The new content is live for any process that reads it now, but a power
     * loss could still lose the rename. Treat as uncertain, never as done.
     */
    DIR_FSYNC,
}

/**
 * The three-state answer to "what is in this file".
 *
 * [Unknown] is deliberately not folded into [Absent]. "I could not read it" and
 * "it is not there" lead to opposite recovery decisions — one must block, the
 * other may proceed — and the whole point of this layer is that callers cannot
 * mistake one for the other by accident.
 */
sealed interface ReadResult {
    data class Found(val bytes: ByteArray, val digest: String) : ReadResult
    data object Absent : ReadResult
    data class Unknown(val reason: String, val cause: Throwable? = null) : ReadResult
}

sealed interface WriteResult {
    data object Ok : WriteResult
    data class Failed(val stage: WriteStage, val cause: Throwable) : WriteResult
}

/**
 * A content digest for a directory tree.
 *
 * [complete] is false when part of the tree could not be read, so the digest
 * describes less than the caller asked about. [stable] is false when two
 * consecutive passes disagreed, i.e. something was writing while we read.
 * A digest that is not both complete and stable must not be compared against a
 * recorded value — it would "differ" for reasons that are not content changes.
 */
data class DirectoryDigest(
    val hash: String,
    val complete: Boolean,
    val stable: Boolean,
)

/**
 * Crash-safe storage for the small metadata files the sandbox depends on.
 *
 * Every write follows the same four steps — write a temporary file, fsync it,
 * rename it over the target, fsync the parent directory — and reports which step
 * failed. A plain `writeText` cannot do this: an interruption leaves a half
 * written file that looks like a real one, and the next launch reads garbage as
 * fact.
 *
 * Pure Kotlin on purpose. The class takes a [File] and nothing else, so the
 * persistence layer can be tested without an Android runtime; callers that do
 * have a `Context` pass `filesDir`-derived paths in. See the 2c-B plan, D4.
 *
 * Not for bulk data. This is for files measured in kilobytes whose loss or
 * corruption would change a recovery decision.
 */
class AtomicStore(private val dir: File) {

    /**
     * Test seam: called at the start of each stage, so a test can throw and
     * simulate the process dying at exactly that point. Production leaves it
     * null. Internal rather than public so it cannot become part of the API.
     */
    internal var testHook: ((WriteStage, String) -> Unit)? = null

    /**
     * Distinguishes concurrent temporary files for the same target.
     *
     * Process-local: two processes writing the same store would still collide,
     * but the sandbox is a single-process app and a cross-process lock would
     * cost more than it protects here. The counter is enough for the case that
     * actually occurs — the log writing several files from one coroutine while
     * another operation runs.
     */
    private val tmpSeq = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Resolve [name] to a file inside [dir], or null when it would escape.
     *
     * Rejects absolute paths, `.`/`..` segments, empty segments, and any path
     * whose canonical form lands outside the store — which is what catches a
     * symlink planted inside the directory that points elsewhere. The sandbox
     * deletes trees based on these names, so a name that resolves outside the
     * store is not a bug to tolerate but a request to refuse.
     */
    fun resolveSafe(name: String): File? {
        if (name.isBlank()) return null
        if (name.startsWith("/") || name.startsWith("\\")) return null
        if (name.contains(":")) return null // windows drive-relative; never valid here
        val segments = name.split('/', '\\')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null

        val root = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val target = File(dir, name)
        val canonical = runCatching { target.canonicalFile }.getOrNull() ?: return null
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
            return null
        }
        // A symlink at the target itself would make `rename` replace the link
        // rather than its target on some platforms, and would make a later read
        // follow the link out of the store. Refuse it outright.
        if (Files.isSymbolicLink(target.toPath())) return null
        return target
    }

    /**
     * Read a file, distinguishing "absent" from "could not read".
     *
     * The digest is returned alongside the bytes so a caller that stores
     * digests can verify without re-reading.
     */
    fun read(name: String): ReadResult {
        val target = resolveSafe(name)
            ?: return ReadResult.Unknown("unsafe path: $name")
        if (!target.exists()) {
            // exists() is false for a dangling symlink too; the symlink check in
            // resolveSafe already refused that case, so Absent is accurate here.
            return ReadResult.Absent
        }
        return try {
            val bytes = target.readBytes()
            ReadResult.Found(bytes, sha256Hex(bytes))
        } catch (e: IOException) {
            ReadResult.Unknown("read failed: ${e.message}", e)
        } catch (e: SecurityException) {
            ReadResult.Unknown("read denied: ${e.message}", e)
        }
    }

    /** Convenience for text metadata; the bytes are UTF-8. */
    fun writeText(name: String, text: String): WriteResult =
        write(name, text.toByteArray(Charsets.UTF_8))

    /**
     * Write [body] to [name] atomically.
     *
     * Returns [WriteResult.Ok] only when all four steps completed. Every other
     * outcome is a [WriteResult.Failed] naming the step, and the temporary file
     * is removed on the way out so a later run does not trip over it.
     */
    fun write(name: String, body: ByteArray): WriteResult {
        val target = resolveSafe(name)
            ?: return WriteResult.Failed(
                WriteStage.PATH_REJECTED,
                IllegalArgumentException("unsafe path: $name"),
            )

        val parent = target.parentFile ?: dir
        if (!parent.exists() && !parent.mkdirs() && !parent.exists()) {
            return WriteResult.Failed(
                WriteStage.TEMP_WRITE,
                IOException("could not create ${parent.absolutePath}"),
            )
        }

        // Hidden and suffixed so a leftover from a crash is obvious and cannot
        // be mistaken for the real file. The counter keeps two concurrent
        // writers off each other's temporary file: with a fixed name they would
        // both write the same path, and whichever renamed last would silently
        // discard the other's bytes while both callers received Ok.
        val tmp = File(parent, ".${target.name}.${tmpSeq.incrementAndGet()}.tmp")
        try {
            // Every stage is wrapped individually. A single outer catch(i.e.
            // IOException) would swallow a fsync or rename failure and report it
            // as TEMP_WRITE, which is exactly the "one failure looks like
            // another" mistake this class exists to prevent.
            val tempFailure = runStage(WriteStage.TEMP_WRITE, name) {
                FileOutputStream(tmp).use { out ->
                    out.write(body)
                    out.flush()
                }
            }
            if (tempFailure != null) return tempFailure

            val fsyncFailure = runStage(WriteStage.FILE_FSYNC, name) {
                FileOutputStream(tmp, true).use { out -> out.fd.sync() }
            }
            if (fsyncFailure != null) return fsyncFailure

            val renameFailure = runStage(WriteStage.RENAME, name) {
                // rename(2) is atomic within a filesystem and replaces the
                // target, which is exactly the guarantee needed and is available
                // on every Android release (Files.move with ATOMIC_MOVE would be
                // API 26+ and no stronger here, both being in one directory).
                if (!tmp.renameTo(target)) {
                    throw IOException("rename ${tmp.name} -> ${target.name} failed")
                }
            }
            if (renameFailure != null) return renameFailure

            val dirFailure = runStage(WriteStage.DIR_FSYNC, name) { fsyncDirectory(parent) }
            if (dirFailure != null) return dirFailure

            return WriteResult.Ok
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * Run one stage, mapping any failure to a [WriteResult.Failed] naming it.
     *
     * Separate from the stages themselves so the stage a failure is attributed
     * to is decided by position, not by exception type — `IOException` is what
     * a full disk, a permission problem and an injected fault all throw.
     */
    private inline fun runStage(
        stage: WriteStage,
        name: String,
        body: () -> Unit,
    ): WriteResult.Failed? = try {
        hook(stage, name)
        body()
        null
    } catch (e: IOException) {
        WriteResult.Failed(stage, e)
    } catch (e: SecurityException) {
        WriteResult.Failed(stage, e)
    }

    /**
     * Digest the tree at [relPath] (or the whole store when blank).
     *
     * Walks twice and reports [DirectoryDigest.stable] only when both passes
     * agree. A single pass cannot distinguish "this is the content" from "this
     * is the content right now, halfway through someone else's write", and a
     * digest used to decide whether a deployment matched would then be wrong in
     * the dangerous direction.
     */
    fun dirDigest(relPath: String = ""): DirectoryDigest {
        val root = if (relPath.isBlank()) dir else resolveSafe(relPath)
            ?: return DirectoryDigest("", complete = false, stable = false)

        // A path that does not exist yields no digest at all. Returning the hash
        // of an empty tree would let a caller conclude "the tree is empty and
        // matches", when the truth is that it could not be looked at.
        if (!root.exists()) return DirectoryDigest("", complete = false, stable = false)

        val first = runCatching { hashTree(root) }
        if (first.isFailure) {
            return DirectoryDigest("", complete = false, stable = false)
        }
        val (hash1, complete1) = first.getOrThrow()
        val second = runCatching { hashTree(root) }.getOrNull()
            ?: return DirectoryDigest(hash1, complete = complete1, stable = false)
        val (hash2, complete2) = second
        return DirectoryDigest(
            hash = if (hash1 == hash2) hash1 else hash2,
            complete = complete1 && complete2,
            stable = hash1 == hash2,
        )
    }

    /** One pass over [root]: content hash plus whether every entry was readable. */
    private fun hashTree(root: File): Pair<String, Boolean> {
        val entries = sortedEntries(root)
        val md = MessageDigest.getInstance("SHA-256")
        var complete = true
        for ((rel, file) in entries) {
            md.update(rel.toByteArray(Charsets.UTF_8))
            md.update(0)
            try {
                val isLink = Files.isSymbolicLink(file.toPath())
                if (isLink) {
                    md.update('L'.code.toByte())
                    val target = runCatching {
                        Files.readSymbolicLink(file.toPath()).toString()
                    }.getOrElse {
                        complete = false
                        ""
                    }
                    md.update(target.toByteArray(Charsets.UTF_8))
                } else if (file.isDirectory) {
                    md.update('D'.code.toByte())
                } else {
                    md.update('F'.code.toByte())
                    file.inputStream().use { input ->
                        val buf = ByteArray(8 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            md.update(buf, 0, n)
                        }
                    }
                }
            } catch (e: IOException) {
                // A file that vanished mid-walk makes the digest incomplete
                // rather than wrong; say so instead of pretending.
                complete = false
            }
            md.update(0)
        }
        return md.digest().joinToString("") { "%02x".format(it) } to complete
    }

    /** Every file and directory under [root], as (relative slash path, file). */
    private fun sortedEntries(root: File): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        val stack = ArrayDeque<Pair<String, File>>()
        stack.addLast("" to root)
        while (stack.isNotEmpty()) {
            val (prefix, current) = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                // The temp file of an in-flight write is not part of the tree's
                // identity; including it would make two identical trees differ.
                if (child.name.startsWith(".") && child.name.endsWith(".tmp")) continue
                out += rel to child
                if (child.isDirectory && !Files.isSymbolicLink(child.toPath())) {
                    stack.addLast(rel to child)
                }
            }
        }
        return out.sortedBy { it.first }
    }

    /** Flush a directory entry so a rename survives a power loss. */
    private fun fsyncDirectory(d: File) {
        FileChannel.open(d.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun hook(stage: WriteStage, name: String) {
        testHook?.invoke(stage, name)
    }

    companion object {
        /** Full SHA-256, 64 hex characters. Truncating would weaken equality. */
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

        /** True when [dir] exists and holds nothing at all. */
        fun isEmptyDir(dir: File): Boolean =
            dir.isDirectory && (dir.list()?.isEmpty() ?: false)

        /** Kept so callers can assert a path stayed inside the store. */
        fun isInside(root: File, candidate: File): Boolean {
            val r = runCatching { root.canonicalFile }.getOrNull() ?: return false
            val c = runCatching { candidate.canonicalFile }.getOrNull() ?: return false
            return c == r || c.path.startsWith(r.path + File.separator)
        }

        /** Present only so the unused-warning stays away; documented in tests. */
        internal val LINK_OPTION: LinkOption = LinkOption.NOFOLLOW_LINKS
    }
}
