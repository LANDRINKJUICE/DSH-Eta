package com.deepseekharness.app.eta

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.deepseekharness.app.DshaAccessibilityService
import com.deepseekharness.app.etatools.device.CoordinateSpace
import com.deepseekharness.app.etatools.device.DeviceController
import com.deepseekharness.app.etatools.device.ElementObservation
import com.deepseekharness.app.etatools.device.ElementSource
import com.deepseekharness.app.etatools.device.Observation
import com.deepseekharness.app.etatools.device.UiNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Eta [DeviceController] 的 DSHA 宿主实现（第一版：只走物理主屏 Display.DEFAULT_DISPLAY）。
 *
 * 屏幕动作全部委托给 [DshaAccessibilityService] 的静态门面。注意：在当前 DSHA 快照中，
 * 这些门面（DshaAccessibilityService.java 第 562-600 行）是显式请求链迁移后的旧入口，
 * 会返回 "[ERR] EXPLICIT_SCREEN_REQUEST_REQUIRED" / "[ERR] FRAME_NOT_OBSERVED"；
 * 本类不掩盖这一点，而是把这类回执规范化成 ok=false 的 JSON，交回工具层如实呈现。
 * 真实路径需要走 capability broker + 3090 桥任务身份，见交接报告「需决策」部分。
 */
class DshaDeviceController(context: Context) : DeviceController {

    private val appContext: Context = context.applicationContext

    // ---------- observe ----------

    override fun observe(
        includeScreenshot: Boolean,
        includeUiTree: Boolean,
        maxNodes: Int,
        displayId: Int,
    ): Observation {
        val raw = safeDump(displayId)
        val (width, height) = screenSize(displayId)
        // 第一版不产出截图：截图像素坐标 == 屏幕像素坐标，避免坐标系换算引入错误。
        val space = CoordinateSpace(width, height, width, height)
        val (content, elements) = parseObservation(raw, includeUiTree, maxNodes)
        return Observation(
            content = content,
            image = null,
            elementObservation = elements,
            coordinateSpace = space,
        )
    }

    private fun safeDump(displayId: Int): String = try {
        if (isVirtual(displayId)) {
            DshaAccessibilityService.virtualDump(displayId)
        } else {
            DshaAccessibilityService.uiDump()
        }
    } catch (throwable: Throwable) {
        "[ERR] DUMP_THREW ${throwable.javaClass.simpleName}: ${throwable.message}"
    }

