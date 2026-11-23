package com.charlesbarton.bananachess.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineInfoTest {
    @Test
    fun `engine module is named`() {
        assertEquals("BananaChessEngine", EngineInfo.NAME)
    }

    @Test
    fun `conformance fixture property points at an existing file`() {
        val path = assertNotNull(System.getProperty("conformance.fixture"))
        val fixture = File(path)

        assertTrue(fixture.isAbsolute, "expected an absolute path, got $path")
        assertTrue(fixture.isFile, "no fixture at $path")
    }
}
