package com.deepseekharness.app.eta

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.deepseekharness.app.HttpShellService
import com.deepseekharness.app.etatools.device.CoordinateSpace
import com.deepseekharness.app.etatools.device.DeviceController
import com.deepseekharness.app.etatools.device.ElementObservation
import com.deepseekharness.app.etatools.device.ElementSource
import com.deepseekharness.app.etatools.device.Observation
import com.deepseekharness.app.etatools.device.UiNode
import com.deepseekharness.app.etatools.model.ModelImage
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Eta [DeviceController] 的 DSHA 宿主实现：全部屏幕动作走本进程 3090 桥
 * （[HttpShellService] 的 `/app/ui/*` 物理屏端点与 `/app/vscreen/*` 虚拟屏端点），
 * 复用 DSHA 自身的 X-Token、任务围栏、识屏模式与授权确认链，不另开 shell、不绕过策略。
 *
 * 任务身份：3090 桥对每条 `/app/ui/*` 请求强制要求真实 `taskSessionId` / `turnId`
 * （缺失回 `SCREEN_TASK_IDENTITY_REQUIRED`）。DeviceController 接口没有任务上下文字段，
 * 因此由宿主在任务开始时调用 [bindPhoneTask] 注入，并在结束时调用 [endPhoneTask]。
 * 本类不臆造会话身份；未绑定时所有需要屏幕授权的方法返回明确的结构化错误。
 */
class DshaDeviceController(context: Context) : DeviceController {

    private val appContext: Context = context.applicationContext

    // ---- 任务上下文（由宿主注入，不用接口方法，故不修改 etatools 契约） ----
    @Volatile
    private var taskSessionId: String? = null

    @Volatile
    private var turnId: String? = null

    /** 最近一次观察的语义索引：UiNode 契约不含 nodeId/supportedActions，故在 adapter 内旁路保存。 */
    @Volatile
    private var semanticIndex: SemanticIndex? = null

    /** 虚拟屏最近一次 see 的凭据（coreInstance / generation / frameSeq）。 */
    @Volatile
    private var vscreenIdentity: VscreenIdentity? = null

    // ------------------------------------------------------------------
    // 公开的宿主侧生命周期（不属于 DeviceController 接口）
    // ------------------------------------------------------------------

    /** 绑定当前手机任务的真实身份；必须在任何屏幕动作前调用。传 null 表示解绑。 */
    fun bindPhoneTask(sessionId: String?, turnId: String?) {
        this.taskSessionId = sessionId?.takeIf { it.isNotBlank() }
        this.turnId = turnId?.takeIf { it.isNotBlank() }
    }

    fun hasPhoneTaskBinding(): Boolean =
        !taskSessionId.isNullOrBlank() && !turnId.isNullOrBlank()

    /** 建立任务围栏（桥要求动作前先 begin；只在绑定后调用）。 */
    fun beginPhoneTask(): String = bridgeUi("task-begin", emptyMap())

    /** 收尾任务围栏；不依赖识屏模式，任何时候都可调用。 */
    fun endPhoneTask(): String = bridgeUi("task-end", emptyMap())

    /** 双击补充能力：DeviceController 无对应方法，桥支持 gesture=double_tap。 */
    fun doubleTap(x: Int, y: Int, displayId: Int): String = coordinateAction(
        "tap",
        mapOf("x" to x.toString(), "y" to y.toString(), "gesture" to "double_tap"),
        displayId,
    )

    /** 双击语义节点补充能力：先聚焦节点再在中心双击。 */
    fun doubleTapElement(observation: ElementObservation, index: Int): String {
        val node = observation.nodes.getOrNull(index)
            ?: return errorResult("ELEMENT_INDEX_OUT_OF_RANGE", "index=$index").toString()
        if (node.bounds.width() <= 0 || node.bounds.height() <= 0)
            return errorResult("ELEMENT_NO_TOUCH_AREA", "index=$index").toString()
        return doubleTap(node.centerX, node.centerY, Display.DEFAULT_DISPLAY)
    }

    // ------------------------------------------------------------------
    // DeviceController：观察
    // ------------------------------------------------------------------

