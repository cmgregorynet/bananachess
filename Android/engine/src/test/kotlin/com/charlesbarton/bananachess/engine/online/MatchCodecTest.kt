package com.charlesbarton.bananachess.engine.online

import com.charlesbarton.bananachess.engine.model.BattleReport
import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.GameStatus
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.Piece
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.PlayerPair
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RestoreBlender
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.model.Scores
import com.charlesbarton.bananachess.engine.model.SmoothieProgress
import com.charlesbarton.bananachess.engine.model.WildcardProgress
import com.charlesbarton.bananachess.engine.model.WinReason
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.setup.GameSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchCodecTest {
    // Golden data

    @Test
    fun `every golden payload decodes and re-encodes to the identical string`() {
        val samples = GoldenSamples.all
        assertTrue(samples.size > 100, "expected the full golden set, found ${samples.size}")
        assertTrue(samples.any { it.payloadJson.contains("blenderBattle") })
        assertTrue(samples.any { it.payloadJson.contains("restoreBlender") })

        for (sample in samples) {
            val payload = MatchCodec.decode(sample.payloadJson)

            assertEquals(sample.payloadJson, MatchCodec.encode(payload), sample.label)
            assertEquals(payload, MatchCodec.decode(sample.payloadJson.encodeToByteArray()), sample.label)
            assertEquals(MatchPayload.CURRENT_SCHEMA_VERSION, payload.schemaVersion, sample.label)
            assertEquals(RulesEngine.RULES_VERSION, payload.rulesVersion, sample.label)
            assertEquals(payload.state.history.size, payload.moveNumber, sample.label)
        }
    }

    @Test
    fun `decoded golden states hash to the keys the Swift engine computed`() {
        for (sample in GoldenSamples.all) {
            val state = MatchCodec.decode(sample.payloadJson).state

            assertEquals(sample.boardKey, state.boardKey, "boardKey of ${sample.label}")
            assertEquals(sample.positionKey, state.positionKey, "positionKey of ${sample.label}")
            assertEquals(sample.boardKey, state.boardHistory.last(), "boardHistory of ${sample.label}")
        }
    }

    @Test
    fun `board history keeps values above the signed 64-bit range`() {
        val histories = GoldenSamples.all.map { MatchCodec.decode(it.payloadJson).state.boardHistory }

        assertTrue(histories.any { history -> history.any { it > Long.MAX_VALUE.toULong() } })
    }

    @Test
    fun `a state encodes on its own to the same bytes it has inside a payload`() {
        for (sample in GoldenSamples.all) {
            val state = MatchCodec.decode(sample.payloadJson).state
            val json = GameStateCodec.encode(state)

            assertTrue(sample.payloadJson.endsWith("\"state\":$json}"), sample.label)
            assertEquals(state, GameStateCodec.decode(json), sample.label)
            assertEquals(state, GameStateCodec.fromJson(GameStateCodec.toJson(state)), sample.label)
        }
    }

    // Shapes the golden file does not contain, checked against strings Swift wrote.

    private val blenderOne = PieceId("one.blender.0")
    private val blenderTwo = PieceId("two.blender.1")
    private val banana = PieceId("one.fruit.0")

    private fun everythingState(status: GameStatus): GameState =
        GameState(
            pieces =
                listOf(
                    Piece(blenderTwo, PlayerId.Two, PieceKind.Blender, null, home = Position(0, 4), respawnTurn = 9),
                    Piece(blenderOne, PlayerId.One, PieceKind.Blender, Position(5, 1), home = Position(5, 1)),
                    Piece(banana, PlayerId.One, PieceKind.Banana, Position(4, 0)),
                ),
            activePlayer = PlayerId.Two,
            scores = Scores(one = 12, two = -7),
            status = status,
            turnNumber = 8,
            history = listOf(RestoreBlender(blenderOne, Position(5, 4)), Move(banana, Position(4, 0), Position(3, 0))),
            ruleSet = RuleSet.Wildcard,
            boardSetup = BoardSetup.Random,
            boardWrap = BoardWrap.All,
            blenderBattles = true,
            boardHistory = listOf(0uL, 1uL, 9223372036854775807uL, 9223372036854775808uL, ULong.MAX_VALUE),
            wildcards =
                WildcardProgress(
                    smoothie = PlayerPair(SmoothieProgress(banana = true), SmoothieProgress(pineapple = true)),
                    smoothieFired = PlayerPair(false, true),
                    bananasDelivered = PlayerPair(2, 0),
                    ownFruitBlended = PlayerPair(0, 5),
                    blenderamaCountered = PlayerPair(true, false),
                ),
        )

    private val everyEvent: List<GameEvent> =
        listOf(
            GameEvent.Moved(banana, Position(4, 0), Position(3, 0)),
            GameEvent.BlenderBattle(
                BattleReport(PlayerId.One, 1, 1, 6, defenderFruit = 0, defenderRoll = 3),
                Position(2, 2),
            ),
            GameEvent.BlenderDestroyed(blenderTwo, PlayerId.Two, by = blenderOne, at = Position(2, 2)),
            GameEvent.BlenderRestored(blenderOne, PlayerId.One, at = Position(5, 4), cost = 3),
            GameEvent.BlenderRespawned(blenderTwo, PlayerId.Two, at = Position(0, 4)),
            GameEvent.BananaReturned(banana, to = Position(4, 0)),
            GameEvent.Blended(
                banana,
                PieceKind.Banana,
                PlayerId.One,
                Position(3, 0),
                awardedTo = PlayerId.Two,
                points = -1,
            ),
            GameEvent.Delivered(banana, PieceKind.Strawberry, PlayerId.One, Position(0, 0), points = 4),
            GameEvent.ScoreChanged(PlayerId.Two, newScore = -7),
            GameEvent.TurnPassed(skipped = PlayerId.Two),
            GameEvent.TurnChanged(active = PlayerId.One),
            GameEvent.GameEnded(Outcome.Draw),
            GameEvent.GameEnded(Outcome.Win(PlayerId.One, WinReason.BananaSplit)),
            GameEvent.GameEnded(Outcome.Win(PlayerId.Two, WinReason.Blenderama)),
            GameEvent.PineappleTurnedGolden(banana, PlayerId.One),
            GameEvent.SmoothiePoured(PlayerId.Two, points = 3),
            GameEvent.Smoothie(PlayerId.One, listOf(blenderTwo, blenderOne), respawnTurn = 9),
            GameEvent.Smoothie(PlayerId.Two, emptyList(), respawnTurn = 0),
        )

    private val everything =
        MatchPayload(
            matchId = SwiftSamples.AWKWARD_ID,
            moveNumber = 2,
            state = everythingState(GameStatus.Ended(Outcome.Draw)),
            lastMove = LastMove(RestoreBlender(blenderOne, Position(5, 4)), PlayerId.One, everyEvent),
        )

    private val everythingJson =
        SwiftSamples.EVERYTHING.replace(SwiftSamples.ID_PLACEHOLDER, SwiftSamples.AWKWARD_ID_ESCAPED)

    @Test
    fun `every event, a draw and an awkward match id encode exactly as Swift writes them`() {
        assertEquals(everythingJson, MatchCodec.encode(everything))
        assertEquals(everything, MatchCodec.decode(everythingJson))
        assertEquals(everything, MatchCodec.decode(everythingJson.encodeToByteArray()))
    }

    @Test
    fun `the wildcard win reasons encode exactly as Swift writes them`() {
        val split = GameStatus.Ended(Outcome.Win(PlayerId.One, WinReason.BananaSplit))
        val blenderama = GameStatus.Ended(Outcome.Win(PlayerId.Two, WinReason.Blenderama))

        for ((status, json) in listOf(split to SwiftSamples.BANANA_SPLIT, blenderama to SwiftSamples.BLENDERAMA)) {
            val payload = MatchPayload(matchId = "m", moveNumber = 2, state = everythingState(status))

            assertEquals(json, MatchCodec.encode(payload))
            assertEquals(payload, MatchCodec.decode(json))
        }
    }

    @Test
    fun `a new match encodes exactly as Swift writes one`() {
        val payload = MatchPayload.newMatch(id = "550E8400-E29B-41D4-A716-446655440000")

        assertEquals(SwiftSamples.FRESH, MatchCodec.encode(payload))
        assertEquals(payload, MatchCodec.decode(SwiftSamples.FRESH))
        assertNull(payload.lastMove)
    }

    @Test
    fun `a slash in a match id is escaped and survives`() {
        val payload = MatchPayload.newMatch(id = "a/b")
        val json = MatchCodec.encode(payload)

        assertTrue(json.startsWith("""{"matchID":"a\/b","""), json.take(40))
        assertEquals("a/b", MatchCodec.decode(json).matchId)
    }

    // Ported from OnlineTests.swift

    @Test
    fun `a payload from a newer schema is refused, not guessed at`() {
        val newer = MatchPayload.CURRENT_SCHEMA_VERSION + 1
        val json = MatchCodec.encode(newMatch().copy(schemaVersion = newer, moveNumber = 3))

        val error = assertFailsWith<MatchCodecError.UnsupportedSchema> { MatchCodec.decode(json) }

        assertEquals(MatchCodecError.UnsupportedSchema(newer, MatchPayload.CURRENT_SCHEMA_VERSION), error)
        assertEquals("This match was played on a newer version of Banana Chess. Update to continue.", error.message)
    }

    @Test
    fun `an older wire format is refused too`() {
        val older = MatchPayload.CURRENT_SCHEMA_VERSION - 1
        val json = MatchCodec.encode(newMatch().copy(schemaVersion = older, moveNumber = 4))

        val error = assertFailsWith<MatchCodecError.OutdatedSchema> { MatchCodec.decode(json) }

        assertEquals(MatchCodecError.OutdatedSchema(older, MatchPayload.CURRENT_SCHEMA_VERSION), error)
        assertEquals(PREVIOUS_RULES, error.message)
    }

    @Test
    fun `a payload from a newer rule set is refused`() {
        val newer = RulesEngine.RULES_VERSION + 1
        val json = MatchCodec.encode(newMatch().copy(rulesVersion = newer))

        val error = assertFailsWith<MatchCodecError.IncompatibleRules> { MatchCodec.decode(json) }

        assertEquals(MatchCodecError.IncompatibleRules(newer, RulesEngine.RULES_VERSION), error)
        assertEquals(PREVIOUS_RULES, error.message)
    }

    @Test
    fun `a match started under the previous rules is refused, not silently continued`() {
        val older = RulesEngine.RULES_VERSION - 1
        val json = MatchCodec.encode(newMatch().copy(rulesVersion = older, moveNumber = 4))

        val error = assertFailsWith<MatchCodecError.IncompatibleRules> { MatchCodec.decode(json) }

        assertEquals(MatchCodecError.IncompatibleRules(older, RulesEngine.RULES_VERSION), error)
    }

    @Test
    fun `a newer schema is named even when the rest of the payload has changed shape`() {
        val error =
            assertFailsWith<MatchCodecError.UnsupportedSchema> {
                MatchCodec.decode("""{"rulesVersion":19,"schemaVersion":17,"somethingNew":[1,2,3]}""")
            }

        assertEquals(17, error.found)
    }

    @Test
    fun `garbage is rejected as malformed`() {
        val error = assertFailsWith<MatchCodecError.Malformed> { MatchCodec.decode("not a match") }

        assertEquals("This match's data could not be read.", error.message)
        assertFailsWith<MatchCodecError.Malformed> { MatchCodec.decode("not a match".encodeToByteArray()) }
    }

    @Test
    fun `a destroyed blender survives encoding`() {
        val mine = Piece(blenderOne, PlayerId.One, PieceKind.Blender, Position(3, 3), home = Position(5, 1))
        val theirs = Piece(blenderTwo, PlayerId.Two, PieceKind.Blender, Position(2, 2), home = Position(0, 4))
        val state =
            GameState(
                pieces =
                    listOf(
                        mine,
                        theirs,
                        Piece(banana, PlayerId.One, PieceKind.Banana, Position(4, 0)),
                        Piece(PieceId("two.fruit.0"), PlayerId.Two, PieceKind.Banana, Position(1, 5)),
                    ),
            )
        val midKnockout = RulesEngine.apply(Move(mine.id, Position(3, 3), Position(2, 2)), state).state

        val decoded =
            MatchCodec.decode(
                MatchCodec.encode(MatchPayload(matchId = "m1", moveNumber = 1, state = midKnockout)),
            )

        assertEquals(midKnockout, decoded.state)
        assertEquals(Position(2, 2), decoded.state.piece(mine.id)?.position, "the attacker survived")
        assertEquals(listOf(theirs.id), decoded.state.destroyedBlenders.map { it.id }, "the defender did not")
        // And the restored match carries on identically.
        assertEquals(advance(midKnockout, turns = 4), advance(decoded.state, turns = 4))
    }

    @Test
    fun `last-move metadata survives the round trip so the opponent's move animates`() {
        val state = GameSetup.newGame()
        val move = RulesEngine.legalMoves(state).first()
        val transition = RulesEngine.apply(move, state)
        val payload =
            MatchPayload(
                matchId = "m1",
                moveNumber = 1,
                state = transition.state,
                lastMove = LastMove(move, PlayerId.One, transition.events),
            )

        val decoded = MatchCodec.decode(MatchCodec.encode(payload))

        assertEquals(transition.events, decoded.lastMove?.events)
        assertEquals(move, decoded.lastMove?.move)
        assertEquals(payload, decoded)
    }

    @Test
    fun `a restore is not a move`() {
        assertNull(everything.lastMove?.move)
    }

    // Input from outside the app

    @Test
    fun `an explicit null last move is the same as none, and unknown keys are ignored`() {
        val json = SwiftSamples.FRESH.replace("""{"matchID"""", """{"extra":{"a":[1]},"lastMove":null,"matchID"""")

        assertEquals(MatchCodec.decode(SwiftSamples.FRESH), MatchCodec.decode(json))
    }

    @Test
    fun `pieces arriving out of order are sorted`() {
        val json = SwiftSamples.FRESH.replace(""""id":"one.blender.0"""", """"id":"zzz"""")

        val state = MatchCodec.decode(json).state

        assertEquals(PieceId("zzz"), state.pieces.last().id)
        assertEquals(state.pieces.sortedBy { it.id }, state.pieces)
    }

    @Test
    fun `payloads of the wrong shape are malformed`() {
        val broken =
            mapOf(
                "empty" to "",
                "an array" to "[]",
                "a string" to "\"match\"",
                "an empty object" to "{}",
                "trailing text" to SwiftSamples.FRESH + " x",
                "cut short" to SwiftSamples.FRESH.dropLast(1),
                "no state" to SwiftSamples.FRESH.substringBefore(""","state"""") + "}",
                "state is null" to SwiftSamples.FRESH.substringBefore(""""state":""") + """"state":null}""",
                "version as a string" to fresh(""""schemaVersion":16""", """"schemaVersion":"16""""),
                "move number as a string" to fresh(""""moveNumber":0""", """"moveNumber":"0""""),
                "move number as a fraction" to fresh(""""moveNumber":0""", """"moveNumber":0.5"""),
                "move number past 32 bits" to fresh(""""moveNumber":0""", """"moveNumber":99999999999"""),
                "match id as a number" to
                    fresh(""""matchID":"550E8400-E29B-41D4-A716-446655440000"""", """"matchID":7"""),
                "boolean as a string" to fresh(""""blenderBattles":false""", """"blenderBattles":"false""""),
                "boolean in capitals" to fresh(""""blenderBattles":false""", """"blenderBattles":FALSE"""),
                "unknown player" to fresh(""""activePlayer":"one"""", """"activePlayer":"three""""),
                "unknown kind" to fresh(""""kind":"banana"""", """"kind":"mango""""),
                "unknown status" to fresh("""{"inProgress":{}}""", """{"paused":{}}"""),
                "two statuses at once" to
                    fresh("""{"inProgress":{}}""", """{"inProgress":{},"ended":{"_0":{"draw":{}}}}"""),
                "status as a string" to fresh("""{"inProgress":{}}""", """"inProgress""""),
                "negative board key" to fresh("[10941334533497594564]", "[-1]"),
                "board key past 64 bits" to fresh("[10941334533497594564]", "[18446744073709551616]"),
                "board key as a string" to fresh("[10941334533497594564]", """["10941334533497594564"]"""),
                "pieces as an object" to fresh(""""history":[]""", """"history":{}"""),
                "a square off the board" to fresh("""{"column":1,"row":5}""", """{"column":1,"row":6}"""),
                "a negative square" to fresh("""{"column":0,"row":4}""", """{"column":-1,"row":4}"""),
                "a square with no row" to fresh("""{"column":1,"row":5}""", """{"column":1}"""),
                "two pieces with one id" to fresh(""""id":"one.blender.1"""", """"id":"one.blender.0""""),
                "two pieces on one square" to
                    fresh(
                        """"owner":"one","position":{"column":4,"row":5}""",
                        """"owner":"one","position":{"column":1,"row":5}""",
                    ),
            )

        for ((label, json) in broken) {
            assertFailsWith<MatchCodecError.Malformed>(label) { MatchCodec.decode(json) }
            assertFailsWith<MatchCodecError.Malformed>(label) { MatchCodec.decode(json.encodeToByteArray()) }
        }
    }

    @Test
    fun `actions and events of the wrong shape are malformed`() {
        val squares = """"destination":{"column":0,"row":3},"origin":{"column":0,"row":4}"""
        val move = """{"move":{"_0":{$squares,"pieceID":"x"}}}"""
        val lastMove = """{"action":$move,"events":[{"turnChanged":{"active":"two"}}],"playedBy":"one"}"""
        val withLastMove = { text: String ->
            SwiftSamples.FRESH.replace("""{"matchID"""", """{"lastMove":$text,"matchID"""")
        }
        assertEquals(PlayerId.One, MatchCodec.decode(withLastMove(lastMove)).lastMove?.playedBy)

        val broken =
            listOf(
                lastMove.replace(""""_0":""", """"move":"""),
                lastMove.replace(""""move"""", """"jump""""),
                lastMove.replace(""""pieceID":"x"""", """"pieceID":1"""),
                lastMove.replace(""""row":3""", """"row":36"""),
                lastMove.replace(""""turnChanged"""", """"turnReversed""""),
                lastMove.replace("""{"active":"two"}""", """{"active":2}"""),
                lastMove.replace("""{"active":"two"}""", "[]"),
                lastMove.replace("""[{"turnChanged":{"active":"two"}}]""", """[{}]"""),
                lastMove.replace("""[{"turnChanged":{"active":"two"}}]""", """{}"""),
                lastMove.replace(""","playedBy":"one"""", ""),
                "[]",
                "7",
            )

        for (text in broken) {
            assertFailsWith<MatchCodecError.Malformed>(text) { MatchCodec.decode(withLastMove(text)) }
        }
    }

    @Test
    fun `bytes that are not UTF-8 are malformed`() {
        val bytes = SwiftSamples.FRESH.encodeToByteArray()
        val idStart = SwiftSamples.FRESH.indexOf("550E")

        assertFailsWith<MatchCodecError.Malformed> {
            MatchCodec.decode(
                byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00),
            )
        }
        assertFailsWith<MatchCodecError.Malformed> {
            MatchCodec.decode(bytes.copyOf().also { it[idStart] = 0xC3.toByte() })
        }
    }

    @Test
    fun `deeply nested input is refused without exhausting the stack`() {
        val depth = 200_000

        assertFailsWith<MatchCodecError.Malformed> { MatchCodec.decode("[".repeat(depth)) }
        assertFailsWith<MatchCodecError.Malformed> { MatchCodec.decode("[".repeat(depth) + "]".repeat(depth)) }
        assertFailsWith<MatchCodecError.Malformed> {
            GameStateCodec.decode("{\"a\":".repeat(depth) + "1" + "}".repeat(depth))
        }
    }

    @Test
    fun `a payload larger than the limit is refused before it is parsed`() {
        val padded = " ".repeat(MatchCodec.MAX_PAYLOAD_BYTES) + SwiftSamples.FRESH

        assertFailsWith<MatchCodecError.Malformed> { MatchCodec.decode(padded) }
        assertFailsWith<MatchCodecError.Malformed> { MatchCodec.decode(padded.encodeToByteArray()) }
        assertEquals(0, MatchCodec.decode(" " + SwiftSamples.FRESH + "\n").moveNumber)
    }

    private fun newMatch(): MatchPayload = MatchPayload.newMatch(id = "m1")

    private fun fresh(
        old: String,
        new: String,
    ): String {
        assertTrue(SwiftSamples.FRESH.contains(old), "the sample no longer contains $old")
        return SwiftSamples.FRESH.replaceFirst(old, new)
    }

    private fun advance(
        state: GameState,
        turns: Int,
    ): GameState =
        (0 until turns).fold(state) { current, _ ->
            RulesEngine.apply(RulesEngine.legalActions(current).first(), current).state
        }

    private companion object {
        const val PREVIOUS_RULES =
            "This match was started under the previous rules and can't be continued. " +
                "Start a new match to play with the current ones."
    }
}
