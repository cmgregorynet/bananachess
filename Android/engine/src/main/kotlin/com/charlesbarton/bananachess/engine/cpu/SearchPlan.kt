package com.charlesbarton.bananachess.engine.cpu

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a difficulty actually *is*, as data: how deep it looks, how carelessly it
 * chooses among what it found, and how long it may take. The numbers are the Swift
 * engine's, where the duels that settled each one are recorded.
 */
data class SearchPlan(
    /** How far ahead to look, if the clock allows. One is a plain one-ply pick with no lookahead. */
    val depth: Int,
    /**
     * What fraction of the ranked moves to choose randomly between. One takes any legal
     * move at all; anything at or below zero always plays the best one it found.
     */
    val candidateFraction: Double,
    /** Stops deepening past this. [hardTimeCap] is absolute. */
    val softTimeBudget: Duration,
    val hardTimeCap: Duration,
    /**
     * How close two lines must be before the tie is broken on banking points now.
     * Zero leaves the search's own judgement alone.
     */
    val tempoMargin: Int = 0,
    /**
     * How many extra plies of *forcing* moves to follow past the nominal depth, so a
     * line is never judged in the middle of a blend. Zero turns it off.
     */
    val quiescenceDepth: Int = 0,
) {
    companion object {
        /** The shipped levels. */
        fun standard(difficulty: CpuDifficulty): SearchPlan =
            when (difficulty) {
                // Looks one move ahead and then picks carelessly among the better half.
                CpuDifficulty.Easy -> {
                    SearchPlan(
                        depth = 1,
                        candidateFraction = 0.5,
                        softTimeBudget = 50.milliseconds,
                        hardTimeCap = 600.milliseconds,
                    )
                }

                // Searches, but kept brisk: a middle setting that stalls is a worse opponent.
                CpuDifficulty.Medium -> {
                    SearchPlan(
                        depth = 4,
                        candidateFraction = 0.0,
                        softTimeBudget = 220.milliseconds,
                        hardTimeCap = 450.milliseconds,
                        tempoMargin = 48,
                    )
                }

                CpuDifficulty.Hard -> {
                    SearchPlan(
                        depth = 12,
                        candidateFraction = 0.0,
                        softTimeBudget = 1_500.milliseconds,
                        hardTimeCap = 2.seconds,
                    )
                }

                // Spends its longer clock on selective depth down forcing lines.
                CpuDifficulty.Brutal -> {
                    SearchPlan(
                        depth = 12,
                        candidateFraction = 0.0,
                        softTimeBudget = 2.seconds,
                        hardTimeCap = 4.seconds,
                        quiescenceDepth = 8,
                    )
                }
            }
    }
}

val CpuDifficulty.plan: SearchPlan get() = SearchPlan.standard(this)