    override fun observe(
        includeScreenshot: Boolean,
        includeUiTree: Boolean,
        maxNodes: Int,
        displayId: Int,
    ): Observation {
        val (screenW, screenH) = screenSize(displayId)
        if (isVirtual(displayId)) {
            return observeVirtual(includeScreenshot, includeUiTree, maxNodes, screenW, screenH)
        }
        val dumpRaw = bridgeUi("dump", emptyMap())
        val content = asObject(dumpRaw)
        val dump = content
        val elements = parseElements(dump, includeUiTree, maxNodes)
        var image: ModelImage? = null
        var coordW = screenW
        var coordH = screenH
        if (includeScreenshot) {
            // 桥的 screenshot 支持 format=mcp，直接回 base64 PNG（path 格式只回磁盘路径）。
            val shotRaw = bridgeUi("screenshot", mapOf("format" to "mcp"))
            val shot = asObject(shotRaw)
            if (shot.optBoolean("ok", false)) {
                val base64 = shot.optString("data", "")
                if (base64.isNotEmpty()) {
                    val bytes = try {
                        Base64.decode(base64, Base64.NO_WRAP)
                    } catch (_: Throwable) {
                        ByteArray(0)
                    }
                    val w = shot.optInt("width", screenW)
                    val h = shot.optInt("height", screenH)
                    image = ModelImage(
                        reference = "data:image/png;base64,$base64",
                        mimeType = shot.optString("mimeType", "image/png"),
                        bytes = bytes.size,
                        width = w,
                        height = h,
                        source = "dsha-3090-screenshot",
                        preserveOriginal = true,
                    )
                    coordW = w
                    coordH = h
                    content.put(
                        "screenshot",
                        JSONObject()
                            .put("mimeType", shot.optString("mimeType", "image/png"))
                            .put("width", w)
                            .put("height", h)
                            .put("coordinatesAvailable", shot.optBoolean("coordinatesAvailable", false))
                            .put("observationId", shot.optString("observationId", "")),
                    )
                } else {
                    content.put("screenshotUnavailable", "SCREENSHOT_DATA_EMPTY")
                }
            } else {
                content.put(
                    "screenshotUnavailable",
                    shot.optString("error", shot.optString("code", "SCREENSHOT_FAILED")),
                )
            }
        }
        // 3090 坐标动作使用「最近一次截图的图像空间」坐标；dump 的 bounds 是屏幕像素。
        // 全屏截图时两者 1:1，这里用截图自身尺寸做恒等映射，避免错误缩放。
        val space = CoordinateSpace(coordW, coordH, coordW, coordH)
        return Observation(content.toString(), image, elements, space)
    }

    private fun observeVirtual(
        includeScreenshot: Boolean,
        includeUiTree: Boolean,
        maxNodes: Int,
        screenW: Int,
        screenH: Int,
    ): Observation {
        val statusRaw = bridgeVscreen("status", emptyMap())
        val status = asObject(statusRaw)
        val core = status.optString("coreInstance", "")
        if (!status.optBoolean("ok", false) || core.isEmpty()) {
            val content = status.put("tool", "observe_screen").put("display", "virtual")
            return Observation(content.toString(), null, null, null)
        }
        val seeRaw = bridgeVscreen("see", mapOf("coreInstance" to core))
        val see = asObject(seeRaw)
        val generation = see.optString("generation", status.optString("generation", ""))
        val frameSeq = see.optLong("frameSeq", status.optLong("frameSeq", -1L))
        if (see.optBoolean("ok", false) && generation.isNotEmpty() && frameSeq >= 0) {
            vscreenIdentity = VscreenIdentity(
                coreInstance = core,
                generation = generation,
                frameSeq = frameSeq,
                width = see.optInt("width", status.optInt("width", screenW)),
                height = see.optInt("height", status.optInt("height", screenH)),
            )
        }
        val content = JSONObject()
            .put("tool", "observe_screen")
            .put("ok", see.optBoolean("ok", false))
            .put("display", "virtual")
            .put("displayId", status.optInt("displayId", -1))
            .put("coreInstance", core)
            .put("generation", generation)
            .put("frameSeq", frameSeq)
            .put("width", see.optInt("width", status.optInt("width", screenW)))
            .put("height", see.optInt("height", status.optInt("height", screenH)))
            .put("package", status.optString("package", ""))
        if (!see.optBoolean("ok", false)) {
            content.put("error", see.optString("error", see.optString("code", "VSCREEN_SEE_FAILED")))
        }
        var image: ModelImage? = null
        val previewB64 = see.optString("previewB64", "")
        if (includeScreenshot && previewB64.isNotEmpty()) {
            val bytes = try {
                Base64.decode(previewB64, Base64.NO_WRAP)
            } catch (_: Throwable) {
                ByteArray(0)
            }
            image = ModelImage(
                reference = "data:image/jpeg;base64,$previewB64",
                mimeType = "image/jpeg",
                bytes = bytes.size,
                width = see.optInt("previewWidth"),
                height = see.optInt("previewHeight"),
                source = "dsha-3090-vscreen-see",
                preserveOriginal = true,
            )
            content.put("previewWidth", see.optInt("previewWidth"))
                .put("previewHeight", see.optInt("previewHeight"))
        }
        var elements: ElementObservation? = null
        if (includeUiTree) {
            val treeRaw = bridgeVscreen("tree", mapOf("coreInstance" to core))
            val tree = asObject(treeRaw)
            elements = parseElements(tree, true, maxNodes)
            content.put("nodes", tree.optJSONArray("nodes") ?: JSONArray())
            content.put("tree", tree)
        }
        val coordW = see.optInt("previewWidth", see.optInt("width", screenW))
        val coordH = see.optInt("previewHeight", see.optInt("height", screenH))
        return Observation(
            content.toString(),
            image,
            elements,
            CoordinateSpace(coordW, coordH, coordW, coordH),
        )
    }

