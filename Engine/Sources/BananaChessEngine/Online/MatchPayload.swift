import Foundation

/// What one client hands the next through Game Center. Versioned, complete, and free of
/// anything the UI merely wants — the state in here is the whole truth of the match.
public struct MatchPayload: Codable, Sendable, Hashable {
    /// Bump when the wire format changes shape. Clients refuse payloads they do not
    /// match exactly rather than guessing.
    ///
    /// 2 — blenders carry an off-board state and a respawn turn.
    /// 3 — matches carry their rule set and wildcard progress.
    /// 4 — Golden Pineapple is tracked per player, since each now has two pineapples.
    /// 5 — Blenderama's hard counter is recorded per player.
    /// 6 — actions replace bare moves; blenders carry no respawn schedule.
    /// 7 — smoothie victims carry a return date again.
    /// 8 — the Mega Blender is gone from the state.
    /// 9 — restore actions name the spawn they land on.
    /// 10 — smoothie progress records whether a stolen ingredient went in.
    public static let currentSchemaVersion = 10

    public let schemaVersion: Int
    public let rulesVersion: Int
    public let matchID: String
    /// Monotonic count of committed moves. The turn-submission guard hangs off this.
    public let moveNumber: Int
    public let state: GameState
    public let lastMove: LastMove?

    public init(
        schemaVersion: Int = MatchPayload.currentSchemaVersion,
        rulesVersion: Int = RulesEngine.rulesVersion,
        matchID: String,
        moveNumber: Int,
        state: GameState,
        lastMove: LastMove? = nil
    ) {
        self.schemaVersion = schemaVersion
        self.rulesVersion = rulesVersion
        self.matchID = matchID
        self.moveNumber = moveNumber
        self.state = state
        self.lastMove = lastMove
    }

    public static func newMatch(
        id: String,
        startingPlayer: PlayerID = .one,
        ruleSet: RuleSet = .standard
    ) -> MatchPayload {
        MatchPayload(
            matchID: id,
            moveNumber: 0,
            state: GameSetup.newGame(startingPlayer: startingPlayer, ruleSet: ruleSet)
        )
    }
}

/// Enough for the receiving device to animate what the opponent just did instead of
/// snapping the board to a new arrangement.
public struct LastMove: Codable, Sendable, Hashable {
    public let action: GameAction
    public let playedBy: PlayerID
    public let events: [GameEvent]

    public init(action: GameAction, playedBy: PlayerID, events: [GameEvent]) {
        self.action = action
        self.playedBy = playedBy
        self.events = events
    }

    public var move: Move? {
        if case .move(let move) = action { return move }
        return nil
    }
}

public enum MatchCodecError: Error, Equatable, LocalizedError {
    case unsupportedSchema(found: Int, supported: Int)
    case outdatedSchema(found: Int, supported: Int)
    case incompatibleRules(found: Int, supported: Int)
    case malformed

    public var errorDescription: String? {
        switch self {
        case .unsupportedSchema:
            "This match was played on a newer version of Banana Chess. Update to continue."
        case .outdatedSchema, .incompatibleRules:
            "This match was started under the previous rules and can't be continued. "
                + "Start a new match to play with blender knockouts."
        case .malformed:
            "This match's data could not be read."
        }
    }
}

public enum MatchCodec {
    /// Sorted keys keep the bytes deterministic, which makes payload equality a usable
    /// assertion in tests and keeps diffs meaningful in logs.
    public static func encode(_ payload: MatchPayload) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(payload)
    }

    public static func decode(_ data: Data) throws -> MatchPayload {
        let payload: MatchPayload
        do {
            payload = try JSONDecoder().decode(MatchPayload.self, from: data)
        } catch {
            throw MatchCodecError.malformed
        }
        // Both directions are refused. A newer payload we cannot understand; an older
        // one we could technically decode, but its blenders played by rules that no
        // longer exist, so continuing it would silently change the game underneath
        // whoever started it.
        guard payload.schemaVersion <= MatchPayload.currentSchemaVersion else {
            throw MatchCodecError.unsupportedSchema(
                found: payload.schemaVersion,
                supported: MatchPayload.currentSchemaVersion
            )
        }
        guard payload.schemaVersion == MatchPayload.currentSchemaVersion else {
            throw MatchCodecError.outdatedSchema(
                found: payload.schemaVersion,
                supported: MatchPayload.currentSchemaVersion
            )
        }
        guard payload.rulesVersion == RulesEngine.rulesVersion else {
            throw MatchCodecError.incompatibleRules(
                found: payload.rulesVersion,
                supported: RulesEngine.rulesVersion
            )
        }
        return payload
    }
}
