package com.deepseekharness.app.etatools.model

/**
 * 工具层类型契约。从 Eta 的模型客户端抽出为顶层类型，避免拖入 provider/loop 链。
 * 这些是工具 schema / 执行器 / MCP 层共用的纯数据与接口类型，无 Android 依赖。
 */

/** 工具执行器：由 AgentLocalTools 实现，MCP server 转发调用。 */
fun interface ToolExecutor {
    fun execute(toolCall: ToolCall): ToolResult
}

/** 一次工具调用：id 由模型生成，argumentsJson 是 JSON 参数原文。 */
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val round: Int = 1,
)

/** 工具执行结果。images 进入模型协议前必须解析为远程 URL 或 data URL。 */
data class ToolResult(
    val content: String,
    val images: List<ModelImage> = emptyList(),
    /** 敏感结果仍供当前 agent loop 使用，但工具参数与原始结果不进入持久会话。 */
    val sensitive: Boolean = false,
    val command: String? = null,
)

/** 图片引用：入口侧可为本地 URI/路径，进入模型协议前必须解析为远程 URL 或 data URL。 */
data class ModelImage(
    val reference: String,
    val mimeType: String,
    val bytes: Int,
    val width: Int? = null,
    val height: Int? = null,
    val source: String = "unknown",
    /** 截图已具有可上传编码，跨进程物化时保留字节，不走附件转码。 */
    val preserveOriginal: Boolean = false,
)