    /** dump/tree 的 nodes[] → ElementObservation；完整映射 UiNode 字段，缺字段用默认值。 */
    private fun parseElements(json: JSONObject, includeUiTree: Boolean, maxNodes: Int): ElementObservation? {
        val all = json.optJSONArray("nodes")
        val target = json.optJSONObject("target")
        val packageName = target?.optString("packageName", "") ?: json.optString("package", "")
        val windowId = target?.optInt("windowId", -1) ?: -1
        val treeId = json.optString("treeId", "")
        val nodes = if (includeUiTree && all != null) toUiNodes(all, packageName, maxNodes) else emptyList()
        val entries = if (includeUiTree && all != null) toSemanticEntries(all, maxNodes) else emptyList()
        if (includeUiTree) {
            semanticIndex = SemanticIndex(
                observationId = treeId.ifEmpty { "dsha-${SystemClock.elapsedRealtime()}" },
                treeId = treeId,
                entries = entries,
            )
        }
        val id = treeId.ifEmpty { "dsha-tree-${SystemClock.elapsedRealtime()}" }
        return ElementObservation(
            id = id,
            source = ElementSource.ACCESSIBILITY,
            packageName = packageName,
            windowId = windowId.takeIf { it >= 0 },
            nodes = nodes,
            maxNodes = maxNodes,
            truncated = json.optBoolean("truncated", false) || (all?.length() ?: 0) > nodes.size,
            treeSignature = treeId,
        )
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
        val actions = actionNames(item)
        val label = item.optString("text", "")
        return UiNode(
            index = index,
            text = label,
            desc = item.optString("description", item.optString("desc", "")),
            className = item.optString("className", item.optString("class", "")),
            packageName = item.optString("packageName", packageName),
            viewId = item.optString("viewId", item.optString("resourceId", "")),
            bounds = bounds,
            clickable = item.optBoolean("clickable", actions.contains("click")),
            longClickable = item.optBoolean(
                "longClickable",
                actions.any { it.contains("long") },
            ),
            scrollable = item.optBoolean("scrollable", false),
            focused = item.optBoolean("focused", false),
            editable = item.optBoolean("editable", actions.contains("set_text")),
            password = item.optBoolean("password", label == "[PASSWORD]"),
            enabled = item.optBoolean("enabled", true),
            checked = if (item.has("checked")) item.optBoolean("checked", false) else null,
            selected = item.optBoolean("selected", false),
            hint = item.optString("hint", ""),
        )
    }

    private fun toSemanticEntries(array: JSONArray, maxNodes: Int): List<SemanticEntry> {
        val limit = maxOf(0, maxNodes)
        val result = ArrayList<SemanticEntry>(minOf(array.length(), limit))
        for (index in 0 until minOf(array.length(), limit)) {
            val item = array.optJSONObject(index) ?: continue
            result += SemanticEntry(
                nodeId = item.optString("nodeId", ""),
                actions = actionNames(item),
            )
        }
        return result
    }

    private fun actionNames(item: JSONObject): Set<String> {
        val result = HashSet<String>()
        item.optJSONArray("supportedActions")?.let { actions ->
            for (i in 0 until actions.length()) {
                actions.optString(i).lowercase(Locale.ROOT).let { if (it.isNotBlank()) result += it }
            }
        }
        return result
    }

    // ------------------------------------------------------------------
    // DeviceController：坐标 / 元素动作
    // ------------------------------------------------------------------

    override fun screenDimensions(displayId: Int): Pair<Int, Int> = screenSize(displayId)

    override fun tap(x: Int, y: Int, displayId: Int): String = coordinateAction(
        "tap",
        mapOf("x" to x.toString(), "y" to y.toString()),
        displayId,
    )

