package com.deepseekharness.app.etatools.device

import android.graphics.Rect

/** 手势触觉反馈的种类，对应宿主实现里的 TAP / LONG_PRESS / SWIPE。 */
internal enum class HostHaptic { TAP, LONG_PRESS, SWIPE }

/**
 * GUI 生命周期与视觉/画布副作用的宿主桥。
 *
 * 原执行器里的宿主画布路由 / 虚拟屏快照 / 状态胶囊避让 / 手势指示器 / 触觉反馈 /
 * GUI 运行控制器都属于宿主 runtime，不能进入 :etatools。这里把它们收敛为一个可空注入点：宿主不注入时全部按物理主屏 + no-op
 * 处理（generation 包裹退化为直接执行），行为安全降级但不影响工具分发与结果格式。
 *
 * release() 不在此接口内：释放手机操作权由 AgentLocalTools 的 guiRelease lambda 单独承担。
 */
internal interface HostGuiBridge : AutoCloseable {
    fun isVirtualDisplay(displayId: Int): Boolean
    fun isVirtualDisplayActive(displayId: Int): Boolean
    fun <T> clearOverlay(area: Rect, gesture: () -> T): T
    fun performHaptic(type: HostHaptic)
    fun showTap(x: Int, y: Int, displayId: Int)
    fun showLongPress(x: Int, y: Int, durationMs: Int, displayId: Int)
    fun showSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, displayId: Int)
    /** 等价于原宿主 GUI 控制器的 withAction，用于把 generation 传入观察新鲜度校验。 */
    fun <T> withAction(action: (generation: Long?) -> T): T
}
