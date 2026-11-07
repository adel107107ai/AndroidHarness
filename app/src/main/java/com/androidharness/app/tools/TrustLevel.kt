package com.androidharness.app.tools

/**
 * Trust provenance for a tool-call turn. LOCAL_USER is the default: the user
 * typed the request or the agent derived it in-session. UNTRUSTED_EXTERNAL
 * marks turns whose trigger arrived from outside the user (webhook payloads,
 * remote messaging, automated triggers) — for those, only a small read-only
 * safe list may run, the same shape Hermes uses for its webhook-safe
 * toolset (_HERMES_WEBHOOK_SAFE_TOOLS).
 */
enum class TrustLevel { LOCAL_USER, UNTRUSTED_EXTERNAL }

object UntrustedGate {

    /** Tools that cannot mutate state or reach the system when the trigger is untrusted. */
    val SAFE_TOOLS = setOf(
        "read_file", "file_info", "list_dir", "search_files", "grep",
        "web_search", "web_fetch", "http_request",
        "ask_user", "recall", "memory_read", "memory_search",
    )

    /** Non-null reason when this tool may not run at this trust level. */
    fun denyReason(toolName: String, trust: TrustLevel): String? {
        if (trust != TrustLevel.UNTRUSTED_EXTERNAL) return null
        if (toolName in SAFE_TOOLS) return null
        return "Blocked: this turn was triggered by untrusted external content, so only the read-only/web safe list may run (requested: $toolName)."
    }
}
