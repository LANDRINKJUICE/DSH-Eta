package com.deepseekharness.app.etatools.tool

import com.deepseekharness.app.etatools.context.PersonalSearchTools

object ToolSwitchIds {
    fun canonicalToolSwitchId(toolName: String): String = when (toolName) {
        "tap" -> "tap_area"
        "long_press_element" -> "long_press"
        "scroll_element" -> "scroll"
        "input_text", "replace_text", "clear_text", "paste_text" -> "type_text"
        "wait", "wait_for_package" -> "wait_for_text"
        "browser_read", "browser_interact", "browser_screenshot" -> "browser_use"
        "search_coloros_notes" -> "search_notes"
        "search_coloros_memories" -> "search_system_memories"
        else -> PersonalSearchTools.canonical(toolName)
    }

    fun isToolEnabledInSet(toolName: String, disabled: Set<String>): Boolean {
        if (disabled.isEmpty()) return true
        val canonical = canonicalToolSwitchId(toolName)
        return toolName !in disabled && canonical !in disabled
    }
}
