package com.deepseekharness.app.etatools.mcp

import android.content.Context
import com.deepseekharness.app.etatools.core.AgentLogger
import com.deepseekharness.app.etatools.tool.AgentLocalTools
import com.deepseekharness.app.etatools.tool.AgentToolCapabilities
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * 单端点、仅绑定 127.0.0.1 的 MCP HTTP JSON-RPC server。
 *
 * 让外部 MCP 客户端（DSH 的 mcp-client）能通过 MCP 协议调用 Eta 的全部工具。
 * 本类只负责协议帧与 HTTP 传输，工具目录来自 [EtaMcpToolCatalog]，
 * 工具执行来自 [EtaMcpToolExecutor]，不改动任何现有工具/执行器/schema 实现。
 */
internal class EtaMcpServer(
    private val port: Int,
    private val token: String?,
    context: Context,
    logger: AgentLogger,
    caps: AgentToolCapabilities,
    local: AgentLocalTools,
) : AutoCloseable {
    private val toolExecutor = EtaMcpToolExecutor(context, logger, caps, local)
    private val sessionId = java.util.UUID.randomUUID().toString()
    private val callLock = Any()

    private val nano = object : NanoHTTPD(HOST, port) {
        override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response =
            handle(session)
    }

    /** 起线程监听 127.0.0.1:[port]。 */
    fun start() {
        nano.start(SOCKET_READ_TIMEOUT, false)
    }

    fun isRunning(): Boolean = nano.isAlive

    /** 关闭监听并释放本地执行器资源。 */
    fun stop() {
        close()
    }

    override fun close() {
        nano.stop()
        toolExecutor.close()
    }

    private fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val body = readBody(session)
        val request = runCatching { JSONObject(body) }.getOrNull()
        val id = if (request != null && request.has("id")) request.opt("id") else JSONObject.NULL

        if (!authorized(session)) {
            return jsonResponse(
                NanoHTTPD.Response.Status.UNAUTHORIZED,
                errorResult(id, -32001, "Unauthorized"),
                mapOf(HEADER_SESSION_ID to sessionId),
            )
        }
        if (request == null) {
            return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                errorResult(id, -32700, "Parse error"),
                mapOf(HEADER_SESSION_ID to sessionId),
            )
        }

        val method = request.optString("method")
        if (method == "notifications/initialized") {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.ACCEPTED,
                NanoHTTPD.MIME_PLAINTEXT,
                "",
            )
        }

        val result: JSONObject = when (method) {
            "initialize" -> JSONObject()
                .put("protocolVersion", McpProtocolMode.LATEST)
                .put(
                    "capabilities",
                    JSONObject().put("tools", JSONObject().put("listChanged", false)),
                )
                .put(
                    "serverInfo",
                    JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION),
                )

            "tools/list" -> JSONObject()
                .put("resultType", "complete")
                .put("tools", EtaMcpToolCatalog.list(caps))

            "tools/call" -> {
                val params = request.optJSONObject("params") ?: JSONObject()
                val name = params.optString("name")
                val arguments = params.optJSONObject("arguments") ?: JSONObject()
                synchronized(callLock) { toolExecutor.call(name, arguments) }
            }

            else -> return jsonResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                errorResult(id, -32601, "Method not found"),
                mapOf(HEADER_SESSION_ID to sessionId),
            )
        }

        val response = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)

        val headers = LinkedHashMap<String, String>()
        headers[HEADER_PROTOCOL_VERSION] = McpProtocolMode.LATEST
        headers[HEADER_SESSION_ID] = session.headers[HEADER_SESSION_ID.lowercase()]
            ?.takeIf { it.isNotBlank() } ?: sessionId
        session.headers[HEADER_METHOD.lowercase()]?.takeIf { it.isNotBlank() }?.let {
            headers[HEADER_METHOD] = it
        }
        session.headers[HEADER_NAME.lowercase()]?.takeIf { it.isNotBlank() }?.let {
            headers[HEADER_NAME] = it
        }
        return jsonResponse(NanoHTTPD.Response.Status.OK, response, headers)
    }

    private fun authorized(session: NanoHTTPD.IHTTPSession): Boolean {
        if (token.isNullOrBlank()) return true
        return session.headers["authorization"] == "Bearer $token"
    }

    private fun readBody(session: NanoHTTPD.IHTTPSession): String {
        val declared = session.headers["content-length"]?.toIntOrNull() ?: return ""
        if (declared <= 0) return ""
        val size = minOf(declared, MAX_BODY_BYTES)
        val buffer = ByteArray(size)
        val input = session.inputStream
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) break
            offset += read
        }
        return String(buffer, 0, offset, Charsets.UTF_8)
    }

    private fun jsonResponse(
        status: NanoHTTPD.Response.Status,
        json: JSONObject,
        headers: Map<String, String> = emptyMap(),
    ): NanoHTTPD.Response {
        val response = NanoHTTPD.newFixedLengthResponse(
            status,
            JSON_MIME_TYPE,
            json.toString(),
        )
        headers.forEach { (name, value) -> response.addHeader(name, value) }
        return response
    }

    private fun errorResult(id: Any?, code: Int, message: String): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put(
                "error",
                JSONObject().put("code", code).put("message", message),
            )

    private companion object {
        const val HOST = "127.0.0.1"
        const val JSON_MIME_TYPE = "application/json; charset=utf-8"
        const val SERVER_NAME = "Eta"
        const val SERVER_VERSION = "1.0.0"
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"
        const val HEADER_METHOD = "Mcp-Method"
        const val HEADER_NAME = "Mcp-Name"
        const val HEADER_SESSION_ID = "Mcp-Session-Id"
        const val SOCKET_READ_TIMEOUT = 20_000
    }
}
