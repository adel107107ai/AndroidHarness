package com.androidharness.app.data

import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.data.db.MemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID
import androidx.room.withTransaction

enum class MemoryScope {
    GLOBAL, PROJECT;

    companion object {
        fun fromStored(value: String?): MemoryScope = entries.firstOrNull { it.name == value } ?: GLOBAL
    }
}

enum class MemoryCategory {
    PROFILE, PREFERENCE, PROJECT, DECISION, FACT;

    companion object {
        fun fromStored(value: String?): MemoryCategory =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: FACT
    }
}

internal object MemoryText {
    const val MAX_ENTRY_CHARS = 600

    fun normalize(raw: String): String? {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        return text.take(MAX_ENTRY_CHARS)
    }
}

/**
 * Builds the small memory block injected into a request. Individual entries are
 * kept for accuracy; only the highest-value ones enter the prompt, so a long
 * memory list cannot quietly turn into a second system prompt.
 */
internal object MemoryPrompt {
    const val MAX_CHARS = 5_000

    data class Block(val text: String, val usedIds: List<String>, val omitted: Int, val supplied: List<MemoryEntity> = emptyList())

    fun build(memories: List<MemoryEntity>, maxChars: Int = MAX_CHARS, query: String = ""): Block {
        val sorted = memories.filter { it.enabled && it.consolidatedInto == null }.sortedWith(
            compareByDescending<MemoryEntity> { it.pinned }
                .thenByDescending { MemoryMatch.relevance(it.text, query) }
                .thenByDescending { it.category in setOf("PROFILE", "PREFERENCE") }
                .thenByDescending { it.scope == MemoryScope.PROJECT.name }
                .thenByDescending { it.updatedAt }
                .thenBy { it.id },
        )
        val text = StringBuilder()
        val used = mutableListOf<String>()
        val supplied = mutableListOf<MemoryEntity>()
        var omitted = 0
        for (memory in sorted) {
            val prefix = "- [id:${memory.id} ${memory.category.lowercase()}${if (memory.isSummary) "; digest, recall(memory_id) for full text" else ""}] "
            val content = if (memory.isSummary) {
                // Select facts from a large digest instead of dropping the whole digest.
                memory.text.lines().filter { it.isNotBlank() && !it.endsWith(":") }.sortedByDescending {
                    MemoryMatch.relevance(it, query)
                }.fold("") { result, line ->
                    if (text.length + prefix.length + result.length + line.length + 2 <= maxChars)
                        result + line + "\n" else result
                }.trim()
            } else memory.text
            val line = prefix + content + "\n"
            if (content.isBlank() || text.length + line.length > maxChars) { omitted++; continue }
            text.append(line)
            used += memory.id
            supplied += memory.copy(text = content)
        }
        if (omitted > 0) {
            val note = "- $omitted more saved memories: recall(query).\n"
            if (text.length + note.length <= maxChars) text.append(note)
        }
        return Block(text.toString().trim(), used, omitted, supplied)
    }

    fun approxTokens(chars: Int): Int = (chars / 4).coerceAtLeast(1)
}

/**
 * App-owned Chat, Code and legacy Shared memories. It is independent of providers and
 * of the workspace files used for project notes.
 */
class MemoryRepository(private val db: AppDatabase, private val settings: SettingsRepository) {
    private val dao get() = db.dao()

    /** DECISION-category memories keep an immutable supersede chain in the decisions table. */
    private val decisionSync = DecisionSync(DecisionLedger(db.decisionDao()))

    val memories: Flow<List<MemoryEntity>> get() = dao.memoriesFlow()

    suspend fun sectionForSession(sessionId: String?): MemorySection =
        if (sessionId != null && ConversationConfig.decode(dao.session(sessionId)?.conversationJson).isChat) MemorySection.CHAT else MemorySection.CODE

    suspend fun isEnabled(sessionId: String? = null): Boolean =
        (sessionId == null || ConversationConfig.decode(dao.session(sessionId)?.conversationJson).personalizationEnabled) &&
            MemoryAccess.enabled(settings.settings.first(), sectionForSession(sessionId))

    suspend fun projectIdForSession(sessionId: String?): String? = sessionId?.takeIf { it.isNotBlank() }?.let { dao.projectIdForSession(it) }

