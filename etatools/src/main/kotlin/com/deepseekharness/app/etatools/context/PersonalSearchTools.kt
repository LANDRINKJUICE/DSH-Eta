package com.deepseekharness.app.etatools.context

/**
 * 从 Eta agent/context/PersonalSearchTools.kt 抽出的纯数据部分（不含依赖 PersonalContextQueryService 的 IndexedPersonalSearch）。
 * 值逐项照抄。
 */
internal object PersonalSearchTools {
    data class Search(
        val name: String,
        val source: String,
        val title: String,
        val description: String,
    )

    val searches =
        listOf(
            Search("search_bills", "bills", "查询账单", "按商户、内容和时间查找账单记录。"),
            Search("search_todos", "todos", "查询待办线索", "查询系统从个人信息中提取的待办线索，未识别时间的记录不能按时间命中。"),
            Search("search_calendar_todos", "calendar_todos", "查询日历待办", "查询日历中的待办及其计划时间。"),
            Search("search_memory_collections", "collections", "查询记忆合集", "查找已保存的记忆合集，不支持业务时间筛选。"),
            Search("search_daily_events", "events", "查询生活事件", "查找系统记录或推断的生活事件，不把推断当成确认事实。"),
            Search("search_flights", "flights", "查询航班行程", "按航班、机场和计划起飞时间检索行程。"),
            Search("search_hotels", "hotels", "查询酒店预订", "按酒店、地址、订单和入住时间检索预订。"),
            Search("search_trains", "trains", "查询火车行程", "按车次、车站和出发时间检索行程。"),
        )
    val names =
        searches.mapTo(linkedSetOf()) { it.name } + setOf("read_personal_item", "summarize_bills")
    val aliases =
        mapOf(
            "search_coloros_notes" to "search_notes",
            "search_coloros_memories" to "search_system_memories",
        )

    fun canonical(name: String): String = aliases[name] ?: name

    fun isIndexedSearch(name: String): Boolean = searches.any { it.name == name }
}
