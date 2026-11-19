package com.deepseekharness.app.etatools.tool

import android.content.Context
import android.graphics.Rect
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.Display
import com.deepseekharness.app.etatools.core.AgentLogger
import com.deepseekharness.app.etatools.core.AgentRunCancelledException
import com.deepseekharness.app.etatools.context.PersonalSearchTools
import com.deepseekharness.app.etatools.device.BoundedRootCommandExecutor
import com.deepseekharness.app.etatools.device.CoordinateSpace
import com.deepseekharness.app.etatools.device.DeviceControlUnavailableException
import com.deepseekharness.app.etatools.device.DeviceController
import com.deepseekharness.app.etatools.device.ElementObservation
import com.deepseekharness.app.etatools.device.HostGuiBridge
import com.deepseekharness.app.etatools.device.HostHaptic
import com.deepseekharness.app.etatools.device.Observation
import com.deepseekharness.app.etatools.device.RootAccess
import com.deepseekharness.app.etatools.memory.AgentMemoryException
import com.deepseekharness.app.etatools.memory.AgentMemoryMutation
import com.deepseekharness.app.etatools.memory.AgentMemoryRepository
import com.deepseekharness.app.etatools.memory.AgentMemoryWriteResult
import com.deepseekharness.app.etatools.model.AgentFileToolCatalog
import com.deepseekharness.app.etatools.model.AgentPhoneToolCatalog
import com.deepseekharness.app.etatools.model.AgentScreenObservationContract
import com.deepseekharness.app.etatools.model.AgentSensitiveToolPolicy
import com.deepseekharness.app.etatools.model.AgentTerminalToolCatalog
import com.deepseekharness.app.etatools.model.ToolCall
import com.deepseekharness.app.etatools.model.ToolExecutor
import com.deepseekharness.app.etatools.model.ToolResult
import com.deepseekharness.app.etatools.skill.GitHubSkillInspection
import com.deepseekharness.app.etatools.skill.GitHubSkillRepository
import com.deepseekharness.app.etatools.skill.GitHubSkillRepositoryParser
import com.deepseekharness.app.etatools.skill.GitHubSkillSourceException
import com.deepseekharness.app.etatools.skill.PublicGitHubSkillSource
import com.deepseekharness.app.etatools.skill.SkillCompatibilityChecker
import com.deepseekharness.app.etatools.skill.SkillIndexService
import com.deepseekharness.app.etatools.skill.SkillInstallErrorCode
import com.deepseekharness.app.etatools.skill.SkillInstallResult
import com.deepseekharness.app.etatools.skill.SkillLoader
import com.deepseekharness.app.etatools.skill.SkillPackageInstaller
import com.deepseekharness.app.etatools.skill.SkillParser
import com.deepseekharness.app.etatools.skill.SkillResourceReadResult
import com.deepseekharness.app.etatools.skill.SkillResourceReader
import com.deepseekharness.app.etatools.terminal.TerminalController
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking

/**
 * Eta 工具执行器在 DSHA :etatools 中的宿主无关版本。
 *
 * GUI / 终端后端通过 [DeviceController] / [TerminalController] 注入；launch_app、present_to_user、
 * open_uri、browser_use、web_search、fetch_url 等宿主特有工具通过可空 lambda 注入，未注入时返回
 * UNSUPPORTED_TOOL。when 分发、参数解析、结果格式与自动补观察逻辑与 Eta 原版保持一致。
 */