    /** Always supplies own/shared memories. Other-section lookup requires an explicit recall request. */
    suspend fun promptBlock(sessionId: String?, query: String = "", onUsed: (List<MemoryEntity>) -> Unit = {}): String? {
        onUsed(emptyList())
        if (!isEnabled(sessionId)) return null
        val section = sectionForSession(sessionId)
        val available = dao.activeMemories(projectIdForSession(sessionId)).filter { MemoryAccess.belongsTo(it, section) }
        val block = MemoryPrompt.build(available, query = query)
        if (block.text.isBlank()) return null
        onUsed(block.supplied)
        if (block.usedIds.isNotEmpty()) dao.markMemoriesUsed(block.usedIds, System.currentTimeMillis())
        return block.text
    }

    /**
     * ACTIVE standing decisions for this section/project — the block the model
     * reviews before taking a contrary action. Null when none stand or memory
     * is off for the section.
     */
    suspend fun decisionPromptBlock(sessionId: String?): String? {
        if (!isEnabled(sessionId)) return null
        val section = sectionForSession(sessionId)
        val project = projectIdForSession(sessionId)
        val rows = db.decisionDao().activeDecisions()
            .filter { it.section == section.name || it.section == DecisionSections.SHARED }
            .filter { it.projectId == null || (project != null && it.projectId == project) }
        return DecisionPrompt.build(rows).ifBlank { null }
    }

    enum class Action { SAVED, UPDATED, UNCHANGED }
    data class WriteResult(val memory: MemoryEntity, val action: Action)

    suspend fun remember(text: String, category: MemoryCategory, requestedScope: MemoryScope, sessionId: String?): MemoryEntity? =
        if (text.isBlank()) null else rememberResult(text, category, requestedScope, sessionId).memory

    suspend fun rememberResult(
        text: String, category: MemoryCategory?, requestedScope: MemoryScope, sessionId: String?,
        memoryId: String? = null, previousText: String? = null, allowSimilar: Boolean = false,
        rationale: String = "",
    ): WriteResult {
        check(isEnabled(sessionId)) { "Memory is off for this section." }
        val section = sectionForSession(sessionId)
        val project = projectIdForSession(sessionId)
        return db.withTransaction {
            val available = dao.allMemories().filter { it.consolidatedInto == null && MemoryAccess.belongsTo(it, section) &&
                (it.scope == MemoryScope.GLOBAL.name || it.projectId == project && project != null) }
            if (memoryId != null) {
                val current = available.firstOrNull { it.id == memoryId } ?: error("Memory is not available in this section/project. Recall it first.")
                val clean = checkedText(text, current.isSummary)
                check(previousText != null && previousText == current.text) { "Memory changed or previous_text is missing. Recall it again before updating." }
                check(current.enabled) { "This memory is disabled. Enable it in Settings first." }
                val conflict = available.firstOrNull { it.id != current.id && it.scope == current.scope && it.projectId == current.projectId &&
                    MemoryMatch.key(it.text) == MemoryMatch.key(clean) }
                check(conflict == null) { "The replacement already exists as memory ${conflict?.id}." }
                if (current.text == clean && current.category == (category?.name ?: current.category)) return@withTransaction WriteResult(current, Action.UNCHANGED)
                val updated = current.copy(text = clean, category = category?.name ?: current.category, updatedAt = System.currentTimeMillis())
                dao.upsertMemory(updated)
                if (updated.category == MemoryCategory.DECISION.name) decisionSync.changed(current.id, current.projectId, section, clean, rationale)
                WriteResult(updated, Action.UPDATED)
            } else {
                val clean = checkedText(text)
                val scope = if (requestedScope == MemoryScope.PROJECT && project != null) MemoryScope.PROJECT else MemoryScope.GLOBAL
                val projectId = project.takeIf { scope == MemoryScope.PROJECT }
                val candidates = available.filter { !it.isSummary && (scope == MemoryScope.PROJECT || it.scope == MemoryScope.GLOBAL.name) }
                val duplicate = candidates.firstOrNull { MemoryMatch.key(it.text) == MemoryMatch.key(clean) }
                if (duplicate != null) {
                    check(duplicate.enabled) { "This fact already exists as a disabled memory. Enable it in Settings before using it again." }
                    return@withTransaction WriteResult(duplicate, Action.UNCHANGED)
                }
                if (!allowSimilar) {
                    val near = candidates.firstOrNull { MemoryMatch.key(it.text) != MemoryMatch.key(clean) && MemoryMatch.similar(it.text, clean) }
                    check(near == null) { "Similar memory [id:${near?.id}]: ${near?.text}. Recall it and update by memory_id, or set allow_similar only if these are distinct facts." }
                }
                save(clean, category ?: MemoryCategory.FACT, scope, projectId, sessionId, section, rationale)
            }
        }
    }

