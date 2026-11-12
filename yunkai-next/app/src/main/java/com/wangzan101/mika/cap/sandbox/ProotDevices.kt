package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import java.io.File

/**
 * PRoot 里能看到的 /dev。不绑整个 /dev:`/dev/socket/`(比如 dnsproxyd,经它能让系统替沙箱做域名解析)
 * 和 binder 设备(`/dev/binder`、`/dev/hwbinder`、`/dev/vndbinder`,经系统服务能把数据带出去)
 * 都能绕过断网,所以只绑下面这些字符设备;`/dev/fd`、`/dev/stdin` 等在根文件系统里建成指向 /proc 的符号链接。
 */
object ProotDevices {
    /** 宿主上存在才绑;同一路径绑到沙箱里同一位置。 */
    val DEVICE_BINDS = listOf(
        "/dev/null",
        "/dev/zero",
        "/dev/random",
        "/dev/urandom",
        "/dev/tty",
        "/dev/ptmx",
        "/dev/pts",
    )

    /** 根文件系统 /dev 里的符号链接:名字 → 目标。 */
    val DEV_SYMLINKS = linkedMapOf(
        "fd" to "/proc/self/fd",
        "stdin" to "/proc/self/fd/0",
        "stdout" to "/proc/self/fd/1",
        "stderr" to "/proc/self/fd/2",
    )

    /** PRoot 的 `-b` 参数(只含宿主上存在的设备)。 */
    fun bindArgs(exists: (String) -> Boolean = { File(it).exists() }): List<String> =
        DEVICE_BINDS.filter(exists).flatMap { listOf("-b", it) }

    /** 在根文件系统里建 /dev 目录和 [DEV_SYMLINKS];已是对的不动。经关口:dev 被换成链接时不会建到外面去。 */
    fun prepareRootfsDev(rootfs: File, gate: WorkspaceFileGate) {
        for ((name, target) in DEV_SYMLINKS) gate.symlink(rootfs, "dev/$name", target)
    }
}
