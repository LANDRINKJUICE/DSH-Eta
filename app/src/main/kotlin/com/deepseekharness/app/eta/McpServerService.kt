package com.deepseekharness.app.eta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.deepseekharness.app.DshaAccessibilityService
import com.deepseekharness.app.RootShell
import com.deepseekharness.app.ShizukuShell
import com.deepseekharness.app.etatools.EtatoolsService
import com.deepseekharness.app.etatools.core.DefaultAgentLogger
import com.deepseekharness.app.etatools.tool.AgentToolCapabilities
import com.deepseekharness.app.etatools.tool.RootAccessState
import com.deepseekharness.app.etatools.tool.RootAccessStatus

/**
 * 在 DSHA 进程内启动 Eta 工具层的 MCP JSON-RPC 服务（127.0.0.1:8790）。
 *
 * 宿主 adapter 全部在这里装配：设备能力取自 DSHA 真实状态，logger 复用 etatools 的
 * DefaultAgentLogger。Service 仅做生命周期托管，不引入自启/保活逻辑，由调用方显式
 * startForegroundService / stopService 控制。
 */
class McpServerService : Service() {

    private var etaService: EtatoolsService? = null
    private var deviceController: DshaDeviceController? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        try {
            val controller = DshaDeviceController(this)
            deviceController = controller
            val service = EtatoolsService.create(
                context = this,
                port = PORT,
                token = null,
                logger = DefaultAgentLogger,
                caps = buildCapabilities(),
                deviceController = controller,
                terminalController = DshaTerminalController(this),
                hostGui = DshaHostGui(),
            )
            etaService = service
            service.start()
            Log.i(TAG, "Eta MCP server started on 127.0.0.1:$PORT")
        } catch (throwable: Throwable) {
            Log.e(TAG, "Eta MCP server failed to start", throwable)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_BIND_TASK -> deviceController?.bindPhoneTask(
                intent.getStringExtra(EXTRA_TASK_SESSION_ID),
                intent.getStringExtra(EXTRA_TURN_ID),
            )
            ACTION_BEGIN_TASK -> deviceController?.beginPhoneTask()
            ACTION_END_TASK -> deviceController?.endPhoneTask()
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        try {
            etaService?.close()
        } catch (throwable: Throwable) {
            Log.w(TAG, "Eta MCP server close failed", throwable)
        } finally {
            etaService = null
        }
        deviceController?.close()
        deviceController = null
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } catch (throwable: Throwable) {
            Log.w(TAG, "stopForeground failed", throwable)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildCapabilities(): AgentToolCapabilities {
        val rootEnabled = safe { RootShell.enabled(this) } ?: false
        val shizukuReady = safe { ShizukuShell.isReady() } ?: false
        val rootAvailable = rootEnabled || shizukuReady
        val suPresent = safe { RootShell.present() } ?: false
        val accessibility = safe { DshaAccessibilityService.connected() } ?: false
        val notifications = safe { DshaNotificationAccess().isEnabled(this) } ?: false
        return AgentToolCapabilities(
            rootAvailable = rootAvailable,
            rootState = RootAccessState(
                status = if (rootAvailable) RootAccessStatus.GRANTED else RootAccessStatus.UNAVAILABLE,
                suPresent = suPresent,
            ),
            accessibilityAvailable = accessibility,
            notificationsAllowed = notifications,
            colorOs = AgentToolCapabilities.isColorOsDevice(),
        )
    }

    private fun startAsForeground() {
        ensureChannel()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val pending = android.app.PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Eta 工具 MCP 服务")
            .setContentText("127.0.0.1:$PORT 为本地 agent 提供 Eta 全量工具")
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Eta MCP 服务",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (throwable: Throwable) {
        Log.w(TAG, "capability probe failed", throwable)
        null
    }

    companion object {
        private const val TAG = "EtaMcpService"
        const val PORT = 8790
        const val ACTION_STOP = "com.deepseekharness.app.eta.STOP_MCP"
        /** 由调用方注入真实手机任务身份；3090 桥禁止臆造会话 ID。 */
        const val ACTION_BIND_TASK = "com.deepseekharness.app.eta.BIND_PHONE_TASK"
        const val ACTION_BEGIN_TASK = "com.deepseekharness.app.eta.BEGIN_PHONE_TASK"
        const val ACTION_END_TASK = "com.deepseekharness.app.eta.END_PHONE_TASK"
        const val EXTRA_TASK_SESSION_ID = "taskSessionId"
        const val EXTRA_TURN_ID = "turnId"
        private const val CHANNEL_ID = "eta_mcp"
        private const val NOTIFICATION_ID = 9067
    }
}
