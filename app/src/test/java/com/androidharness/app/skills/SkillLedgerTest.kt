package com.androidharness.app.skills

import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Skill usage ledger: accumulate, survive restart, tolerate corruption, stay bounded. */
class SkillLedgerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ledger() = SkillLedger(FileFs(tmp.root))

    @Test
    fun `recording accumulates count and last used`() = runBlocking {
        val l = ledger()
        l.recordUse("android-build", now = 1_000)
        l.recordUse("android-build", now = 2_000)
        l.recordUse("git", now = 1_500)
        assertEquals(2, l.usage("android-build")?.count)
        assertEquals(2_000L, l.usage("android-build")?.lastUsedAt)
        assertEquals(1, l.usage("git")?.count)
    }

    @Test
    fun `stale finds never used and old skills`() = runBlocking {
        val now = 100L * 86_400_000
        val l = ledger()
        l.recordUse("fresh", now = now)
        l.recordUse("old", now = now - 40L * 86_400_000)
        assertEquals(listOf("old", "never"), l.stale(listOf("fresh", "old", "never"), days = 30, now = now))
    }

    @Test
    fun `state survives a restart because it is file backed`() = runBlocking {
        ledger().recordUse("android-build", now = 5_000)
        assertEquals(1, ledger().usage("android-build")?.count)
        assertTrue(tmp.root.resolve(".harness/skill-usage.json").isFile)
    }

    @Test
    fun `a corrupt state file is tolerated and rewritten`() = runBlocking {
        tmp.newFolder(".harness")
        tmp.root.resolve(".harness/skill-usage.json").writeText("not json{")
        val l = ledger()
        l.recordUse("git", now = 1)
        assertEquals(1, l.usage("git")?.count)
    }

    @Test
    fun `the ledger stays bounded to the newest entries`() = runBlocking {
        val l = ledger()
        repeat(150) { l.recordUse("skill-$it", now = it.toLong()) }
        assertNull(l.usage("skill-0"))
        assertNull(l.usage("skill-49"))
        assertNotNull(l.usage("skill-50"))
        assertNotNull(l.usage("skill-149"))
    }
}
