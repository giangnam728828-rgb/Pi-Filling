package industries.huginn.pifilling.storage

import java.io.File

/**
 * How much disk the app is using, broken down by what the user could act on.
 *
 * The breakdown matters more than the total: "1.2 GB" tells nobody anything,
 * while "sandbox 1.1 GB, history 200 KB" tells them what deleting something
 * would actually recover.
 */
data class StorageUsage(
    val sandboxBytes: Long,
    val metaBytes: Long,
    val historyBytes: Long,
    val otherBytes: Long,
) {
    val totalBytes: Long get() = sandboxBytes + metaBytes + historyBytes + otherBytes
}

/**
 * A measurement, or the reason there is not one.
 *
 * `null` is deliberately not used for "could not measure": zero bytes and
 * unmeasurable bytes look the same on screen and mean opposite things, and
 * showing "0 B" for a 1.2 GB sandbox because a directory walk failed is worse
 * than showing nothing.
 */
sealed interface StorageReport {
    data class Measured(val usage: StorageUsage) : StorageReport

    /** Some directories could not be read; the figure is a lower bound. */
    data class Partial(val usage: StorageUsage, val unreadable: List<String>) : StorageReport

    data class Failed(val reason: String) : StorageReport
}

/**
 * Walk the app's private directory and attribute bytes to categories.
 *
 * Pure Kotlin on a [File] so it can be tested without an Android runtime, which
 * is the same constraint the persistence layer follows.
 */
object StorageUsageReader {

    fun read(filesDir: File): StorageReport {
        if (!filesDir.exists()) {
            return StorageReport.Failed("应用数据目录不存在")
        }
        val unreadable = mutableListOf<String>()

        fun size(dir: File, label: String): Long {
            if (!dir.exists()) return 0L
            var total = 0L
            val stack = ArrayDeque<File>()
            stack.addLast(dir)
            while (stack.isNotEmpty()) {
                val f = stack.removeLast()
                when {
                    f.isDirectory -> {
                        val children = f.listFiles()
                        if (children == null) {
                            unreadable += label
                        } else {
                            children.forEach { stack.addLast(it) }
                        }
                    }
                    // A file that vanished mid-walk contributes nothing; that is
                    // not an error worth reporting, unlike a directory we cannot
                    // list.
                    else -> total += runCatching { f.length() }.getOrDefault(0L)
                }
            }
            return total
        }

        val sandbox = size(File(filesDir, "linux-sandbox"), "沙盒")
        val meta = size(File(filesDir, "sandbox-meta"), "恢复元数据")
        val history = runCatching {
            listOf("chat-history.json", "open-session.id")
                .map { File(filesDir, it) }
                .filter { it.isFile }
                .sumOf { it.length() }
        }.getOrElse {
            unreadable += "会话历史"
            0L
        }

        // Everything else in filesDir that is not one of the categories above.
        val known = sandbox + meta + history
        val all = size(filesDir, "应用数据")
        val other = (all - known).coerceAtLeast(0L)

        val usage = StorageUsage(sandbox, meta, history, other)
        return if (unreadable.isEmpty()) {
            StorageReport.Measured(usage)
        } else {
            StorageReport.Partial(usage, unreadable.distinct())
        }
    }

    /**
     * The largest entries under [dir], for diagnosis.
     *
     * The category totals say how much; this says *what*. When a category is
     * unexpectedly large, the answer is almost always a handful of leftovers —
     * an archive that was never deleted, a staging directory from a failed
     * install — and they are invisible in a total.
     */
    fun largestEntries(dir: File, limit: Int = 12): List<Pair<String, Long>> {
        if (!dir.exists()) return emptyList()
        val out = mutableListOf<Pair<String, Long>>()
        val stack = ArrayDeque<Pair<File, String>>()
        stack.addLast(dir to "")
        while (stack.isNotEmpty()) {
            val (f, prefix) = stack.removeLast()
            val children = f.listFiles() ?: continue
            for (child in children) {
                val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) {
                    // A directory's own size is the sum of its files; report it
                    // whole and do not descend, or the list fills with leaves.
                    out += rel to directorySize(child)
                } else {
                    out += rel to runCatching { child.length() }.getOrDefault(0L)
                }
            }
        }
        return out.sortedByDescending { it.second }.take(limit)
    }

    private fun directorySize(dir: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (f.isDirectory) {
                f.listFiles()?.forEach { stack.addLast(it) }
            } else {
                total += runCatching { f.length() }.getOrDefault(0L)
            }
        }
        return total
    }

    /** "1.2 GB" — the same units the sandbox sheet uses. */
    fun format(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
