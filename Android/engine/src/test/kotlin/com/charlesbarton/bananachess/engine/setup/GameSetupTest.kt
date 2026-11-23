package com.charlesbarton.bananachess.engine.setup

import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.GameStatus
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.model.Scores
import com.charlesbarton.bananachess.engine.model.WildcardProgress
import com.charlesbarton.bananachess.engine.support.ConformanceFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GameSetupTest {
    @Test
    fun `every fixture opening is reproduced exactly`() {
        val games = ConformanceFixture.games
        assertEquals(108, games.size)
        assertEquals(36, games.count { it.boardSetupValue == BoardSetup.Random })

        for (game in games) {
            val state = game.newGame()

            assertEquals(game.opening.pieces.map { it.toPiece() }, state.pieces, game.label)
            assertEquals(game.opening.activePlayerValue, state.activePlayer, game.label)
            assertEquals(game.startingPlayerValue, state.activePlayer, game.label)
            assertEquals(1, state.turnNumber, game.label)
            assertEquals(game.ruleSetValue, state.ruleSet, game.label)
            assertEquals(game.boardSetupValue, state.boardSetup, game.label)
            assertEquals(game.boardWrapValue, state.boardWrap, game.label)
            assertEquals(game.blenderBattles, state.blenderBattles, game.label)
        }
    }

    @Test
    fun `a new game starts clean`() {
        val state = GameSetup.newGame()

        assertEquals(PlayerId.One, state.activePlayer)
        assertEquals(Scores(), state.scores)
        assertEquals(GameStatus.InProgress, state.status)
        assertEquals(emptyList(), state.history)
        assertEquals(listOf(state.boardKey), state.boardHistory)
        assertEquals(WildcardProgress(), state.wildcards)
        assertEquals(RuleSet.Standard, state.ruleSet)
        assertEquals(BoardWrap.Flat, state.boardWrap)
    }

    @Test
    fun `standard puts blenders behind and mirrored fruit in front`() {
        val state = GameSetup.newGame()

        assertEquals(16, state.pieces.size)
        assertEquals(
            listOf(Position(5, 1), Position(5, 4)),
            state.blenders(PlayerId.One).map { it.position },
        )
        assertEquals(
            listOf(Position(0, 1), Position(0, 4)),
            state.blenders(PlayerId.Two).map { it.position },
        )
        for (player in PlayerId.entries) {
            val fruit = state.fruit(player)
            assertEquals(GameSetup.fruitOrder, fruit.map { it.kind })
            assertTrue(fruit.all { it.position?.row == player.fruitRow })
        }
        // Each player reads the same order from their own left.
        assertEquals(listOf(0, 1, 2, 3, 4, 5), state.fruit(PlayerId.One).map { it.position?.column })
        assertEquals(listOf(5, 4, 3, 2, 1, 0), state.fruit(PlayerId.Two).map { it.position?.column })
        assertTrue(state.pieces.all { it.home == it.position })
    }

    @Test
    fun `blendy swaps the two rows`() {
        val state = GameSetup.newGame(boardSetup = BoardSetup.Blendy)

        for (player in PlayerId.entries) {
            assertTrue(state.fruit(player).all { it.position?.row == player.backRow })
            assertTrue(state.blenders(player).all { it.position?.row == player.fruitRow })
            assertEquals(GameSetup.blenderHomeColumns, state.blenders(player).map { it.position?.column })
        }
        assertEquals(BoardSetup.Blendy, state.boardSetup)
    }

    @Test
    fun `random is reproducible from a seed and keeps fruit off its delivery row`() {
        for (seed in 0uL until 200uL) {
            val state = GameSetup.newGame(boardSetup = BoardSetup.Random, seed = seed)

            assertEquals(state, GameSetup.newGame(boardSetup = BoardSetup.Random, seed = seed))
            assertEquals(
                16,
                state.pieces
                    .mapNotNull { it.position }
                    .toSet()
                    .size,
                "seed $seed",
            )
            assertTrue(state.pieces.all { it.home == it.position }, "seed $seed")
            for (player in PlayerId.entries) {
                assertTrue(state.fruit(player).none { it.position?.row == player.deliveryRow }, "seed $seed")
                assertEquals(GameSetup.fruitOrder, state.fruit(player).map { it.kind })
            }
        }
        assertNotEquals(
            GameSetup.newGame(boardSetup = BoardSetup.Random, seed = 1uL).pieces,
            GameSetup.newGame(boardSetup = BoardSetup.Random, seed = 2uL).pieces,
        )
    }

    @Test
    fun `random without a seed still deals a legal board`() {
        val state = GameSetup.newGame(boardSetup = BoardSetup.Random)

        assertEquals(
            16,
            state.pieces
                .mapNotNull { it.position }
                .toSet()
                .size,
        )
    }

    @Test
    fun `tuning constants`() {
        assertEquals(6, GameSetup.fruitCount)
        assertEquals(2, GameSetup.bananaCount)
        assertEquals(3, GameSetup.BANANA_DELIVERIES_TO_WIN)
        assertEquals(2, GameSetup.fruitOrder.count { it == PieceKind.Pineapple })
        assertEquals("two.blender.1", GameSetup.pieceId(PlayerId.Two, "blender", 1).rawValue)
    }
}
