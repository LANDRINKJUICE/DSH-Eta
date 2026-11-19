package com.deepseekharness.app.eta

import android.content.Context
import com.deepseekharness.app.RootShell
import com.deepseekharness.app.ShizukuShell
import com.deepseekharness.app.core.HarnessController
import com.deepseekharness.app.etatools.terminal.AgentFileOperations
import com.deepseekharness.app.etatools.terminal.FileToolBackend
import com.deepseekharness.app.etatools.terminal.FileToolDirectoryPage
import com.deepseekharness.app.etatools.terminal.FileToolException
import com.deepseekharness.app.etatools.terminal.FileToolKind
import com.deepseekharness.app.etatools.terminal.FileToolStat
import com.deepseekharness.app.etatools.terminal.FileToolWriteResult
import com.deepseekharness.app.etatools.terminal.TerminalController
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Eta [TerminalController] 的 DSHA 宿主实现（完整版）。
 *
 * - 命令：`environment=linux` 走 DSHA 自己的 proot 容器（[HarnessController.proot]），
 *   `environment=android` 走 DSHA 的策略化 RootShell → Shizuku → 普通 shell 链；不自己提权、不绕过策略。
 * - 文件：7 个文件工具全部落到 [AgentFileOperations]（与 etatools 的 linux 后端同一套结果结构与错误码），
 *   后端为 [HostFileBackend]：`linux` 路径映射到容器 rootfs，宿主路径按 cwd/绝对路径解析。
 * - 会话/作业：`terminal` 的 open/close、`terminal_job` 与 `terminal_daemon` 的
 *   start/list/read/wait/stop/logs 全生命周期在本进程内管理，日志落 app 私有目录，close/interruptAll 负责回收。
 */
class DshaTerminalController(context: Context) : TerminalController {

    private val appContext: Context = context.applicationContext
    private val sessions = ConcurrentHashMap<String, ShellSession>()
    private val jobs = ConcurrentHashMap<String, BackgroundJob>()
    private val sequence = AtomicInteger(0)
    private val jobLogDir: File = File(appContext.filesDir, "eta-terminal-logs")

    // ------------------------------------------------------------------
    // TerminalController
    // ------------------------------------------------------------------

    override fun executeTool(name: String, args: JSONObject): String = when (name) {
        "terminal" -> terminal(args)
        "terminal_job" -> backgroundTool(args, daemon = false)
        "terminal_daemon" -> backgroundTool(args, daemon = true)
        else -> failure("UNSUPPORTED_TERMINAL_TOOL", "name=$name")
    }

    override fun fileTool(name: String, args: JSONObject): String {
        val backend = HostFileBackend(appContext, args)
        val ops = AgentFileOperations(backend)
        return when (name) {
            "read_file" -> ops.readFile(
                path = args.optString("path", ""),
                offsetBytes = args.optLong("offset_bytes", 0L),
                maxBytes = args.optInt("max_bytes", 16_000),
                startLine = if (args.has("start_line")) args.optInt("start_line") else null,
                maxLines = args.optInt("max_lines", 200),
                expectedRevision = args.optString("expected_revision", "").ifBlank { null },
            )

            "write_file" -> ops.writeFile(
                path = args.optString("path", ""),
                content = args.optString("content", ""),
                append = args.optBoolean("append", false),
                expectedRevision = args.optString("expected_revision", "").ifBlank { null },
            )

            "edit_file" -> ops.editFile(
                path = args.optString("path", ""),
                oldText = args.optString("old_text", ""),
                newText = args.optString("new_text", ""),
                replaceAll = args.optBoolean("replace_all", false),
                expectedRevision = args.optString("expected_revision", "").ifBlank { null },
            )

            "stat_file" -> ops.statFile(args.optString("path", ""))
            "list_directory" -> ops.listDirectory(
                path = args.optString("path", ""),
                showHidden = args.optBoolean("show_hidden", false),
                limit = args.optInt("limit", 80),
                offset = args.optInt("offset", 0),
                expectedRevision = args.optString("expected_revision", "").ifBlank { null },
            )

            "glob_files" -> ops.globFiles(
                path = args.optString("path", ""),
                pattern = args.optString("pattern", ""),
                limit = args.optInt("limit", 80),
                cursor = args.optString("cursor", "").ifBlank { null },
                includeHidden = args.optBoolean("include_hidden", false),
            )

            "grep_files" -> ops.grepFiles(
                path = args.optString("path", ""),
                query = args.optString("query", ""),
                glob = args.optString("glob", "**/*"),
                limit = args.optInt("limit", 50),
                cursor = args.optString("cursor", "").ifBlank { null },
                includeHidden = args.optBoolean("include_hidden", false),
                caseSensitive = args.optBoolean("case_sensitive", true),
            )

            else -> failure("UNSUPPORTED_FILE_TOOL", "name=$name")
        }
    }

