package com.deepseekharness.app.etatools

import android.content.Context
import com.deepseekharness.app.etatools.core.AgentLogger
import com.deepseekharness.app.etatools.device.DeviceController
import com.deepseekharness.app.etatools.device.HostGuiBridge
import com.deepseekharness.app.etatools.mcp.EtaMcpServer
import com.deepseekharness.app.etatools.terminal.TerminalController
import com.deepseekharness.app.etatools.tool.AgentLocalTools
import com.deepseekharness.app.etatools.tool.AgentToolCapabilities
import org.json.JSONObject

/**
 * :etatools 面向 :app 的唯一 public 入口。
 *
 * 封装 [AgentLocalTools] 与 [EtaMcpServer] 的构造细节：宿主实现 [DeviceController] /
 * [TerminalController]，并通过可选 lambda 注入宿主特有动作，即可在 127.0.0.1 上启动
 * MCP JSON-RPC 服务，使 DSH 侧能够调用 Eta 全量工具。
 *
 * 本文件只做可见性门面，不改变工具、协议与执行器实现逻辑。
 *
 * 注意：当前 [AgentLocalTools] 的构造签名没有 notificationAccess 参数（它在内部构造
 * AgentStructuredDeviceTools 时使用默认 null），因此本门面不提供 notificationAccess
 * 入参，避免臆造无法透传的参数。若后续需要通知读取能力，应先在 AgentLocalTools 构造
 * 上增加该参数并透传给 AgentStructuredDeviceTools，再同步扩展本门面。
 */
class EtatoolsService private constructor(
    private val portValue: Int,
    private val server: EtaMcpServer,
    private val tools: AgentLocalTools,
) : AutoCloseable {

    /** MCP 服务监听端口，与 [create] 传入的 port 一致。 */
    val port: Int get() = portValue

    /** 启动 127.0.0.1:[port] 的 MCP HTTP 监听。 */
    fun start() {
        server.start()
    }

    /** 关闭监听并释放执行器资源；重复调用安全（底层实现幂等）。 */
    override fun close() {
        server.close()
        tools.close()
    }

    companion object {
        fun create(
            context: Context,
            port: Int,
            token: String?,
            logger: AgentLogger,
            caps: AgentToolCapabilities,
            deviceController: DeviceController,
            terminalController: TerminalController,
            hostGui: HostGuiBridge? = null,
            hostAppAction: ((String, JSONObject) -> String?)? = null,
            browserAction: ((String, JSONObject) -> String?)? = null,
            webAction: ((String, JSONObject) -> String?)? = null,
        ): EtatoolsService {
            val tools = AgentLocalTools(
                context = context,
                logger = logger,
                deviceController = deviceController,
                terminalController = terminalController,
                hostGui = hostGui,
                hostAppAction = hostAppAction,
                browserAction = browserAction,
                webAction = webAction,
            )
            val server = EtaMcpServer(port, token, context, logger, caps, tools)
            return EtatoolsService(port, server, tools)
        }
    }
}
