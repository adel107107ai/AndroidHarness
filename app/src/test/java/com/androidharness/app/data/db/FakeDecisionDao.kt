package com.androidharness.app.data.db

/** In-memory DecisionDao stand-in for JVM tests; implements the same interface as the Room impl. */
class FakeDecisionDao : DecisionDao {
    val rows = LinkedHashMap<String, DecisionEntity>()

    override suspend fun insertDecision(row: DecisionEntity) {
        require(row.id !in rows) { "duplicate id ${row.id}" }
        rows[row.id] = row
    }

    override suspend fun updateDecision(row: DecisionEntity) {
        require(row.id in rows) { "unknown id ${row.id}" }
        rows[row.id] = row
    }

    override suspend fun decision(id: String): DecisionEntity? = rows[id]

    override suspend fun activeDecision(topicKey: String): DecisionEntity? =
        rows.values.filter { it.topicKey == topicKey && it.status == "ACTIVE" }
            .maxByOrNull { it.decidedAt }

    override suspend fun history(topicKey: String): List<DecisionEntity> =
        rows.values.filter { it.topicKey == topicKey }.sortedBy { it.decidedAt }
}
