package com.deepseekharness.app.etatools.device

import android.content.Context

/**
 * GUI / 设备操作的宿主实现契约。
 *
 * :etatools 只负责工具参数解析、权限判定与结果格式化，实际触屏、观察屏幕、读剪贴板等
 * 动作由宿主模块实现本接口后注入。这里不引用无障碍服务、虚拟屏或 Xposed 的任何实现类型，
 * 只依赖 [Observation] / [ElementObservation] 纯数据契约与 Android framework。
 */
interface DeviceController : AutoCloseable {
    fun observe(includeScreenshot: Boolean, includeUiTree: Boolean, maxNodes: Int, displayId: Int): Observation
    fun screenDimensions(displayId: Int): Pair<Int, Int>
    fun tap(x: Int, y: Int, displayId: Int): String
    fun tapElement(observation: ElementObservation, index: Int): String
    fun longPress(x: Int, y: Int, durationMs: Int, displayId: Int): String
    fun longPressElement(observation: ElementObservation, index: Int, durationMs: Int): String
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, displayId: Int): String
    fun scroll(direction: String, amount: String, displayId: Int): String
    fun scrollElement(observation: ElementObservation, index: Int, direction: String, amount: String): String
    fun inputText(text: String, displayId: Int): String
    fun replaceText(text: String, index: Int?, observation: ElementObservation?, displayId: Int): String
    fun clearText(index: Int?, observation: ElementObservation?, displayId: Int): String
    fun pressKey(button: String, displayId: Int): String
    fun waitMs(durationMs: Int): String
    fun waitForText(text: String, timeoutMs: Int, includeDesc: Boolean, matchMode: String, displayId: Int): String
    fun waitForPackage(packageName: String, timeoutMs: Int, displayId: Int): String
    fun clipboardSet(context: Context, text: String): String
    fun clipboardGet(context: Context): String
    fun pasteText(text: String, displayId: Int): String
    fun openSystemPanel(panel: String): String
}
