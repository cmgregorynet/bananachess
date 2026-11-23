package com.charlesbarton.bananachess.engine.model

/** Stable identifier that survives encoding, undo, and network round trips. Ordered by plain string comparison. */
@JvmInline
value class PieceId(
    val rawValue: String,
) : Comparable<PieceId> {
    override fun compareTo(other: PieceId): Int = rawValue.compareTo(other.rawValue)

    override fun toString(): String = rawValue
}

enum class PieceKind(
    override val rawValue: String,
) : RawValued {
    Banana("banana"),
    Strawberry("strawberry"),
    Pineapple("pineapple"),
    Blender("blender"),
    ;

    val isFruit: Boolean get() = this != Blender

    /** Points a blender's owner receives for blending this fruit. Delivery pays double. */
    val baseValue: Int
        get() =
            when (this) {
                Banana -> 1
                Strawberry -> 2
                Pineapple -> 3
                Blender -> 0
            }

    val deliveryValue: Int get() = baseValue * 2
}

data class Piece(
    val id: PieceId,
    val owner: PlayerId,
    val kind: PieceKind,
    /**
     * Null while the piece is off the board: a destroyed blender waiting to be bought
     * back, or a delivered banana waiting for its spawn square to clear. A piece that
     * is nowhere is genuinely nowhere: it does not occupy its spawn.
     */
    val position: Position?,
    /** The square this piece started on. Blenders are restored here; delivered bananas return here. */
    val home: Position? = null,
    /**
     * Blenders only, and only after a Tropical Smoothie: the global turn at whose start
     * this blender comes back on its own. A blender destroyed in combat has no such
     * date; it returns when, and only when, its owner pays for it.
     */
    val respawnTurn: Int? = null,
) {
    val isFruit: Boolean get() = kind.isFruit
    val isBlender: Boolean get() = kind == PieceKind.Blender
    val isOnBoard: Boolean get() = position != null

    /**
     * Off the board. Destroyed in combat if there is no return date, in which case it
     * only comes back when its owner buys it; blown up by a smoothie if there is.
     */
    val isDestroyed: Boolean get() = isBlender && position == null

    /** Off the board but due back on its own after a Tropical Smoothie. */
    val isAwaitingRespawn: Boolean get() = isDestroyed && respawnTurn != null

    /** Off the board for good unless bought back. */
    val needsBuyingBack: Boolean get() = isDestroyed && respawnTurn == null

    /**
     * Delivered, scored, and queued to reappear on its spawn as soon as that square is
     * free. Wildcard rules only: bananas do not recycle in a standard match.
     */
    val isWaitingToReturn: Boolean get() = kind == PieceKind.Banana && position == null
}
