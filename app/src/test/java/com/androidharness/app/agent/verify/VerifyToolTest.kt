package com.androidharness.app.agent.verify

import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolRegistry
import com.androidharness.app.tools.ToolResult
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Detector + tool, end to end against a scripted shell. */
class VerifyToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Scripted shell: records commands, returns queued results. */
    private class FakeShell : Tool {
        val commands = mutableListOf<String>()
        val results = ArrayDeque<ToolResult>()
        override val name = "shell"
        override val description = "scripted shell"
        override val parametersSchema = buildJsonObject {}
        override val isReadOnly = false
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            commands += args["command"]?.jsonPrimitive?.contentOrNull ?: ""
            return results.removeFirstOrNull() ?: ToolResult(false, "no scripted result")
        }
    }

    private fun ctx() = ToolContext(FileFs(tmp.root))

    private suspend fun runVerify(
        vararg results: ToolResult,
        args: JsonObject = buildJsonObject {},
    ): Pair<ToolResult, FakeShell> {
        val shell = FakeShell().apply { results.forEach { this.results += it } }
        val registry = ToolRegistry(listOf(shell))
        val tool = VerifyTool { registry.get("shell") }
        return tool.execute(args, ctx()) to shell
    }

    @Test
    fun `gradle projects get a detected recipe and it persists`() {
        tmp.newFile("settings.gradle.kts")
        val (recipe, note) = RecipeDetector.loadOrDetect(tmp.root)
        assertEquals("gradle", recipe?.kind)
        assertEquals("./gradlew check", recipe?.phases?.values?.single())
        assertTrue(note.contains("detected"))
        assertTrue(tmp.root.resolve(".harness/verify.json").isFile)
        val (again, note2) = RecipeDetector.loadOrDetect(tmp.root)
        assertEquals(recipe, again)
        assertTrue(note2.contains("loaded"))
    }

    @Test
    fun `node projects pick their package manager`() {
        tmp.newFile("package.json").writeText("""{"scripts":{"test":"vitest"}}""")
        tmp.newFile("pnpm-lock.yaml")
        val (recipe, _) = RecipeDetector.loadOrDetect(tmp.root)
        assertEquals("pnpm test", recipe?.phases?.values?.single())
    }

    @Test
    fun `no recognizable shape yields no recipe`() {
        val (recipe, note) = RecipeDetector.loadOrDetect(tmp.root)
        assertNull(recipe)
        assertTrue(note.contains("no recipe"))
    }

    @Test
    fun `phases run through the shell and success reports ok`() = runBlocking {
        tmp.newFile("settings.gradle.kts")
        val (res, shell) = runVerify(ToolResult(true, "exit code: 0\nall checks passed"))
        assertTrue(res.ok)
        assertTrue(res.output.contains("[verify] PASSED"))
        assertEquals(listOf("./gradlew check"), shell.commands)
    }

    @Test
    fun `a failed phase fails the run and stops on failure`() = runBlocking {
        tmp.newFile("settings.gradle.kts")
        val (res, shell) = runVerify(ToolResult(false, "exit code: 1\ncompilation error"))
        assertFalse(res.ok)
        assertTrue(res.output.contains("[verify] FAILED"))
        assertEquals(1, shell.commands.size)
    }

    @Test
    fun `a saved custom recipe wins over detection`() = runBlocking {
        tmp.newFile("settings.gradle.kts")
        RecipeDetector.save(tmp.root, VerifyRecipe("probe", "custom", linkedMapOf("lint" to "custom-lint-cmd")))
        val (res, shell) = runVerify(ToolResult(true, "exit code: 0\nclean"))
        assertTrue(res.ok)
        assertEquals(listOf("custom-lint-cmd"), shell.commands)
        assertTrue(res.output.contains("[lint] PASSED"))
    }

    @Test
    fun `phase filter selects a subset`() = runBlocking {
        tmp.newFile("settings.gradle.kts")
        RecipeDetector.save(tmp.root, VerifyRecipe("probe", "custom", linkedMapOf("lint" to "cmd-lint", "test" to "cmd-test")))
        val (res, shell) = runVerify(
            ToolResult(true, "exit code: 0\nok"),
            args = buildJsonObject {
                put("phases", buildJsonArray { add(JsonPrimitive("test")) })
            },
        )
        assertTrue(res.ok)
        assertEquals(listOf("cmd-test"), shell.commands)
    }
}
