package com.wangzan101.mika.core.cap.sandbox

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Sha256Test {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun knownVector() {
        val f = tmp.newFile("abc.txt").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.ofFile(f))
    }

    @Test fun emptyFile() {
        val f = tmp.newFile("empty")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.ofFile(f))
    }
}
