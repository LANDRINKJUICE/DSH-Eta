package com.deepseekharness.app.etatools.mcp

import android.content.Context
import com.deepseekharness.app.etatools.core.AgentLogger
import com.deepseekharness.app.etatools.model.ToolCall
import com.deepseekharness.app.etatools.tool.AgentLocalTools
import com.deepseekharness.app.etatools.tool.AgentToolCapabilities
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把一次 MCP `tools/call` 转交给 Eta 的本地执行器 [AgentLocalTools]，
 * 并把 ToolResult 包装成 MCP 的 tool result。
 *
 * 不改动任何现有工具/执行器实现，只在最外层适配协议帧。
 */
internal class EtaMcpToolExecutor(
    context: Context,
    logger: AgentLogger,
    private val caps: AgentToolCapabilities,
    private val local: AgentLocalTools,
) : AutoCloseable {
    /**
     * 执行名为 [name] 的工具，参数为 [arguments]。
     * 返回 MCP tool result：
     * `{"resultType":"complete","content":[...],"isError":false}`。
     */
    fun call(name: String, arguments: JSONObject): JSONObject {
        caps.unavailableCode(name)?.let { code ->
            return toolResult(
                isError = true,
                content = JSONArray().put(
                    textContent(
                        JSONObject()
                            .put("ok", false)
                            .put("code", code)
                            .put("message", "该工具当前不可用（$code），本次未执行")
                            .toString(),
                    ),
                ),
            )
        }
        return try {
            val result = local.execute(
                ToolCall(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    argumentsJson = arguments.toString(),
                ),
            )
            val content = JSONArray().put(textContent(result.content))
            for (image in result.images) {
                content.put(
                    JSONObject()
                        .put("type", "image")
                        .put("mimeType", image.mimeType)
                        .put("data", base64Payload(image.reference, image.mimeType)),
                )
            }
            toolResult(isError = false, content = content)
        } catch (throwable: Throwable) {
            toolResult(
                isError = true,
                content = JSONArray().put(
                    textContent(
                        JSONObject()
                            .put("ok", false)
                            .put("code", "EXECUTION_FAILED")
                            .put("message", throwable.message ?: throwable.javaClass.simpleName)
                            .toString(),
                    ),
                ),
            )
        }
    }

    override fun close() {
        local.close()
    }

    private fun textContent(text: String): JSONObject =
        JSONObject().put("type", "text").put("text", text)

    private fun toolResult(isError: Boolean, content: JSONArray): JSONObject =
        JSONObject()
            .put("resultType", "complete")
            .put("content", content)
            .put("isError", isError)

    /** 允许传入裸 base64 或 `data:<mime>;base64,<payload>` 形式，统一取 payload。 */
    private fun base64Payload(reference: String, mimeType: String): String {
        val prefix = "data:$mimeType;base64,"
        return if (reference.startsWith(prefix, ignoreCase = true)) {
            reference.substring(prefix.length)
        } else {
            val genericPrefix = "data:"
            if (reference.startsWith(genericPrefix, ignoreCase = true)) {
                val comma = reference.indexOf(',')
                if (comma >= 0) reference.substring(comma + 1) else reference
            } else {
                reference
            }
        }
    }
}
