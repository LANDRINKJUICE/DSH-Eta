package com.deepseekharness.app.etatools.model

/**
 * 稳定会话消息类型。从 Eta 模型客户端抽出的纯数据类：字段逐项照抄，
 * 不携带 @Serializable 注解（:etatools 未引入 kotlinx-serialization 依赖）。
 */
data class ConversationMessage(
    val role: String,
    val content: String = "",
    val contentJson: String = "",
    val toolCallId: String = "",
    val reasoningContent: String = "",
    val toolCallsJson: String = "",
    val contextSummary: Boolean = false,
    val compactedUserTurns: Int = 0,
    val summaryThroughUserTurn: Int = 0,
    val messageId: String = "",
    val compactionArchiveId: String = "",
)