    override fun tapElement(observation: ElementObservation, index: Int): String {
        val node = observation.nodes.getOrNull(index)
            ?: return errorResult("ELEMENT_INDEX_OUT_OF_RANGE", "index=$index").toString()
        if (node.bounds.width() <= 0 || node.bounds.height() <= 0)
            return errorResult("ELEMENT_NO_TOUCH_AREA", "index=$index").toString()
        // 文字点按不依赖坐标与截图凭据，控件位置变化后仍稳定；桥对同名文字会自行拒绝歧义。
        if (node.text.isNotBlank() && node.text != "[PASSWORD]") {
            val byText = bridgeUi("tap", mapOf("text" to node.text))
            if (asObject(byText).optBoolean("ok", false)) return byText
            // 文字点按失败不静默吞掉：若桥明确拒绝歧义/未找到，回退到节点中心坐标点按。
            val code = asObject(byText).optString("error", asObject(byText).optString("code", ""))
            if (code !in setOf("NODE_NOT_FOUND", "SCREEN_TEXT_AMBIGUOUS", "AMBIGUOUS_TEXT"))
                return byText
        }
        return tap(node.centerX, node.centerY, Display.DEFAULT_DISPLAY)
    }

    override fun longPress(x: Int, y: Int, durationMs: Int, displayId: Int): String {
        if (isVirtual(displayId)) {
            return errorResult(
                "VSCREEN_GESTURE_UNSUPPORTED",
                "虚拟屏 /app/vscreen/* 未提供 long_press；DOWN 后 observation 立即失效，无法跨请求完成长按手势（物理屏支持）",
            ).toString()
        }
        return coordinateAction(
            "tap",
            mapOf(
                "x" to x.toString(),
                "y" to y.toString(),
                "gesture" to "long_press",
                "ms" to durationMs.coerceIn(500, 5000).toString(),
            ),
            displayId,
        )
    }

    override fun longPressElement(
        observation: ElementObservation,
        index: Int,
        durationMs: Int,
    ): String {
        val node = observation.nodes.getOrNull(index)
            ?: return errorResult("ELEMENT_INDEX_OUT_OF_RANGE", "index=$index").toString()
        if (node.bounds.width() <= 0 || node.bounds.height() <= 0)
            return errorResult("ELEMENT_NO_TOUCH_AREA", "index=$index").toString()
        return longPress(node.centerX, node.centerY, durationMs, Display.DEFAULT_DISPLAY)
    }