    suspend fun add(text: String, category: MemoryCategory, requestedScope: MemoryScope, projectId: String?, section: MemorySection = MemorySection.SHARED): MemoryEntity? =
        if (text.isBlank()) null else db.withTransaction { save(checkedText(text), category, requestedScope, projectId, null, section).memory }

    private suspend fun save(text: String, category: MemoryCategory, requestedScope: MemoryScope, projectId: String?, sessionId: String?, section: MemorySection, rationale: String = ""): WriteResult {
        val scope = if (requestedScope == MemoryScope.PROJECT && projectId != null) MemoryScope.PROJECT else MemoryScope.GLOBAL
        val actualProject = projectId.takeIf { scope == MemoryScope.PROJECT }
        val existing = dao.allMemories().firstOrNull { it.consolidatedInto == null && !it.isSummary && it.scope == scope.name &&
            it.projectId == actualProject && MemoryAccess.belongsTo(it, section) && MemoryMatch.key(it.text) == MemoryMatch.key(text) }
        if (existing != null) {
            check(existing.enabled) { "This fact already exists as a disabled memory. Enable it in Settings before using it again." }
            return WriteResult(existing, Action.UNCHANGED)
        }
        val now = System.currentTimeMillis()
        val memory = MemoryEntity(UUID.randomUUID().toString(), text, category.name, scope.name, actualProject,
            sessionId, createdAt = now, updatedAt = now, section = section.name)
        dao.upsertMemory(memory)
        if (category == MemoryCategory.DECISION) decisionSync.changed(memory.id, actualProject, section, text, rationale)
        trim()
        return WriteResult(memory, Action.SAVED)
    }

    private fun checkedText(raw: String, summary: Boolean = false): String {
        val text = if (summary) raw.trim() else raw.replace(Regex("\\s+"), " ").trim()
        require(text.isNotBlank()) { "Enter a memory." }
        require(text.length <= if (summary) MemoryConsolidation.MAX_CHARS else MemoryText.MAX_ENTRY_CHARS) { "Memory is too long. Shorten it before saving." }
        return text
    }

    suspend fun update(memory: MemoryEntity, text: String, category: MemoryCategory, scope: MemoryScope, projectId: String?, section: MemorySection = MemorySection.fromStored(memory.section)) = db.withTransaction {
        val current = dao.memory(memory.id) ?: error("This memory was deleted.")
        check(current.updatedAt == memory.updatedAt && current.text == memory.text && current.consolidatedInto == null) { "This memory changed. Reopen it before saving." }
        val clean = checkedText(text, current.isSummary)
        val actualProject = projectId.takeIf { scope == MemoryScope.PROJECT }
        check(dao.allMemories().none { it.id != memory.id && it.consolidatedInto == null && it.section == section.name &&
            it.scope == scope.name && it.projectId == actualProject && MemoryMatch.key(it.text) == MemoryMatch.key(clean) }) { "This memory already exists." }
        dao.upsertMemory(current.copy(text = clean, category = category.name, scope = if (actualProject != null) "PROJECT" else "GLOBAL",
            projectId = actualProject, section = section.name, updatedAt = System.currentTimeMillis()))
        when {
            category == MemoryCategory.DECISION -> decisionSync.changed(current.id, actualProject, section, clean)
            current.category == MemoryCategory.DECISION.name -> decisionSync.removed(current.id, "category changed")
        }
    }

    suspend fun consolidate(plan: MemoryConsolidation, reviewedText: String): MemoryEntity = db.withTransaction {
        val current = plan.originals.map { original ->
            val latest = dao.memory(original.id)
            check(latest != null && latest.copy(lastUsedAt = original.lastUsedAt, useCount = original.useCount) == original && latest.consolidatedInto == null && latest.enabled) { "Memories changed. Review the consolidation again." }
            latest
        }
        check(current.size >= 2 && current.map { Triple(it.section, it.scope, it.projectId) }.distinct().size == 1) { "Choose memories from one section and scope." }
        val text = checkedText(reviewedText, summary = true)
        val now = System.currentTimeMillis()
        val summary = MemoryEntity(UUID.randomUUID().toString(), text, scope = plan.scope.name, projectId = plan.projectId,
            createdAt = now, updatedAt = now, pinned = current.any { it.pinned }, section = plan.section.name, isSummary = true)
        dao.upsertMemory(summary)
        dao.consolidateMemories(current.map { it.id }, summary.id)
        current.filter { it.category == MemoryCategory.DECISION.name }
            .forEach { decisionSync.removed(it.id, "consolidated") }
        summary
    }

