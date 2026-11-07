package com.androidharness.app.data

import com.androidharness.app.data.db.DecisionDao
import com.androidharness.app.data.db.DecisionEntity
import java.util.UUID

/** Valid sections, matching the memory sections. */
object DecisionSections {
    const val CHAT = "CHAT"
    const val CODE = "CODE"
    const val SHARED = "SHARED"
    val ALL = setOf(CHAT, CODE, SHARED)
}

/**
 * The decision ledger on top of [DecisionDao]. Rules, in order of strength:
 *  1. Nothing is ever deleted and nothing is ever rewritten in place — a new
 *     decision supersedes the old one, which keeps its rationale on disk.
 *  2. A topic has at most one ACTIVE decision: [record] closes the previous
 *     one in the same transaction that inserts its successor, and the chain
 *     link (supersedesId) is derived automatically.
 *  3. Closing without a replacement ([supersede]) keeps the row and records
 *     the reason in closedReason.
 */
class DecisionLedger(private val dao: DecisionDao) {

    suspend fun record(
        projectId: String? = null,
        section: String = DecisionSections.SHARED,
        topicKey: String,
        statement: String,
        rationale: String = "",
        evidenceRef: String? = null,
    ): DecisionEntity {
        require(statement.isNotBlank()) { "A decision needs a statement." }
        val topic = topicKey.trim()
        require(topic.isNotEmpty()) { "A decision needs a topic key." }
        check(section in DecisionSections.ALL) { "Unknown section: $section" }
        val now = System.currentTimeMillis()
        val row = DecisionEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            section = section,
            topicKey = topic,
            statement = statement.trim(),
            rationale = rationale.trim(),
            status = "ACTIVE",
            supersedesId = null, // set by the transaction below
            evidenceRef = evidenceRef,
            createdAt = now,
            decidedAt = now,
        )
        // The topic's current ACTIVE decision (if any) is the one being replaced.
        val prior = dao.activeDecision(topic)?.id
        dao.recordAndSupersede(prior, row.copy(supersedesId = prior), now)
        return row.copy(supersedesId = prior)
    }

    /** The one ACTIVE decision for a topic, or null when none stands. */
    suspend fun active(topicKey: String): DecisionEntity? = dao.activeDecision(topicKey.trim())

    /** Close a decision without a replacement (reverted / withdrawn). */
    suspend fun supersede(id: String, reason: String): DecisionEntity {
        require(reason.isNotBlank()) { "Closing a decision needs a reason." }
        val row = requireNotNull(dao.decision(id)) { "Decision $id not found." }
        check(row.status == "ACTIVE") {
            "Decision ${row.id} is ${row.status}; only ACTIVE decisions can be closed."
        }
        val closed = row.copy(status = "SUPERSEDED", closedReason = reason.trim())
        dao.updateDecision(closed)
        return closed
    }

    /** The full chain for a topic, oldest first — never truncated, nothing deleted. */
    suspend fun history(topicKey: String): List<DecisionEntity> = dao.history(topicKey.trim())
}

/**
 * Keeps the ledger in step with the memories table: every save or edit of a
 * DECISION memory appends an immutable chain entry (topic `mem:<id>`), and
 * deletion or consolidation closes the active one with a reason. Memory rows
 * hold the current truth; the ledger holds the history and the rationale.
 */
internal class DecisionSync(private val ledger: DecisionLedger) {

    suspend fun changed(
        memoryId: String,
        projectId: String?,
        section: MemorySection,
        statement: String,
        rationale: String = "",
    ) {
        ledger.record(
            projectId = projectId,
            section = section.name,
            topicKey = "mem:$memoryId",
            statement = statement,
            rationale = rationale,
        )
    }

    /** Closes the active chain entry; a no-op when nothing is active. */
    suspend fun removed(memoryId: String, reason: String) {
        val active = ledger.active("mem:$memoryId") ?: return
        ledger.supersede(active.id, reason = reason)
    }
}

/**
 * Formats ACTIVE decisions for the context prompt — the "review previous
 * decisions" half of the loop. Pure and capped, like [MemoryPrompt].
 */
internal object DecisionPrompt {
    const val MAX_CHARS = 2_500

    fun build(decisions: List<DecisionEntity>, maxChars: Int = MAX_CHARS): String {
        val text = StringBuilder()
        val sorted = decisions.filter { it.status == "ACTIVE" }.sortedByDescending { it.decidedAt }
        var omitted = 0
        for (d in sorted) {
            val rationale = if (d.rationale.isBlank()) "" else " — ${d.rationale}"
            val line = "- [${d.topicKey}] ${d.statement}$rationale\n"
            if (text.length + line.length > maxChars) { omitted++; continue }
            text.append(line)
        }
        if (omitted > 0) text.append("- $omitted more standing decisions: recall them by topic.\n")
        return text.toString().trim()
    }
}
