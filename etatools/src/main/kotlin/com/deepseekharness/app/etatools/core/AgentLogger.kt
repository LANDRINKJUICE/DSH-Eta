package com.deepseekharness.app.etatools.core

internal interface AgentLogger {
    /**
     * 记录仅用于开发期诊断的信息。
     *
     * supplier 只能构造诊断文本，不能承担程序正确性依赖的副作用；Release 构建会删除整次调用。
     */
    fun debug(message: () -> String)

    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
}
