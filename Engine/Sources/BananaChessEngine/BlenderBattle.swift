/// The full arithmetic of one blender battle, kept so the board can show its working.
///
/// A battle is the optional alternative to a blender simply destroying whatever it runs
/// into. Both sides count the value of the fruit they still have **standing on the
/// board** — blended fruit is gone and delivered fruit has left, so neither counts — the
/// attacker adds two for going in, and both roll a die.
public struct BattleReport: Hashable, Codable, Sendable {
    public let attacker: PlayerID
    public let attackerFruit: Int
    public let attackerBonus: Int
    public let attackerRoll: Int
    public let defenderFruit: Int
    public let defenderRoll: Int

    public init(
        attacker: PlayerID,
        attackerFruit: Int,
        attackerBonus: Int,
        attackerRoll: Int,
        defenderFruit: Int,
        defenderRoll: Int
    ) {
        self.attacker = attacker
        self.attackerFruit = attackerFruit
        self.attackerBonus = attackerBonus
        self.attackerRoll = attackerRoll
        self.defenderFruit = defenderFruit
        self.defenderRoll = defenderRoll
    }

    public var defender: PlayerID { attacker.opponent }
    public var attackerTotal: Int { attackerFruit + attackerBonus + attackerRoll }
    public var defenderTotal: Int { defenderFruit + defenderRoll }

    /// A tie goes to the attacker.
    ///
    /// The rule says "highest total wins", which leaves a draw undefined, and a battle
    /// has to end with exactly one blender gone — the two cannot share the square. The
    /// attacker's `+2` says the design means attacking to be favoured, and attacker-wins
    /// also matches what a blender collision did before battles existed.
    public var attackerWins: Bool { attackerTotal >= defenderTotal }

    public var winner: PlayerID { attackerWins ? attacker : defender }
}

/// How a blender battle is settled.
public enum BattleResolution: Hashable, Sendable {
    /// Two real dice, drawn deterministically from the position so that both devices in
    /// an online match compute the same battle from the same move.
    case dice
    /// No dice: both sides roll the average. Used by the CPU's search only.
    ///
    /// The dice have to be a pure function of the position, or online play desynchronises
    /// — but that also means a searching CPU could read the roll before deciding whether
    /// to attack, and would then only ever pick battles it had already won. Searching
    /// with the dice replaced by their average keeps the CPU honest: it weighs a battle
    /// on the fruit either side has standing, which is the part it can actually see.
    case expected
}
