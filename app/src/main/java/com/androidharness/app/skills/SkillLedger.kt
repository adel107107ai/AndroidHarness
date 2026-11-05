package com.androidharness.app.skills

import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.serialization.json.Json

/**
 * Usage tracking for skills (Hermes' skill_ledger/skill_usage): which skills
 * the agent actually loads, how often, and when last. File-backed in the
 * workspace (.harness/skill-usage.json) so it survives restarts and stays
 * per-workspace; bounded so long histories cannot grow without limit.
 */
class SkillLedger(private val workspace: WorkspaceFs) {

    @kotlinx.serialization.Serializable
    data class Entry(val name: String, val count: Int = 0, val lastUsedAt: Long = 0)

    @kotlinx.serialization.Serializable
    private data class State(val entries: List<Entry> = emptyList())

    suspend fun recordUse(name: String, now: Long = System.currentTimeMillis()) {
        val current = read().entries
        val prior = current.firstOrNull { it.name == name }
        val next = (current.filterNot { it.name == name } +
            Entry(name, (prior?.count ?: 0) + 1, now))
            .sortedByDescending { it.lastUsedAt }
            .take(MAX_ENTRIES)
        write(State(next))
    }

    suspend fun usage(name: String): Entry? = read().entries.firstOrNull { it.name == name }

    /** Skills never loaded or not used within [days] — candidates for review or removal. */
    suspend fun stale(names: List<String>, days: Int = 30, now: Long = System.currentTimeMillis()): List<String> {
        val cutoff = now - days * 86_400_000L
        val state = read()
        return names.filter { name ->
            (state.entries.firstOrNull { it.name == name }?.lastUsedAt ?: 0) < cutoff
        }
    }

    private fun read(): State = runCatching {
        workspace.resolve(FILE).takeIf { it.exists && it.isFile }?.readText()
    }.getOrNull()?.let { text ->
        runCatching { json.decodeFromString(State.serializer(), text) }.getOrNull()
    } ?: State()

    private suspend fun write(state: State) {
        workspace.resolve(FILE).writeText(json.encodeToString(State.serializer(), state))
    }

    companion object {
        const val FILE = ".harness/skill-usage.json"
        const val MAX_ENTRIES = 100
        private val json = Json { ignoreUnknownKeys = true }
    }
}
