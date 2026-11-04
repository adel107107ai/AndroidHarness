package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The untrusted-content gate: prompt-injection defense at the tool boundary. */
class UntrustedGateTest {

    @Test
    fun `untrusted turns block mutating and system tools`() {
        for (tool in listOf(
            "shell", "shell_background", "write_file", "edit_file", "multi_edit",
            "apply_patch", "delete_file", "move_file", "pkg_install",
            "git_push", "git_commit", "git_pull", "skill_manage",
        )) {
            assertNotNull(tool, UntrustedGate.denyReason(tool, TrustLevel.UNTRUSTED_EXTERNAL))
        }
    }

    @Test
    fun `untrusted turns keep the read-only safe list open`() {
        for (tool in listOf(
            "read_file", "file_info", "list_dir", "search_files", "grep",
            "web_search", "web_fetch", "ask_user", "recall", "memory_read",
        )) {
            assertNull(tool, UntrustedGate.denyReason(tool, TrustLevel.UNTRUSTED_EXTERNAL))
        }
    }

    @Test
    fun `local user turns are never gated`() {
        for (tool in listOf("shell", "write_file", "delete_file", "pkg_install", "git_push")) {
            assertNull(UntrustedGate.denyReason(tool, TrustLevel.LOCAL_USER))
        }
    }

    @Test
    fun `the reason names the requested tool`() {
        val reason = UntrustedGate.denyReason("shell", TrustLevel.UNTRUSTED_EXTERNAL)
        assertNotNull(reason)
        assertEquals(
            "Blocked: this turn was triggered by untrusted external content, so only the read-only/web safe list may run (requested: shell).",
            reason,
        )
    }
}
