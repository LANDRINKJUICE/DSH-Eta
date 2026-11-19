package com.deepseekharness.app.etatools.tool

sealed interface ToolExecutionDecision {
    data object Allow : ToolExecutionDecision

    data class Reject(
        val code: String,
        val message: String,
    ) : ToolExecutionDecision
}
