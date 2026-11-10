package industries.huginn.pifilling.sandbox.meta

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The reservation is the only durable trace of a launch that has not been
 * confirmed registered. If it can be mistaken for "no reservation", a wipe will
 * read an empty registry and conclude that everything died — while the process
 * it could not see is still holding the rootfs.
 */
class SpawnReservationTest {

    private lateinit var dir: File
    private lateinit var store: AtomicStore
    private lateinit var reservations: SpawnReservationStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("spawn").toFile()
        store = AtomicStore(dir)
        reservations = SpawnReservationStore(store)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun reservation(
        id: String = "spawn#1",
        pid: Int = -1,
        start: Long = -1,
    ) = SpawnReservation(
        id = id,
        format = SpawnReservation.CURRENT_FORMAT,
        purpose = "setup",
        seq = 1,
        startedAt = 1_700_000_000_000,
        pid = pid,
        startTimeTicks = start,
    )

    // ---- round trip ---------------------------------------------------------

    @Test
    fun `a written reservation reads back intact`() {
        val r = reservation()
        assertEquals(WriteResult.Ok, reservations.write(r))
        val read = reservations.read()
        assertNotNull(read)
        assertEquals("spawn#1", read!!.id)
        assertEquals("setup", read.purpose)
        assertEquals(1L, read.seq)
        assertFalse("no pid yet", read.hasPid)
    }

    @Test
    fun `a reservation with a pid reports hasPid`() {
        assertEquals(WriteResult.Ok, reservations.write(reservation(pid = 42, start = 999)))
        val read = reservations.read()!!
        assertTrue(read.hasPid)
        assertEquals(42, read.pid)
        assertEquals(999L, read.startTimeTicks)
    }

    @Test
    fun `updatePid fills in the identity without changing the id`() {
        val original = reservation()
        reservations.write(original)
        assertEquals(WriteResult.Ok, reservations.updatePid(original, 42, 999))
        val read = reservations.read()!!
        assertEquals(original.id, read.id)
        assertEquals(42, read.pid)
        assertTrue(read.hasPid)
    }

    // ---- the three states ---------------------------------------------------

    @Test
    fun `nothing written reads as None`() {
        assertEquals(ReservationState.None, reservations.state())
        assertNull(reservations.read())
    }

    @Test
    fun `a written reservation reads as Pending`() {
        reservations.write(reservation())
        val state = reservations.state()
        assertTrue(state is ReservationState.Pending)
    }

    @Test
    fun `a cleared reservation reads as None`() {
        reservations.write(reservation())
        assertEquals(WriteResult.Ok, reservations.clear())
        assertEquals(ReservationState.None, reservations.state())
    }

    @Test
    fun `a corrupt reservation is Unreadable, never None`() {
        // This is the confusion the whole file exists to prevent: a reservation
        // that cannot be parsed must not look like "no reservation was made".
        store.writeText(SpawnReservation.FILE_NAME, "{\"id\": broken")
        val state = reservations.state()
        assertTrue("expected Unreadable, got $state", state is ReservationState.Unreadable)
        assertNull("read() may fold it, state() must not", reservations.read())
    }

    @Test
    fun `an unreadable reservation is Unreadable`() {
        // A directory where the file should be: read() fails rather than
        // returning bytes.
        File(dir, SpawnReservation.FILE_NAME).mkdirs()
        assertTrue(reservations.state() is ReservationState.Unreadable)
    }

    // ---- failure handling ---------------------------------------------------

    @Test
    fun `a failed write is reported and leaves nothing behind`() {
        store.testHook = { stage, name ->
            if (name == SpawnReservation.FILE_NAME && stage == WriteStage.RENAME) {
                throw IOException("injected")
            }
        }
        val r = reservations.write(reservation())
        assertTrue("must surface the failure", r is WriteResult.Failed)
        store.testHook = null
        assertEquals(ReservationState.None, reservations.state())
    }

    @Test
    fun `clear is idempotent`() {
        assertEquals(WriteResult.Ok, reservations.clear())
        assertEquals(ReservationState.None, reservations.state())
    }

    @Test
    fun `a blank file reads as None rather than corrupt`() {
        // clear() writes "" rather than deleting, so an empty file is the
        // normal post-clear state and must not be reported as corrupt.
        store.writeText(SpawnReservation.FILE_NAME, "")
        assertEquals(ReservationState.None, reservations.state())
    }

    @Test
    fun `the purpose survives a round trip`() {
        reservations.write(reservation().copy(purpose = "npm ci"))
        assertEquals("npm ci", reservations.read()!!.purpose)
    }
}
