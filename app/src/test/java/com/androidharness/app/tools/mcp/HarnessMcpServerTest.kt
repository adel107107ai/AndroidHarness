package com.androidharness.app.tools.mcp

import com.androidharness.app.tools.Schema
import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolRegistry
import com.androidharness.app.tools.ToolResult
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.Socket

/** The MCP server end to end over a raw socket: protocol, security, tool dispatch. */
class HarnessMcpServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeTool(
        override val name: String,
        private val reply: (JsonObject) -> ToolResult,
    ) : Tool {
        val received = mutableListOf<JsonObject>()
        override val description = "fake tool $name"
        override val parametersSchema = Schema.obj(mapOf("value" to Schema.string("input")))
        override val isReadOnly = true
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            received += args
            return reply(args)
        }
    }

    private lateinit var server: HarnessMcpServer

    @After
    fun tearDown() {
        if (::server.isInitialized) server.close()
    }

    private fun start(vararg tools: Tool, allowed: Set<String> = HarnessMcpServer.DEFAULT_SAFE_TOOLS): HarnessMcpServer {
        val registry = ToolRegistry(tools.toList())
        server = HarnessMcpServer(
            registry = { registry },
            workspace = { FileFs(tmp.root) },
            tokenFile = File(tmp.root, "mcp-token"),
            allowedTools = allowed,
        )
        server.start(0)
        return server
    }

    private fun post(port: Int, body: String, token: String? = "x"): Pair<Int, String> {
        Socket("127.0.0.1", port).use { s ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("POST /mcp HTTP/1.1\r\nHost: localhost\r\n")
                if (token != null) append("Authorization: Bearer $token\r\n")
                append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
            }
            s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
            val text = s.getInputStream().bufferedReader().readText()
            return text.substringAfter(" ").substringBefore(" ").toInt() to text.substringAfter("\r\n\r\n", "")
        }
    }

    private fun HarnessMcpServer.post(body: String, token: String? = this.token) = post(port, body, token)

    @Test
    fun `initialize reports protocol version capabilities and server info`() {
        start(FakeTool("read_file") { ToolResult(true, "ok") })
        val (code, body) = server.post(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""",
        )
        assertEquals(200, code)
        val result = body.rpcResult()
        assertEquals("2024-11-05", result["protocolVersion"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(result["capabilities"]?.jsonObject?.get("tools"))
        assertEquals("androidharness", result["serverInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `a wrong or missing token is rejected before any method handling`() {
        start(FakeTool("read_file") { ToolResult(true, "ok") })
        val (wrong, _) = server.post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", token = "nope")
        assertEquals(401, wrong)
        val (missing, _) = server.post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", token = null)
        assertEquals(401, missing)
    }

    @Test
    fun `tools list only exposes the allowed set`() {
        val allowedTool = FakeTool("file_info") { ToolResult(true, "info") }
        val unsafeTool = FakeTool("shell") { ToolResult(true, "should never run") }
        start(allowedTool, unsafeTool)
        val (_, body) = server.post("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
        val names = body.rpcResult()["tools"]?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.orEmpty()
        assertTrue(names.contains("file_info"))
        assertFalse(names.contains("shell"))
    }

    @Test
    fun `tools call executes the tool with arguments and maps the result`() {
        val tool = FakeTool("file_info") { args ->
            ToolResult(true, "value=" + (args["value"]?.jsonPrimitive?.contentOrNull ?: ""))
        }
        start(tool)
        val (code, body) = server.post(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"file_info","arguments":{"value":"hello"}}}""",
        )
        assertEquals(200, code)
        val result = body.rpcResult()
        assertEquals("false", result["isError"]?.jsonPrimitive?.contentOrNull)
        assertTrue(result["content"].toString().contains("value=hello"))
        assertEquals(listOf("""{"value":"hello"}"""), tool.received.map { it.toString() })
    }

    @Test
    fun `a disallowed tool call is refused with an isError result`() {
        val unsafe = FakeTool("shell") { ToolResult(true, "ran!") }
        start(unsafe)
        val (_, body) = server.post(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"shell","arguments":{}}}""",
        )
        val result = body.rpcResult()
        assertEquals("true", result["isError"]?.jsonPrimitive?.contentOrNull)
        assertTrue(result.toString().contains("not exposed over MCP"))
        assertEquals(0, unsafe.received.size) // never executed
    }

    @Test
    fun `unknown methods and malformed json are protocol errors`() {
        start(FakeTool("read_file") { ToolResult(true, "ok") })
        val (_, unknown) = server.post("""{"jsonrpc":"2.0","id":5,"method":"bogus"}""")
        assertEquals("-32601", unknown.rpcError()?.get("code")?.jsonPrimitive?.contentOrNull)
        val (_, malformed) = server.post("""{not json""")
        assertEquals("-32700", malformed.rpcError()?.get("code")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `notifications without an id get an empty accepted response`() {
        start(FakeTool("read_file") { ToolResult(true, "ok") })
        val (code, body) = server.post(
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
        )
        assertEquals(202, code)
        assertEquals("", body)
    }

    @Test
    fun `the token persists across restarts`() {
        start(FakeTool("read_file") { ToolResult(true, "ok") })
        val first = server.token
        server.close()
        val again = HarnessMcpServer(
            registry = { ToolRegistry(emptyList()) },
            workspace = { FileFs(tmp.root) },
            tokenFile = File(tmp.root, "mcp-token"),
        )
        assertEquals(first, again.token)
        again.close()
    }

    @Test
    fun `binding is loopback only`() {
        start(FakeTool("read_file") { ToolResult(true, "ok") })
        val field = server.javaClass.getDeclaredField("listener")
        field.isAccessible = true
        val socket = field.get(server) as java.net.ServerSocket
        assertEquals("127.0.0.1", socket.inetAddress.hostAddress)
    }

    private fun String.rpcResult(): JsonObject {
        val root = Json.parseToJsonElement(this).jsonObject
        return root["result"]?.jsonObject ?: JsonObject(emptyMap())
    }

    private fun String.rpcError(): JsonObject? =
        Json.parseToJsonElement(this).jsonObject["error"]?.jsonObject
}
