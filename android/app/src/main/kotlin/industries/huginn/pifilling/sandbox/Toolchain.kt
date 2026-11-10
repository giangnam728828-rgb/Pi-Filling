package industries.huginn.pifilling.sandbox

/**
 * An optional toolchain the user can add to the sandbox.
 *
 * The bundled image ships 24 packages — Node, npm, Git, Python and the shell
 * utilities — which covers the common case offline. Anything beyond that (a C
 * compiler, a JVM, PHP) has to be fetched, so it cannot be part of the bundle
 * without making the APK enormous for users who never touch it.
 *
 * Hence: core stays baked in, toolchains are opt-in and download on demand. The
 * screen says so plainly rather than showing a toggle that silently needs
 * network.
 */
data class Toolchain(
    val id: String,
    /** Shown as the row title. */
    val label: String,
    /** One line of what is inside, in the user's words. */
    val hint: String,
    /** Packages to `apk add`. */
    val packages: List<String>,
    /** Rough download size in MB, so the row can warn before a tap. */
    val approxMegabytes: Int,
) {
    companion object {
        /**
         * The catalogue.
         *
         * Chosen for what an agent on a phone actually gets asked to do: compile
         * something, run a JVM tool, edit a PHP site. Each entry is self-contained
         * — installing one does not drag in another.
         */
        val ALL: List<Toolchain> = listOf(
            Toolchain(
                id = "c",
                label = "C / C++",
                hint = "gcc, g++, make, cmake, gdb",
                packages = listOf("build-base", "cmake", "gdb"),
                approxMegabytes = 120,
            ),
            Toolchain(
                id = "python",
                label = "Python 扩展",
                hint = "venv、构建工具、常用库头文件",
                packages = listOf("py3-virtualenv", "python3-dev", "py3-numpy"),
                approxMegabytes = 60,
            ),
            Toolchain(
                id = "java",
                label = "Java / Kotlin",
                hint = "OpenJDK 17、Gradle 依赖可运行",
                packages = listOf("openjdk17", "gradle"),
                approxMegabytes = 260,
            ),
            Toolchain(
                id = "php",
                label = "PHP",
                hint = "php-cli、常用扩展、composer",
                packages = listOf("php83", "php83-phar", "php83-openssl", "composer"),
                approxMegabytes = 45,
            ),
            Toolchain(
                id = "rust",
                label = "Rust",
                hint = "rustc、cargo（仅 musl 目标）",
                packages = listOf("rust", "cargo"),
                approxMegabytes = 180,
            ),
            Toolchain(
                id = "go",
                label = "Go",
                hint = "go 编译器与标准库",
                packages = listOf("go"),
                approxMegabytes = 220,
            ),
        )

        fun byId(id: String): Toolchain? = ALL.firstOrNull { it.id == id }
    }
}

/** What the app knows about one toolchain's presence. */
enum class ToolchainState {
    /** Not installed. */
    ABSENT,

    /** Installed and usable. */
    PRESENT,

    /** The install failed; the reason is shown next to the row. */
    FAILED,
}
