package com.androidharness.app.data

import com.androidharness.app.data.db.FakeDecisionDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The memories ↔ ledger mapping: mem:<id> topics, supersede-on-edit, close-on-remove. */
class DecisionSyncTest {

    @Test
    fun `saving a decision memory opens an active chain entry`() = runBlocking {
        val ledger = DecisionLedger(FakeDecisionDao())
        val sync = DecisionSync(ledger)
        sync.changed("m1", projectId = "p1", section = MemorySection.CODE, statement = "use release key")

        val active = ledger.active("mem:m1")
        assertEquals(1, ledger.history("mem:m1").size)
        assertEquals("ACTIVE", active?.status)
        assertEquals("use release key", active?.statement)
        assertEquals("CODE", active?.section)
        assertEquals("p1", active?.projectId)
    }

    @Test
    fun `editing the same decision memory supersedes and keeps the chain`() = runBlocking {
        val ledger = DecisionLedger(FakeDecisionDao())
        val sync = DecisionSync(ledger)
        sync.changed("m1", null, MemorySection.SHARED, "sign with debug key")
        sync.changed("m1", null, MemorySection.SHARED, "sign with release key")

        val history = ledger.history("mem:m1")
        assertEquals(2, history.size) // nothing deleted
        assertEquals("SUPERSEDED", history[0].status)
        assertEquals("ACTIVE", history[1].status)
        assertEquals(history[0].id, history[1].supersedesId)
        assertEquals("sign with release key", ledger.active("mem:m1")?.statement)
    }

    @Test
    fun `removing closes the active entry with the reason`() = runBlocking {
        val ledger = DecisionLedger(FakeDecisionDao())
        val sync = DecisionSync(ledger)
        sync.changed("m2", null, MemorySection.CHAT, "export via ftp")
        sync.removed("m2", "memory deleted")

        assertNull(ledger.active("mem:m2"))
        val closed = ledger.history("mem:m2").single()
        assertEquals("SUPERSEDED", closed.status)
        assertEquals("memory deleted", closed.closedReason)
    }

    @Test
    fun `removing without any entry is a no-op`() = runBlocking {
        val ledger = DecisionLedger(FakeDecisionDao())
        val sync = DecisionSync(ledger)
        sync.removed("ghost", "memory deleted")
        assertEquals(0, ledger.history("mem:ghost").size)
    }
}
