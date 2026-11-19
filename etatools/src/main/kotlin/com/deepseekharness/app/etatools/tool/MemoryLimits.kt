package com.deepseekharness.app.etatools.tool

/** 从 Eta data/repository/AgentMemoryRepository.kt 中 AgentMemoryStore 的 companion object 抽出的记忆读写上限。值逐项照抄。 */
internal object MemoryLimits {
    const val MIN_READ_CHARS = 1
    const val MAX_READ_CHARS = 32_000
    const val MAX_WRITE_CONTENT_CHARS = 3_500
}
