package industries.huginn.pifilling.sandbox

/**
 * Matches apk's own progress lines, in both phases of `apk add`:
 *
 *   (3/24) Downloading openssl-3.1.0-r3.apk
 *   (3/24) Installing openssl (3.1.0-r3)
 *
 * Group 1 = done count, group 2 = total, group 3 = the item's name. The
 * download phase and the install phase both use the same counter, and the
 * download phase is where most of the time goes — counting only the install
 * lines made the progress bar sit still for the whole download, which is what
 * read as "the bar is not moving".
 *
 * A top-level constant rather than a `private val` in the manager so the
 * parser is unit-testable without a `Context`.
 */
val APK_PROGRESS_REGEX: Regex =
    Regex("\\((\\d+)/(\\d+)\\)\\s+(?:Downloading|Installing)\\s+(\\S+)")
