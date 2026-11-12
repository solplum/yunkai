package com.wangzan101.mika.cap.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.cap.sandbox.SandboxPathContract
import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import org.junit.runner.RunWith
import java.io.File

/** 路径规则契约在真机的文件系统上跑(符号链接、canonicalFile 的行为以安卓为准)。 */
@RunWith(AndroidJUnit4::class)
class AndroidSandboxPathContractDeviceTest : SandboxPathContract() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    override fun sandbox(): SandboxPort = AndroidSandbox(context, SandboxState(context))
    override fun outsideDir(): File = File(context.filesDir, "contract-outside").apply { mkdirs() }
}
