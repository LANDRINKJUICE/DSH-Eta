package com.deepseekharness.app.etatools.tool

/**
 * 从 Eta agent/tool/AgentToolRequirements.kt 抽出的最小枚举集合。
 * 仅工具 schema 层需要，不引入 LocalToolRequirement / AgentToolRequirements 守门逻辑。
 */
internal enum class RootRequirement { NONE, PARTIAL, REQUIRED }
internal enum class LsposedRequirement { NONE, OPTIONAL, REQUIRED }
internal enum class ToolSystemAccess { NONE, NOTIFICATIONS, USAGE, LOCATION, CALENDAR_READ, CALENDAR_WRITE }
