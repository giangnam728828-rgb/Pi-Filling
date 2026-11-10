package industries.huginn.pifilling.sandbox

import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/**
 * Downloads and extracts the Alpine minirootfs. Ported from Kai's
 * RootfsDownloader: a pinned Alpine version, a list of mirrors tried in order,
 * and a hand-rolled tar extractor with a zip-slip guard (an APK can't depend on
 * a system `tar`, and we will not trust archive paths blindly).
 */
class RootfsDownloader {

    /** Maps an Android ABI to Alpine's architecture directory name. */
    fun alpineArch(androidAbi: String): String = when (androidAbi) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "armv7"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> throw IllegalArgumentException("unsupported ABI: $androidAbi")
    }

    private fun minirootfsUrl(mirror: String, arch: String): String =
        "$mirror/$ALPINE_BRANCH/releases/$arch/alpine-minirootfs-$ALPINE_VERSION-$arch.tar.gz"

    /**
     * What a download is doing, in enough detail for the UI to be honest about
     * it: which mirror of how many, how much has arrived, and how fast.
     */
    data class DownloadProgress(
        val mirrorIndex: Int,
        val mirrorCount: Int,
        val mirrorHost: String,
        val bytesRead: Long,
        val bytesTotal: Long?,
        val bytesPerSecond: Long?,
    )

    /**
     * Download the minirootfs tarball for [androidAbi] into [dest], trying each
     * mirror until one succeeds.
     *
     * Progress is reported as structured data rather than a bare fraction: a
     * fraction cannot say "mirror 2 of 6, 84 MB of 200 MB, 2.4 MB/s", and that
     * is exactly what the user needs when a download looks stuck. [bytesTotal]
     * is null when the server sends no Content-Length, and callers must render
     * that as indeterminate rather than inventing a percentage.
     */
    suspend fun download(
        androidAbi: String,
        dest: File,
        onProgress: (DownloadProgress) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val arch = alpineArch(androidAbi)
        var lastError: Exception? = null

        val ordered = orderMirrors()

        ordered.forEachIndexed { index, mirror ->
            coroutineContext.ensureActive()
            val url = minirootfsUrl(mirror, arch)
            val host = runCatching { java.net.URL(url).host }.getOrDefault(mirror)
            try {
                fetch(url, dest) { read, total, speed ->
                    onProgress(
                        DownloadProgress(
                            mirrorIndex = index + 1,
                            mirrorCount = ordered.size,
                            mirrorHost = host,
                            bytesRead = read,
                            bytesTotal = total,
                            bytesPerSecond = speed,
                        ),
                    )
                }
                Log.i(TAG, "downloaded rootfs from $mirror")
                return@withContext
            } catch (e: Exception) {
                Log.w(TAG, "mirror failed ($mirror): ${e.message}")
                lastError = e
                dest.delete()
            }
        }
        throw IOException("all ${MIRRORS.size} Alpine mirrors failed", lastError)
    }

    private suspend fun fetch(
        urlStr: String,
        dest: File,
        onProgress: (bytesRead: Long, bytesTotal: Long?, bytesPerSecond: Long?) -> Unit,
    ) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode} for $urlStr")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 }
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var sum = 0L

                    // Speed is measured over a window rather than per chunk: a
                    // per-chunk rate on a 64 KiB buffer is mostly noise, and the
                    // first window has no elapsed time to divide by, so it
                    // reports null instead of a made-up number.
                    var windowStart = System.currentTimeMillis()
                    var windowBytes = 0L
                    var speed: Long? = null

                    while (input.read(buf).also { read = it } != -1) {
                        coroutineContext.ensureActive()
                        output.write(buf, 0, read)
                        sum += read
                        windowBytes += read

                        val elapsed = System.currentTimeMillis() - windowStart
                        if (elapsed >= SPEED_WINDOW_MS) {
                            speed = (windowBytes * 1000L) / elapsed
                            windowStart = System.currentTimeMillis()
                            windowBytes = 0L
                        }
                        onProgress(sum, total, speed)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * True when [file] starts with the gzip magic `1f 8b`.
     *
     * Used instead of trusting a file name: see [extract].
     */
    fun isGzip(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val magic = ByteArray(2)
            input.read(magic) == 2 &&
                magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
        }
    }.getOrDefault(false)

    /**
     * Wraps [input] to count bytes actually consumed from the *compressed*
     * source.
     *
     * The tar reader skips data it does not need (padding, pax headers), so
     * counting at the call sites would miss it; counting here reflects how far
     * through the archive we are, which is what the progress bar means.
     */
    private class CountingInputStream(
        private val delegate: InputStream,
        private val onCount: (Long) -> Unit,
    ) : InputStream() {
        private var total = 0L
        override fun read(): Int = delegate.read().also { if (it >= 0) onCount(++total) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, len).also { if (it > 0) { total += it; onCount(total) } }
        override fun skip(n: Long): Long = delegate.skip(n).also { if (it > 0) { total += it; onCount(total) } }
        override fun available(): Int = delegate.available()
        override fun close() = delegate.close()
    }

    /**
     * Extract [archive] into [rootfs] — gzipped or not.
     *
     * The format cannot be inferred from the name. AGP's asset pipeline has been
     * observed to decompress `*.tar.gz` assets on the way into the APK *and* to
     * drop the `.gz` suffix, so an image baked as gzip can reach the device as a
     * plain tar called `.tar`. Assuming gzip there means `GZIPInputStream` throws
     * on the first two bytes and provisioning dies with a message about the
     * archive rather than about the real problem. Two bytes of sniffing is
     * cheaper than that.
     *
     * Handles regular files, directories, symlinks, and GNU long names. Both
     * entry paths AND relative symlink targets are resolved and checked to stay
     * within [rootfs] (zip-slip / path-traversal guard). Absolute symlink
     * targets are kept verbatim — proot interprets them against the guest root
     * at runtime.
     */
    suspend fun extract(
        archive: File,
        rootfs: File,
        /** Compressed bytes consumed, total (if known), current entry name. */
        onProgress: ((bytesRead: Long, bytesTotal: Long?, entry: String?) -> Unit)? = null,
    ): Unit = withContext(Dispatchers.IO) {
        rootfs.mkdirs()
        val rootCanonical = rootfs.canonicalFile

        val raw = BufferedInputStream(archive.inputStream())
        val gzipped = run {
            raw.mark(2)
            val magic = ByteArray(2)
            val read = raw.read(magic)
            raw.reset()
            read == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
        }
        Log.i(TAG, "extracting ${archive.name} (${if (gzipped) "gzip" else "plain"} tar)")

        val totalCompressed = archive.length().takeIf { it > 0 }
        var lastReported = -1L
        val body: InputStream = (if (gzipped) GZIPInputStream(raw) else raw).let { stream ->
            if (onProgress == null) stream else CountingInputStream(stream) { read ->
                // Report on whole-percent changes only: the tar reader consumes
                // 512-byte blocks, and calling back per block would recompose
                // the UI hundreds of times a second for no visible benefit.
                if (totalCompressed != null) {
                    val pct = read * 100 / totalCompressed
                    if (pct != lastReported) {
                        lastReported = pct
                        onProgress(read, totalCompressed, null)
                    }
                }
            }
        }
        body.use { input ->
            val header = ByteArray(BLOCK)
            var pendingLongName: String? = null

            while (true) {
                coroutineContext.ensureActive()
                if (!readFully(input, header)) break
                if (header.all { it.toInt() == 0 }) break // end-of-archive marker

                val name = pendingLongName ?: tarString(header, 0, 100).let { n ->
                    val prefix = tarString(header, 345, 155)
                    if (prefix.isNotEmpty()) "$prefix/$n" else n
                }
                pendingLongName = null

                val size = tarOctal(header, 124, 12)
                val mode = tarOctal(header, 100, 8)
                val type = header[156].toInt().toChar()
                val linkName = tarString(header, 157, 100)

                when (type) {
                    'L' -> { // GNU long name: the data block holds the next entry's name
                        pendingLongName = readBlockString(input, size)
                        continue
                    }
                    'x', 'g' -> { // pax extended headers: skip the data, keep std fields
                        skip(input, paddedSize(size))
                        continue
                    }
                }

                val target = File(rootfs, name)
                val targetCanonical = target.canonicalFile
                if (!targetCanonical.path.startsWith(rootCanonical.path)) {
                    throw IOException("blocked path traversal in rootfs tar: $name")
                }

                when (type) {
                    '5' -> target.mkdirs()
                    '2', '1' -> writeSymlink(linkName, target, rootCanonical) // symlink/hardlink
                    '0', '\u0000' -> {
                        target.parentFile?.mkdirs()
                        writeFile(input, target, size)
                        // Apply the archived executable bit. Java creates files
                        // non-executable, so without this every binary in the
                        // rootfs lands as -rw------- and proot fails on the
                        // first command with "'/bin/sh' is not executable" —
                        // /bin/sh is a symlink to busybox, and busybox is the
                        // file that actually needs +x. Owner-only is enough:
                        // proot runs as this app's uid.
                        if (mode and TAR_EXEC_BITS != 0L) {
                            target.setExecutable(true, /* ownerOnly = */ true)
                        }
                    }
                    else -> skip(input, paddedSize(size)) // unsupported (char/block/fifo) — skip
                }
            }
        }
    }

    private fun writeFile(input: java.io.InputStream, target: File, size: Long) {
        target.outputStream().use { out ->
            var remaining = size
            val buf = ByteArray(64 * 1024)
            while (remaining > 0) {
                val toRead = minOf(remaining, buf.size.toLong()).toInt()
                val n = input.read(buf, 0, toRead)
                if (n < 0) throw IOException("truncated tar entry: ${target.name}")
                out.write(buf, 0, n)
                remaining -= n
            }
        }
        skip(input, padding(size))
    }

    private fun writeSymlink(linkName: String, target: File, rootCanonical: File) {
        if (linkName.isEmpty()) return
        // Reject a relative target that escapes the rootfs (zip-slip via symlink).
        // Absolute targets are kept verbatim (proot resolves them in-guest).
        if (!linkName.startsWith("/")) {
            val resolved = File(target.parentFile, linkName).canonicalFile
            if (!resolved.path.startsWith(rootCanonical.path)) {
                Log.w(TAG, "blocked symlink escaping rootfs: ${target.name} -> $linkName")
                return
            }
        }
        target.parentFile?.mkdirs()
        target.delete()
        try {
            Os.symlink(linkName, target.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "symlink failed ${target.name} -> $linkName: ${e.message}")
        }
    }

    /**
     * Name resolution inside the sandbox.
     *
     * 8.8.8.8 is unreliable on mainland carriers, and the whole reason this
     * provisioning exists is a China-first user base, so AliDNS and Tencent
     * DNS lead: a host that can reach any one of them resolves apk/npm, and
     * the Google servers remain as a last resort for other regions.
     */
    fun writeResolvConf(rootfs: File) {
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/resolv.conf").writeText(
            "nameserver 223.5.5.5\n" +
                "nameserver 119.29.29.29\n" +
                "nameserver 8.8.8.8\n",
        )
    }

    /**
     * Point apk at the same Alpine branch we provisioned from.
     *
     * Every mirror is listed, not just one: apk resolves a package file from the
     * first repository that has it, and when that fetch fails it walks the rest
     * of the list. A single-mirror file (the old behaviour) meant one flaky CDN
     * file produced "1 error; 167 MiB in 120 packages" with no recovery, which
     * is exactly the "工具装不上" the sandbox sheet used to show.
     */
    /**
     * Mirror order for both the rootfs download and the apk repositories file.
     *
     * On zh-locale devices (this product's main market) the Tsinghua mirror is
     * the fastest reachable one, so lead with it; everywhere else the CDN order
     * is better. An explicit [preferred] mirror overrides the locale guess —
     * that is what the user-facing apk-source setting sets.
     */
    /**
     * Mirror order for both the rootfs download and the apk repositories file.
     *
     * An explicit [preferred] mirror overrides everything — that is what the
     * user-facing apk-source setting sets. With no explicit choice, the order
     * is decided by language: on zh devices the Tsinghua mirror leads (this
     * product's main market, and the fastest reachable mirror there), every
     * other language keeps the CDN order.
     *
     * [autoLanguage] is the *display* language the app is actually drawn in.
     * It must come from the app's language choice, not from
     * `java.util.Locale.getDefault()`: a user can force this app into
     * Traditional Chinese while the phone's system locale is English, and the
     * JVM default locale is the system one — so "automatic" mirror order that
     * keyed off the JVM locale would lead with European CDNs for a user who is
     * clearly operating in Chinese and wants the Chinese mirror first.
     */
    fun orderMirrors(preferred: String? = null, autoLanguage: String? = null): List<String> {
        val china = MIRRORS.firstOrNull { it.contains(CHINA_MIRROR_HOST) }
        if (preferred != null) {
            val hit = MIRRORS.firstOrNull { it == preferred || it.contains(preferred) }
                ?: preferred // a URL the user typed that we do not know; honour it verbatim
            return listOf(hit) + MIRRORS.filter { it != hit }
        }
        val language = autoLanguage ?: java.util.Locale.getDefault().language
        return if (language.startsWith("zh") && china != null) {
            listOf(china) + MIRRORS.filter { it != china }
        } else MIRRORS
    }

    fun writeRepositories(rootfs: File, mirrors: List<String> = orderMirrors()) {
        File(rootfs, "etc/apk").mkdirs()
        val lines = mirrors.flatMap { mirror ->
            listOf("$mirror/$ALPINE_BRANCH/main", "$mirror/$ALPINE_BRANCH/community")
        }
        File(rootfs, "etc/apk/repositories").writeText(lines.joinToString("\n") + "\n")
    }

    // ---- tar primitives ----

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return off != 0 // false only if nothing at all was read
            off += n
        }
        return true
    }

    private fun readBlockString(input: java.io.InputStream, size: Long): String {
        val data = ByteArray(size.toInt())
        readFully(input, data)
        skip(input, padding(size))
        return String(data).trim('\u0000', '\n', ' ')
    }

    private fun skip(input: java.io.InputStream, n: Long) {
        var remaining = n
        val buf = ByteArray(8 * 1024)
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val read = input.read(buf, 0, toRead)
            if (read < 0) break
            remaining -= read
        }
    }

    private fun padding(size: Long): Long = (BLOCK - (size % BLOCK)) % BLOCK
    private fun paddedSize(size: Long): Long = size + padding(size)

    private fun tarString(header: ByteArray, offset: Int, len: Int): String {
        var end = offset
        val limit = offset + len
        while (end < limit && header[end].toInt() != 0) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun tarOctal(header: ByteArray, offset: Int, len: Int): Long {
        // GNU base-256 encoding sets the high bit of the first byte. Alpine's
        // minirootfs uses plain octal; fail clearly rather than misparse if that
        // ever changes.
        if (header[offset].toInt() and 0x80 != 0) {
            throw IOException("unsupported GNU base-256 tar field at offset $offset")
        }
        val s = tarString(header, offset, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    companion object {
        const val ALPINE_VERSION = "3.21.3"
        const val ALPINE_BRANCH = "v3.21"
        private const val BLOCK = 512
        /** Owner/group/other execute bits in a tar header's mode field (0o111). */
        private const val TAR_EXEC_BITS = 0b001_001_001L
        /** Long enough to average out jitter, short enough to react. */
        private const val SPEED_WINDOW_MS = 500L
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val TAG = "RootfsDownloader"

        /** Alpine mirrors tried in order (ported from Kai's RootfsDownloader). */
        const val CHINA_MIRROR_HOST = "mirrors.tuna.tsinghua.edu.cn"

        val MIRRORS = listOf(
            "https://dl-cdn.alpinelinux.org/alpine",
            "https://mirrors.edge.kernel.org/alpine",
            "https://ftp.halifax.rwth-aachen.de/alpine",
            "https://alpine.ethz.ch/alpine",
            "https://mirror.csclub.uwaterloo.ca/alpine",
            "https://mirrors.tuna.tsinghua.edu.cn/alpine",
        )
    }
}
