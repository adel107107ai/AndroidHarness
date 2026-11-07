package com.androidharness.app.agent.verify

import com.androidharness.app.tools.Schema
import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Runs the project's verification recipe phase by phase through the real
 * shell tool, so VerificationEvidence sees each phase as a plain shell check
 * (gradle/npm/pytest command + exit code) and keeps its freshness rules —
 * no separate evidence wiring needed.
 */
class VerifyTool(private val shell: () -> Tool?) : Tool {

    override val name = "verify_run"
    override val description =
        "Run the project's verification recipe (.harness/verify.json; detected from the project shape when missing) " +
            "phase by phase through the shell. Each phase is reported as pass/fail verification evidence."
    override val isReadOnly = false

    override val parametersSchema = Schema.obj(
        mapOf(
            "phases" to Schema.array(
                Schema.string("Only run these phase names; default: every phase in order."),
                "phase names",
            ),
            "stop_on_failure" to Schema.boolean("Stop at the first failed phase. Default true."),
        ),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val shellTool = shell() ?: return ToolResult(false, "verify_run needs the shell tool.")
        val root = ctx.workspace.shellRoot
            ?: return ToolResult(false, "verify_run needs a filesystem-backed workspace.")

        val (recipe, note) = RecipeDetector.loadOrDetect(root)
        if (recipe == null) {
            return ToolResult(
                false,
                "No verification recipe ($note). Create ${VerifyRecipe.FILE} with " +
                    "{\"name\": …, \"kind\": \"custom\", \"phases\": {\"<phase>\": \"<command>\"}}.",
            )
        }
        val selected = args["phases"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
        val stopOnFailure = args["stop_on_failure"]?.jsonPrimitive?.contentOrNull != "false"

        val phases = recipe.phases.filterKeys { selected == null || it in selected }
        if (phases.isEmpty()) {
            return ToolResult(false, "No such phase. Recipe phases: ${recipe.phases.keys}.")
        }

        val lines = mutableListOf("verify recipe: ${recipe.name} (${recipe.kind}, $note)")
        var failed = false
        for ((phase, command) in phases) {
            if (failed && stopOnFailure) {
                lines += "[$phase] SKIPPED"
                continue
            }
            val res = shellTool.execute(
                buildJsonObject {
                    put("command", command)
                    put("timeout_seconds", 600)
                },
                ctx,
            )
            failed = failed || !res.ok
            lines += "[$phase] ${if (res.ok) "PASSED" else "FAILED"}: $command\n${res.output.takeLast(1200).trim()}"
        }
        return ToolResult(!failed, lines.joinToString("\n\n"))
    }
}