    override fun interruptAll() {
        jobs.values.forEach { stopJob(it) }
        sessions.clear()
    }

    // ------------------------------------------------------------------
    // terminal：exec / open / close / check_root
    // ------------------------------------------------------------------

    private fun terminal(args: JSONObject): String {
        val action = args.optString("action", "exec").ifBlank { "exec" }
            .lowercase(java.util.Locale.ROOT)
        when (action) {
            "check_root" -> return executeOnce("id -u", args, "check_root")
            "open" -> {
                val id = "sess-" + UUID.randomUUID().toString().substring(0, 8)
                val session = ShellSession(
                    id = id,
                    cwd = resolveCwd(args),
                    environment = environmentOf(args),
                    identity = identityOf(args),
                )
                sessions[id] = session
                return JSONObject()
                    .put("ok", true)
                    .put("tool", "terminal")
                    .put("action", "open")
                    .put("session_id", id)
                    .put("cwd", session.cwd)
                    .put("environment", session.environment)
                    .put("identity", session.identity)
                    .toString()
            }

            "close" -> {
                val id = args.optString("session_id", "")
                val removed = sessions.remove(id)
                return JSONObject()
                    .put("ok", true)
                    .put("tool", "terminal")
                    .put("action", "close")
                    .put("session_id", id)
                    .put("closed", removed != null)
                    .toString()
            }

            "exec", "start" -> {
                val command = args.optString("command", "")
                if (command.isBlank()) return failure("INVALID_ARGUMENT", "command 不能为空")
                val sessionId = args.optString("session_id", "")
                val session = sessionId.takeIf { it.isNotBlank() }?.let { sessions[it] }
                val effective = if (session != null) "cd ${quote(session.cwd)} && $command" else command
                val merged = if (session != null) args else cloneWith(args) {
                    it.put("environment", session.environment).put("identity", session.identity)
                }
                return executeOnce(effective, merged, "terminal")
            }

            else -> return failure("UNSUPPORTED_TERMINAL_ACTION", "action=$action")
        }
    }

    private fun executeOnce(command: String, args: JSONObject, tool: String): String {
        val timeoutMs = args.optInt("timeout_ms", 30_000).coerceIn(0, 180_000)
        val mergeStderr = args.optBoolean("merge_stderr", false)
        val environment = environmentOf(args)
        val result = runCommand(command, timeoutMs, mergeStderr, environment)
        return JSONObject()
            .put("ok", result.ok)
            .put("tool", tool)
            .put("transport", result.transport)
            .put("command", command)
            .put("environment", environment)
            .put("identity", identityOf(args))
            .put("output", result.output)
            .put("exit_code", result.exitCode)
            .put("timed_out", result.timedOut)
            .toString()
    }

    private data class CommandResult(
        val ok: Boolean,
        val transport: String,
        val output: String,
        val exitCode: Int?,
        val timedOut: Boolean,
    )

    /** 单一命令执行：linux 走 DSHA proot，android 走 RootShell/Shizuku/系统 sh。 */
    private fun runCommand(
        command: String,
        timeoutMs: Int,
        mergeStderr: Boolean,
        environment: String,
    ): CommandResult {
        if (environment == "linux") {
            val process = try {
                HarnessController.get(appContext).proot().execRootfs(command)
            } catch (throwable: Throwable) {
                return CommandResult(
                    false,
                    "proot",
                    "[ERR] LINUX_ENV_UNAVAILABLE ${throwable.javaClass.simpleName}: ${throwable.message}",
                    null,
                    false,
                )
            }
            return readProcess(process, timeoutMs, mergeStderr, "proot")
        }
        val rootEnabled = try {
            RootShell.enabled(appContext)
        } catch (_: Throwable) {
            false
        }
        if (rootEnabled) {
            val output = try {
                RootShell.exec(appContext, command, 0)
            } catch (throwable: Throwable) {
                "[ERR] ROOT_EXEC_THREW ${throwable.javaClass.simpleName}: ${throwable.message}"
            }
            return CommandResult(!isFailureMarker(output), "root", output, null, false)
        }
        val shizukuReady = try {
            ShizukuShell.isReady()
        } catch (_: Throwable) {
            false
        }
        if (shizukuReady) {
            val output = try {
                ShizukuShell.exec(command)
            } catch (throwable: Throwable) {
                "[ERR] SHIZUKU_EXEC_THREW ${throwable.javaClass.simpleName}: ${throwable.message}"
            }
            return CommandResult(!isFailureMarker(output), "shizuku", output, null, false)
        }
        return try {
            val builder = ProcessBuilder("/system/bin/sh", "-c", command)
            builder.redirectErrorStream(mergeStderr)
            readProcess(builder.start(), timeoutMs, mergeStderr, "shell")
        } catch (throwable: Throwable) {
            CommandResult(
                false,
                "shell",
                "[ERR] SHELL_UNAVAILABLE ${throwable.javaClass.simpleName}: ${throwable.message}",
                null,
                false,
            )
        }
    }

