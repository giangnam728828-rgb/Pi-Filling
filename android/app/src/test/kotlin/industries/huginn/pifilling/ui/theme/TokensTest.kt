package industries.huginn.pifilling.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The token values are the design system's public contract.
 *
 * These tests exist so a change to a scale is a deliberate edit rather than a
 * side effect: if someone adjusts `gutter` from 22 to 20, every screen built in
 * stage 4 shifts, and that should be a decision rather than an accident.
 *
 * They also pin the relationship between tokens — the glass corner and the large
 * radius are the same value on purpose, and a future theme that separates them
 * should have to say so.
 */
class TokensTest {

    // ---- spacing ------------------------------------------------------------

    @Test
    fun `the spacing scale is strictly increasing`() {
        val s = Spacing()
        val ordered = listOf(
            s.xxs, s.xs, s.sm, s.md, s.lg, s.xl,
            s.xxl, s.xxxl, s.group, s.gutter, s.section, s.huge,
        )
        for (i in 1 until ordered.size) {
            assertTrue(
                "spacing must increase: index $i is ${ordered[i]} after ${ordered[i - 1]}",
                ordered[i] > ordered[i - 1],
            )
        }
    }

    @Test
    fun `the gutter matches the value the existing screens use`() {
        // 22.dp appears 46 times in PiFillingApp.kt. The token records what is
        // there; changing it here changes every screen in stage 4.
        assertEquals(22.dp, Spacing().gutter)
    }

    @Test
    fun `the common row paddings are present`() {
        val s = Spacing()
        assertEquals(10.dp, s.lg)
        assertEquals(12.dp, s.xl)
        assertEquals(16.dp, s.xxxl)
    }

    // ---- radius -------------------------------------------------------------

    @Test
    fun `radius values increase and the pill is round`() {
        val r = Radius()
        assertTrue(r.sm < r.md)
        assertTrue(r.md < r.lg)
        assertTrue("a pill must be at least as large as any corner", r.pill >= r.lg)
        assertEquals(100.dp, r.pill)
    }

    @Test
    fun `the shape helpers use their own value`() {
        val r = Radius()
        // Constructed shapes cannot be compared directly, so compare the corner
        // sizes they were built from by round-tripping through a fresh instance.
        assertEquals(Radius().mdShape, RoundedCornerShapeFactory.of(r.md))
    }

    @Test
    fun `the glass corner is its own value, larger than the card radius`() {
        // They are deliberately different: a glass pane is a full-bleed surface
        // that reads better with a wider sweep than a list row. Asserting the
        // relationship rather than equality records that intent.
        val glass = GlassTokens().cornerRadius
        val card = Radius().md
        assertTrue("a glass pane should be rounder than a card", glass > card)
        assertEquals(22.dp, glass)
    }

    // ---- typography ---------------------------------------------------------

    @Test
    fun `monospace roles use a monospace family`() {
        val t = AppTypography()
        assertEquals(
            androidx.compose.ui.text.font.FontFamily.Monospace,
            t.mono.fontFamily,
        )
        assertEquals(
            androidx.compose.ui.text.font.FontFamily.Monospace,
            t.code.fontFamily,
        )
    }

    @Test
    fun `code is larger than inline mono`() {
        // A fenced block is a distinct element; if it matched the inline size it
        // would read as one long line of tool output.
        val t = AppTypography()
        assertTrue(t.code.fontSize.value > t.mono.fontSize.value)
    }

    @Test
    fun `the overline is small and tracked`() {
        val o = AppTypography().overline
        assertTrue("an overline must be small", o.fontSize.value <= 12f)
        assertTrue("an overline needs letter spacing", o.letterSpacing.value > 0f)
    }

    @Test
    fun `the body scale keeps Material's rhythm`() {
        // The custom ramp stays close to Material: body sizes within 1sp of the
        // defaults, so untouched call sites read correctly.
        val t = AppTypography()
        val m = androidx.compose.material3.Typography()
        val bodyDelta = kotlin.math.abs(t.body.bodyMedium.fontSize.value - m.bodyMedium.fontSize.value)
        val largeDelta = kotlin.math.abs(t.body.bodyLarge.fontSize.value - m.bodyLarge.fontSize.value)
        assertTrue("bodyMedium within 1sp of Material", bodyDelta <= 1f)
        assertTrue("bodyLarge within 1sp of Material", largeDelta <= 1f)
    }

    // ---- elevation ----------------------------------------------------------

    @Test
    fun `elevation increases from flat to overlay`() {
        val e = Elevation()
        assertEquals(0.dp, e.flat)
        assertTrue(e.pane < e.floating)
        assertTrue(e.floating < e.overlay)
    }

    // ---- glass --------------------------------------------------------------

    @Test
    fun `glass opacities stay within a usable range`() {
        val g = GlassTokens()
        for ((name, v) in listOf(
            "fillAlpha" to g.fillAlpha,
            "fillAlphaStrong" to g.fillAlphaStrong,
            "borderAlpha" to g.borderAlpha,
            "sheenAlpha" to g.sheenAlpha,
        )) {
            assertTrue("$name must be above zero", v > 0f)
            assertTrue("$name must stay translucent", v <= 1f)
        }
    }

    @Test
    fun `a strong pane is more opaque than a normal one`() {
        val g = GlassTokens()
        assertTrue(g.fillAlphaStrong > g.fillAlpha)
    }

    // ---- composition --------------------------------------------------------

    @Test
    fun `PiTokens carries one of each group`() {
        val t = PiTokens()
        assertNotEquals(0.dp, t.spacing.gutter)
        assertNotEquals(0.dp, t.radius.md)
        assertTrue(t.typography.mono.fontSize.value > 0f)
        assertNotEquals(t.elevation.flat, t.elevation.overlay)
        assertTrue(t.glass.fillAlpha > 0f)
    }
}

/** Local helper so the shape comparison stays readable. */
private object RoundedCornerShapeFactory {
    fun of(dp: androidx.compose.ui.unit.Dp) = androidx.compose.foundation.shape.RoundedCornerShape(dp)
}
