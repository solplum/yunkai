package com.wangzan101.mika.cap.web.cdp

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.wangzan101.mika.core.cap.web.cdp.CdpStream
import com.wangzan101.mika.core.cap.web.cdp.CdpTransport
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 到本进程 WebView 调试套接字的两种连法。端点就是抽象套接字名。
 * 读写的时限由协议层管(到点关流,阻塞的读写随之返回),这里不设套接字超时。
 */
internal class DirectTransport : CdpTransport {
    override fun open(endpoint: String): CdpStream {
        val s = LocalSocket()
        try {
            s.connect(LocalSocketAddress(endpoint, LocalSocketAddress.Namespace.ABSTRACT))
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
        return object : CdpStream {
            override val input: InputStream = s.inputStream
            override val output: OutputStream = s.outputStream
            override fun close() {
                runCatching { s.shutdownInput() }
                runCatching { s.shutdownOutput() }
                s.close()
            }
        }
    }
}

/** 经别人(内置助手,shell 身份)代连:[relay] 连好后把套接字交回来;代连的那一方不在时它抛 IOException。 */
internal class RelayTransport(
    private val relay: (String) -> ParcelFileDescriptor?,
) : CdpTransport {
    override fun open(endpoint: String): CdpStream {
        val pfd = try {
            relay(endpoint)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("phone helper could not relay: ${e.message}", e)
        } ?: throw IOException("phone helper refused or could not connect $endpoint")
        return FdStream(pfd)
    }
}

/** 交回来的套接字 fd。关闭时先 shutdown,让阻塞在读上的线程返回。 */
private class FdStream(private val pfd: ParcelFileDescriptor) : CdpStream {
    override val input: InputStream = FileInputStream(pfd.fileDescriptor)
    override val output: OutputStream = FileOutputStream(pfd.fileDescriptor)

    override fun close() {
        runCatching { Os.shutdown(pfd.fileDescriptor, OsConstants.SHUT_RDWR) }
        pfd.close()
    }
}
