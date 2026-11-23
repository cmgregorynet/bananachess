package com.charlesbarton.bananachess.engine.model

import com.charlesbarton.bananachess.engine.support.ConformanceFixture
import com.charlesbarton.bananachess.engine.support.parseAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ActionOrderingTest {
    private val a = PieceId("one.blender.0")
    private val b = PieceId("one.fruit.2")

    @Test
    fun `positions order by row then column`() {
        val shuffled = listOf(Position(2, 0), Position(0, 5), Position(1, 3), Position(0, 0), Position(1, 1))

        assertEquals(
            listOf(Position(0, 0), Position(0, 5), Position(1, 1), Position(1, 3), Position(2, 0)),
            shuffled.sorted(),
        )
        assertEquals(Board.allPositions, Board.allPositions.reversed().sorted())
    }

    @Test
    fun `piece ids order as plain strings`() {
        val ids = listOf("two.fruit.0", "one.fruit.10", "one.fruit.2", "one.blender.1", "Two.x").map(::PieceId)

        assertEquals(
            listOf("Two.x", "one.blender.1", "one.fruit.10", "one.fruit.2", "two.fruit.0"),
            ids.sorted().map { it.rawValue },
        )
    }

    @Test
    fun `moves order by piece then destination and ignore origin`() {
        val first = Move(a, Position(3, 3), Position(2, 2))
        val second = Move(a, Position(1, 1), Position(2, 4))
        val third = Move(b, Position(0, 0), Position(0, 1))

        assertEquals(listOf<GameAction>(first, second, third), listOf<GameAction>(third, second, first).sorted())

        val sameTargetOtherOrigin = Move(a, Position(1, 1), Position(2, 2))
        assertEquals(0, first.compareTo(sameTargetOtherOrigin))
        assertNotEquals(first, sameTargetOtherOrigin)
    }

    @Test
    fun `every move sorts before every restore`() {
        val lateMove = Move(PieceId("two.fruit.5"), Position(5, 5), Position(5, 4))
        val earlyRestore = RestoreBlender(PieceId("a"), Position(0, 0))

        assertTrue(lateMove < earlyRestore)
        assertTrue(earlyRestore > lateMove)
        assertEquals(listOf(lateMove, earlyRestore), listOf(earlyRestore, lateMove).sorted())
    }

    @Test
    fun `restores order by piece then square`() {
        val restores =
            listOf(
                RestoreBlender(b, Position(0, 1)),
                RestoreBlender(a, Position(5, 4)),
                RestoreBlender(a, Position(5, 1)),
            )

        assertEquals(
            listOf(
                RestoreBlender(a, Position(5, 1)),
                RestoreBlender(a, Position(5, 4)),
                RestoreBlender(b, Position(0, 1)),
            ),
            restores.sorted(),
        )
    }

    @Test
    fun `description strings`() {
        assertEquals("r4c0", Position(4, 0).toString())
        assertEquals("one.fruit.2", b.toString())
        assertEquals("one.fruit.2 r4c2→r3c2", Move(b, Position(4, 2), Position(3, 2)).toString())
        assertEquals('→', Move(b, Position(4, 2), Position(3, 2)).toString()[16])
        assertEquals("restore one.blender.0 at r5c4", RestoreBlender(a, Position(5, 4)).toString())
    }

    @Test
    fun `parseAction reads both kinds and refuses anything else`() {
        assertEquals(Move(b, Position(4, 2), Position(3, 2)), parseAction("one.fruit.2 r4c2→r3c2"))
        assertEquals(RestoreBlender(a, Position(5, 4)), parseAction("restore one.blender.0 at r5c4"))
        assertFailsWith<IllegalStateException> { parseAction("one.fruit.2 r4c2->r3c2") }
    }

    @Test
    fun `first-step action descriptions of every fixture game round-trip`() {
        var checked = 0
        for (game in ConformanceFixture.games) {
            val step = game.steps.first()
            val legal = checkNotNull(step.legalBefore) { "${game.label}: the first step lists its legal actions" }
            assertTrue(step.action in legal, game.label)

            val parsed = legal.map(::parseAction)
            assertEquals(legal, parsed.map { it.toString() }, game.label)
            // The fixture lists legal actions in canonical order, so sorting must not move anything.
            assertEquals(legal, parsed.reversed().sorted().map { it.toString() }, game.label)
            assertEquals(step.action, parseAction(step.action).toString(), game.label)
            checked += legal.size
        }
        assertTrue(checked > 108)
    }

    @Test
    fun `every action description in the fixture round-trips, restores included`() {
        val descriptions = ConformanceFixture.games.flatMap { game -> game.steps.map { it.action } }
        val parsed = descriptions.map(::parseAction)

        assertEquals(descriptions, parsed.map { it.toString() })
        assertTrue(parsed.any { it is RestoreBlender })
        assertIs<Move>(parsed.first())
    }
}
