package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.NioFdSys
import com.wangzan101.mika.core.workspace.WorkspaceFileGate

/** JVM 测试里的关口(按路径的近似;手机上是 AndroidFdSys.gate)。 */
internal val JVM_GATE = WorkspaceFileGate(NioFdSys)
