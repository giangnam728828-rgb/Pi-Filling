package industries.huginn.pifilling.sandbox.meta

/**
 * What actually happened when a process was told to die.
 *
 * The four fields are separate because each answers a different question, and
 * conflating any two of them is how a live process becomes invisible:
 *
 *  - [killRequested] says the request was issued. `destroyForcibly()` returns
 *    immediately and says nothing about the outcome; treating the request as the
 *    result is the single most common version of this mistake.
 *  - [rootExited] says the process we started is gone.
 *  - [knownTreeExited] says every process we *saw* is gone.
 *  - [treeWasComplete] says we saw all of them. Without this, "the ones I know
 *    about are gone" is not evidence about the ones I never enumerated.
 *
 * Only [safeToClear] permits releasing the spawn reservation. An incomplete tree
 * blocks even when every known member exited, because a surviving descendant
 * would still hold the rootfs and no later observation could prove otherwise.
 */
data class KillOutcome(
    val killRequested: Boolean,
    val rootExited: Boolean,
    val knownTreeExited: Boolean,
    val treeWasComplete: Boolean,
) {
    /**
     * Whether the reservation may be cleared.
     *
     * Deliberately strict: all four must hold. `treeWasComplete` is the one that
     * is easy to forget, and forgetting it is exactly how an unenumerated child
     * becomes an unaccounted-for process.
     */
    val safeToClear: Boolean
        get() = killRequested && rootExited && knownTreeExited && treeWasComplete

    /** Human-readable account for logs and diagnostics. */
    fun describe(): String = buildString {
        append("killRequested=").append(killRequested)
        append(" rootExited=").append(rootExited)
        append(" knownTreeExited=").append(knownTreeExited)
        append(" treeWasComplete=").append(treeWasComplete)
    }

    companion object {
        /** Nothing was attempted; nothing may be cleared. */
        val NOT_ATTEMPTED = KillOutcome(
            killRequested = false,
            rootExited = false,
            knownTreeExited = false,
            treeWasComplete = false,
        )
    }
}
