package com.charlesbarton.bananachess.match

import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.engine.model.PlayerId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatchModeTest {
    @Test
    fun `local play names both seats and puts player one at the bottom`() {
        val mode = MatchMode.LocalVersus

        assertEquals("Player One", mode.name(PlayerId.One))
        assertEquals("Player Two", mode.name(PlayerId.Two))
        assertEquals(PlayerId.One, mode.bottomPlayer)
        assertTrue(mode.canControl(PlayerId.One))
        assertTrue(mode.canControl(PlayerId.Two))
    }

    @Test
    fun `a computer match names the human You and the CPU by its difficulty`() {
        val mode = MatchMode.Computer(difficulty = CpuDifficulty.Brutal, humanPlays = PlayerId.Two)

        assertEquals("You", mode.name(PlayerId.Two))
        assertEquals("Brutal CPU", mode.name(PlayerId.One))
        assertEquals(PlayerId.Two, mode.bottomPlayer)
        assertTrue(mode.canControl(PlayerId.Two))
        assertFalse(mode.canControl(PlayerId.One))
    }

    @Test
    fun `a link match names the local player You and the other Your opponent`() {
        val mode = MatchMode.ByLink(matchId = "abc", localPlayer = PlayerId.Two)

        assertEquals("You", mode.name(PlayerId.Two))
        assertEquals("Your opponent", mode.name(PlayerId.One))
        assertEquals(PlayerId.Two, mode.bottomPlayer)
        assertTrue(mode.canControl(PlayerId.Two))
        assertFalse(mode.canControl(PlayerId.One))
    }
}
