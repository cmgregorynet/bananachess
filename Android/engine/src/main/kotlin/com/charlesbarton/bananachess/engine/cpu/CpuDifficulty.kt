package com.charlesbarton.bananachess.engine.cpu

import com.charlesbarton.bananachess.engine.model.RawValued

enum class CpuDifficulty(
    override val rawValue: String,
    val displayName: String,
    val blurb: String,
) : RawValued {
    Easy("easy", "Easy", "Moves without a plan. A good first game."),
    Medium("medium", "Medium", "Takes the point in front of it."),
    Hard("hard", "Hard", "Looks several moves ahead, and will trade."),
    Brutal("brutal", "Brutal", "Answers your plan before you finish it. Takes its time."),
}
