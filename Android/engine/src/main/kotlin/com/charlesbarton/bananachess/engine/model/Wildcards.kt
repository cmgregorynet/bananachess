package com.charlesbarton.bananachess.engine.model

import com.charlesbarton.bananachess.engine.setup.GameSetup

/**
 * Which rule set a match is being played under. Fixed when the match is created and
 * carried in the state, so a saved game or an online payload always says which game it is.
 */
enum class RuleSet(
    override val rawValue: String,
    val displayName: String,
    val blurb: String,
) : RawValued {
    Standard("standard", "Standard", "Score the most points."),
    Wildcard("wildcard", "Wildcard", "Golden pineapples, smoothies, and two ways to win outright."),
    ;

    val usesWildcards: Boolean get() = this == Wildcard
}

/** A value held once per player. Two named fields rather than a map, which keeps the wire format byte-stable. */
data class PlayerPair<T>(
    val one: T,
    val two: T,
) {
    constructor(value: T) : this(value, value)

    operator fun get(player: PlayerId): T = if (player == PlayerId.One) one else two

    /** A copy with [player]'s value replaced. */
    fun updated(
        player: PlayerId,
        value: T,
    ): PlayerPair<T> = if (player == PlayerId.One) copy(one = value) else copy(two = value)
}

/** Which fruit types a player has blended at least one of. */
data class SmoothieProgress(
    val banana: Boolean = false,
    val strawberry: Boolean = false,
    val pineapple: Boolean = false,
) {
    /**
     * One of every fruit type, whoever grew them. There is no restriction on whose
     * fruit counts: a recipe made entirely of your own is a smoothie like any other.
     */
    val isComplete: Boolean get() = banana && strawberry && pineapple
    val collectedCount: Int get() = listOf(banana, strawberry, pineapple).count { it }

    fun has(kind: PieceKind): Boolean =
        when (kind) {
            PieceKind.Banana -> banana
            PieceKind.Strawberry -> strawberry
            PieceKind.Pineapple -> pineapple
            PieceKind.Blender -> false
        }

    /** A copy with [kind] collected. */
    internal fun recording(kind: PieceKind): SmoothieProgress =
        when (kind) {
            PieceKind.Banana -> copy(banana = true)
            PieceKind.Strawberry -> copy(strawberry = true)
            PieceKind.Pineapple -> copy(pineapple = true)
            PieceKind.Blender -> this
        }
}

/**
 * Everything the wildcard rules need to remember. Always present so the shape of a
 * saved match never depends on its rule set; simply untouched in a standard game.
 */
data class WildcardProgress(
    /** Fruit types each player has blended, toward a Tropical Smoothie. */
    val smoothie: PlayerPair<SmoothieProgress> = PlayerPair(SmoothieProgress()),
    /** Smoothies only go off once each. */
    val smoothieFired: PlayerPair<Boolean> = PlayerPair(false),
    /** Bananas each player has delivered, toward a Banana Split. */
    val bananasDelivered: PlayerPair<Int> = PlayerPair(0),
    /** Fruit each player has blended *of their own*, toward a Blenderama. */
    val ownFruitBlended: PlayerPair<Int> = PlayerPair(0),
    /**
     * Set the moment the opponent scores at all. Blenderama is then permanently
     * unavailable to this player: tracked explicitly rather than inferred, so the
     * board can say so out loud.
     */
    val blenderamaCountered: PlayerPair<Boolean> = PlayerPair(false),
)

/** How a match was won. A points lead is only one of the ways. */
enum class WinReason(
    override val rawValue: String,
    val headline: String,
) : RawValued {
    Score("score", "wins"),
    BananaSplit("bananaSplit", "wins by Banana Split"),
    Blenderama("blenderama", "wins by Blenderama"),
}

/**
 * One player's position on each alternate win, read off the state. The scoreboard
 * tracker and the self-blend warning both ask this, so they can never disagree about
 * whether a run is still alive.
 */
data class WildcardStanding(
    val smoothie: SmoothieProgress,
    val hasFiredSmoothie: Boolean,
    val bananasDelivered: Int,
    val ownFruitBlended: Int,
    val isBlenderamaCountered: Boolean,
    /**
     * Bananas recycle, so a Split only dies when the player has no banana left at all
     * to run across: every one of theirs has been blended.
     */
    val isBananaSplitBlocked: Boolean,
    /**
     * Countered by the opponent scoring, or dead because some of this player's fruit
     * left the board by a route other than their own blender.
     */
    val isBlenderamaBlocked: Boolean,
) {
    val bananaSplitTarget: Int get() = GameSetup.BANANA_DELIVERIES_TO_WIN
    val blenderamaTarget: Int get() = GameSetup.fruitCount
    val isBananaSplitComplete: Boolean get() = bananasDelivered >= bananaSplitTarget
}

fun GameState.wildcardStanding(player: PlayerId): WildcardStanding {
    val fruit = fruit(player)
    val bananas = wildcards.bananasDelivered[player]
    val blended = wildcards.ownFruitBlended[player]
    return WildcardStanding(
        smoothie = wildcards.smoothie[player],
        hasFiredSmoothie = wildcards.smoothieFired[player],
        bananasDelivered = bananas,
        ownFruitBlended = blended,
        isBlenderamaCountered = wildcards.blenderamaCountered[player],
        isBananaSplitBlocked =
            bananas < GameSetup.BANANA_DELIVERIES_TO_WIN && fruit.none { it.kind == PieceKind.Banana },
        isBlenderamaBlocked =
            wildcards.blenderamaCountered[player] || blended + fruit.size < GameSetup.fruitCount,
    )
}