    suspend fun restoreOriginals(summaryId: String) = db.withTransaction {
        val summary = dao.memory(summaryId)
        check(summary?.isSummary == true) { "This consolidation is no longer available." }
        val originals = dao.allMemories().filter { it.consolidatedInto == summaryId }
        check(originals.isNotEmpty()) { "The original entries have already been restored." }
        val initialText = MemoryConsolidation.plans(originals.map { it.copy(consolidatedInto = null) }).singleOrNull()?.text
        dao.restoreConsolidatedMemories(summaryId)
        originals.filter { it.category == MemoryCategory.DECISION.name }
            .forEach { decisionSync.changed(it.id, it.projectId, MemorySection.fromStored(it.section), it.text) }
        if (summary.text == initialText) dao.deleteMemory(summaryId)
        else dao.upsertMemory(summary.copy(enabled = false)) // Keep later edits available for review.
    }

    suspend fun remove(id: String) = db.withTransaction {
        val current = dao.memory(id)
        check(current?.consolidatedInto == null) { "Restore the original entries before deleting one of them." }
        val children = if (current?.isSummary == true) dao.allMemories().filter { it.consolidatedInto == id } else emptyList()
        (children + listOfNotNull(current))
            .filter { it.category == MemoryCategory.DECISION.name }
            .forEach { decisionSync.removed(it.id, "memory deleted") }
        children.forEach { dao.deleteMemory(it.id) }
        dao.deleteMemory(id)
    }

    suspend fun clearAll() = dao.deleteAllMemories()

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setMemoryEnabled(id, enabled, System.currentTimeMillis())
        if (enabled) trim()
    }

    suspend fun setPinned(id: String, pinned: Boolean) = dao.setMemoryPinned(id, pinned, System.currentTimeMillis())

    /**
     * Adds memories from a backup without touching entries already present.
     * [remapProject] lets a chat restore point a project memory at its mapped
     * workspace, and lets a settings restore drop a project that no longer
     * exists (the entry is kept as global).
     */
    suspend fun importMissing(
        incoming: List<BackupMemory>,
        remapProject: (String?) -> String? = { it },
    ): Int {
        val plan = MemoryBackupMerge.plan(dao.allMemories(), incoming, remapProject)
        if (plan.isEmpty()) return 0
        val now = System.currentTimeMillis()
        plan.forEach { dao.upsertMemory(MemoryBackupMerge.entity(it, now)) }
        return plan.size
    }

    suspend fun recallById(id: String, sessionId: String?, requestedSection: MemorySection? = null): MemoryEntity {
        val from = sectionForSession(sessionId)
        val target = requestedSection ?: from
        check(MemoryAccess.canRecall(settings.settings.first(), from, target)) { "Memory lookup for that section is off in Settings > Memory." }
        return dao.activeMemories(projectIdForSession(sessionId)).firstOrNull { it.id == id && MemoryAccess.belongsTo(it, target) }
            ?: error("This memory is not available in this section/project. Recall current memories first.")
    }

    /** Explicit cross-section lookup is possible only when its switch is enabled. */
    suspend fun search(query: String, sessionId: String?, requestedSection: MemorySection? = null): List<MemoryEntity> {
        val from = sectionForSession(sessionId)
        val target = requestedSection ?: from
        val prefs = settings.settings.first()
        check(MemoryAccess.canRecall(prefs, from, target)) { "Memory lookup for that section is off in Settings > Memory." }
        val active = dao.activeMemories(projectIdForSession(sessionId)).filter { MemoryAccess.belongsTo(it, target) }
        return active.filter { query.isBlank() || MemoryMatch.relevance(it.text + " " + it.category, query) > 0 }
            .sortedWith(compareByDescending<MemoryEntity> { MemoryMatch.relevance(it.text, query) }
                .thenByDescending { it.pinned }.thenByDescending { it.updatedAt }).take(if (query.isBlank()) 20 else 40)
    }

    /** Keep the store bounded without deleting anything: oldest unpinned entries are disabled. */
    private suspend fun trim() {
        val enabled = dao.enabledMemories()
        if (enabled.size <= MAX_ENABLED) return
        val now = System.currentTimeMillis()
        enabled.filterNot { it.pinned }
            .sortedWith(compareBy<MemoryEntity> { it.lastUsedAt }.thenBy { it.updatedAt })
            .take(enabled.size - MAX_ENABLED)
            .forEach { dao.setMemoryEnabled(it.id, false, now) }
    }

    companion object {
        const val MAX_ENABLED = 400
        val toolNames = setOf("remember", "recall")
    }
}
