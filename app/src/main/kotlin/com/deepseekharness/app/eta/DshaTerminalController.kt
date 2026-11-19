package com.deepseekharness.app.eta

import android.content.Context
import com.deepseekharness.app.RootShell
import com.deepseekharness.app.ShizukuShell
import com.deepseekharness.app.etatools.terminal.TerminalController
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Eta [TerminalController] 的 DSHA 宿主实现（第一版）。
 *
 * 命令统一经 [RootShell.exec]（受 DSHA 的 root 开关与命令策略约束），root 未启用但
 * Shizuku 已就绪时回落到 [ShizukuShell.exec]。不自己开 shell、不绕过 DSHA 策略。
 * 文件工具用受策略约束的 cat / ls / stat 近似实现；写类与第一版范围外的工具明确返回不支持。
 */
class DshaTerminalController(context: Context) : TerminalController {

    private val appContext: Context = context.applicationContext

    override fun executeTool(name: String, args: JSONObject): String {
        val fallbackAction = if (name == "terminal") "exec" else "start"
        val action = args.optString("action", fallbackAction).ifEmpty { fallbackAction }

        if (action.equals("check_root", ignoreCase = true)) {
            return commandResult("id -u", "root-check")
        }
        if (name !in SUPPORTED_TERMINAL_TOOLS) {
            return unsupported("UNSUPPORTED_TERMINAL_TOOL", "name=$name 第一版未实现")
        }
        if (action !in setOf("exec", "start")) {
            return unsupported(
                "UNSUPPORTED_TERMINAL_ACTION",
                "name=$name action=$action；第一版只支持 exec/start（后台会话由 DSHA 原生终端负责）",
            )
        }
        val command = args.optString("command", "")
        if (command.isBlank()) return unsupported("INVALID_ARGUMENT", "command 不能为空")
        return commandResult(command, name)
    }

    override fun fileTool(name: String, args: JSONObject): String {
        if (name !in SUPPORTED_FILE_TOOLS) {
            return unsupported("UNSUPPORTED_FILE_TOOL", "name=$name 第一版未实现")
        }
        val path = args.optString("path", "")
        if (path.isBlank()) return unsupported("INVALID_ARGUMENT", "path 不能为空")
        val quoted = shellQuote(path)
        val (transport, raw) = when (name) {
            "read_file" -> runRaw("cat -- $quoted")
            "list_directory" -> runRaw("ls -la -- $quoted")
            "stat_file" -> runRaw("stat -- $quoted")
            else -> return unsupported("UNSUPPORTED_FILE_TOOL", "name=$name")
        }
        if (!succeeded(raw)) {
            return JSONObject()
                .put("ok", false)
                .put("error", "HOST_FILE_OPERATION_FAILED")
                .put("transport", transport)
                .put("output", raw)
                .toString()
        }
        return when (name) {
            "read_file" -> readFileResult(raw, args, transport)
            "list_directory" -> JSONObject()
                .put("ok", true)
                .put("transport", transport)
                .put("path", path)
                .put("raw", stripExitMarker(raw))
                .toString()
            "stat_file" -> JSONObject()
                .put("ok", true)
                .put("transport", transport)
                .put("path", path)
                .put("raw", stripExitMarker(raw))
                .toString()
            else -> unsupported("UNSUPPORTED_FILE_TOOL", "name=$name")
        }
    }

    override fun interruptAll() {
        // 第一版没有自己启动的后台作业；DSHA 终端会话由宿主自行管理。
    }

    // ---------- 内部实现 ----------

    private fun commandResult(command: String, tool: String): String {
        val (transport, raw) = runRaw(command)
        return JSONObject()
            .put("ok", succeeded(raw))
            .put("transport", transport)
            .put("tool", tool)
            .put("command", command)
            .put("output", raw)
            .toString()
    }

    private fun readFileResult(raw: String, args: JSONObject, transport: String): String {
        val text = stripExitMarker(raw)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val offset = maxOf(0, args.optInt("offset_bytes", 0)).coerceAtMost(bytes.size)
        val limit = args.optInt("max_bytes", 16_000).coerceIn(4, 262_144)
        val end = minOf(bytes.size, offset + limit)
        val content = String(bytes, offset, end - offset, StandardCharsets.UTF_8)
        val result = JSONObject()
            .put("ok", true)
            .put("transport", transport)
            .put("content", content)
            .put("offset_bytes", offset)
            .put("next_offset_bytes", end)
            .put("max_bytes", limit)
            .put("has_more", end < bytes.size)
            .put("expected_revision", Integer.toHexString(content.hashCode()).padStart(8, '0'))
        if (args.has("start_line")) {
            result.put("start_line_reached", false)
        }
        return result.toString()
    }

    private fun runRaw(command: String): Pair<String, String> {
        val rootEnabled = try {
            RootShell.enabled(appContext)
        } catch (throwable: Throwable) {
            false
        }
        if (rootEnabled) {
            val output = try {
                RootShell.exec(appContext, command, 0)
            } catch (throwable: Throwable) {
                "[ERR] ROOT_EXEC_THREW ${throwable.javaClass.simpleName}: ${throwable.message}"
            }
            return "root" to output
        }
        val shizukuReady = try {
            ShizukuShell.isReady()
        } catch (throwable: Throwable) {
            false
        }
        if (shizukuReady) {
            val output = try {
                ShizukuShell.exec(command)
            } catch (throwable: Throwable) {
                "[ERR] SHIZUKU_EXEC_THREW ${throwable.javaClass.simpleName}: ${throwable.message}"
            }
            return "shizuku" to output
        }
        return "unavailable" to
            "[ERR] NO_SHELL_TRANSPORT root_enabled=false shizuku_ready=false；请先在 DSHA 设置中启用 root shell 或连接 Shizuku"
    }

    private fun succeeded(output: String): Boolean = !output.contains("[POLICY_BLOCKED]") &&
        !output.contains("[ROOT_UNAVAILABLE]") &&
        !output.contains("[SHIZUKU_SERVICE_NOT_READY]") &&
        !output.contains("[EXECUTION_UNKNOWN]") &&
        !output.trim().startsWith("[ERR]")

    private fun stripExitMarker(raw: String): String {
        val lines = raw.removeSuffix("\n").split('\n')
        return if (lines.isNotEmpty() && EXIT_MARKER.matches(lines.last())) {
            lines.dropLast(1).joinToString("\n")
        } else {
            raw
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun unsupported(code: String, detail: String): String = JSONObject()
        .put("ok", false)
        .put("error", code)
        .put("detail", detail)
        .toString()

    companion object {
        private val SUPPORTED_TERMINAL_TOOLS = setOf("terminal", "terminal_job", "terminal_daemon")
        private val SUPPORTED_FILE_TOOLS = setOf("read_file", "list_directory", "stat_file")
        private val EXIT_MARKER = Regex("\\[EXIT=\\d+\\]")
    }
}
