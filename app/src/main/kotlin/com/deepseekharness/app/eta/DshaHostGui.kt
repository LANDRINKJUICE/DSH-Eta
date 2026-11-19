package com.deepseekharness.app.eta

import android.graphics.Rect
import com.deepseekharness.app.etatools.device.HostGuiBridge
import com.deepseekharness.app.etatools.device.HostHaptic

/**
 * [HostGuiBridge] 的 DSHA 第一版实现：物理主屏 + 全 no-op 副作用。
 *
 * 契约明确允许宿主不注入画布/虚拟屏能力；这里选择注入一个安全降级实现，
 * 使 generation 包裹退化为直接执行，不影响工具分发与结果格式。
 */
class DshaHostGui : HostGuiBridge {

    override fun isVirtualDisplay(displayId: Int): Boolean = false

    override fun isVirtualDisplayActive(displayId: Int): Boolean = false

    override fun <T> clearOverlay(area: Rect, gesture: () -> T): T = gesture()

    override fun performHaptic(type: HostHaptic) {
        // 第一版不注入触觉反馈。
    }

    override fun showTap(x: Int, y: Int, displayId: Int) {
        // 第一版不绘制手势指示器。
    }

    override fun showLongPress(x: Int, y: Int, durationMs: Int, displayId: Int) {
        // 第一版不绘制手势指示器。
    }

    override fun showSwipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
        displayId: Int,
    ) {
        // 第一版不绘制手势指示器。
    }

    override fun <T> withAction(action: (generation: Long?) -> T): T = action(null)

    override fun close() {
        // 无资源。
    }
}