internal class AgentLocalTools(
    private val context: Context,
    private val logger: AgentLogger,
    /** GUI / 设备动作后端，由宿主实现后注入。 */
    private val deviceController: DeviceController,
    /** 终端 / 文件工具后端，由宿主实现后注入。 */
    private val terminalController: TerminalController,
    private val terminalToolsEnabled: () -> Boolean = { true },
    private val deviceDirectToolsEnabled: () -> Boolean = { true },
    private val deviceSensitiveReadToolsEnabled: () -> Boolean = { true },
    private val deviceSensitiveActionToolsEnabled: () -> Boolean = { true },
    private val memoryToolsEnabled: () -> Boolean = {
        runBlocking { AgentMemoryRepository.isEnabled() }
    },
    private val memoryWritable: Boolean = true,
    /** 宿主截图排除名单；当前实现由 [DeviceController] 内部消费，保留参数以兼容既有调用点。 */
    private val screenshotExcludedPackages: () -> Set<String> = { emptySet() },
    private val screenObservationProvider: (
        (AgentScreenObservationContract.Options) -> Observation
    )? = null,
    private val beforeToolExecution: (String) -> ToolExecutionDecision = {
        ToolExecutionDecision.Allow
    },
    private val skillIndexService: SkillIndexService? = null,
    private val skillLoader: SkillLoader? = null,
    private val skillResourceReader: SkillResourceReader? = null,
    private val githubSkillSource: PublicGitHubSkillSource? = null,
    private val skillPackageInstaller: SkillPackageInstaller? = null,
    runAvailableSkillIds: Set<String> = emptySet(),
    pendingSkillConflict: PendingSkillConflictCapability? = null,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
    private val isToolEnabled: (String) -> Boolean = { true },
    /** 等价于原宿主 GUI 控制器的 release()；runtime 侧的控制器类型未纳入 etatools。 */
    private val guiRelease: (() -> Unit)? = null,
    /** 画布路由、手势视觉反馈与 generation 包裹的宿主桥；缺省按物理主屏 / no-op 处理。 */
    private val hostGui: HostGuiBridge? = null,
    /** 宿主应用动作：launch_app / present_to_user / open_uri。返回 null 表示宿主未实现。 */
    private val hostAppAction: ((String, JSONObject) -> String?)? = null,
    /** 宿主浏览器动作：browser_use。返回 null 表示宿主未实现。 */
    private val browserAction: ((String, JSONObject) -> String?)? = null,
    /** 宿主网页动作：web_search / fetch_url。返回 null 表示宿主未实现。 */
    private val webAction: ((String, JSONObject) -> String?)? = null,
    /** 网页类工具的总开关，沿用原 BROWSER_TOOLS_DISABLED 语义。 */
    private val browserToolsEnabled: () -> Boolean = { true },
) : ToolExecutor, AutoCloseable {

    private val closed = AtomicBoolean(false)
    @Volatile
    private var activeDisplayId: Int = Display.DEFAULT_DISPLAY
    private val rootCommandExecutor = BoundedRootCommandExecutor(logger, rootAvailable = rootAvailable)
    private val structuredDeviceTools = AgentStructuredDeviceTools(
        context = context,
        logger = logger,
        root = rootCommandExecutor,
        rootAvailable = rootAvailable,
        foregroundAction = ::executeForegroundIntent,
    )
    private val imageTools = AgentImageTools(context, rootCommandExecutor, rootAvailable)
    private val publishedObservation = AtomicReference(PublishedObservation())
    private val runAvailableSkillIds = runAvailableSkillIds
        .mapTo(mutableSetOf(), SkillParser::normalizeSkillLookup)
    private val mutatedSkillIds = ConcurrentHashMap.newKeySet<String>()
    private val skillTreeMutationUncertain = AtomicBoolean(false)
    private val pendingSkillConflict = AtomicReference(pendingSkillConflict)
    private val inspectedGitHubSnapshots =
        ConcurrentHashMap<String, GitHubInspectionSnapshot>()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        publishedObservation.set(PublishedObservation())
        terminalController.interruptAll()
        deviceController.close()
        rootCommandExecutor.close()
        githubSkillSource?.close()
        inspectedGitHubSnapshots.clear()
        hostGui?.close()
    }

    override fun execute(toolCall: ToolCall): ToolResult {
        if (closed.get()) throw AgentRunCancelledException()
        val gui = hostGui
        return if (gui != null && AgentToolRequirements.requiresGuiControl(toolCall.name)) {
            gui.withAction { generation -> executeLocal(toolCall, generation) }
        } else {
            executeLocal(toolCall, null)
        }
    }

    private fun executeLocal(
        toolCall: ToolCall,
        guiGeneration: Long?,
    ): ToolResult =
        runCatching {
            if (closed.get()) throw AgentRunCancelledException()
            val args = JSONObject(toolCall.argumentsJson.ifBlank { "{}" })
            if (AgentToolRequirements.find(toolCall.name) != null &&
                AgentToolRequirements.rootDenied(toolCall.name, args, rootAvailable())
            ) {
                return@runCatching textResult(errorResult("ROOT_REQUIRED", "此操作需要 Root 授权，本次未执行"))
            }
            if (!isToolEnabled(toolCall.name)) {
                return@runCatching ToolResult(
                    content = errorResult("TOOL_DISABLED", "工具 ${toolCall.name} 已在工具列表中关闭，本次未执行"),
                    sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
                )
            }
            deviceToolPermissionError(toolCall.name)?.let { return@runCatching it }
            memoryToolPermissionError(toolCall.name)?.let { return@runCatching it }
            if (guiGeneration != null && publishedObservation.get().generation != guiGeneration) {
                publishedObservation.set(PublishedObservation())
                if (AgentToolRequirements.requiresFreshObservation(toolCall.name)) {
                    return@runCatching textResult(errorResult(
                        "NO_OBSERVATION", "手机操作权已变化，请先调用 observe_screen 重新观察，再操作当前页面",
                    ))
                }
            }
            val preparation = if (AgentToolRequirements.hasForegroundIntentFallback(toolCall.name)) {
                ToolExecutionDecision.Allow
            } else {
                beforeToolExecution(toolCall.name)
            }
            when (val decision = preparation) {
                ToolExecutionDecision.Allow -> Unit
                is ToolExecutionDecision.Reject -> {
                    if (decision.code.startsWith("ACCESSIBILITY_")) publishedObservation.set(PublishedObservation())
                    return@runCatching textResult(
                        errorResult(
                            code = decision.code,
                            message = decision.message,
                        ),
                    )
                }
            }
            when (toolCall.name) {
                "get_current_context" -> textResult(DeviceContextTool.current(context))
                "search_apps" -> textResult(searchApps(args))
                "launch_app" -> {
                    publishedObservation.set(PublishedObservation())
                    textResult(hostAppAction?.invoke(toolCall.name, args) ?: unsupportedTool(toolCall.name))
                }
                "present_to_user" -> {
                    publishedObservation.set(PublishedObservation())
                    textResult(hostAppAction?.invoke(toolCall.name, args) ?: unsupportedTool(toolCall.name))
                }
                "open_uri" -> {
                    publishedObservation.set(PublishedObservation())
                    textResult(hostAppAction?.invoke(toolCall.name, args) ?: unsupportedTool(toolCall.name))
                }
                "browser_use" -> textResult(
                    browserAction?.invoke(toolCall.name, args) ?: unsupportedTool(toolCall.name)
                )
                "web_search", "fetch_url" -> {
                    if (!browserToolsEnabled()) textResult(errorResult("BROWSER_TOOLS_DISABLED", "请先启用网页搜索、读取与浏览器工具"))
                    else textResult(webAction?.invoke(toolCall.name, args) ?: unsupportedTool(toolCall.name))
                }
                "observe_screen" -> observeScreen(args, guiGeneration)
                "release_gui" -> {
                    publishedObservation.set(PublishedObservation())
                    guiRelease?.invoke()
                    textResult(JSONObject().put("ok", true).put("tool", "release_gui").toString())
                }
                "tap" -> afterAction(tap(args))
                "tap_area" -> afterAction(tapArea(args))
                "tap_element" -> afterAction(tapElement(args))
                "long_press" -> afterAction(longPress(args))
                "long_press_element" -> afterAction(longPressElement(args))
                "swipe" -> afterAction(swipe(args))
                "scroll" -> afterAction(deviceController.scroll(args.optString("direction"), args.optString("amount"), currentActiveDisplayId()))
                "scroll_element" -> afterAction(scrollElement(args))
                "type_text" -> afterAction(typeText(args))
                // 旧会话或旧入口仍可能请求这些工具名；保留执行路径，但不再出现在模型目录里。
                "input_text" -> afterAction(inputText(args))
                "replace_text" -> afterAction(replaceText(args))
                "clear_text" -> afterAction(clearText(args))
                "set_clipboard" -> textResult(setClipboard(args))
                "get_clipboard" -> textResult(getClipboard())
                "paste_text" -> afterAction(pasteText(args))
                "press_key" -> afterAction(deviceController.pressKey(args.optString("button"), currentActiveDisplayId()))
                "wait" -> textResult(deviceController.waitMs(args.optInt("duration_ms", 1_000)))
                "wait_for_text" -> textResult(waitForText(args))
                "wait_for_package" -> textResult(waitForPackage(args))
                "open_system_panel" -> {
                    publishedObservation.set(PublishedObservation())
                    activeDisplayId = Display.DEFAULT_DISPLAY
                    textResult(deviceController.openSystemPanel(args.optString("panel")))
                }
                in DEVICE_TOOL_NAMES ->
                    structuredDeviceTools.execute(toolCall.name, args)
                        ?: textResult(errorResult("UNKNOWN_TOOL", "未知设备工具"))
                "read_image" -> fileVisionTool { imageTools.readImage(args) }
                in AgentTerminalToolCatalog.names -> textResult(terminalTool {
                    if (toolCall.name == "terminal" && args.optString("action").equals("check_root", ignoreCase = true)) checkRoot()
                    else terminalController.executeTool(toolCall.name, args)
                })
                in AgentFileToolCatalog.names -> textResult(terminalTool { terminalController.fileTool(toolCall.name, args) })
                "memory_get" -> textResult(memoryGet(args))
                "memory_write" -> textResult(memoryWrite(args))
                "skills_list" -> textResult(skillsList(args))
                "skills_read" -> textResult(skillsRead(args))
                "skills_read_resource" -> textResult(skillsReadResource(args))
                "skills_list_curated" -> textResult(skillsListCurated())
                "skills_inspect_github" -> textResult(skillsInspectGitHub(args))
                "skills_install_from_github" -> textResult(skillsInstallFromGitHub(args))
                else -> textResult(
                    errorResult(
                        code = "UNKNOWN_TOOL",
                        message = "未知工具：${toolCall.name}"
                    )
                )
            }
        }.getOrElse { throwable ->
            if (throwable is AgentRunCancelledException) throw throwable
            textResult(
                errorResult(
                    code = when (throwable) {
                        is InvalidToolArgumentException, is org.json.JSONException -> "INVALID_ARGUMENT"
                        is DeviceControlUnavailableException -> "ACCESSIBILITY_UNAVAILABLE"
                        is ActiveDisplayUnavailableException -> "DISPLAY_UNAVAILABLE"
                        else -> "TOOL_ERROR"
                    },
                    message = throwable.message ?: throwable.javaClass.simpleName
                )
            )
        }.let { result ->
            if (result.sensitive || !AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
                result
            } else {
                result.copy(sensitive = true)
            }
        }

    private fun executeForegroundIntent(toolName: String, action: () -> JSONObject): JSONObject {
        fun execute(): JSONObject {
            if (closed.get()) throw AgentRunCancelledException()
            if (!isToolEnabled(toolName)) {
                return JSONObject(errorResult("TOOL_DISABLED", "工具 $toolName 已在工具列表中关闭，本次未执行"))
            }
            deviceToolPermissionError(toolName)?.let { return JSONObject(it.content) }
            return when (val decision = beforeToolExecution(toolName)) {
                ToolExecutionDecision.Allow -> {
                    publishedObservation.set(PublishedObservation())
                    action()
                }
                is ToolExecutionDecision.Reject -> JSONObject(errorResult(decision.code, decision.message))
            }
        }
        val gui = hostGui ?: return execute()
        return gui.withAction { execute() }
    }

    private fun deviceToolPermissionError(
        toolName: String,
    ): ToolResult? {
        val error = when {
            toolName in DEVICE_DIRECT_TOOL_NAMES && !deviceDirectToolsEnabled() ->
                "DEVICE_DIRECT_TOOLS_DISABLED" to "请先启用设备直达工具"
            toolName in DEVICE_SENSITIVE_READ_TOOL_NAMES && !deviceSensitiveReadToolsEnabled() ->
                "DEVICE_SENSITIVE_READ_TOOLS_DISABLED" to "请先允许读取敏感设备信息"
            toolName in DEVICE_SENSITIVE_ACTION_TOOL_NAMES && !deviceSensitiveActionToolsEnabled() ->
                "DEVICE_SENSITIVE_ACTION_TOOLS_DISABLED" to "请先允许敏感设备操作"
            else -> null
        } ?: return null
        return ToolResult(
            content = errorResult(error.first, error.second),
            sensitive = toolName in DEVICE_SENSITIVE_READ_TOOL_NAMES ||
                toolName in DEVICE_SENSITIVE_ACTION_TOOL_NAMES,
        )
    }

    private fun terminalTool(block: () -> String): String {
        if (!terminalToolsEnabled()) {
            return errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具")
        }
        return block()
    }

    private fun fileVisionTool(block: () -> ToolResult): ToolResult {
        if (!terminalToolsEnabled()) {
            return textResult(errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具"))
        }
        return block()
    }

    private fun memoryToolPermissionError(toolName: String): ToolResult? {
        if (toolName == "memory_write" && !memoryWritable) {
            return ToolResult(
                content = errorResult("REAL_MEMORY_READ_ONLY", "角色会话的现实记忆只读；剧情请使用角色记忆工具"),
                sensitive = true,
            )
        }
        if (toolName !in MEMORY_TOOL_NAMES || memoryToolsEnabled()) return null
        return ToolResult(
            content = errorResult("MEMORY_DISABLED", "记忆已在设置中关闭"),
            sensitive = true,
        )
    }

    private fun memoryGet(args: JSONObject): String = try {
        val result = AgentMemoryRepository.read(
            query = args.optString("query").takeIf(String::isNotBlank),
            startLine = args.optInt("start_line", 1),
            maxChars = args.optInt("max_chars", 12_000),
        )
        JSONObject()
            .put("ok", true)
            .put("revision", result.snapshot.revision)
            .put("bytes", result.snapshot.byteSize)
            .put("line_count", result.snapshot.lineCount)
            .put("start_line", result.startLine ?: JSONObject.NULL)
            .put("end_line", result.endLine ?: JSONObject.NULL)
            .put("matched_lines", result.matchedLines)
            .put("has_more", result.hasMore)
            .put("content", result.content)
            .toString()
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆读取失败")
    }

    private fun memoryWrite(args: JSONObject): String = try {
        val revision = args.getString("revision")
        val mutation = when (args.getString("mode")) {
            "replace_range" -> AgentMemoryMutation.ReplaceRange(
                revision = revision,
                startLine = args.getInt("start_line"),
                endLine = args.getInt("end_line"),
                content = args.requiredText("content"),
            )
            "append" -> AgentMemoryMutation.Append(
                revision = revision,
                content = args.requiredText("content"),
            )
            "clear" -> AgentMemoryMutation.Clear(revision)
            else -> error("不支持的记忆写入模式")
        }
        when (val result = AgentMemoryRepository.mutate(mutation)) {
            is AgentMemoryWriteResult.Success -> JSONObject()
                .put("ok", true)
                .put("revision", result.snapshot.revision)
                .put("bytes", result.snapshot.byteSize)
                .put("line_count", result.snapshot.lineCount)
                .toString()
            is AgentMemoryWriteResult.Conflict -> JSONObject()
                .put("ok", false)
                .put("code", "MEMORY_CONFLICT")
                .put("message", "记忆已发生变化，请先调用 memory_get 获取最新内容")
                .put("revision", result.snapshot.revision)
                .put("bytes", result.snapshot.byteSize)
                .put("line_count", result.snapshot.lineCount)
                .toString()
        }
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆写入失败")
    }

    private fun currentActiveDisplayId(): Int {
        val id = activeDisplayId
        val gui = hostGui ?: return Display.DEFAULT_DISPLAY
        if (!gui.isVirtualDisplay(id)) return Display.DEFAULT_DISPLAY
        return if (gui.isVirtualDisplayActive(id)) {
            id
        } else {
            publishedObservation.set(PublishedObservation())
            throw ActiveDisplayUnavailableException()
        }
    }

    private fun checkCancellation() {
        if (closed.get() || Thread.currentThread().isInterrupted) throw AgentRunCancelledException()
    }

    private fun publishObservation(observation: PublishedObservation) {
        checkCancellation()
        publishedObservation.set(observation)
        if (closed.get() || Thread.currentThread().isInterrupted) {
            publishedObservation.compareAndSet(observation, PublishedObservation())
            throw AgentRunCancelledException()
        }
    }

    private fun observeScreen(args: JSONObject, generation: Long?): ToolResult {
        publishedObservation.set(PublishedObservation())
        val startedAt = SystemClock.elapsedRealtime()
        val options = AgentScreenObservationContract.resolve(args)
        val displayId = currentActiveDisplayId()
        val observation = screenObservationProvider?.invoke(options)
            ?: deviceController.observe(
                includeScreenshot = options.includeScreenshot,
                includeUiTree = options.includeUiTree,
                maxNodes = options.maxNodes,
                displayId = displayId,
            )
        publishObservation(
            PublishedObservation(
                elements = observation.elementObservation,
                coordinateSpace = observation.coordinateSpace,
                generation = generation.takeIf { JSONObject(observation.content).optBoolean("ok") },
            ),
        )
        logger.debug {
            "Agent local tool action=observe_screen outcome=completed " +
                "display=$displayId observation=${observation.elementObservation?.id} " +
                "nodes=${observation.elementObservation?.nodes?.size ?: 0} " +
                "image=${observation.image?.bytes ?: 0} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} " +
                "coordinate=${observation.coordinateSpace?.summary()}"
        }
        return ToolResult(
            content = observation.content,
            images = listOfNotNull(observation.image)
        )
    }

    /**
     * 成功的 GUI 动作附带一次轻量观察：只读 UI 树、不截图，节点数减半。
     * 模型据此确认动作生效并直接用新的 observation_id 继续操作，不必再单独 observe_screen；
     * 失败或结果未知的动作不附带，保持"先重新观察"的既有约束。
     */
    private fun afterAction(raw: String): ToolResult {
        checkCancellation()
        val result = runCatching { JSONObject(raw) }.getOrNull()
        if (result == null || !result.optBoolean("ok")) return textResult(raw)
        val before = publishedObservation.get().elements
        val after = runCatching {
            val displayId = currentActiveDisplayId()
            screenObservationProvider?.invoke(AFTER_ACTION_OPTIONS)
                ?: deviceController.observe(
                    includeScreenshot = false,
                    includeUiTree = true,
                    maxNodes = AFTER_ACTION_OPTIONS.maxNodes,
                    displayId = displayId,
                )
        }.getOrElse { throwable ->
            publishedObservation.set(PublishedObservation())
            checkCancellation()
            if (throwable is AgentRunCancelledException) throw throwable
            logger.debug { "Agent local tool after-action observation failed: type=${throwable.javaClass.simpleName}" }
            Observation(
                content = JSONObject()
                    .put("ok", false)
                    .put("ui_tree_status", "query_failed")
                    .put("code", if (throwable is ActiveDisplayUnavailableException) "DISPLAY_UNAVAILABLE" else "OBSERVATION_FAILED")
                    .put("ui_tree_note", "动作已执行，但后续观察失败；旧节点与坐标已失效，请先重新观察，不要重复动作")
                    .toString(),
                image = null,
                coordinateSpace = null,
                elementObservation = null,
            )
        }
        // 新快照取代动作前的快照：旧 index 在界面变化后本就不可靠，继续保留只会让模型误用。
        publishObservation(
            if (after.elementObservation == null) PublishedObservation()
            else publishedObservation.get().copy(elements = after.elementObservation, coordinateSpace = after.coordinateSpace),
        )
        result.put("after", AgentAfterActionSummary.build(before, after))
        return textResult(result.toString())
    }

    private fun tap(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val point = convertPoint(
            x = args.getInt("x"),
            y = args.getInt("y"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        return clearOverlay(displayId, point) {
            if (hostGui?.isVirtualDisplay(displayId) != true) {
                hostGui?.performHaptic(HostHaptic.TAP)
            }
            showTap(point.x, point.y, displayId)
            deviceController.tap(point.x, point.y, displayId)
        }
    }

    private fun tapArea(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val x1 = args.getInt("x1")
        val y1 = args.getInt("y1")
        val x2 = args.getInt("x2")
        val y2 = args.getInt("y2")
        val coordinateSpace = args.optString("coordinate_space")
        val first = convertPoint(x1, y1, coordinateSpace, displayId)
        val second = convertPoint(x2, y2, coordinateSpace, displayId)
        val point = ScreenPoint(
            x = ((first.x.toLong() + second.x.toLong()) / 2L).toInt(),
            y = ((first.y.toLong() + second.y.toLong()) / 2L).toInt(),
        )
        return clearOverlay(displayId, point) {
            if (hostGui?.isVirtualDisplay(displayId) != true) {
                hostGui?.performHaptic(HostHaptic.TAP)
            }
            showTap(point.x, point.y, displayId)
            deviceController.tap(point.x, point.y, displayId)
        }
    }

    private fun tapElement(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val node = observation.nodes.firstOrNull { it.index == index }
        if (node == null) {
            return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        }
        val result = clearOverlay(displayId, ScreenPoint(node.centerX, node.centerY)) {
            deviceController.tapElement(observation, index)
        }
        if (result.isOkJson()) {
            if (hostGui?.isVirtualDisplay(displayId) != true) {
                hostGui?.performHaptic(HostHaptic.TAP)
            }
            showTap(node.centerX, node.centerY, displayId)
        }
        return result
    }

    private fun longPressElement(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val node = observation.nodes.firstOrNull { it.index == index }
        val durationMs = args.optInt("duration_ms", 800)
        if (node == null) {
            return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        }
        val result = clearOverlay(displayId, ScreenPoint(node.centerX, node.centerY)) {
            deviceController.longPressElement(observation, index, durationMs)
        }
        if (result.isOkJson()) {
            if (hostGui?.isVirtualDisplay(displayId) != true) {
                hostGui?.performHaptic(HostHaptic.LONG_PRESS)
            }
            showLongPress(node.centerX, node.centerY, durationMs, displayId)
        }
        return result
    }

    private fun longPress(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val point = convertPoint(
            x = args.getInt("x"),
            y = args.getInt("y"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        val durationMs = args.optInt("duration_ms", 800)
        return clearOverlay(displayId, point) {
            if (hostGui?.isVirtualDisplay(displayId) != true) {
                hostGui?.performHaptic(HostHaptic.LONG_PRESS)
            }
            showLongPress(point.x, point.y, durationMs, displayId)
            deviceController.longPress(point.x, point.y, durationMs, displayId)
        }
    }

    private fun swipe(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val start = convertPoint(
            x = args.getInt("x1"),
            y = args.getInt("y1"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        val end = convertPoint(
            x = args.getInt("x2"),
            y = args.getInt("y2"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        val durationMs = args.optInt("duration_ms", 500)
        return clearOverlay(displayId, start, end) {
            if (hostGui?.isVirtualDisplay(displayId) != true) {
                hostGui?.performHaptic(HostHaptic.SWIPE)
            }
            showSwipe(start.x, start.y, end.x, end.y, durationMs, displayId)
            deviceController.swipe(
                start.x,
                start.y,
                end.x,
                end.y,
                durationMs,
                displayId,
            )
        }
    }

    private fun scrollElement(args: JSONObject): String {
        val observation = requireElementObservation(args) ?: return observationError(args)
        return deviceController.scrollElement(
            observation = observation,
            index = args.optInt("index", -1),
            direction = args.optString("direction"),
            amount = args.optString("amount"),
        )
    }

    /**
     * 统一的文本输入：replace（默认）整体替换为 text，text 为空即清空；append 在光标处插入。
     * 指定 index 时先确认节点来自最近一次观察；不指定时作用于当前输入焦点。
     * submit=true 在写入成功后按输入法回车，用于搜索、发送等提交动作。
     */
    private fun typeText(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val text = args.requiredText("text")
        val mode = args.optString("mode", "replace").trim().lowercase(Locale.ROOT).ifBlank { "replace" }
        val written = when (mode) {
            "replace" -> replaceText(args)
            "append" -> {
                if (text.isEmpty()) return errorResult("INVALID_ARGUMENT", "append 模式的 text 不能为空")
                if (args.optNullableInt("index") != null) {
                    return errorResult("INVALID_ARGUMENT", "append 只作用于当前输入焦点；要写入指定输入框请用 mode=replace")
                }
                // 长文本走选区插入加粘贴回退，比逐字键入更稳定。
                if (text.length > TYPE_TEXT_INCREMENTAL_CHARS) pasteText(args) else deviceController.inputText(text, displayId)
            }
            else -> return errorResult("INVALID_ARGUMENT", "mode 只能是 replace 或 append")
        }
        val json = runCatching { JSONObject(written) }.getOrNull() ?: return written
        json.put("tool", "type_text").put("mode", mode)
        if (!json.optBoolean("ok") || !args.optBoolean("submit")) return json.toString()
        val submitted = runCatching { JSONObject(deviceController.pressKey("ENTER", displayId)) }.getOrNull()
        json.put("submitted", submitted?.optBoolean("ok") == true)
        if (submitted?.optBoolean("ok") != true) {
            // 文本已写入但提交失败：整体仍算成功，避免模型重复输入；由 submitted=false 提示单独补按回车或点按钮。
            json.put("submit_error", submitted?.optString("code").orEmpty().ifBlank { "SUBMIT_FAILED" })
        }
        return json.toString()
    }

    private fun inputText(args: JSONObject): String {
        val text = args.requiredText("text")
        if (text.length > 1_000) {
            return errorResult("TEXT_TOO_LONG", "input_text 最多支持 1000 个字符")
        }
        return when (args.optString("mode", "append").lowercase(Locale.ROOT)) {
            "replace" -> replaceText(args)
            "paste" -> pasteText(args)
            else -> deviceController.inputText(text, currentActiveDisplayId())
        }
    }

    private fun replaceText(args: JSONObject): String {
        val index = args.optNullableInt("index")
        val observation = if (index != null) {
            requireElementObservation(args) ?: return observationError(args)
        } else {
            null
        }
        return deviceController.replaceText(
            text = args.requiredText("text"),
            index = index,
            observation = observation,
            displayId = currentActiveDisplayId(),
        )
    }

    private fun clearText(args: JSONObject): String {
        val index = args.optNullableInt("index")
        val observation = if (index != null) {
            requireElementObservation(args) ?: return observationError(args)
        } else {
            null
        }
        return deviceController.clearText(index = index, observation = observation, displayId = currentActiveDisplayId())
    }

    private fun setClipboard(args: JSONObject): String =
        deviceController.clipboardSet(requireContext(), args.requiredText("text"))

    private fun getClipboard(): String =
        deviceController.clipboardGet(requireContext())

    private fun pasteText(args: JSONObject): String =
        deviceController.pasteText(args.requiredText("text"), currentActiveDisplayId())

    private fun waitForText(args: JSONObject): String =
        deviceController.waitForText(
            text = args.requiredText("text"),
            timeoutMs = args.optInt("timeout_ms", 10_000),
            includeDesc = args.optBoolean("include_desc", true),
            matchMode = args.optString("match", "contains"),
            displayId = currentActiveDisplayId(),
        )

    private fun waitForPackage(args: JSONObject): String =
        deviceController.waitForPackage(
            packageName = args.optString("package_name"),
            timeoutMs = args.optInt("timeout_ms", 10_000),
            displayId = currentActiveDisplayId(),
        )

    /**
     * 坐标系必须显式声明。旧版按"上一次观察是否带截图"隐式切换默认值，模型无从得知，
     * 经常把截图像素当成屏幕像素；缺省时直接报参数错误，让模型在下一步改正。
     */
    private fun convertPoint(
        x: Int,
        y: Int,
        coordinateSpace: String,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): ScreenPoint {
        val space = publishedObservation.get().coordinateSpace
        val requestedSpace = coordinateSpace.trim().lowercase(Locale.ROOT)
        if (requestedSpace.isBlank()) {
            throw InvalidToolArgumentException(
                "缺少 coordinate_space：看截图定位用 normalized（0–999），坐标来自 ui_nodes 用 screen",
            )
        }
        if (requestedSpace == "normalized") {
            if (x !in 0..NORMALIZED_MAX || y !in 0..NORMALIZED_MAX) {
                throw InvalidToolArgumentException("normalized 坐标必须在 0–$NORMALIZED_MAX 之间：($x,$y)")
            }
            val (width, height) = space?.let { it.screenWidth to it.screenHeight }
                ?: deviceController.screenDimensions(displayId)
            return ScreenPoint(
                x = (x.toLong() * (width - 1) / NORMALIZED_MAX).toInt(),
                y = (y.toLong() * (height - 1) / NORMALIZED_MAX).toInt(),
            )
        }
        if (requestedSpace == "screen") {
            val (width, height) = space?.let { it.screenWidth to it.screenHeight }
                ?: deviceController.screenDimensions(displayId)
            if (x !in 0 until width || y !in 0 until height) {
                throw InvalidToolArgumentException(
                    "屏幕坐标超出范围：($x,$y) not in ${width}x$height",
                )
            }
            return ScreenPoint(x, y)
        }
        if (requestedSpace != "screenshot") {
            throw InvalidToolArgumentException("coordinate_space 只能是 normalized、screen 或 screenshot")
        }
        if (space == null) {
            throw InvalidToolArgumentException(
                "最近一次观察没有附图，没有 screenshot 坐标系；看截图定位请用 normalized，或先带截图重新观察",
            )
        }
        val point = runCatching { space.fromScreenshot(x, y) }
            .getOrElse { throwable ->
                throw InvalidToolArgumentException(
                    throwable.message ?: "截图坐标超出范围",
                )
            }
        return ScreenPoint(point.x, point.y)
    }

    private fun searchApps(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "query 不能为空")
        }
        val includeSystem = args.optBoolean("include_system", false)
        val limit = args.optInt("limit", 10).coerceIn(1, 20)
        val apps = findAppsByName(query, includeSystem).take(limit)
        return JSONObject()
            .put("ok", true)
            .put("tool", "search_apps")
            .put("query", query)
            .put("apps", apps.toJsonArray())
            .toString()
    }

    private fun checkRoot(): String {
        val probe = RootAccess.request(requireContext())
        kotlinx.coroutines.runBlocking {
            while (probe.isActive && !closed.get()) kotlinx.coroutines.delay(100)
        }
        if (closed.get()) return errorResult("CANCELLED", "已取消等待 Root 检测")
        val state = RootAccess.state.value
        return JSONObject().put("ok", true).put("tool", "terminal").put("action", "check_root")
            .put("root_available", state.isGranted).put("status", state.status.name.lowercase(Locale.ROOT))
            .put("checking", state.isChecking).put("su_present", state.suPresent)
            .put("uid", if (state.isGranted) 0 else JSONObject.NULL)
            .put("message", if (state.isGranted) "已确认 Root 授权；后续新命令默认使用 Root" else
                "尚未确认 Root 授权；请根据 status 检查授权或检测结果，默认 user 不表示终端不支持 Root")
            .toString()
    }

    private fun findAppsByName(query: String, includeSystem: Boolean): List<AppInfo> {
        val normalizedQuery = query.normalized()
        return installedLauncherApps()
            .asSequence()
            .filter { includeSystem || !it.isSystemApp }
            .mapNotNull { app ->
                val score = app.matchScore(query, normalizedQuery)
                if (score == Int.MAX_VALUE) null else score to app
            }
            .sortedWith(compareBy<Pair<Int, AppInfo>> { it.first }.thenBy { it.second.appName })
            .map { it.second }
            .toList()
    }

    private fun AppInfo.matchScore(rawQuery: String, normalizedQuery: String): Int {
        val normalizedName = appName.normalized()
        val normalizedPackage = packageName.normalized()
        return when {
            packageName.equals(rawQuery, ignoreCase = true) -> 0
            appName.equals(rawQuery, ignoreCase = true) -> 1
            normalizedName == normalizedQuery -> 2
            normalizedPackage.contains(normalizedQuery) -> 3
            normalizedName.contains(normalizedQuery) -> 4
            else -> Int.MAX_VALUE
        }
    }

    private fun installedLauncherApps(): List<AppInfo> {
        val context = requireContext()
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(0L)
        )
        val apps = linkedMapOf<String, AppInfo>()
        resolveInfos.forEach { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@forEach
            val applicationInfo = activityInfo.applicationInfo ?: return@forEach
            val packageName = applicationInfo.packageName ?: return@forEach
            val appName = resolveInfo.loadLabel(packageManager).toString().trim()
                .takeIf { it.isNotBlank() }
                ?: packageName
            apps.putIfAbsent(
                packageName,
                AppInfo(
                    packageName = packageName,
                    appName = appName,
                    isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                )
            )
        }
        return apps.values.toList()
    }

    private fun requireContext(): Context = context

    private fun List<AppInfo>.toJsonArray(): JSONArray =
        JSONArray().also { array ->
            forEach { app ->
                array.put(
                    JSONObject()
                        .put("app_name", app.appName)
                        .put("package_name", app.packageName)
                        .put("is_system_app", app.isSystemApp)
                )
            }
        }

    private fun String.normalized(): String =
        trim().lowercase(Locale.ROOT)

    private fun String.isOkJson(): Boolean =
        runCatching { JSONObject(this).optBoolean("ok", false) }.getOrDefault(false)

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private fun requireElementObservation(
        args: JSONObject,
    ): ElementObservation? {
        val current = publishedObservation.get().elements ?: return null
        val requestedId = args.optString("observation_id").trim()
        return current.takeIf {
            ObservationReferencePolicy.validate(current.id, requestedId) ==
                ObservationReferencePolicy.Status.MATCH
        }
    }

    private fun observationError(args: JSONObject): String {
        val current = publishedObservation.get().elements
        val requestedId = args.optString("observation_id").trim()
        return when (ObservationReferencePolicy.validate(current?.id, requestedId)) {
            ObservationReferencePolicy.Status.NO_OBSERVATION ->
                errorResult("NO_OBSERVATION", "请先调用 observe_screen 获取 UI 节点")
            ObservationReferencePolicy.Status.ID_REQUIRED -> errorResult(
                "OBSERVATION_ID_REQUIRED",
                "节点动作必须携带同一次 observe_screen 返回的 observation_id",
            )
            ObservationReferencePolicy.Status.STALE -> errorResult(
                "STALE_OBSERVATION",
                "observation_id=$requestedId 已过期；当前为 ${current?.id}，请重新观察屏幕",
            )
            ObservationReferencePolicy.Status.MATCH -> errorResult(
                "OBSERVATION_ERROR",
                "观察快照状态异常，请重新观察屏幕",
            )
        }
    }

    // ==================== Skills tools ====================

    private fun skillsList(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val query = args.optString("query").trim().lowercase()
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val entries = indexService.listInstalledSkills()
            .filter { entry -> SkillCompatibilityChecker.evaluate(entry).available }
            .filter { entry -> isVisibleInCurrentRun(entry.id) }
            .filter { entry ->
                if (query.isBlank()) true
                else listOf(entry.id, entry.name, entry.description, entry.skillFilePath, entry.rootPath)
                    .any { it.lowercase().contains(query) }
            }
            .take(limit)
        val items = JSONArray()
        entries.forEach { entry ->
            val capabilities = JSONArray()
            if (entry.hasScripts) capabilities.put("scripts")
            if (entry.hasReferences) capabilities.put("references")
            if (entry.hasAssets) capabilities.put("assets")
            if (entry.hasEvals) capabilities.put("evals")
            items.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("description", entry.description)
                    .put("enabled", entry.enabled)
                    .put("source", entry.source)
                    .put("rootPath", entry.rootPath)
                    .put("skillFilePath", entry.skillFilePath)
                    .put("capabilities", capabilities)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", entries.size)
            .put("items", items)
            .toString()
    }

    private fun skillsRead(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val loader = skillLoader
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能加载器未初始化")
        val skillId = args.optString("skillId").trim()
        if (skillId.isBlank()) return errorResult("MISSING_PARAM", "缺少 skillId")
        val maxChars = args.optInt("maxChars", 16_000).coerceIn(512, 64_000)
        val entry = indexService.findInstalledSkill(skillId)
            ?: return errorResult("NOT_FOUND", "未找到 skill：$skillId")
        if (!isVisibleInCurrentRun(entry.id)) return nextTurnRequired(entry.id)
        val compat = SkillCompatibilityChecker.evaluate(entry)
        if (!compat.available) return errorResult("INCOMPATIBLE", compat.reason ?: "当前环境不可用")
        val resolved = loader.load(entry, "agent 主动读取 skill")
            ?: return errorResult("READ_FAILED", "读取 SKILL.md 失败：${entry.skillFilePath}")
        val body = if (resolved.bodyMarkdown.length <= maxChars) {
            resolved.bodyMarkdown
        } else {
            resolved.bodyMarkdown.take(maxChars) + "\n..."
        }
        val references = JSONArray()
        resolved.loadedReferences.forEach { references.put(it) }
        val frontmatter = JSONObject()
        resolved.frontmatter.forEach { (k, v) -> frontmatter.put(k, v) }
        return JSONObject()
            .put("ok", true)
            .put("id", entry.id)
            .put("name", entry.name)
            .put("description", entry.description)
            .put("rootPath", entry.rootPath)
            .put("skillFilePath", entry.skillFilePath)
            .put("scriptsDir", resolved.scriptsDir ?: JSONObject.NULL)
            .put("assetsDir", resolved.assetsDir ?: JSONObject.NULL)
            .put("references", references)
            .put("frontmatter", frontmatter)
            .put("bodyMarkdown", body)
            .toString()
    }

    private fun skillsReadResource(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val reader = skillResourceReader
            ?: return errorResult("SKILLS_UNAVAILABLE", "Skill 资源读取器未初始化")
        val skillId = args.getString("skillId").trim()
        val relativePath = args.getString("relativePath").trim()
        val maxChars = args.optInt("maxChars", 16_000).coerceIn(512, 64_000)
        val entry = indexService.findInstalledSkill(skillId)
            ?: return errorResult("NOT_FOUND", "未找到已启用 Skill：$skillId")
        if (!isVisibleInCurrentRun(entry.id)) return nextTurnRequired(entry.id)
        val compatibility = SkillCompatibilityChecker.evaluate(entry)
        if (!compatibility.available) {
            return errorResult(
                "INCOMPATIBLE",
                compatibility.reason ?: "当前环境不可用",
            )
        }
        return when (val result = reader.readText(entry, relativePath)) {
            is SkillResourceReadResult.Success -> {
                val truncated = result.text.length > maxChars
                val visibleText = if (truncated) {
                    result.text.take(maxChars).let { prefix ->
                        if (prefix.lastOrNull()?.isHighSurrogate() == true) {
                            prefix.dropLast(1)
                        } else {
                            prefix
                        }
                    }
                } else {
                    result.text
                }
                JSONObject()
                    .put("ok", true)
                    .put("skillId", entry.id)
                    .put("relativePath", result.relativePath)
                    .put("text", visibleText)
                    .put("truncated", truncated)
                    .put("totalChars", result.text.length)
                    .toString()
            }
            is SkillResourceReadResult.Failure -> errorResult(
                code = result.error.code.name,
                message = result.error.message,
            )
        }
    }

    private fun skillsListCurated(): String {
        val source = githubSkillSource
            ?: return errorResult("SKILL_INSTALLER_UNAVAILABLE", "GitHub Skill 服务未初始化")
        return skillSourceResult {
            val inspection = source.listCurated()
            rememberInspection(
                repository = GitHubSkillRepositoryParser.parse(inspection.repository),
                inspection = inspection,
                rememberDefault = true,
            )
            inspectionResult(inspection)
        }
    }

    private fun skillsInspectGitHub(args: JSONObject): String {
        val source = githubSkillSource
            ?: return errorResult("SKILL_INSTALLER_UNAVAILABLE", "GitHub Skill 服务未初始化")
        return skillSourceResult {
            val repository = GitHubSkillRepositoryParser.resolve(
                repository = args.getString("repository"),
                explicitRef = args.optString("ref").takeIf { args.has("ref") },
                explicitPath = args.optString("path").takeIf { args.has("path") },
            )
            val inspection = source.inspect(repository)
            rememberInspection(
                repository = repository,
                inspection = inspection,
                rememberDefault = repository.ref == null,
            )
            inspectionResult(inspection)
        }
    }

    private fun skillsInstallFromGitHub(args: JSONObject): String {
        val replaceExisting = args.optBoolean("replaceExisting", false)
        return skillSourceResult {
            val requestedRepository = GitHubSkillRepositoryParser.resolve(
                repository = args.getString("repository"),
                explicitRef = args.optString("ref").takeIf { args.has("ref") },
                explicitPath = null,
            )
            val pathsJson = args.getJSONArray("paths")
            val selectedPaths = (0 until pathsJson.length()).map { index ->
                GitHubSkillRepositoryParser.normalizeRelativePath(pathsJson.getString(index))
            }
            if (replaceExisting && selectedPaths.size != 1) {
                return@skillSourceResult errorResult(
                    "SKILL_REPLACE_SCOPE_TOO_BROAD",
                    "一次只能替换一个 Skill 路径；请逐个重试",
                )
            }
            val expectedReplacementId = args.optString("expectedReplacementId").trim()
            val repository = if (replaceExisting) {
                validateReplacementReplay(
                    requestedRepository = requestedRepository,
                    selectedPaths = selectedPaths,
                    expectedReplacementId = expectedReplacementId,
                )?.let { return@skillSourceResult it }
                requestedRepository.copy(ref = pendingSkillConflict.get()!!.commitSha)
            } else {
                val snapshot = inspectedGitHubSnapshots[
                    inspectionKey(requestedRepository.slug, requestedRepository.ref)
                ] ?: return@skillSourceResult errorResult(
                    "SKILL_INSPECTION_REQUIRED",
                    "安装前必须在本轮先检查同一仓库与 ref 的 Skill 候选",
                )
                val invalidSelection = selectedPaths.firstOrNull {
                    it !in snapshot.candidatesByPath
                }
                if (invalidSelection != null) {
                    return@skillSourceResult errorResult(
                        "INVALID_SKILL_SELECTION",
                        "所选路径不在本轮检查返回的候选中：$invalidSelection",
                    )
                }
                val snapshotPrefix = snapshot.prefix
                if (
                    snapshotPrefix != null &&
                    selectedPaths.any {
                        it != snapshotPrefix && !it.startsWith("$snapshotPrefix/")
                    }
                ) {
                    return@skillSourceResult errorResult(
                        "INVALID_SKILL_SELECTION",
                        "所选路径不在本轮检查的目录范围内",
                    )
                }
                requestedRepository.copy(ref = snapshot.commitSha)
            }
            val prefix = requestedRepository.path?.takeUnless { it == "." }
            if (
                prefix != null &&
                selectedPaths.any { it != prefix && !it.startsWith("$prefix/") }
            ) {
                return@skillSourceResult errorResult(
                    "INVALID_SKILL_SELECTION",
                    "所选路径不在 GitHub URL 指定目录内",
                )
            }
            val source = githubSkillSource
                ?: return@skillSourceResult errorResult(
                    "SKILL_INSTALLER_UNAVAILABLE",
                    "GitHub Skill 服务未初始化",
                )
            val installer = skillPackageInstaller
                ?: return@skillSourceResult errorResult(
                    "SKILL_INSTALLER_UNAVAILABLE",
                    "Skill 安装器未初始化",
                )
            source.downloadArchive(repository).use { archive ->
                if (closed.get()) {
                    return@skillSourceResult errorResult(
                        "SKILL_INSTALL_CANCELLED",
                        "Skill 安装已取消，未提交文件",
                    )
                }
                val result = installer.installRepositoryZip(
                    openStream = { archive.file.inputStream() },
                    selectedPaths = selectedPaths,
                    replaceUserSkills = replaceExisting,
                    expectedReplacementIds = if (replaceExisting) {
                        setOf(expectedReplacementId)
                    } else {
                        emptySet()
                    },
                    isCancelled = closed::get,
                )
                installResult(
                    result = result,
                    repository = archive.repository,
                    ref = archive.ref,
                    commitSha = archive.commitSha,
                    selectedPaths = selectedPaths,
                )
            }
        }
    }

    private fun inspectionResult(
        inspection: GitHubSkillInspection,
    ): String {
        val installedIds = skillIndexService
            ?.listSkillsForManagement()
            .orEmpty()
            .filter { it.installed }
            .mapTo(mutableSetOf()) { SkillParser.normalizeSkillLookup(it.id) }
        val items = JSONArray()
        inspection.candidates.forEach { candidate ->
            items.put(
                JSONObject()
                    .put("name", candidate.name)
                    .put("path", candidate.path)
                    .put(
                        "installed",
                        SkillParser.normalizeSkillLookup(candidate.name) in installedIds,
                    ),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("repository", inspection.repository)
            .put("ref", inspection.ref)
            .put("commitSha", inspection.commitSha)
            .put("prefix", inspection.prefix ?: JSONObject.NULL)
            .put("count", inspection.candidates.size)
            .put("items", items)
            .toString()
    }

    private fun rememberInspection(
        repository: GitHubSkillRepository,
        inspection: GitHubSkillInspection,
        rememberDefault: Boolean,
    ) {
        val snapshot = GitHubInspectionSnapshot(
            commitSha = inspection.commitSha,
            prefix = inspection.prefix,
            candidatesByPath = inspection.candidates.associate { it.path to it.name },
        )
        inspectedGitHubSnapshots[inspectionKey(repository.slug, repository.ref)] = snapshot
        inspectedGitHubSnapshots[inspectionKey(repository.slug, inspection.ref)] = snapshot
        inspectedGitHubSnapshots[inspectionKey(repository.slug, inspection.commitSha)] = snapshot
        if (rememberDefault) {
            inspectedGitHubSnapshots[inspectionKey(repository.slug, null)] = snapshot
        }
    }

    private fun inspectionKey(repository: String, ref: String?): String =
        "${repository.lowercase(Locale.ROOT)}@${ref.orEmpty()}"

    private fun validateReplacementReplay(
        requestedRepository: GitHubSkillRepository,
        selectedPaths: List<String>,
        expectedReplacementId: String,
    ): String? {
        val pending = pendingSkillConflict.get() ?: return errorResult(
            "SKILL_REPLACE_CAPABILITY_REQUIRED",
            "没有可供精确重放的 Skill 冲突",
        )
        if (
            !requestedRepository.slug.equals(pending.repository, ignoreCase = true) ||
            requestedRepository.ref != pending.commitSha ||
            selectedPaths.singleOrNull() != pending.selectedPath ||
            expectedReplacementId != pending.expectedReplacementId
        ) {
            return errorResult(
                "SKILL_REPLACE_CAPABILITY_MISMATCH",
                "覆盖参数必须精确重放冲突结果中的仓库、commitSha、路径与 Skill ID",
            )
        }
        return null
    }

    private fun isVisibleInCurrentRun(skillId: String): Boolean {
        val normalized = SkillParser.normalizeSkillLookup(skillId)
        return normalized in runAvailableSkillIds && normalized !in mutatedSkillIds
    }

    private fun nextTurnRequired(skillId: String): String = errorResult(
        "NEXT_TURN_REQUIRED",
        "Skill $skillId 在本轮已安装或变更，将从下一轮对话开始可用",
    )

    private fun installResult(
        result: SkillInstallResult,
        repository: String,
        ref: String,
        commitSha: String,
        selectedPaths: List<String>,
    ): String = when (result) {
        is SkillInstallResult.Success -> {
            pendingSkillConflict.set(null)
            val installed = JSONArray()
            result.installed.forEach { skill ->
                mutatedSkillIds += SkillParser.normalizeSkillLookup(skill.id)
                installed.put(
                    JSONObject()
                        .put("id", skill.id)
                        .put("name", skill.name),
                )
            }
            JSONObject()
                .put("ok", true)
                .put("repository", repository)
                .put("ref", ref)
                .put("commitSha", commitSha)
                .put("selectedPaths", JSONArray(selectedPaths))
                .put("installed", installed)
                .put("available", "next_turn")
                .put("scriptsExecuted", false)
                .put("message", "Skill 已安装并启用，将从下一轮对话开始可用；安装过程未执行脚本")
                .toString()
        }
        is SkillInstallResult.Conflict -> {
            val conflicts = JSONArray()
            result.conflicts.forEach { conflict ->
                conflicts.put(
                    JSONObject()
                        .put("id", conflict.id)
                        .put("name", conflict.name)
                        .put("replaceAllowed", conflict.replaceAllowed),
                )
            }
            pendingSkillConflict.set(
                result.conflicts.singleOrNull()
                    ?.takeIf { it.replaceAllowed && selectedPaths.size == 1 }
                    ?.let { conflict ->
                        PendingSkillConflictCapability(
                            repository = repository,
                            commitSha = commitSha,
                            selectedPath = selectedPaths.single(),
                            expectedReplacementId = conflict.id,
                            expectedReplacementName = conflict.name,
                        )
                    },
            )
            JSONObject()
                .put("ok", false)
                .put("code", "SKILL_CONFLICT")
                .put("message", "Skill 已存在；可替换的单个用户 Skill 可按返回参数直接重试，内置 Skill 不可覆盖")
                .put("repository", repository)
                .put("ref", ref)
                .put("commitSha", commitSha)
                .put("selectedPaths", JSONArray(selectedPaths))
                .put("conflicts", conflicts)
                .toString()
        }
        is SkillInstallResult.Failure -> {
            if (result.error.code == SkillInstallErrorCode.COMMIT_FAILED) {
                skillTreeMutationUncertain.set(true)
            }
            errorResult(
                code = result.error.code.name,
                message = result.error.message,
            )
        }
    }

    private inline fun skillSourceResult(block: () -> String): String = try {
        block()
    } catch (failure: GitHubSkillSourceException) {
        errorResult(failure.code, failure.message ?: "GitHub Skill 请求失败")
    }

    private fun unsupportedTool(name: String): String = errorResult(
        code = "UNSUPPORTED_TOOL",
        message = "工具 $name 的宿主实现未注入，本次未执行",
    )

    private fun errorResult(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private fun textResult(content: String): ToolResult =
        ToolResult(content)

    private data class ScreenPoint(val x: Int, val y: Int)

    private class InvalidToolArgumentException(message: String) : IllegalArgumentException(message)

    private class ActiveDisplayUnavailableException : IllegalStateException(
        "当前虚拟屏已关闭，旧观察已失效；请重新启动目标应用后再观察和操作",
    )

    private data class PublishedObservation(
        val elements: ElementObservation? = null,
        val coordinateSpace: CoordinateSpace? = null,
        val generation: Long? = null,
    )

    private data class GitHubInspectionSnapshot(
        val commitSha: String,
        val prefix: String?,
        val candidatesByPath: Map<String, String>,
    )

        /** 手势经过状态胶囊时让它暂时穿透并淡出；虚拟屏内的手势由宿主桥自行决定。 */
    private fun <T> clearOverlay(displayId: Int, vararg points: ScreenPoint, gesture: () -> T): T {
        val gui = hostGui ?: return gesture()
        if (gui.isVirtualDisplay(displayId)) return gesture()
        val area = Rect(
            points.minOf { it.x },
            points.minOf { it.y },
            points.maxOf { it.x } + 1,
            points.maxOf { it.y } + 1,
        )
        return gui.clearOverlay(area, gesture)
    }

    private fun showTap(x: Int, y: Int, displayId: Int = Display.DEFAULT_DISPLAY) {
        hostGui?.showTap(x, y, displayId)
    }

    private fun showLongPress(
        x: Int,
        y: Int,
        durationMs: Int,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ) {
        hostGui?.showLongPress(x, y, durationMs, displayId)
    }

    private fun showSwipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ) {
        hostGui?.showSwipe(x1, y1, x2, y2, durationMs, displayId)
    }

    private data class AppInfo(
        val packageName: String,
        val appName: String,
        val isSystemApp: Boolean = false
    )

    private companion object {
        val DEVICE_DIRECT_TOOL_NAMES = AgentPhoneToolCatalog.direct + setOf(
            "set_alarm",
            "set_timer",
            "device_status",
            "inspect_app",
            "network_info",
            "top_memory_apps",
            "top_storage_apps",
            "media_control",
            "set_volume",
        )
        val DEVICE_SENSITIVE_READ_TOOL_NAMES = PersonalSearchTools.names + AgentPhoneToolCatalog.reads + setOf(
            "search_notes", "search_system_memories",
            "get_setting",
            "wifi_credentials",
            "recent_notifications",
            "search_notification_history",
            "recent_app_activity",
            "app_usage_summary",
            "get_current_location",
            "get_device_environment",
            "list_alarms",
            "list_active_timers",
            "search_clipboard_history",
            "get_health_summary",
            "read_sms_code",
            "get_logcat",
            "search_media",
            "search_audio",
            "search_recordings",
            "search_files",
            "search_calendar_events",
            "search_contacts",
            "search_call_history",
            "search_messages",
            "search_downloads",
            "search_coloros_notes",
            "search_coloros_recordings",
            "search_recording_summaries",
            "search_coloros_memories",
            "search_saved_places",
            "search_personal_orders",
            "search_qq_chat_images",
            "search_wechat_chat_images",
        )
        val DEVICE_SENSITIVE_ACTION_TOOL_NAMES = AgentPhoneToolCatalog.writes + setOf(
            "set_setting",
            "set_device_state",
            "app_state_control",
        )
        val DEVICE_TOOL_NAMES =
            DEVICE_DIRECT_TOOL_NAMES + DEVICE_SENSITIVE_READ_TOOL_NAMES +
                DEVICE_SENSITIVE_ACTION_TOOL_NAMES
        val MEMORY_TOOL_NAMES = setOf("memory_get", "memory_write")
    }
}

private val AFTER_ACTION_OPTIONS = AgentScreenObservationContract.Options(
    includeScreenshot = false,
    includeUiTree = true,
    maxNodes = 30,
)

/** append 超过该长度改走粘贴路径；逐字键入的增量重建对长文本既慢又容易被输入法打断。 */
private const val TYPE_TEXT_INCREMENTAL_CHARS = 200

/** 归一化坐标上界：0–999，与主流 GUI 模型的输出习惯一致。 */
private const val NORMALIZED_MAX = 999
