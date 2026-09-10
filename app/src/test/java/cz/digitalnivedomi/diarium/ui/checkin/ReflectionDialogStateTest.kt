package cz.digitalnivedomi.diarium.ui.checkin

import cz.digitalnivedomi.diarium.core.data.AiReflectionRepository
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The open/close policy of the post-save reflection window, driven straight off
 * [CheckInStateHolder].
 *
 * Deliberately a plain JVM test: the window's policy is the holder's
 * transitions, not the dialog's pixels, and this repo's Compose render tests do
 * not idle reliably. Every case below is a gesture the screen performs, in order,
 * on a holder — so nothing here depends on recomposition, rotation or navigation.
 */
class ReflectionDialogStateTest {

    private fun holder(date: String = DATE) = CheckInStateHolder(date)

    @Test
    fun `a successful save opens the reflection window`() {
        val holder = holder()
        assertFalse("a fresh form shows no window", holder.state.showReflectionDialog)

        // The screen's save(): markSaving() on the tap, markSaved() when the RPC
        // resolves — that is the one transition allowed to open the window.
        holder.markSaving()
        holder.markSaved()

        assertTrue(holder.state.saved)
        assertTrue(holder.state.showReflectionDialog)
    }

    @Test
    fun `closing the window keeps the day stored`() {
        val holder = holder()
        holder.markSaved()

        holder.dismissReflectionDialog()

        assertFalse(holder.state.showReflectionDialog)
        // "Zavřít" hides the window only; the day stays saved.
        assertTrue(holder.state.saved)
    }

    @Test
    fun `only a later save brings the window back`() {
        val holder = holder()
        holder.markSaved()
        holder.dismissReflectionDialog()
        assertFalse(holder.state.showReflectionDialog)

        // Nothing happens on its own: it takes another save to re-open it.
        holder.markSaved()

        assertTrue(holder.state.showReflectionDialog)
    }

    @Test
    fun `loading a row never opens the window`() {
        // What a return to the tab — or a configuration change — does: a fresh
        // holder loads the stored day. The window must stay shut.
        val holder = holder()
        holder.load(DiaryEntry(mood = 4, aiReflection = "Dnes sis to užil."))

        assertFalse(holder.state.showReflectionDialog)
    }

    @Test
    fun `switching day closes an open window`() {
        val holder = holder()
        holder.markSaved()
        assertTrue(holder.state.showReflectionDialog)

        holder.setDate("2026-09-09")

        assertFalse(holder.state.showReflectionDialog)
        assertNull(holder.state.reflection)
    }

    @Test
    fun `a day that already has a reflection needs no request`() {
        val holder = holder()
        holder.load(DiaryEntry(mood = 4, aiReflection = "Dnes sis to užil."))

        // Seeded from the row, so opening the window must not cost a network call.
        assertEquals("Dnes sis to užil.", holder.state.reflection)
        assertFalse(holder.state.reflectionNeedsRequest)
    }

    @Test
    fun `a day without a reflection asks for one`() {
        val holder = holder()
        holder.load(DiaryEntry(mood = 4))

        assertNull(holder.state.reflection)
        assertTrue(holder.state.reflectionNeedsRequest)
    }

    @Test
    fun `a request already in flight is not asked again`() {
        val holder = holder()
        holder.load(DiaryEntry(mood = 4))
        holder.markReflectionLoading()

        // The window re-keys off showReflectionDialog; this is what keeps a
        // recomposition during the request from firing a second one.
        assertFalse(holder.state.reflectionNeedsRequest)
    }

    @Test
    fun `the scheduled fetch happens exactly once per save`() {
        // What actually fires the network call is the *pair*: the window is open
        // AND the state still needs a request (see the LaunchedEffect in
        // CheckInScreen). A closed window with a fresh holder is "nothing to do".
        fun triggers(h: CheckInStateHolder) =
            h.state.showReflectionDialog && h.state.reflectionNeedsRequest

        val holder = holder()
        assertFalse(triggers(holder))

        holder.markSaved()
        assertTrue(triggers(holder))

        // The request starts (markReflectionLoading) and lands (setReflection +
        // markReflectionStored): after that the flag pair says "nothing to do".
        holder.markReflectionLoading()
        assertFalse(triggers(holder))

        holder.setReflection("Dnes sis to užil.")
        holder.markReflectionStored()
        assertFalse(triggers(holder))

        // A recomposition while the window stays open cannot ask again either.
        holder.markReflectionLoading()
        assertFalse(triggers(holder))
    }

    @Test
    fun `the generated text lands in the open window`() {
        val holder = holder()
        holder.markSaved()
        holder.markReflectionLoading()

        holder.setReflection("Dnes sis to užil.")

        assertEquals("Dnes sis to užil.", holder.state.reflection)
        assertFalse(holder.state.reflectionLoading)
        assertNull(holder.state.reflectionError)
        assertTrue(holder.state.showReflectionDialog)

        // Writing it back to the day must not close the window either.
        holder.markReflectionStored()
        assertTrue(holder.state.showReflectionDialog)
    }

    @Test
    fun `a failed request shows the repository message and the spinner stops`() {
        val holder = holder()
        holder.markSaved()
        holder.markReflectionLoading()

        holder.markReflectionError(AiReflectionRepository.MESSAGE_CONNECT)

        assertEquals(AiReflectionRepository.MESSAGE_CONNECT, holder.state.reflectionError)
        assertFalse(holder.state.reflectionLoading)
        // Still open, so the user reads the reason instead of a silent no-op.
        assertTrue(holder.state.showReflectionDialog)
    }

    @Test
    fun `a cooldown is shown verbatim and the stored reflection stays visible`() {
        val holder = holder()
        holder.load(DiaryEntry(mood = 4, aiReflection = "Dnes sis to užil."))
        holder.markSaved()

        // "Znovu vygenerovat" brought back the server's own sentence; the text the
        // day already had must survive it untouched.
        holder.markReflectionLoading()
        holder.markReflectionError(AiReflectionRepository.MESSAGE_COOLDOWN)

        assertEquals(AiReflectionRepository.MESSAGE_COOLDOWN, holder.state.reflectionError)
        assertEquals("Dnes sis to užil.", holder.state.reflection)
        assertTrue(holder.state.showReflectionDialog)
    }

    @Test
    fun `the AI flow's own write never reopens a dismissed window`() {
        val holder = holder()
        holder.markSaved()
        holder.dismissReflectionDialog()

        // The screen's generateReflection() finishes and stores the text with a
        // second save — markReflectionStored(), not markSaved().
        holder.markReflectionStored()

        assertFalse(holder.state.showReflectionDialog)
        assertTrue(holder.state.saved)
    }

    private companion object {
        const val DATE = "2026-09-10"
    }
}
