package com.charlesbarton.bananachess.engine.support

import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.RawValued
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.model.rawValueOf
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.setup.GameSetup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.zip.GZIPInputStream

/**
 * The golden data written by the Swift engine (`golden.json.gz`): 18 games, each with
 * positions sampled along the way and what Swift computed for them.
 *
 * Parsed once per test JVM. Use `GoldenFixture.games`; each [GoldenGame] rebuilds its
 * states by replaying its recorded actions through the Kotlin [RulesEngine].
 */
object GoldenFixture {
    val file: GoldenFile by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/golden.json.gz")) { "golden.json.gz is missing" }
        val text = GZIPInputStream(stream).use { it.readBytes().decodeToString() }
        Json.decodeFromString<GoldenFile>(text)
    }

    val games: List<GoldenGame> get() = file.games
}

@Serializable
data class GoldenFile(
    val schemaVersion: Int,
    val rulesVersion: Int,
    val games: List<GoldenGame>,
)

@Serializable
data class GoldenGame(
    val seed: ULong,
    val ruleSet: String,
    val boardSetup: String,
    val boardWrap: String,
    val blenderBattles: Boolean,
    val startingPlayer: String,
    /** Action description strings, in the order they were played. */
    val actions: List<String>,
    val samples: List<GoldenSample>,
) {
    val ruleSetValue: RuleSet get() = raw(ruleSet)
    val boardSetupValue: BoardSetup get() = raw(boardSetup)
    val boardWrapValue: BoardWrap get() = raw(boardWrap)
    val startingPlayerValue: PlayerId get() = raw(startingPlayer)

    /** A label for assertion messages. */
    val label: String get() = "$ruleSet/$boardSetup/$boardWrap/battles=$blenderBattles/seed=$seed"

    fun newGame(): GameState =
        GameSetup.newGame(
            startingPlayer = startingPlayerValue,
            ruleSet = ruleSetValue,
            boardSetup = boardSetupValue,
            boardWrap = boardWrapValue,
            blenderBattles = blenderBattles,
            seed = seed,
        )

    /**
     * Every state of the game, replayed with real battle dice: index 0 is the opening
     * and index `n` is the state after `n` actions, which is what [GoldenSample.step] counts.
     */
    fun states(): List<GameState> = actions.runningFold(newGame(), ::advance)

    /** The state after [step] actions. */
    fun stateAt(step: Int): GameState {
        require(step in 0..actions.size) { "$label has ${actions.size} actions, asked for step $step" }
        return actions.take(step).fold(newGame(), ::advance)
    }

    private fun advance(
        state: GameState,
        action: String,
    ): GameState = RulesEngine.apply(parseAction(action), state).state
}

/** One sampled position and the values the Swift engine computed for it. */
@Serializable
data class GoldenSample(
    /** How many actions had been applied; 0 is the opening. */
    val step: Int,
    /** Decimal string of an unsigned 64-bit key. */
    val boardKey: String,
    /** Decimal string of an unsigned 64-bit key. */
    val positionKey: String,
    /** `Evaluation.score` for player one with the default weights. */
    val evaluationOne: Int,
    /** `Evaluation.score` for player two with the default weights. */
    val evaluationTwo: Int,
    /** The Swift `MatchPayload` JSON for this position, byte for byte. */
    val payloadJSON: String,
    /** The Swift `MatchLink` URL for this position. */
    val link: String,
) {
    val boardKeyValue: ULong get() = boardKey.toULong()
    val positionKeyValue: ULong get() = positionKey.toULong()
}

private inline fun <reified E> raw(value: String): E where E : Enum<E>, E : RawValued =
    checkNotNull(rawValueOf<E>(value)) { "unknown ${E::class.simpleName} '$value'" }