    private fun readProcess(
        process: Process,
        timeoutMs: Int,
        mergeStderr: Boolean,
        transport: String,
    ): CommandResult {
        val output = StringBuilder()
        val reader = Thread {
            try {
                val stream = if (mergeStderr) process.inputStream else process.inputStream
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { br ->
                    var line: String?
                    while (true) {
                        line = br.readLine() ?: break
                        if (output.isNotEmpty()) output.append('\n')
                        output.append(line)
                    }
                }
            } catch (_: Throwable) {
            }
        }
        reader.isDaemon = true
        reader.start()
        val timedOut = if (timeoutMs > 0) {
            !process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } else {
            process.waitFor()
            false
        }
        if (timedOut) process.destroyForcibly()
        reader.join(2_000)
        var exit: Int? = null
        try {
            exit = process.exitValue()
        } catch (_: Throwable) {
        }
        val text = output.toString()
        return CommandResult(!isFailureMarker(text) && !timedOut, transport, text, exit, timedOut)
    }

    // ------------------------------------------------------------------
    // terminal_job / terminal_daemon：完整生命周期
    // ------------------------------------------------------------------

    private fun backgroundTool(args: JSONObject, daemon: Boolean): String {
        val tool = if (daemon) "terminal_daemon" else "terminal_job"
        val verbs = if (daemon) {
            setOf("start", "list", "logs", "stop")
        } else {
            setOf("start", "list", "read", "wait", "stop")
        }
        val action = args.optString("action", "list").ifBlank { "list" }
            .lowercase(java.util.Locale.ROOT)
        if (action !in verbs)
            return failure("UNSUPPORTED_TERMINAL_ACTION", "$tool action=$action；支持 ${verbs.joinToString("/")}")

        return when (action) {
            "start" -> {
                val command = args.optString("command", "")
                if (command.isBlank()) return failure("INVALID_ARGUMENT", "command 不能为空")
                val id = (if (daemon) "daemon-" else "job-") +
                    sequence.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 6)
                val logFile = File(jobLogDir, "$id.log")
                val job = BackgroundJob(
                    id = id,
                    command = command,
                    description = args.optString("description", ""),
                    environment = environmentOf(args),
                    identity = identityOf(args),
                    cwd = resolveCwd(args),
                    logFile = logFile,
                    daemon = daemon,
                    timeoutMs = args.optInt("timeout_ms", 600_000),
                )
                jobs[id] = job
                startJob(job)
                JSONObject()
                    .put("ok", true)
                    .put("tool", tool)
                    .put("action", "start")
                    .put(if (daemon) "task_id" else "job_id", id)
                    .put("output_path", logFile.absolutePath)
                    .put("status", job.status)
                    .put("completion_notifications_enabled", true)
                    .toString()
            }

            "list" -> {
                val activeOnly = if (args.has("active_only")) args.optBoolean("active_only", true) else !daemon
                val array = JSONArray()
                jobs.values
                    .filter { it.daemon == daemon }
                    .filter { !activeOnly || it.status == "running" }
                    .sortedBy { it.startedAt }
                    .forEach { job ->
                        array.put(
                            JSONObject()
                                .put(if (daemon) "task_id" else "job_id", job.id)
                                .put("command", job.command)
                                .put("description", job.description)
                                .put("status", job.status)
                                .put("exit_code", job.exitCode ?: JSONObject.NULL)
                                .put("output_path", job.logFile.absolutePath)
                                .put("started_at", job.startedAt)
                                .put("environment", job.environment)
                                .put("identity", job.identity),
                        )
                    }
                JSONObject()
                    .put("ok", true)
                    .put("tool", tool)
                    .put("action", "list")
                    .put(if (daemon) "tasks" else "jobs", array)
                    .toString()
            }

            "read", "logs" -> {
                val idKey = if (daemon) "task_id" else "job_id"
                val job = jobs[args.optString(idKey, "")]
                    ?.takeIf { it.daemon == daemon }
                    ?: return failure("JOB_NOT_FOUND", "$idKey 不存在或已回收")
                val maxBytes = if (daemon) 65_536 else args.optInt("max_bytes", 16_000).coerceIn(4, 65_536)
                val tail = readTail(job.logFile, maxBytes)
                JSONObject()
                    .put("ok", true)
                    .put("tool", tool)
                    .put("action", action)
                    .put(idKey, job.id)
                    .put("status", job.status)
                    .put("exit_code", job.exitCode ?: JSONObject.NULL)
                    .put("output_path", job.logFile.absolutePath)
                    .put("output", tail)
                    .put("truncated", job.logFile.length() > maxBytes)
                    .toString()
            }

            "wait" -> {
                val job = jobs[args.optString("job_id", "")]
                    ?.takeIf { !it.daemon }
                    ?: return failure("JOB_NOT_FOUND", "job_id 不存在或已回收")
                val waitMs = args.optInt("wait_ms", 30_000).coerceIn(0, 60_000)
                val done = job.thread?.let {
                    it.join(waitMs.toLong())
                    !it.isAlive
                } ?: true
                JSONObject()
                    .put("ok", true)
                    .put("tool", tool)
                    .put("action", "wait")
                    .put("job_id", job.id)
                    .put("status", job.status)
                    .put("exit_code", job.exitCode ?: JSONObject.NULL)
                    .put("timed_out", !done)
                    .put("output_path", job.logFile.absolutePath)
                    .toString()
            }

            "stop" -> {
                val idKey = if (daemon) "task_id" else "job_id"
                val job = jobs[args.optString(idKey, "")]
                    ?.takeIf { it.daemon == daemon }
                    ?: return failure("JOB_NOT_FOUND", "$idKey 不存在或已回收")
                stopJob(job)
                JSONObject()
                    .put("ok", true)
                    .put("tool", tool)
                    .put("action", "stop")
                    .put(idKey, job.id)
                    .put("status", job.status)
                    .toString()
            }

            else -> failure("UNSUPPORTED_TERMINAL_ACTION", "action=$action")
        }
    }

    private fun startJob(job: BackgroundJob) {
        val effective = if (job.cwd.isNotBlank()) "cd ${quote(job.cwd)} && ${job.command}" else job.command
        val process = try {
            if (job.environment == "linux") {
                HarnessController.get(appContext).proot().execRootfs(effective)
            } else {
                ProcessBuilder("/system/bin/sh", "-c", effective)
                    .redirectErrorStream(true)
                    .start()
            }
        } catch (throwable: Throwable) {
            job.status = "failed"
            job.exitCode = -1
            appendLog(job.logFile, "[ERR] START_FAILED ${throwable.javaClass.simpleName}: ${throwable.message}\n")
            return
        }
        job.process = process
        job.status = "running"
        val thread = Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    var line: String?
                    while (true) {
                        if (job.timeoutMs > 0 &&
                            System.currentTimeMillis() - job.startedAt > job.timeoutMs.toLong()
                        ) {
                            process.destroyForcibly()
                            job.status = "stopped"
                            break
                        }
                        line = reader.readLine() ?: break
                        appendLog(job.logFile, line + "\n")
                    }
                }
                val code = try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    -1
                }
                if (job.status == "running") {
                    job.status = if (code == 0) "completed" else "failed"
                    job.exitCode = code
                }
            } catch (throwable: Throwable) {
                if (job.status == "running") {
                    job.status = "failed"
                    appendLog(job.logFile, "[ERR] JOB_THREW ${throwable.javaClass.simpleName}: ${throwable.message}\n")
                }
            }
        }
        thread.isDaemon = true
        thread.name = "eta-terminal-${job.id}"
        job.thread = thread
        thread.start()
    }

    private fun stopJob(job: BackgroundJob) {
        job.process?.destroyForcibly()
        if (job.status == "running") job.status = "stopped"
        job.thread?.interrupt()
    }

    private fun appendLog(file: File, text: String) {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
        } catch (_: Throwable) {
        }
    }

    private fun readTail(file: File, maxBytes: Int): String {
        if (!file.isFile) return ""
        val bytes = try {
            Files.readAllBytes(file.toPath())
        } catch (_: Throwable) {
            return ""
        }
        val start = maxOf(0, bytes.size - maxBytes)
        return String(bytes, start, bytes.size - start, StandardCharsets.UTF_8)
    }

    private data class ShellSession(
        val id: String,
        val cwd: String,
        val environment: String,
        val identity: String,
    )

    private data class BackgroundJob(
        val id: String,
        val command: String,
        val description: String,
        val environment: String,
        val identity: String,
        val cwd: String,
        val logFile: File,
        val daemon: Boolean,
        val timeoutMs: Int,
        val startedAt: Long = System.currentTimeMillis(),
        @Volatile var status: String = "running",
        @Volatile var exitCode: Int? = null,
        @Volatile var process: Process? = null,
        @Volatile var thread: Thread? = null,
    )

    // ------------------------------------------------------------------
    // 文件工具后端：把宿主文件命名空间适配成 etatools 的 FileToolBackend
    // ------------------------------------------------------------------

    private class HostFileBackend(
        private val context: Context,
        private val args: JSONObject,
    ) : FileToolBackend {

        override val environment: String =
            args.optString("environment", "android").ifBlank { "android" }

        override val identity: String =
            args.optString("identity", "root").ifBlank { "root" }

        private val cwd: String = args.optString("cwd", "")

        private val rootfs: File? by lazy {
            try {
                HarnessController.get(context).proot().getRootfsDir()
            } catch (_: Throwable) {
                null
            }
        }

        override fun resolve(path: String): String {
            val raw = path.trim()
            if (raw.isEmpty()) throw FileToolException("INVALID_ARGUMENT", "path 不能为空")
            val candidate = if (environment == "linux") {
                val root = rootfs
                    ?: throw FileToolException("LINUX_ENV_UNAVAILABLE", "DSHA 容器 rootfs 不可用，请先完成安装")
                // resolve() 必须幂等：stat/write 会用 resolve 的返回值再次调用 resolve。
                val rootPath = try { root.canonicalPath } catch (_: Throwable) { root.path }
                if (raw == rootPath || raw.startsWith(rootPath + File.separator)) return raw
                val relative = if (raw.startsWith("/")) raw.substring(1) else joinRelative(cwd, raw)
                File(root, relative)
            } else if (raw.startsWith("/")) {
                File(raw)
            } else {
                val base = cwd.ifBlank { context.filesDir.absolutePath }
                File(base, raw)
            }
            val canonical = try {
                candidate.canonicalFile
            } catch (_: Throwable) {
                throw FileToolException("FILE_ACCESS_DENIED", "路径无法解析：$raw")
            }
            if (environment == "linux") {
                val root = rootfs?.canonicalPath
                if (root != null && canonical.path != root &&
                    !canonical.path.startsWith(root + File.separator)
                ) {
                    throw FileToolException("FILE_ACCESS_DENIED", "路径越出容器 rootfs：$raw")
                }
            }
            return canonical.path
        }

        override fun stat(path: String): FileToolStat {
            val resolved = resolve(path)
            val p = Paths.get(resolved)
            val attrs: BasicFileAttributes = try {
                Files.readAttributes(p, BasicFileAttributes::class.java)
            } catch (error: NoSuchFileException) {
                throw error
            } catch (error: IOException) {
                throw FileToolException("FILE_IO_ERROR", "stat 失败：$resolved（${error.message}）")
            }
            val kind = when {
                Files.isSymbolicLink(p) -> FileToolKind.SYMLINK
                attrs.isDirectory -> FileToolKind.DIRECTORY
                attrs.isRegularFile -> FileToolKind.FILE
                else -> FileToolKind.OTHER
            }
            val signature = "$resolved|${kind.wireName}|${attrs.size()}|${attrs.lastModifiedTime().toMillis()}"
            return FileToolStat(
                path = resolved,
                name = p.fileName?.toString() ?: resolved,
                kind = kind,
                sizeBytes = attrs.size(),
                revision = sha256(signature).take(16),
            )
        }

        override fun readBytes(path: String, offset: Long, limit: Int): ByteArray {
            val resolved = resolve(path)
            val all = try {
                Files.readAllBytes(Paths.get(resolved))
            } catch (error: NoSuchFileException) {
                throw error
            } catch (error: IOException) {
                throw FileToolException("FILE_IO_ERROR", "读取失败：$resolved（${error.message}）")
            }
            val from = offset.coerceIn(0, all.size.toLong()).toInt()
            val to = minOf(all.size, from + maxOf(0, limit))
            return all.copyOfRange(from, to)
        }

        override fun list(
            path: String,
            offset: Int,
            limit: Int,
            expectedRevision: String?,
        ): FileToolDirectoryPage {
            val resolved = resolve(path)
            val revision = stat(resolved).revision
            if (expectedRevision != null && expectedRevision != revision)
                throw FileToolException("FILE_CHANGED", "目录已变化，请重新列举")
            val names = ArrayList<String>()
            try {
                Files.newDirectoryStream(Paths.get(resolved)).use { stream ->
                    for (entry in stream) names += entry.fileName.toString()
                }
            } catch (error: IOException) {
                throw FileToolException("FILE_IO_ERROR", "列举失败：$resolved（${error.message}）")
            }
            names.sort()
            val from = offset.coerceIn(0, names.size)
            val to = minOf(names.size, from + maxOf(0, limit))
            val entries = (from until to).map { index -> stat(join(resolved, names[index])) }
            return FileToolDirectoryPage(
                entries = entries,
                nextOffset = to,
                hasMore = to < names.size,
                revision = revision,
            )
        }

        override fun write(
            path: String,
            bytes: ByteArray,
            append: Boolean,
            expectedRevision: String?,
            expectedSha256: String?,
        ): FileToolWriteResult {
            val resolved = resolve(path)
            val p = Paths.get(resolved)
            if (Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (expectedRevision != null && stat(resolved).revision != expectedRevision)
                    throw FileToolException("FILE_CHANGED", "文件已变化，请重新读取")
                if (expectedSha256 != null) {
                    val actual = sha256(Files.readAllBytes(p))
                    if (actual != expectedSha256)
                        throw FileToolException("FILE_CHANGED", "文件内容与编辑前不一致，未写入")
                }
            } else if (expectedRevision != null || expectedSha256 != null) {
                throw FileToolException("FILE_CHANGED", "文件已不存在，未写入")
            }
            try {
                p.parent?.let { Files.createDirectories(it) }
                val options = if (append) {
                    arrayOf(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND,
                    )
                } else {
                    arrayOf(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                    )
                }
                Files.write(p, bytes, *options)
            } catch (error: IOException) {
                throw FileToolException("FILE_IO_ERROR", "写入失败：$resolved（${error.message}）")
            }
            return FileToolWriteResult(stat = stat(resolved), atomic = false)
        }

        override fun checkActive() {
            if (environment == "linux" && rootfs == null)
                throw FileToolException("LINUX_ENV_UNAVAILABLE", "容器不可用")
        }

        private fun join(base: String, name: String): String = File(base, name).path

        private fun joinRelative(base: String?, raw: String): String {
            if (base.isNullOrBlank()) return raw
            return base.trim().trimStart('/') + "/" + raw.trimStart('/')
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    // ------------------------------------------------------------------
    // 通用工具
    // ------------------------------------------------------------------

    private fun environmentOf(args: JSONObject): String =
        args.optString("environment", "android").ifBlank { "android" }
            .lowercase(java.util.Locale.ROOT)

    private fun identityOf(args: JSONObject): String =
        args.optString("identity", "root").ifBlank { "root" }
            .lowercase(java.util.Locale.ROOT)

    private fun resolveCwd(args: JSONObject): String = args.optString("cwd", "").trim()

    private fun isFailureMarker(output: String): Boolean =
        output.contains("[POLICY_BLOCKED]") ||
            output.contains("[ROOT_UNAVAILABLE]") ||
            output.contains("[SHIZUKU_SERVICE_NOT_READY]") ||
            output.contains("[EXECUTION_UNKNOWN]") ||
            output.trimStart().startsWith("[ERR]")

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun failure(code: String, detail: String): String = JSONObject()
        .put("ok", false)
        .put("error", code)
        .put("detail", detail)
        .toString()

    private inline fun cloneWith(args: JSONObject, block: (JSONObject) -> Unit): JSONObject {
        val copy = JSONObject()
        args.keys().forEach { key -> copy.put(key, args.opt(key)) }
        block(copy)
        return copy
    }

    private companion object {
        const val TAG = "EtaTerminalController"
    }
}
