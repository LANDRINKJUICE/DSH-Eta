package com.deepseekharness.app.etatools.terminal

import org.json.JSONObject

/**
 * 终端 / 文件工具的宿主实现契约。
 *
 * :etatools 不打包 Linux rootfs、终端运行时或文件工具后端；命令与文件操作由宿主实现本接口
 * 后注入。interruptAll 在工具执行器关闭时调用，用于回收宿主侧可能仍在运行的作业。
 */
interface TerminalController {
    fun executeTool(name: String, args: JSONObject): String
    fun fileTool(name: String, args: JSONObject): String
    fun interruptAll()
}
