package com.androidharness.app.tools.mcp

import com.androidharness.app.llm.ToolSchema
import com.androidharness.app.tools.Schema
import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolRegistry
import com.androidharness.app.tools.ToolResult
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * A localhost-only MCP (Model Context Protocol) server that serves this app's
 * tools to another agent — the Hermes hermes_tools_mcp_server pattern: the
 * phone becomes a device-tool for a desktop agent reached via adb forward.
 *
 * Security posture:
 *  - Binds to 127.0.0.1 only; no LAN interface is ever opened.
 *  - Every request must carry the bearer token persisted at [tokenFile]
 *    (app-private storage), so other local apps cannot drive the tools.
 *  - Only [allowedTools] are exposed; the default is a read-only safe list —
 *    shell and file writes stay off unless the caller widens the set, and a
 *    widened shell still runs under the normal sandbox policy (sandboxOff=false).
 */
class HarnessMcpServer(
    private val registry: () -> ToolRegistry,
    private val workspace: suspend () -> WorkspaceFs,
    private val tokenFile: File,
    val allowedTools: Set<String> = DEFAULT_SAFE_TOOLS,
) : Closeable {

    private val closed = AtomicBoolean(false)
    private var listener: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "MCP-server").apply { isDaemon = true } }
    val token: String by lazy { loadOrCreateToken() }
    val port: Int get() = listener?.localPort ?: 0

    fun start(port: Int = 0) {
        check(listener == null) { "MCP server already started." }
        closed.set(false)
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            // Loopback only: an InetAddress bind keeps every other interface closed.
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
        listener = socket
        pool.execute {
            while (!closed.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { handle(client) }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener?.close() }
        pool.shutdownNow()
        // A barrier, not a race (the FTP lesson): an in-flight response must
        // finish before callers observe the server as gone.
        runCatching { pool.awaitTermination(5, TimeUnit.SECONDS) }
    }

    private fun handle(client: Socket) {
        client.use { s ->
            s.soTimeout = 30_000
            val reader = s.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                write(s, 400, "")
                return
            }
            val headers = readHeaders(reader)
            val (code, body) = route(parts[0], parts[1], reader, headers)
            write(s, code, body)
        }
    }

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        return headers
    }

    private fun route(method: String, path: String, reader: BufferedReader, headers: Map<String, String>): Pair<Int, String> {
        if (headers["authorization"]?.substringAfter("Bearer ")?.trim() != token) return 401 to ""
        if (method != "POST" || path != "/mcp") return 405 to ""
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 1..1_000_000) return 400 to ""
        val body = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val request = runCatching { json.parseToJsonElement(String(body, 0, read)).jsonObject }
            .getOrElse { return 200 to rpcError(null, -32700, "Parse error") }
        val id = request["id"]
        val rpcMethod = request["method"]?.jsonPrimitive?.contentOrNull
            ?: return 200 to rpcError(id, -32600, "Invalid Request")
        // Notifications carry no id: acknowledge with an empty 202, no body.
        if (id == null || id is JsonNull) return 202 to ""
        return when (rpcMethod) {
            "initialize" -> 200 to rpcResult(
                id,
                buildJsonObject {
                    put("protocolVersion", "2024-11-05")
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") { put("name", "androidharness"); put("version", "1.0.0") }
                },
            )
            "ping" -> 200 to rpcResult(id, buildJsonObject {})
            "tools/list" -> 200 to rpcResult(id, toolsList())
            "tools/call" -> toolsCall(id, request)
            else -> 200 to rpcError(id, -32601, "Method not found: $rpcMethod")
        }
    }

    private fun toolsList(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            registry().schemas().filter { it.name in allowedTools }.forEach { schema ->
                add(
                    buildJsonObject {
                        put("name", schema.name)
                        put("description", schema.description)
                        put("inputSchema", schema.parametersJson)
                    },
                )
            }
        }
    }

    private fun toolsCall(id: JsonElement?, request: JsonObject): Pair<Int, String> {
        val params = request["params"] as? JsonObject
        val toolName = params?.get("name")?.jsonPrimitive?.contentOrNull
            ?: return 200 to rpcError(id, -32602, "Missing tool name")
        if (toolName !in allowedTools) {
            return 200 to rpcResult(id, callError("Tool '$toolName' is not exposed over MCP (not in the allow list)."))
        }
        val tool = registry().get(toolName)
            ?: return 200 to rpcResult(id, callError("Unknown tool: $toolName"))
        val args = params["arguments"] as? JsonObject ?: buildJsonObject {}
        val result: ToolResult = runCatching {
            runBlocking { tool.execute(args, ToolContext(workspace())) }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            return 200 to rpcResult(id, callError(e.message ?: "$toolName failed"))
        }
        return 200 to rpcResult(
            id,
            buildJsonObject {
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", result.output) })
                }
                put("isError", !result.ok)
            },
        )
    }

    private fun callError(message: String): JsonObject = buildJsonObject {
        putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", message) }) }
        put("isError", true)
    }

    private fun rpcResult(id: JsonElement?, result: JsonObject): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("result", result)
        }.toString()

    private fun rpcError(id: JsonElement?, code: Int, message: String): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            putJsonObject("error") { put("code", code); put("message", message) }
        }.toString()

    private fun write(socket: Socket, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        405 -> "Method Not Allowed"
        else -> "Error"
    }

    private fun loadOrCreateToken(): String {
        tokenFile.parentFile?.mkdirs()
        if (tokenFile.isFile) {
            val existing = tokenFile.readText().trim()
            if (existing.length >= 24) return existing
        }
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString { repeat(40) { append(chars[Random.nextInt(chars.length)]) } }
            .also { tokenFile.writeText(it) }
    }

    companion object {
        /** Read-only tools only; everything that writes, executes or installs stays off. */
        val DEFAULT_SAFE_TOOLS = setOf(
            "read_file", "file_info", "list_dir", "search_files", "grep",
            "read_logcat", "memory_read", "memory_search", "recall",
            "web_search", "web_fetch", "search_chats", "recent_chats", "read_chat_snippet",
            "skills_list", "skill_view",
        )
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Agent-facing control for the localhost MCP server: start (optional port),
 * stop, status. The token is only shown in status so the user can hand it to
 * the desktop side; the server never leaves loopback regardless.
 */
class McpServerManageTool(private val server: HarnessMcpServer) : Tool {

    override val name = "mcp_server"
    override val description =
        "Manage the localhost MCP server that exposes this app's read-only tools to another agent " +
            "(a desktop agent reaches it via adb forward). Actions: start (optional port arg), stop, status."
    override val isReadOnly = false

    override val parametersSchema = Schema.obj(
        mapOf(
            "action" to Schema.string("start | stop | status"),
            "port" to Schema.integer("Port for start (default: ephemeral)."),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult(false, "Missing required argument: action")
        return when (action) {
            "start" -> {
                val port = args["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                runCatching { server.start(port) }.fold(
                    onSuccess = {
                        ToolResult(true, "MCP server on 127.0.0.1:${server.port} (read-only safe list; token via mcp_server status).")
                    },
                    onFailure = { ToolResult(false, "Could not start: ${it.message}") },
                )
            }
            "stop" -> {
                server.close()
                ToolResult(true, "MCP server stopped.")
            }
            "status" -> ToolResult(
                true,
                if (server.port == 0) "MCP server: stopped"
                else "MCP server: running on 127.0.0.1:${server.port}\n" +
                    "token: ${server.token}\n" +
                    "exposed tools: " + server.allowedTools.sorted().joinToString(", "),
            )
            else -> ToolResult(false, "Unknown action: $action")
        }
    }
}
