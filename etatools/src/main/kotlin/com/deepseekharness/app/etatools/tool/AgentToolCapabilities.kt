package com.deepseekharness.app.etatools.tool

import android.os.Build
import java.util.Locale
import org.json.JSONArray

/** 每轮冻结的运行条件；不包含用户开关，也不触发授权请求。 */
data class AgentToolCapabilities(
    val rootAvailable: Boolean,
    val rootState: RootAccessState? = null,
    val lsposedAvailable: Boolean = false,
    val accessibilityAvailable: Boolean = true,
    val accessibilityRecoveryAvailable: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val usageAllowed: Boolean = true,
    val locationAllowed: Boolean = true,
    val colorOs: Boolean = true,
    val calendarReadable: Boolean = true,
    val calendarWritable: Boolean = true,
    val flashlightAvailable: Boolean = true,
    val hotspotAvailable: Boolean = true,
    val nfcAvailable: Boolean = true,
    val nightLightAvailable: Boolean = true,
) {
    fun unavailableCode(name: String): String? {
        if (name in setOf("get_flashlight", "set_flashlight") && !flashlightAvailable) return "DEVICE_UNSUPPORTED"
        if (name in setOf("get_hotspot", "set_hotspot") && !hotspotAvailable) return "DEVICE_UNSUPPORTED"
        val requirement = AgentToolRequirements.find(name) ?: return "UNKNOWN_TOOL"
        if (requirement.rootRequirement == RootRequirement.REQUIRED && !rootAvailable) return "ROOT_REQUIRED"
        if (requirement.lsposedRequirement == LsposedRequirement.REQUIRED && !lsposedAvailable) return "LSPOSED_REQUIRED"
        if (requirement.colorOs && !colorOs) return "DEVICE_UNSUPPORTED"
        if (requirement.accessibility && !accessibilityAvailable && !accessibilityRecoveryAvailable) {
            return "ACCESSIBILITY_UNAVAILABLE"
        }
        return when (requirement.systemAccess) {
            ToolSystemAccess.CALENDAR_READ -> if (rootAvailable || calendarReadable) null else "CALENDAR_PERMISSION_REQUIRED"
            ToolSystemAccess.CALENDAR_WRITE -> if (rootAvailable || calendarWritable) null else "CALENDAR_PERMISSION_REQUIRED"
            ToolSystemAccess.NONE -> null
            ToolSystemAccess.NOTIFICATIONS -> if (notificationsAllowed ||
                (rootAvailable && (name == "recent_notifications" ||
                    (name == "search_personal_orders" && colorOs)))
            ) null else "NOTIFICATION_ACCESS_REQUIRED"
            ToolSystemAccess.USAGE -> if (usageAllowed) null else "APP_USAGE_ACCESS_REQUIRED"
            ToolSystemAccess.LOCATION -> if (locationAllowed) null else "LOCATION_PERMISSION_REQUIRED"
        }
    }

    fun project(tools: JSONArray): JSONArray {
        val rootProjected = AgentToolRequirements.project(tools, rootAvailable)
        return JSONArray().also { visible ->
            for (index in 0 until rootProjected.length()) {
                val tool = rootProjected.getJSONObject(index)
                val name = tool.getJSONObject("function").getString("name")
                if (unavailableCode(name) == null) {
                    if (name in setOf("get_device_state", "set_device_state") && (!nfcAvailable || !nightLightAvailable)) {
                        val target = tool.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").getJSONObject("target")
                        target.put("enum", JSONArray(SystemStateTargets.targets.filterNot { it == "nfc" && !nfcAvailable || it == "night_light" && !nightLightAvailable }))
                    }
                    visible.put(tool)
                }
            }
        }
    }

    companion object {
        fun isColorOsDevice(): Boolean = Build.MANUFACTURER.lowercase(Locale.ROOT) in
            setOf("oppo", "oneplus", "realme")

    }
}