    /**
     * DSHA 真实 dump 回执形如（ScreenSnapshotReader.read）：
     * {"state":"COMPLETED","kind":"dsha-screen-tree-v1","text":"...","treeId":"...",
     *  "nodes":[{"nodeId","text","description","clickable","editable","scrollable",
     *           "supportedActions":[],"enabled","checked","boundsHaveArea","bounds":[l,t,r,b]}],
     *  "target":{"displayId","windowId","packageName"}, ...}
     * 旧门面可能返回节点数组或纯文本；三种形态都做兼容，且 content 恒为合法 JSON
     * （AgentLocalTools.observeScreen 会直接对 content 做 JSONObject(...)）。
     */
    private fun parseObservation(
        raw: String,
        includeUiTree: Boolean,
        maxNodes: Int,
    ): Pair<String, ElementObservation?> {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            val code = if (trimmed.contains("EXPLICIT_SCREEN_REQUEST_REQUIRED")) {
                "SCREEN_BRIDGE_UNAVAILABLE"
            } else if (trimmed.contains("FRAME_NOT_OBSERVED")) {
                "FRAME_NOT_OBSERVED"
            } else {
                "DSHA_DUMP_UNAVAILABLE"
            }
            return JSONObject()
                .put("ok", false)
                .put("error", code)
                .put("raw", trimmed)
                .toString() to null
        }
        return try {
            if (trimmed.startsWith("{")) {
                val json = JSONObject(trimmed)
                val target = json.optJSONObject("target")
                val packageName = target?.optString("packageName", "") ?: ""
                val windowId = target?.optInt("windowId", -1) ?: -1
                val treeId = json.optString("treeId", "")
                val nodes = if (includeUiTree) {
                    json.optJSONArray("nodes")?.let { toUiNodes(it, packageName, maxNodes) }
                        ?: emptyList()
                } else {
                    emptyList()
                }
                val allCount = json.optJSONArray("nodes")?.length() ?: 0
                val result = JSONObject(trimmed)
                if (!result.has("ok")) {
                    result.put("ok", json.optString("state") == "COMPLETED" || !json.has("state"))
                }
                val elements = ElementObservation(
                    id = treeId.ifEmpty { "dsha-tree-${SystemClock.elapsedRealtime()}" },
                    source = ElementSource.ACCESSIBILITY,
                    packageName = packageName,
                    windowId = windowId.takeIf { it >= 0 },
                    nodes = nodes,
                    maxNodes = maxNodes,
                    truncated = json.optBoolean("truncated", false) || allCount > nodes.size,
                    treeSignature = treeId,
                )
                result.toString() to elements
            } else {
                val array = JSONArray(trimmed)
                val nodes = if (includeUiTree) toUiNodes(array, "", maxNodes) else emptyList()
                val result = JSONObject()
                    .put("ok", true)
                    .put("nodes", array)
                result.toString() to ElementObservation(
                    id = "dsha-tree-${SystemClock.elapsedRealtime()}",
                    source = ElementSource.ACCESSIBILITY,
                    packageName = "",
                    windowId = null,
                    nodes = nodes,
                    maxNodes = maxNodes,
                    truncated = array.length() > nodes.size,
                    treeSignature = "",
                )
            }
        } catch (throwable: Throwable) {
            JSONObject()
                .put("ok", false)
                .put("error", "DUMP_PARSE_FAILED")
                .put("detail", "${throwable.javaClass.simpleName}: ${throwable.message}")
                .put("raw", trimmed.take(2000))
                .toString() to null
        }
    }

    private fun toUiNodes(array: JSONArray, packageName: String, maxNodes: Int): List<UiNode> {
        val limit = maxOf(0, maxNodes)
        val result = ArrayList<UiNode>(minOf(array.length(), limit))
        for (index in 0 until minOf(array.length(), limit)) {
            val item = array.optJSONObject(index) ?: continue
            result += toUiNode(item, index, packageName)
        }
        return result
    }

    private fun toUiNode(item: JSONObject, index: Int, packageName: String): UiNode {
        val boundsArray = item.optJSONArray("bounds")
        val bounds = if (boundsArray != null && boundsArray.length() >= 4) {
            Rect(
                boundsArray.optInt(0),
                boundsArray.optInt(1),
                boundsArray.optInt(2),
                boundsArray.optInt(3),
            )
        } else {
            Rect()
        }
        val actionNames = HashSet<String>()
        item.optJSONArray("supportedActions")?.let { actions ->
            for (i in 0 until actions.length()) actionNames.add(actions.optString(i).lowercase())
        }
        val label = item.optString("text", "")
        return UiNode(
            index = index,
            text = label,
            desc = item.optString("description", ""),
            className = item.optString("className", ""),
            packageName = packageName.ifEmpty { item.optString("packageName", "") },
            viewId = item.optString("viewId", ""),
            bounds = bounds,
            clickable = item.optBoolean("clickable", false),
            longClickable = actionNames.any { it.contains("long") },
            scrollable = item.optBoolean("scrollable", false),
            focused = item.optBoolean("focused", false),
            editable = item.optBoolean("editable", false),
            password = label == "[PASSWORD]",
            enabled = item.optBoolean("enabled", true),
            checked = if (item.has("checked")) item.optBoolean("checked", false) else null,
            selected = item.optBoolean("selected", false),
            hint = item.optString("hint", ""),
        )
    }

    // ---------- 屏幕动作 ----------

    override fun screenDimensions(displayId: Int): Pair<Int, Int> = screenSize(displayId)

    override fun tap(x: Int, y: Int, displayId: Int): String {
        physicalOnly(displayId)?.let { return it }
        return normalize(safeCall { DshaAccessibilityService.uiTap(x, y) })
    }

    override fun tapElement(observation: ElementObservation, index: Int): String {
        val node = observation.nodes.getOrNull(index)
            ?: return unsupported("ELEMENT_INDEX_OUT_OF_RANGE", "index=$index 超出 observation 节点范围")
        if (node.bounds.width() <= 0 || node.bounds.height() <= 0) {
            return unsupported("ELEMENT_NO_TOUCH_AREA", "index=$index 无可触摸面积")
        }
        return tap(node.centerX, node.centerY, Display.DEFAULT_DISPLAY)
    }

    override fun longPress(x: Int, y: Int, durationMs: Int, displayId: Int): String = unsupported(
        "LONG_PRESS_UNSUPPORTED",
        "DSHA 静态门面未暴露长按；第一版不模拟（3090 桥 /app/ui/tap?gesture=long_press 需 observationId）",
    )

    override fun longPressElement(
        observation: ElementObservation,
        index: Int,
        durationMs: Int,
    ): String = unsupported("LONG_PRESS_UNSUPPORTED", "第一版不支持元素长按")

    override fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
        displayId: Int,
    ): String {
        physicalOnly(displayId)?.let { return it }
        return normalize(safeCall { DshaAccessibilityService.uiSwipe(x1, y1, x2, y2, durationMs) })
    }

    override fun scroll(direction: String, amount: String, displayId: Int): String {
        physicalOnly(displayId)?.let { return it }
        val (width, height) = screenSize(displayId)
        val centerX = width / 2
        val centerY = height / 2
        val distance = when (amount.trim().lowercase()) {
            "small" -> maxOf(1, height / 8)
            "large" -> maxOf(1, height * 2 / 5)
            else -> maxOf(1, height / 4)
        }
        val fromX: Int
        val fromY: Int
        val toX: Int
        val toY: Int
        when (direction.trim().lowercase()) {
            "up" -> {
                fromX = centerX; fromY = centerY + distance
                toX = centerX; toY = centerY - distance
            }
            "down" -> {
                fromX = centerX; fromY = centerY - distance
                toX = centerX; toY = centerY + distance
            }
            "left" -> {
                fromX = centerX + distance; fromY = centerY
                toX = centerX - distance; toY = centerY
            }
            "right" -> {
                fromX = centerX - distance; fromY = centerY
                toX = centerX + distance; toY = centerY
            }
            else -> return unsupported("INVALID_DIRECTION", "direction=$direction")
        }
        return swipe(fromX, fromY, toX, toY, 300, displayId)
    }

    override fun scrollElement(
        observation: ElementObservation,
        index: Int,
        direction: String,
        amount: String,
    ): String {
        val node = observation.nodes.getOrNull(index)
            ?: return unsupported("ELEMENT_INDEX_OUT_OF_RANGE", "index=$index")
        if (node.bounds.width() <= 0 || node.bounds.height() <= 0) {
            return unsupported("ELEMENT_NO_TOUCH_AREA", "index=$index")
        }
        val centerX = node.centerX
        val centerY = node.centerY
        val distance = when (amount.trim().lowercase()) {
            "small" -> maxOf(1, node.bounds.height() / 4)
            "large" -> maxOf(1, node.bounds.height() * 3 / 4)
            else -> maxOf(1, node.bounds.height() / 2)
        }
        val fromX: Int
        val fromY: Int
        val toX: Int
        val toY: Int
        when (direction.trim().lowercase()) {
            "up" -> { fromX = centerX; fromY = centerY + distance; toX = centerX; toY = centerY - distance }
            "down" -> { fromX = centerX; fromY = centerY - distance; toX = centerX; toY = centerY + distance }
            "left" -> { fromX = centerX + distance; fromY = centerY; toX = centerX - distance; toY = centerY }
            "right" -> { fromX = centerX - distance; fromY = centerY; toX = centerX + distance; toY = centerY }
            else -> return unsupported("INVALID_DIRECTION", "direction=$direction")
        }
        return swipe(fromX, fromY, toX, toY, 300, Display.DEFAULT_DISPLAY)
    }

    override fun inputText(text: String, displayId: Int): String {
        physicalOnly(displayId)?.let { return it }
        return normalize(safeCall { DshaAccessibilityService.uiInput(text) })
    }

    override fun replaceText(
        text: String,
        index: Int?,
        observation: ElementObservation?,
        displayId: Int,
    ): String {
        physicalOnly(displayId)?.let { return it }
        if (observation != null && index != null) {
            val focus = tapElement(observation, index)
            if (!JSONObject(focus).optBoolean("ok", false)) return focus
        }
        val cleared = clearText(index, observation, displayId)
        if (!JSONObject(cleared).optBoolean("ok", false)) return cleared
        return inputText(text, displayId)
    }

    override fun clearText(index: Int?, observation: ElementObservation?, displayId: Int): String {
        physicalOnly(displayId)?.let { return it }
        // 第一版近似：全选 + 删除，两步都用 DSHA 的按键门面。
        val selected = normalize(safeCall { DshaAccessibilityService.uiKey("ctrl_a") })
        if (!JSONObject(selected).optBoolean("ok", false)) return selected
        return normalize(safeCall { DshaAccessibilityService.uiKey("delete") })
    }

    override fun pressKey(button: String, displayId: Int): String {
        physicalOnly(displayId)?.let { return it }
        val name = button.trim().lowercase()
        if (name.isEmpty()) return unsupported("INVALID_BUTTON", "button 为空")
        return normalize(safeCall { DshaAccessibilityService.uiKey(name) })
    }

    override fun waitMs(durationMs: Int): String {
        val waited = maxOf(0, durationMs)
        try {
            Thread.sleep(waited.toLong())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return JSONObject()
                .put("ok", false)
                .put("error", "INTERRUPTED")
                .put("waited_ms", waited)
                .toString()
        }
        return JSONObject().put("ok", true).put("waited_ms", waited).toString()
    }

    override fun waitForText(
        text: String,
        timeoutMs: Int,
        includeDesc: Boolean,
        matchMode: String,
        displayId: Int,
    ): String {
        val deadline = SystemClock.elapsedRealtime() + maxOf(0, timeoutMs)
        val exact = matchMode.trim().lowercase() == "exact"
        var found = false
        while (!found && SystemClock.elapsedRealtime() < deadline) {
            val dump = safeDump(displayId)
            found = if (exact) {
                dump.lines().any { it.trim().equals(text, ignoreCase = true) }
            } else {
                dump.contains(text, ignoreCase = true)
            }
            if (!found) {
                try {
                    Thread.sleep(200)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        return JSONObject()
            .put("ok", found)
            .put("found", found)
            .put("text", text)
            .put("match", matchMode)
            .put("timed_out", !found)
            .toString()
    }

    override fun waitForPackage(packageName: String, timeoutMs: Int, displayId: Int): String {
        val deadline = SystemClock.elapsedRealtime() + maxOf(0, timeoutMs)
        var found = false
        var observed = ""
        while (!found && SystemClock.elapsedRealtime() < deadline) {
            observed = try {
                DshaAccessibilityService.currentPackage() ?: ""
            } catch (throwable: Throwable) {
                ""
            }
            found = observed == packageName
            if (!found) {
                try {
                    Thread.sleep(200)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        return JSONObject()
            .put("ok", found)
            .put("found", found)
            .put("package_name", packageName)
            .put("current_package", observed)
            .put("timed_out", !found)
            .toString()
    }

    // ---------- 剪贴板 / 系统面板 ----------

    override fun clipboardSet(context: Context, text: String): String = try {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            unsupported("CLIPBOARD_UNAVAILABLE", "系统无 ClipboardManager")
        } else {
            manager.setPrimaryClip(ClipData.newPlainText("eta", text))
            JSONObject().put("ok", true).toString()
        }
    } catch (throwable: Throwable) {
        JSONObject()
            .put("ok", false)
            .put("error", "CLIPBOARD_SET_FAILED")
            .put("detail", "${throwable.javaClass.simpleName}: ${throwable.message}")
            .toString()
    }

    override fun clipboardGet(context: Context): String = try {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = manager?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        JSONObject().put("ok", true).put("text", text).toString()
    } catch (throwable: Throwable) {
        JSONObject()
            .put("ok", false)
            .put("error", "CLIPBOARD_GET_FAILED")
            .put("detail", "${throwable.javaClass.simpleName}: ${throwable.message}")
            .toString()
    }

    override fun pasteText(text: String, displayId: Int): String {
        physicalOnly(displayId)?.let { return it }
        val written = clipboardSet(appContext, text)
        if (!JSONObject(written).optBoolean("ok", false)) return written
        return normalize(safeCall { DshaAccessibilityService.uiKey("paste") })
    }

    override fun openSystemPanel(panel: String): String {
        val key = when (panel.trim().lowercase()) {
            "notifications", "notification" -> "notifications"
            "quick_settings", "quicksettings", "quick" -> "quicksettings"
            "settings" -> "settings"
            "lock" -> "lock"
            else -> return unsupported("INVALID_PANEL", "panel=$panel")
        }
        return normalize(safeCall { DshaAccessibilityService.uiKey(key) })
    }

    override fun close() {
        // 无宿主资源，无需释放。
    }

    // ---------- 内部工具 ----------

    private fun isVirtual(displayId: Int): Boolean = displayId > Display.DEFAULT_DISPLAY

    private fun physicalOnly(displayId: Int): String? = if (isVirtual(displayId)) {
        unsupported(
            "VIRTUAL_DISPLAY_UNSUPPORTED",
            "第一版只支持物理主屏 displayId=0，收到 displayId=$displayId",
        )
    } else {
        null
    }

    private inline fun safeCall(action: () -> String): String = try {
        action()
    } catch (throwable: Throwable) {
        "[ERR] HOST_CALL_THREW ${throwable.javaClass.simpleName}: ${throwable.message}"
    }

    /** DSHA 回执可能是 JSON，也可能是 [ERR] 文本；统一成合法 JSON，避免工具层解析异常。 */
    private fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            return try {
                JSONArray(trimmed)
                trimmed
            } catch (throwable: Throwable) {
                JSONObject().put("ok", false).put("error", "REPLY_PARSE_FAILED").put("raw", trimmed).toString()
            }
        }
        if (trimmed.startsWith("{")) {
            return try {
                val json = JSONObject(trimmed)
                if (!json.has("ok")) {
                    json.put("ok", json.optString("state").equals("COMPLETED", ignoreCase = true))
                }
                json.toString()
            } catch (throwable: Throwable) {
                JSONObject().put("ok", false).put("error", "REPLY_PARSE_FAILED").put("raw", trimmed).toString()
            }
        }
        val code = when {
            trimmed.contains("EXPLICIT_SCREEN_REQUEST_REQUIRED") -> "SCREEN_BRIDGE_UNAVAILABLE"
            trimmed.contains("FRAME_NOT_OBSERVED") -> "FRAME_NOT_OBSERVED"
            trimmed.startsWith("[ERR]") -> "DSHA_SCREEN_ERROR"
            else -> "DSHA_SCREEN_RESULT"
        }
        return JSONObject()
            .put("ok", false)
            .put("error", code)
            .put("raw", trimmed)
            .toString()
    }

    private fun unsupported(code: String, detail: String): String = JSONObject()
        .put("ok", false)
        .put("error", code)
        .put("detail", detail)
        .toString()

    @Suppress("DEPRECATION")
    private fun screenSize(displayId: Int): Pair<Int, Int> {
        val target = if (isVirtual(displayId)) displayId else Display.DEFAULT_DISPLAY
        return try {
            val manager = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = manager?.getDisplay(target)
            if (display != null) {
                val metrics = DisplayMetrics()
                display.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return metrics.widthPixels to metrics.heightPixels
                }
            }
            windowBounds()
        } catch (throwable: Throwable) {
            windowBounds()
        }
    }

    private fun windowBounds(): Pair<Int, Int> = try {
        val manager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val bounds = manager?.currentWindowMetrics?.bounds
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            bounds.width() to bounds.height()
        } else {
            1080 to 1920
        }
    } catch (throwable: Throwable) {
        1080 to 1920
    }
}
