package com.deepseekharness.app.etatools.mcp

import com.deepseekharness.app.etatools.model.AgentToolCatalog
import com.deepseekharness.app.etatools.tool.AgentToolCapabilities
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 Eta 的模型工具目录投影成 MCP `tools/list` 所需的工具描述。
 *
 * 只做"OpenAI function 描述 -> MCP tool 描述"的字段搬运，不引入任何新的工具，
 * 也不修改 [AgentToolCatalog] 或 [AgentToolCapabilities] 的实现。
 */
internal object EtaMcpToolCatalog {
    /** 返回 MCP 工具数组，每个元素形如：
     * `{"name":...,"description":...,"inputSchema":{...},"annotations":{"title":...}}`
     */
    fun list(caps: AgentToolCapabilities): JSONArray {
        val openAiTools = caps.project(
            AgentToolCatalog.build(
                terminalTools = true,
                browserTools = true,
                deviceDirectTools = true,
                deviceSensitiveReadTools = true,
                deviceSensitiveActionTools = true,
                memoryTools = true,
                skillGitHubDiscovery = true,
                skillGitHubInstall = true,
                capabilities = caps,
                localWebSearch = true,
            ),
        )
        val mcpTools = JSONArray()
        for (index in 0 until openAiTools.length()) {
            val function = openAiTools.getJSONObject(index).optJSONObject("function") ?: continue
            val name = function.optString("name")
            if (name.isNullOrEmpty()) continue
            mcpTools.put(
                JSONObject()
                    .put("name", name)
                    .put("description", function.optString("description"))
                    .put("inputSchema", function.optJSONObject("parameters") ?: JSONObject())
                    .put("annotations", JSONObject().put("title", name)),
            )
        }
        return mcpTools
    }
}
