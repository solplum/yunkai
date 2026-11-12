package com.wangzan101.mika.cap.sandbox

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.wangzan101.mika.core.workspace.FdStat
import com.wangzan101.mika.core.workspace.FdSys
import com.wangzan101.mika.core.workspace.FdSysException
import com.wangzan101.mika.core.workspace.OpenFd
import com.wangzan101.mika.core.workspace.OpenHow
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel

/**
 * [FdSys] 的安卓实现:每一步都 open(O_NOFOLLOW),下一段经 `/proc/self/fd/<目录句柄>/<名字>` 相对已打开的目录走,
 * 不再从路径开头重新解析;实际位置读 `/proc/self/fd/N` 的链接,类型与硬链接数看 fstat。
 * 目录也用 O_RDONLY|O_NONBLOCK 打开再按 fstat 判类型(OsConstants 没有 O_DIRECTORY,各架构的值也不同)。
 */
object AndroidFdSys : FdSys {
    /** 手机上唯一的那道关口。 */
    val gate: WorkspaceFileGate by lazy { WorkspaceFileGate(this) }

    private class Fd(val pfd: ParcelFileDescriptor, private val writable: Boolean) : OpenFd {
        private var ch: FileChannel? = null
        val proc: String get() = "/proc/self/fd/${pfd.fd}"

        override fun realPath(): String = errno { Os.readlink(proc) }
        override fun stat(): FdStat = errno { Os.fstat(pfd.fileDescriptor) }.toFdStat()

        // 从 FileDescriptor 建的流不拥有它,关流不关句柄;句柄由 pfd 关
        override fun channel(): FileChannel = ch ?: (if (writable) FileOutputStream(pfd.fileDescriptor).channel else FileInputStream(pfd.fileDescriptor).channel).also { ch = it }

        override fun truncate(size: Long) = errno { Os.ftruncate(pfd.fileDescriptor, size) }

        override fun close() {
            runCatching { ch?.close() }
            runCatching { pfd.close() }
        }
    }

    private fun fd(dir: OpenFd) = dir as Fd
    private fun at(dir: OpenFd, name: String) = "${fd(dir).proc}/$name"

    private const val DIR_FLAGS = 0 // O_RDONLY
    private val NOFOLLOW_CLOEXEC get() = OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC

    private fun open(path: String, flags: Int, mode: Int, writable: Boolean): Fd {
        val raw = errno { Os.open(path, flags, mode) }
        // 要 int 句柄号拼 /proc 路径:dup 成 ParcelFileDescriptor,原句柄随即关掉
        return try { Fd(ParcelFileDescriptor.dup(raw), writable) } finally { runCatching { Os.close(raw) } }
    }

    private fun dir(path: String): Fd {
        val d = open(path, DIR_FLAGS or OsConstants.O_NONBLOCK or NOFOLLOW_CLOEXEC, 0, writable = false)
        if (d.stat().kind != FdStat.Kind.DIR) { d.close(); throw FdSysException(FdSysException.Kind.NOT_DIR) }
        return d
    }

    override fun openRoot(path: File): OpenFd = dir(path.path)

    override fun openDir(dir: OpenFd, name: String, create: Boolean): OpenFd {
        val p = at(dir, name)
        if (create) {
            try { Os.mkdir(p, 448 /* 0700 */) } catch (e: ErrnoException) { if (e.errno != OsConstants.EEXIST) throw map(e) }
        }
        return dir(p)
    }

    override fun openFile(dir: OpenFd, name: String, how: OpenHow): OpenFd {
        val p = at(dir, name)
        // O_NONBLOCK:被换成 FIFO 时打开不会一直等对端;之后按 fstat 拒掉
        return when (how) {
            OpenHow.READ -> open(p, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or NOFOLLOW_CLOEXEC, 0, writable = false)
            OpenHow.CREATE_NEW -> open(p, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or NOFOLLOW_CLOEXEC, 384 /* 0600 */, writable = true)
            OpenHow.WRITE -> open(p, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_NONBLOCK or NOFOLLOW_CLOEXEC, 384, writable = true)
            OpenHow.APPEND -> open(p, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_APPEND or OsConstants.O_NONBLOCK or NOFOLLOW_CLOEXEC, 384, writable = true)
        }
    }

    override fun lstat(dir: OpenFd, name: String): FdStat? = try {
        Os.lstat(at(dir, name)).toFdStat()
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.ENOENT) null else throw map(e)
    }

    override fun readlink(dir: OpenFd, name: String): String? = try { Os.readlink(at(dir, name)) } catch (e: ErrnoException) { null }

    override fun list(dir: OpenFd): List<String> = File(fd(dir).proc).list()?.toList().orEmpty()

    // bionic 的 remove():unlink,是目录时 rmdir;都不跟随最后一段的链接
    override fun remove(dir: OpenFd, name: String) = errno { Os.remove(at(dir, name)) }

    override fun rename(dir: OpenFd, from: String, to: String) = errno { Os.rename(at(dir, from), at(dir, to)) }

    override fun symlink(dir: OpenFd, name: String, target: String) = errno { Os.symlink(target, at(dir, name)) }

    override fun chmod(fd: OpenFd, mode: Int) = errno { Os.fchmod(fd(fd).pfd.fileDescriptor, mode) }

    private fun StructStat.toFdStat(): FdStat {
        val kind = when {
            OsConstants.S_ISREG(st_mode) -> FdStat.Kind.FILE
            OsConstants.S_ISDIR(st_mode) -> FdStat.Kind.DIR
            OsConstants.S_ISLNK(st_mode) -> FdStat.Kind.LINK
            else -> FdStat.Kind.OTHER
        }
        return FdStat(kind, st_nlink, st_size, st_dev, st_ino)
    }

    private inline fun <T> errno(block: () -> T): T = try { block() } catch (e: ErrnoException) { throw map(e) }

    private fun map(e: ErrnoException): Exception = when (e.errno) {
        OsConstants.ENOENT -> FdSysException(FdSysException.Kind.NOT_FOUND, e)
        OsConstants.ENOTDIR -> FdSysException(FdSysException.Kind.NOT_DIR, e)
        OsConstants.EEXIST -> FdSysException(FdSysException.Kind.EXISTS, e)
        OsConstants.ELOOP -> FdSysException(FdSysException.Kind.LINK, e)
        OsConstants.EISDIR, OsConstants.ENXIO -> FdSysException(FdSysException.Kind.NOT_FILE, e)
        else -> java.io.IOException(e.message, e)
    }
}
