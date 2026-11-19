package com.deepseekharness.app.etatools.device

import android.graphics.Rect
import android.view.Display
import com.deepseekharness.app.etatools.model.ModelImage

/**
 * 屏幕观察契约：从 Eta 宿主的控制器 / 无障碍快照实现
 * 抽出的纯数据 / 枚举类型。仅依赖 Android framework 与 [ModelImage]，
 * 不依赖无障碍服务实现、设备控制器实现、Xposed 或 UI。
 */

data class Observation(
    val content: String,
    val image: ModelImage?,
    val elementObservation: ElementObservation?,
    val coordinateSpace: CoordinateSpace?,
)

data class ElementObservation(
    val id: String,
    val source: ElementSource,
    val packageName: String,
    val windowId: Int?,
    val nodes: List<UiNode>,
    val maxNodes: Int,
    val truncated: Boolean,
    val treeSignature: String = "",
    val accessibilitySnapshot: NodeSnapshot? = null,
)

enum class ElementSource(val wireName: String) {
    ACCESSIBILITY("accessibility"),
    UIAUTOMATOR("uiautomator"),
}

data class CoordinateSpace(
    val screenWidth: Int,
    val screenHeight: Int,
    val screenshotWidth: Int,
    val screenshotHeight: Int
) {
    fun fromScreenshot(x: Int, y: Int): ScreenPoint {
        require(x in 0 until screenshotWidth && y in 0 until screenshotHeight) {
            "截图坐标超出范围：($x,$y) not in ${screenshotWidth}x$screenshotHeight"
        }
        return ScreenPoint(
            x = (x.toFloat() * screenWidth / screenshotWidth).toInt(),
            y = (y.toFloat() * screenHeight / screenshotHeight).toInt()
        )
    }

    fun summary(): String =
        "screen=${screenWidth}x$screenHeight,screenshot=${screenshotWidth}x$screenshotHeight"
}

data class ScreenPoint(val x: Int, val y: Int)

data class UiNode(
    val index: Int,
    val text: String,
    val desc: String,
    val className: String,
    val packageName: String,
    val viewId: String,
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val focused: Boolean,
    val editable: Boolean,
    val password: Boolean,
    val enabled: Boolean,
    /** 开关、勾选框等的状态；null 表示节点不可勾选。 */
    val checked: Boolean? = null,
    val selected: Boolean = false,
    /** 输入框提示语，只在内容为空时有意义，用于识别"这是哪个输入框"。 */
    val hint: String = "",
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()
}

enum class NodeSnapshotStatus {
    AVAILABLE, EMPTY, NO_WINDOW, ROOT_UNAVAILABLE, TIMEOUT, BUSY, SERVICE_UNAVAILABLE, QUERY_FAILED,
}

data class ObservedWindow(
    val packageName: String?,
    val windowId: Int,
    val displayId: Int,
    val focused: Boolean,
    val active: Boolean,
)

data class NodeSnapshotObservation(
    val status: NodeSnapshotStatus,
    val snapshot: NodeSnapshot? = null,
    val window: ObservedWindow? = null,
)

/**
 * 节点快照的纯契约版本。宿主实现里的完整类型还带有
 * serviceToken / contentGeneration / indexedNodes 与 hasUnambiguousIdentity，
 * 它们仅供无障碍服务做节点定位与新鲜度校验，属实现细节，未纳入本契约；
 * 原先由 indexedNodes 派生的 nodes 在这里作为普通字段保留。
 */
data class NodeSnapshot(
    val id: String,
    val packageName: String,
    val windowId: Int,
    val capturedAtElapsedMs: Long,
    val truncated: Boolean,
    val nodes: List<UiNode>,
    val displayId: Int = Display.DEFAULT_DISPLAY,
)
