package com.androidharness.app.data

import com.androidharness.app.data.db.FakeDecisionDao
import com.androidharness.app.data.db.DecisionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The invariants that make “review previous decisions, then decide” real:
 * nothing deleted, nothing rewritten in place, one ACTIVE per topic, the
 * chain replayable through supersedesId.
 */
class DecisionLedgerTest {

    private fun ledger() = DecisionLedger(FakeDecisionDao())

    @Test
    fun `a new decision supersedes the previous active and keeps its history`() = runBlocking {
        val l = ledger()
        val a = l.record(topicKey = "signing", statement = "Sign with debug key", rationale = "no release key yet")
        val b = l.record(topicKey = "signing", statement = "Sign with release key", rationale = "key generated")
        val history = l.history("signing")
        assertEquals(2, history.size) // nothing deleted
        assertEquals("SUPERSEDED", history.first { it.id == a.id }.status)
        assertEquals("ACTIVE", history.first { it.id == b.id }.status)
        assertEquals(a.id, b.supersedesId)
        assertEquals(b.id, l.active("signing")?.id)
        // The old rationale survives the supersede — the whole point.
        assertEquals("no release key yet", history.first { it.id == a.id }.rationale)
    }

    @Test
    fun `a three step conflict chain stays replayable and active is unique`() = runBlocking {
        val l = ledger()
        val a = l.record(topicKey = "build", statement = "use gradle 9.8.2")
        val b = l.record(topicKey = "build", statement = "use wrapper 9.7.0")
        val c = l.record(topicKey = "build", statement = "use wrapper 9.7.0 with pins")
        assertEquals(listOf(a.id, b.id, c.id), l.history("build").map { it.id })
        assertNull(a.supersedesId)
        assertEquals(a.id, b.supersedesId)
        assertEquals(b.id, c.supersedesId)
        assertEquals(c.id, l.active("build")?.id)
        assertEquals(1, l.history("build").count { it.status == "ACTIVE" })
    }

    @Test
    fun `supersede without replacement records the reason and empties active`() = runBlocking {
        val l = ledger()
        val a = l.record(topicKey = "export", statement = "Export via FTP")
        val closed = l.supersede(a.id, reason = "feature withdrawn")
        assertEquals("SUPERSEDED", closed.status)
        assertEquals("feature withdrawn", closed.closedReason)
        assertNull(l.active("export"))
        assertEquals(1, l.history("export").size)
    }

    @Test
    fun `cannot close an already closed decision`() = runBlocking {
        val l = ledger()
        l.record(topicKey = "x", statement = "one")
        val b = l.record(topicKey = "x", statement = "two")
        val supersededId = l.history("x").first { it.status == "SUPERSEDED" }.id
        try {
            l.supersede(supersededId, "late close")
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
        assertEquals(b.id, l.active("x")?.id)
    }

    @Test
    fun `statements topics and sections are validated`() = runBlocking {
        val l = ledger()
        try {
            l.record(topicKey = "y", statement = "  ")
            fail("blank statement")
        } catch (_: IllegalArgumentException) {
        }
        try {
            l.record(topicKey = "  ", statement = "s")
            fail("blank topic")
        } catch (_: IllegalArgumentException) {
        }
        try {
            l.record(topicKey = "y", statement = "s", section = "NOPE")
            fail("bad section")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun `decision prompt lists only active decisions newest first and caps`() = runBlocking {
        val l = ledger()
        l.record(topicKey = "build", statement = "old decision", rationale = "first")
        l.record(topicKey = "build", statement = "new decision", rationale = "replaces first")
        val prompt = DecisionPrompt.build(l.history("build"))
        assertTrue(prompt.contains("[build] new decision — replaces first"))
        assertFalse("superseded rows stay out of the prompt", prompt.contains("old decision"))

        val many = (1..50).map {
            DecisionEntity(id = "$it", topicKey = "t$it", statement = "s$it", createdAt = it.toLong(), decidedAt = it.toLong())
        }
        val capped = DecisionPrompt.build(many, maxChars = 400)
        assertTrue("capped block stays bounded, got ${capped.length}", capped.length <= 480)
        assertTrue(capped.contains("more standing decisions"))
        assertTrue(capped.endsWith("recall them by topic."))
    }

    @Test
    fun `decision prompt is empty when nothing stands`() {
        assertEquals("", DecisionPrompt.build(emptyList()))
        val closed = DecisionEntity(id = "x", topicKey = "t", statement = "s", status = "SUPERSEDED", createdAt = 1, decidedAt = 1)
        assertEquals("", DecisionPrompt.build(listOf(closed)))
    }
}
