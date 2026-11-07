package com.androidharness.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The denial breaker: nudge at the threshold, per-session isolation, reset on approval. */
class ApprovalDenialBreakerTest {

    @Test
    fun `nudge appears only at the threshold and resets on approval`() {
        val session = "s-${System.nanoTime()}"
        assertNull(ApprovalDenialBreaker.recordDenial(session))
        assertNull(ApprovalDenialBreaker.recordDenial(session))
        val nudge = ApprovalDenialBreaker.recordDenial(session)
        assertNotNull(nudge)
        assertTrue(nudge!!.contains("3 consecutive"))
        ApprovalDenialBreaker.reset(session)
        assertNull(ApprovalDenialBreaker.recordDenial(session))
    }

    @Test
    fun `sessions are isolated`() {
        val a = "a-${System.nanoTime()}"
        val b = "b-${System.nanoTime()}"
        ApprovalDenialBreaker.recordDenial(a)
        ApprovalDenialBreaker.recordDenial(a)
        assertNull(ApprovalDenialBreaker.recordDenial(b))
        assertNotNull(ApprovalDenialBreaker.recordDenial(a))
    }

    @Test
    fun `nudge tells the model to change approach`() {
        val text = ApprovalDenialBreaker.addendum(4)
        assertTrue(text.contains("4 consecutive"))
        assertTrue(text.contains("change the approach"))
        assertTrue(text.contains("clarifying question"))
    }
}
