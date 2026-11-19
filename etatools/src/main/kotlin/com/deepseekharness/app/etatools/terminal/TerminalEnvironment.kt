package com.deepseekharness.app.etatools.terminal

const val SELECTED_LINUX_WIRE_NAME = "linux"

/** Eta 支持的 Linux 用户态发行版。内核仍由 Android 提供，发行版只替换 rootfs。 */
enum class LinuxDistribution(val wireName: String) {
    ALPINE("alpine"),
    DEBIAN("debian"),
}

enum class TerminalEnvironment(
    val wireName: String,
    val linuxDistribution: LinuxDistribution? = null,
) {
    ANDROID("android"),
    ALPINE("alpine", LinuxDistribution.ALPINE),
    DEBIAN("debian", LinuxDistribution.DEBIAN),
}

val TerminalEnvironment.isLinux: Boolean
    get() = linuxDistribution != null

val LinuxDistribution.terminalEnvironment: TerminalEnvironment
    get() = when (this) {
        LinuxDistribution.ALPINE -> TerminalEnvironment.ALPINE
        LinuxDistribution.DEBIAN -> TerminalEnvironment.DEBIAN
    }
