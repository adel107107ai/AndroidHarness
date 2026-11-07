package com.androidharness.app.agent.verify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File

@kotlinx.serialization.Serializable
data class VerifyRecipe(
    val name: String,
    /** gradle | node | python | custom */
    val kind: String,
    /** Ordered phase name → command. */
    val phases: Map<String, String>,
) {
    companion object {
        const val FILE = ".harness/verify.json"
    }
}

/**
 * Detects the verification recipe from the project shape (the cues Hermes'
 * recipes use: gradlew, package.json, pyproject) and persists it under
 * .harness/verify.json. A saved recipe always wins over detection, so a
 * project can tailor its phases — including device-specific flags.
 */
object RecipeDetector {

    private val json = Json { ignoreUnknownKeys = true }

    fun loadOrDetect(root: File): Pair<VerifyRecipe?, String> {
        val file = root.resolve(VerifyRecipe.FILE)
        if (file.isFile) {
            return runCatching { json.decodeFromString<VerifyRecipe>(file.readText()) }.fold(
                onSuccess = { recipe -> recipe to "loaded ${VerifyRecipe.FILE}" },
                onFailure = { null to "${VerifyRecipe.FILE} is invalid (${it.message}); ignoring it" },
            )
        }
        val detected = detect(root)
        if (detected != null) save(root, detected)
        return detected to (detected?.let { "detected ${it.kind} project" } ?: "no recipe detected")
    }

    fun save(root: File, recipe: VerifyRecipe) {
        val file = root.resolve(VerifyRecipe.FILE)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(VerifyRecipe.serializer(), recipe))
    }

    fun detect(root: File): VerifyRecipe? = when {
        root.resolve("gradlew").isFile || root.resolve("settings.gradle.kts").isFile ||
            root.resolve("settings.gradle").isFile || root.resolve("build.gradle.kts").isFile ->
            VerifyRecipe(root.name, "gradle", linkedMapOf("verify" to "./gradlew check"))

        root.resolve("package.json").isFile -> nodeRecipe(root)

        root.resolve("pyproject.toml").isFile || root.resolve("requirements.txt").isFile ||
            root.resolve("setup.py").isFile ->
            VerifyRecipe(root.name, "python", linkedMapOf("verify" to "python3 -m pytest"))

        else -> null
    }

    private fun nodeRecipe(root: File): VerifyRecipe? {
        val pkg = runCatching {
            Json.parseToJsonElement(root.resolve("package.json").readText()).jsonObject
        }.getOrNull() ?: return null
        val scripts = pkg["scripts"]?.jsonObject ?: return null
        if (!scripts.containsKey("test")) return null
        val runner = when {
            root.resolve("pnpm-lock.yaml").isFile -> "pnpm"
            root.resolve("yarn.lock").isFile -> "yarn"
            else -> "npm"
        }
        return VerifyRecipe(root.name, "node", linkedMapOf("verify" to "$runner test"))
    }
}