    override fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
        displayId: Int,
    ): String {
        if (isVirtual(displayId)) {
            return vscreenAction(
                "swipe",
                mapOf(
                    "x1" to x1.toString(), "y1" to y1.toString(),
                    "x2" to x2.toString(), "y2" to y2.toString(),
                    "ms" to durationMs.coerceIn(0, 3000).toString(),
                ),
            )
        }
        return coordinateAction(
            "swipe",
            mapOf(
                "x1" to x1.toString(), "y1" to y1.toString(),
                "x2" to x2.toString(), "y2" to y2.toString(),
                "ms" to durationMs.coerceIn(50, 5000).toString(),
            ),
            displayId,
        )
    }

    override fun scroll(direction: String, amount: String, displayId: Int): String {
        val (width, height) = screenSize(displayId)
        val centerX = width / 2
        val centerY = height / 2
        val distance = when (amount.trim().lowercase(Locale.ROOT)) {
            "small" -> maxOf(1, height / 8)
            "large" -> maxOf(1, height * 2 / 5)
            else -> maxOf(1, height / 4)
        }
        val (fromX, fromY, toX, toY) = gestureEnds(direction, centerX, centerY, distance)
            ?: return errorResult("INVALID_DIRECTION", "direction=$direction").toString()
        return swipe(fromX, fromY, toX, toY, 300, displayId)
    }

    override fun scrollElement(
        observation: ElementObservation,
        index: Int,
        direction: String,
        amount: String,
    ): String {
        val node = observation.nodes.getOrNull(index)
            ?: return errorResult("ELEMENT_INDEX_OUT_OF_RANGE", "index=$index").toString()
        val dir = direction.trim().lowercase(Locale.ROOT)
        // 语义滚动优先：用观察时保存的 treeId/nodeId 与桥声明支持的 action。
        val action = when (dir) {
            "down", "forward", "next" -> "scroll_forward"
            "up", "backward", "previous" -> "scroll_backward"
            else -> null
        }
        val entry = semanticIndex?.takeIf { it.observationId == observation.id || it.treeId == observation.id }
            ?.entries?.getOrNull(index)
        if (action != null && entry != null && entry.nodeId.isNotEmpty() && entry.actions.contains(action)) {
            return bridgeUi(
                "node",
                mapOf("treeId" to semanticIndex!!.treeId, "nodeId" to entry.nodeId, "action" to action),
                observeAfter = true,
            )
        }
        if (node.bounds.width() <= 0 || node.bounds.height() <= 0)
            return errorResult("ELEMENT_NO_TOUCH_AREA", "index=$index").toString()
        val distance = when (amount.trim().lowercase(Locale.ROOT)) {
            "small" -> maxOf(1, node.bounds.height() / 4)
            "large" -> maxOf(1, node.bounds.height() * 3 / 4)
            else -> maxOf(1, node.bounds.height() / 2)
        }
        val (fromX, fromY, toX, toY) = gestureEnds(direction, node.centerX, node.centerY, distance)
            ?: return errorResult("INVALID_DIRECTION", "direction=$direction").toString()
        return swipe(fromX, fromY, toX, toY, 300, Display.DEFAULT_DISPLAY)
    }

    // ------------------------------------------------------------------
    // DeviceController：文本 / 按键 / 剪贴板
    // ------------------------------------------------------------------

    override fun inputText(text: String, displayId: Int): String {
        if (isVirtual(displayId)) {
            return vscreenAction("type", mapOf("text" to text))
        }
        return bridgeUi("input", mapOf("text" to text), observeAfter = true)
    }

    override fun replaceText(
        text: String,
        index: Int?,
        observation: ElementObservation?,
        displayId: Int,
    ): String {
        if (!isVirtual(displayId) && index != null && observation != null) {
            val entry = semanticIndex?.takeIf {
                it.observationId == observation.id || it.treeId == observation.id
            }?.entries?.getOrNull(index)
            if (entry != null && entry.nodeId.isNotEmpty() && entry.actions.contains("set_text")) {
                return bridgeUi(
                    "node",
                    mapOf(
                        "treeId" to semanticIndex!!.treeId,
                        "nodeId" to entry.nodeId,
                        "action" to "set_text",
                        "text" to text,
                    ),
                    observeAfter = true,
                )
            }
        }
        // ACTION_SET_TEXT 本身就是整体替换；先聚焦目标框再写入，等价于「清空后输入」。
        if (index != null && observation != null) {
            val focused = tapElement(observation, index)
            if (!asObject(focused).optBoolean("ok", false)) return focused
        }
        return inputText(text, displayId)
    }

    override fun clearText(index: Int?, observation: ElementObservation?, displayId: Int): String =
        replaceText("", index, observation, displayId)

    override fun pressKey(button: String, displayId: Int): String {
        val name = button.trim().lowercase(Locale.ROOT)
        if (name.isEmpty()) return errorResult("INVALID_BUTTON", "button 为空").toString()
        // 桥 /app/ui/key 仅支持 6 个全局动作；虚拟屏 /app/vscreen/key 支持 4 个 keycode。
        val physicalKey = when (name) {
            "back" -> "back"
            "home" -> "home"
            "recents", "recent" -> "recents"
            "notifications", "notification" -> "notifications"
            "quicksettings", "quick_settings", "quick" -> "quicksettings"
            "lock" -> "lock"
            else -> null
        }
        if (!isVirtual(displayId) && physicalKey != null) {
            return bridgeUi("key", mapOf("name" to physicalKey), observeAfter = true)
        }
        if (isVirtual(displayId)) {
            val code = when (name) {
                "back" -> 4
                "home" -> 3
                "enter" -> 66
                "delete" -> 67
                else -> -1
            }
            if (code > 0) return vscreenAction("key", mapOf("keycode" to code.toString()))
        }
        // ENTER：物理屏只有实验性特权端点能注入 keycode=66，走真实授权链尝试，不伪造成功。
        if (!isVirtual(displayId) && name in setOf("enter", "return")) {
            return pressPrivilegedKey(66)
        }
        // PASTE：桥无 paste 按键，剪贴板 + ACTION_SET_TEXT 是语义等价实现。
        if (name == "paste") {
            val clip = clipboardGet(appContext)
            val text = runCatching { JSONObject(clip).optString("text", "") }.getOrDefault("")
            return inputText(text, displayId)
        }
        return errorResult(
            "KEY_UNSUPPORTED_BY_3090_BRIDGE",
            "button=$button；物理屏 /app/ui/key 仅支持 back/home/recents/notifications/quicksettings/lock，" +
                "ENTER 走 /app/priv/key（需开启特权执行），PASTE 走剪贴板 + 输入",
        ).toString()
    }

    /** 物理屏 ENTER：/app/priv/key 需要先取帧凭据；任一步被拒绝都如实回传，绝不自动重放。 */
    private fun pressPrivilegedKey(keycode: Int): String {
        val frameRaw = bridgePriv("frame", emptyMap())
        val frame = asObject(frameRaw)
        if (!frame.optBoolean("ok", false))
            return errorResult(
                "PRIVILEGED_KEY_UNAVAILABLE",
                frame.optString("error", frame.optString("code", "PRIVILEGED_EXECUTION_DISABLED")),
                frameRaw,
            ).toString()
        val params = mutableMapOf(
            "keycode" to keycode.toString(),
            "frameSeq" to frame.optLong("frameSeq", -1L).toString(),
            "observationId" to frame.optString("observationId", ""),
            "generation" to frame.optString("generation", ""),
        )
        return bridgePriv("key", params)
    }

    override fun waitMs(durationMs: Int): String {
        val waited = maxOf(0, durationMs)
        return try {
            Thread.sleep(waited.toLong())
            JSONObject().put("ok", true).put("waited_ms", waited).toString()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            errorResult("INTERRUPTED", "waited_ms=$waited").toString()
        }
    }

    override fun waitForText(
        text: String,
        timeoutMs: Int,
        includeDesc: Boolean,
        matchMode: String,
        displayId: Int,
    ): String {
        val deadline = SystemClock.elapsedRealtime() + maxOf(0, timeoutMs)
        val mode = matchMode.trim().lowercase(Locale.ROOT)
        var found = false
        var last = ""
        while (!found && SystemClock.elapsedRealtime() < deadline) {
            val raw = if (isVirtual(displayId)) {
                bridgeVscreen("tree", vscreenIdentity?.coreInstance?.let {
                    mapOf("coreInstance" to it)
                } ?: emptyMap())
            } else {
                bridgeUi("dump", emptyMap())
            }
            last = raw
            val json = asObject(raw)
            val haystack = ArrayList<String>()
            json.optString("text", "").let { if (it.isNotBlank()) haystack += it }
            json.optJSONArray("nodes")?.let { nodes ->
                for (i in 0 until nodes.length()) {
                    val node = nodes.optJSONObject(i) ?: continue
                    haystack += node.optString("text", "")
                    if (includeDesc) haystack += node.optString("description", "")
                }
            }
            found = haystack.any { line -> matches(line, text, mode) }
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
            .put("last", if (found) JSONObject.NULL else last.take(2000))
            .toString()
    }

    private fun matches(line: String, query: String, mode: String): Boolean = when (mode) {
        "exact" -> line.trim().equals(query, ignoreCase = true)
        "regex" -> try {
            Regex(query, RegexOption.IGNORE_CASE).containsMatchIn(line)
        } catch (_: Throwable) {
            false
        }
        else -> line.contains(query, ignoreCase = true)
    }

    override fun waitForPackage(packageName: String, timeoutMs: Int, displayId: Int): String {
        val deadline = SystemClock.elapsedRealtime() + maxOf(0, timeoutMs)
        var found = false
        var observed = ""
        while (!found && SystemClock.elapsedRealtime() < deadline) {
            val raw = if (isVirtual(displayId)) {
                bridgeVscreen("status", emptyMap())
            } else {
                bridgeUi("dump", emptyMap())
            }
            val json = asObject(raw)
            observed = json.optJSONObject("target")?.optString("packageName", "")
                ?.takeIf { it.isNotBlank() }
                ?: json.optString("package", "")
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

    override fun clipboardSet(context: Context, text: String): String = try {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            errorResult("CLIPBOARD_UNAVAILABLE", "系统无 ClipboardManager").toString()
        } else {
            manager.setPrimaryClip(ClipData.newPlainText("dsha-eta", text))
            JSONObject().put("ok", true).toString()
        }
    } catch (throwable: Throwable) {
        errorResult("CLIPBOARD_SET_FAILED", "${throwable.javaClass.simpleName}: ${throwable.message}").toString()
    }

    override fun clipboardGet(context: Context): String = try {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = manager?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        JSONObject().put("ok", true).put("text", text).toString()
    } catch (throwable: Throwable) {
        errorResult("CLIPBOARD_GET_FAILED", "${throwable.javaClass.simpleName}: ${throwable.message}").toString()
    }

    override fun pasteText(text: String, displayId: Int): String {
        val written = clipboardSet(appContext, text)
        if (!asObject(written).optBoolean("ok", false)) return written
        // 桥无 paste 键；写入剪贴板后用 ACTION_SET_TEXT 落到当前焦点，语义等价。
        return inputText(text, displayId)
    }

    override fun openSystemPanel(panel: String): String {
        val key = when (panel.trim().lowercase(Locale.ROOT)) {
            "notifications", "notification" -> "notifications"
            "quick_settings", "quicksettings", "quick" -> "quicksettings"
            "lock" -> "lock"
            else -> return errorResult("INVALID_PANEL", "panel=$panel；桥仅支持 notifications/quicksettings/lock").toString()
        }
        return bridgeUi("key", mapOf("name" to key), observeAfter = true)
    }

    override fun close() {
        taskSessionId = null
        turnId = null
        semanticIndex = null
        vscreenIdentity = null
    }

    // ------------------------------------------------------------------
    // 3090 桥传输
    // ------------------------------------------------------------------

    /** 每条 /app/ui/* 都强制带任务身份；动作默认带 observe=1 拿动作后新树。 */
    private fun bridgeUi(
        operation: String,
        params: Map<String, String>,
        observeAfter: Boolean = false,
    ): String {
        val sid = taskSessionId
        val tid = turnId
        if (sid.isNullOrBlank() || tid.isNullOrBlank()) {
            return errorResult(
                "PHONE_TASK_CONTEXT_NOT_BOUND",
                "宿主必须先调用 DshaDeviceController.bindPhoneTask(sessionId, turnId) 并 beginPhoneTask()；" +
                    "3090 桥拒绝臆造的会话身份",
            ).toString()
        }
        val all = LinkedHashMap<String, String>(params)
        all["taskSessionId"] = sid
        all["turnId"] = tid
        if (observeAfter && operation in OBSERVING_ACTIONS) all["observe"] = "1"
        return httpGet("/app/ui/$operation", all)
    }

    /** 虚拟屏端点用 coreInstance/generation/frameSeq 凭据，不签发也不接受像素 observationId。 */
    private fun bridgeVscreen(operation: String, params: Map<String, String>): String =
        httpGet("/app/vscreen/$operation", params)

    /** 实验性特权屏端点同样强制任务身份。 */
    private fun bridgePriv(operation: String, params: Map<String, String>): String {
        val sid = taskSessionId
        val tid = turnId
        if (sid.isNullOrBlank() || tid.isNullOrBlank())
            return errorResult("PHONE_TASK_CONTEXT_NOT_BOUND", "请先 bindPhoneTask").toString()
        val all = LinkedHashMap<String, String>(params)
        all["taskSessionId"] = sid
        all["turnId"] = tid
        return httpGet("/app/priv/$operation", all)
    }

    /** 坐标动作统一流程：先截图取一次性 observationId，再发坐标手势。 */
    private fun coordinateAction(
        operation: String,
        params: Map<String, String>,
        displayId: Int,
    ): String {
        val shotRaw = bridgeUi("screenshot", mapOf("format" to "path"))
        val shot = asObject(shotRaw)
        if (!shot.optBoolean("ok", false)) {
            return errorResult(
                shot.optString("error", shot.optString("code", "SCREENSHOT_FAILED")),
                "坐标触控需要先截图取凭据",
                shotRaw,
            ).toString()
        }
        if (!shot.optBoolean("coordinatesAvailable", false)) {
            return errorResult(
                "COORDINATES_UNAVAILABLE",
                shot.optString("reason", "目标窗口几何不可核验或被遮挡，桥未签发坐标凭据"),
                shotRaw,
            ).toString()
        }
        val observationId = shot.optString("observationId", "")
        if (observationId.isEmpty())
            return errorResult("OBSERVATION_ID_MISSING", "截图未返回 observationId", shotRaw).toString()
        val all = LinkedHashMap<String, String>(params)
        all["observationId"] = observationId
        return bridgeUi(operation, all, observeAfter = true)
    }

    /** 虚拟屏动作每次重新 see 取最新帧（输入凭据一次性、绑定 generation/frameSeq）。 */
    private fun vscreenAction(operation: String, params: Map<String, String>): String {
        val status = asObject(bridgeVscreen("status", emptyMap()))
        val core = status.optString("coreInstance", "")
        if (core.isEmpty())
            return errorResult(
                status.optString("error", "VSCREEN_NOT_CREATED"),
                "虚拟屏未创建或当前不是虚拟识屏模式",
                status.toString(),
            ).toString()
        val see = asObject(bridgeVscreen("see", mapOf("coreInstance" to core)))
        if (!see.optBoolean("ok", false))
            return errorResult(
                see.optString("error", see.optString("code", "VSCREEN_SEE_FAILED")),
                see.optString("nextAction", "重新观察虚拟屏后再动作"),
                see.toString(),
            ).toString()
        val generation = see.optString("generation", status.optString("generation", ""))
        val frameSeq = see.optLong("frameSeq", status.optLong("frameSeq", -1L))
        if (generation.isEmpty() || frameSeq < 0)
            return errorResult("VSCREEN_CREDENTIAL_MISSING", "see 未返回 generation/frameSeq", see.toString()).toString()
        vscreenIdentity = VscreenIdentity(core, generation, frameSeq, see.optInt("width"), see.optInt("height"))
        val all = LinkedHashMap<String, String>(params)
        all["coreInstance"] = core
        all["generation"] = generation
        if (operation != "node") all["frameSeq"] = frameSeq.toString()
        return bridgeVscreen(operation, all)
    }

    private fun httpGet(path: String, params: Map<String, String>): String {
        val port = HttpShellService.port()
        if (port <= 0) return errorResult("BRIDGE_NOT_READY", "HttpShellService.port()=0，3090 桥未监听").toString()
        val token = HttpShellService.currentToken()
        if (token.isEmpty()) return errorResult("BRIDGE_TOKEN_UNAVAILABLE", "currentToken() 为空，桥身份不可用").toString()
        val query = buildString {
            var first = true
            for ((key, value) in params) {
                if (!first) append('&')
                first = false
                append(encode(key)).append('=').append(encode(value))
            }
        }
        val url = URL("http://127.0.0.1:$port$path${if (query.isEmpty()) "" else "?$query"}")
        var connection: HttpURLConnection? = null
        return try {
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("X-Token", token)
            connection.connectTimeout = 10_000
            connection.readTimeout = 40_000
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    val buffer = StringBuilder()
                    var line: String?
                    while (true) {
                        line = reader.readLine() ?: break
                        if (buffer.isNotEmpty()) buffer.append('\n')
                        buffer.append(line)
                    }
                    buffer.toString()
                }
            } ?: ""
            if (code !in 200..299 && text.isBlank())
                errorResult("BRIDGE_HTTP_$code", "path=$path").toString()
            else
                text
        } catch (throwable: Throwable) {
            errorResult(
                "BRIDGE_CALL_THREW",
                "${throwable.javaClass.simpleName}: ${throwable.message} path=$path",
            ).toString()
        } finally {
            connection?.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** 桥回执可能是 JSON、OK 或 [ERR] 文本，统一成合法 JSON，避免工具层解析异常。 */
    private fun asObject(raw: String): JSONObject {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            return try {
                val json = JSONObject(trimmed)
                if (!json.has("ok")) {
                    json.put(
                        "ok",
                        json.optString("state").equals("COMPLETED", ignoreCase = true) ||
                            (json.optString("status").equals("ok", ignoreCase = true)),
                    )
                }
                json
            } catch (_: Throwable) {
                errorResult("BRIDGE_REPLY_PARSE_FAILED", trimmed.take(2000))
            }
        }
        if (trimmed.startsWith("[")) {
            return try {
                val array = JSONArray(trimmed)
                JSONObject().put("ok", true).put("nodes", array)
            } catch (_: Throwable) {
                errorResult("BRIDGE_REPLY_PARSE_FAILED", trimmed.take(2000))
            }
        }
        if (trimmed.equals("OK", ignoreCase = true)) return JSONObject().put("ok", true).put("raw", "OK")
        val code = when {
            trimmed.startsWith("[ERR]") -> trimmed.removePrefix("[ERR]").trim().substringBefore(' ')
            else -> "BRIDGE_TEXT_REPLY"
        }
        return errorResult(code, trimmed.take(2000))
    }

    private fun errorResult(code: String, detail: String, raw: String? = null): JSONObject =
        JSONObject().put("ok", false).put("error", code).put("detail", detail).apply {
            if (raw != null) put("raw", raw.take(4000))
        }

    private fun isVirtual(displayId: Int): Boolean = displayId > Display.DEFAULT_DISPLAY

    private fun gestureEnds(
        direction: String,
        centerX: Int,
        centerY: Int,
        distance: Int,
    ): Quad? = when (direction.trim().lowercase(Locale.ROOT)) {
        "up", "backward", "previous" -> Quad(centerX, centerY + distance, centerX, centerY - distance)
        "down", "forward", "next" -> Quad(centerX, centerY - distance, centerX, centerY + distance)
        "left" -> Quad(centerX + distance, centerY, centerX - distance, centerY)
        "right" -> Quad(centerX - distance, centerY, centerX + distance, centerY)
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun screenSize(displayId: Int): Pair<Int, Int> {
        val target = if (isVirtual(displayId)) displayId else Display.DEFAULT_DISPLAY
        return try {
            val manager = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = manager?.getDisplay(target)
            if (display != null) {
                val metrics = DisplayMetrics()
                display.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0)
                    return metrics.widthPixels to metrics.heightPixels
            }
            vscreenIdentity?.let { if (it.width > 0 && it.height > 0) return it.width to it.height }
            windowBounds()
        } catch (_: Throwable) {
            windowBounds()
        }
    }

    private fun windowBounds(): Pair<Int, Int> = try {
        val manager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val bounds = manager?.currentWindowMetrics?.bounds
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0)
            bounds.width() to bounds.height()
        else
            1080 to 1920
    } catch (_: Throwable) {
        1080 to 1920
    }

    private data class Quad(val x1: Int, val y1: Int, val x2: Int, val y2: Int)
    private data class SemanticEntry(val nodeId: String, val actions: Set<String>)
    private data class SemanticIndex(
        val observationId: String,
        val treeId: String,
        val entries: List<SemanticEntry>,
    )
    private data class VscreenIdentity(
        val coreInstance: String,
        val generation: String,
        val frameSeq: Long,
        val width: Int,
        val height: Int,
    )

    companion object {
        private val OBSERVING_ACTIONS = setOf(
            "tap", "input", "key", "swipe", "node", "tapText",
        )
    }
}
