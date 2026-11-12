package com.wangzan101.mika.core.cap.sandbox

import java.io.File
import java.nio.file.Files

class FakeSandboxContractTest : SandboxPathContract() {
    private val outside: File = Files.createTempDirectory("contract-outside").toFile()
    override fun sandbox(): SandboxPort = FakeSandbox()
    override fun outsideDir(): File = outside
}
