import BananaChessEngine
import Foundation

struct MatchSnapshot: Codable, Sendable {
    var rulesVersion: Int = RulesEngine.rulesVersion
    let mode: MatchMode
    let state: GameState
}

/// Resume support for local and CPU matches: one Codable snapshot in Application
/// Support, written after a move fully resolves. Online matches are not cached here —
/// Game Center is their only authority.
actor MatchSnapshotStore {
    private let url: URL

    init(filename: String = "current-match.json") {
        let directory = URL.applicationSupportDirectory
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        url = directory.appending(path: filename)
    }

    func save(_ snapshot: MatchSnapshot) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        // Best effort: a failed resume write costs the player a restart, nothing more.
        try? data.write(to: url, options: .atomic)
    }

    func load() -> MatchSnapshot? {
        guard let data = try? Data(contentsOf: url),
              let snapshot = try? JSONDecoder().decode(MatchSnapshot.self, from: data)
        else { return nil }
        // A match saved under older rules would decode cleanly and then play by rules
        // its board was never arranged for. Drop it rather than change the game
        // underneath whoever left it.
        guard snapshot.rulesVersion == RulesEngine.rulesVersion else {
            clear()
            return nil
        }
        return snapshot
    }

    func clear() {
        try? FileManager.default.removeItem(at: url)
    }
}
