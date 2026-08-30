import BananaChessEngine
import CloudKit
import Foundation

/// Live play over CloudKit: `CKShare` hands out the invitation as a URL, and database
/// subscriptions push the opponent's move to the other device so it arrives on its own.
///
/// The whole match lives in one record. That is only reasonable because a `MatchPayload`
/// is already the entire truth of a match — there is nothing to merge, only a newer
/// version to take — and it is what lets a save be guarded by `ifServerRecordUnchanged`,
/// which turns "two people moved at once" into a conflict CloudKit reports rather than a
/// board that silently disagrees with itself.
///
/// One match, one zone. `CKShare` requires its root record to live in a custom zone in
/// the private database, so each match gets its own and the zone name is the match id.
@MainActor
final class CloudKitMatchService: NSObject, OnlineMatchService {
    /// Must match the container created on the App ID — see `docs/cloudkit.md`. It is the
    /// one value the code and the developer account have to agree on.
    static let containerIdentifier = "iCloud.com.charlesbarton.bananachess"

    private enum Field {
        static let recordType = "Match"
        static let payload = "payload"
        static let moveNumber = "moveNumber"
        static let finished = "finished"
        static let ownerName = "ownerName"
    }

    /// Built on first use, never at launch, and never without the entitlement.
    ///
    /// `CKContainer(identifier:)` does not fail politely when the app has no iCloud
    /// entitlement — it traps. Holding it in a stored property with a default argument
    /// meant it was constructed inside `App.init()`, which killed the app on launch for
    /// everyone before the capability existed. It is now reached only through
    /// `cloudContainer()`, and only after checking the entitlement is actually there.
    private var madeContainer: CKContainer?
    private let overrideContainer: CKContainer?
    private var localUserRecordName: String?
    private let broadcaster = MatchUpdateBroadcaster()
    /// Change tokens, so a push only fetches what actually moved.
    private var databaseTokens: [CKDatabase.Scope: CKServerChangeToken] = [:]

    init(container: CKContainer? = nil) {
        self.overrideContainer = container
        super.init()
    }

    /// Whether this build may talk to CloudKit at all.
    ///
    /// Read from the Info.plist rather than from the entitlements, because iOS has no
    /// supported way to read your own entitlements at runtime — `SecTaskCopyValue-
    /// ForEntitlement` is macOS only. So the flag is set by hand, in the same file and
    /// the same sitting as the capability itself; see `docs/cloudkit.md`.
    ///
    /// It has to be checked before the container is ever constructed. `CKContainer` traps
    /// rather than failing when the entitlement is missing, so getting this wrong is not
    /// a degraded feature, it is an app that does not start.
    static var isConfigured: Bool {
        Bundle.main.object(forInfoDictionaryKey: "BananaChessCloudKitEnabled") as? Bool ?? false
    }

    private func cloudContainer() throws -> CKContainer {
        if let overrideContainer { return overrideContainer }
        if let madeContainer { return madeContainer }
        guard Self.isConfigured else { throw CloudKitMatchError.notConfigured }
        let made = CKContainer(identifier: Self.containerIdentifier)
        madeContainer = made
        return made
    }

    // MARK: - Account

    func authenticateLocalPlayer() async throws -> OnlineLocalPlayer {
        guard try await try cloudContainer().accountStatus() == .available else {
            throw CloudKitMatchError.noAccount
        }
        let id = try await cloudContainer().userRecordID()
        localUserRecordName = id.recordName
        await ensureSubscriptions()
        return OnlineLocalPlayer(id: id.recordName, displayName: "You")
    }

    // MARK: - Creating and sharing

    /// CloudKit has no matchmaking, so an automatch and an invitation are the same act:
    /// make the match, make the share, hand back the URL.
    func startAutomatch(_ options: MatchOptions) async throws -> OnlineMatchSummary {
        try await inviteFriend(options)
    }

    func inviteFriend(_ options: MatchOptions) async throws -> OnlineMatchSummary {
        let owner = try await requireUser()
        let matchID = UUID().uuidString
        let zone = CKRecordZone(zoneName: matchID)
        _ = try await try cloudContainer().privateCloudDatabase.save(zone)

        let payload = MatchPayload.newMatch(id: matchID, options: options)
        let record = CKRecord(
            recordType: Field.recordType,
            recordID: CKRecord.ID(recordName: matchID, zoneID: zone.zoneID)
        )
        record[Field.payload] = try MatchCodec.encode(payload) as CKRecordValue
        record[Field.moveNumber] = payload.moveNumber as CKRecordValue
        record[Field.finished] = 0 as CKRecordValue
        record[Field.ownerName] = owner as CKRecordValue

        let share = CKShare(rootRecord: record)
        // Anyone holding the link may play. Naming participants would mean knowing who
        // they are before sending it, which is the opposite of inviting by link.
        share.publicPermission = .readWrite
        share[CKShare.SystemFieldKey.title] = "Banana Chess" as CKRecordValue

        let saved = try await try cloudContainer().privateCloudDatabase.modifyRecords(
            saving: [record, share], deleting: []
        )
        for (_, result) in saved.saveResults { _ = try result.get() }

        return summary(payload: payload, localPlayer: .one, shareURL: share.url, isOwner: true)
    }

    /// Called when the recipient taps the share URL.
    func accept(_ metadata: CKShare.Metadata) async throws -> String {
        _ = try await cloudContainer().accept(metadata)
        await ensureSubscriptions()
        let id = metadata.hierarchicalRootRecordID?.recordName
            ?? metadata.rootRecordID.recordName
        await broadcast(.invitationAccepted(id: id))
        return id
    }

    // MARK: - Reading

    func loadMatches() async throws -> [OnlineMatchSummary] {
        var found: [OnlineMatchSummary] = []
        for scope in [CKDatabase.Scope.private, .shared] {
            let database = try cloudContainer().database(with: scope)
            let zones = (try? await database.allRecordZones()) ?? []
            for zone in zones where zone.zoneID.zoneName != CKRecordZone.default().zoneID.zoneName {
                if let summary = try? await match(in: zone.zoneID, from: database) {
                    found.append(summary)
                }
            }
        }
        return found.sorted { $0.lastActivity > $1.lastActivity }
    }

    func loadMatch(id: String) async throws -> OnlineMatchSummary {
        for scope in [CKDatabase.Scope.private, .shared] {
            let database = try cloudContainer().database(with: scope)
            let zones = (try? await database.allRecordZones()) ?? []
            guard let zone = zones.first(where: { $0.zoneID.zoneName == id }) else { continue }
            return try await match(in: zone.zoneID, from: database)
        }
        throw CloudKitMatchError.matchNotFound
    }

    private func match(in zoneID: CKRecordZone.ID, from database: CKDatabase) async throws -> OnlineMatchSummary {
        let id = CKRecord.ID(recordName: zoneID.zoneName, zoneID: zoneID)
        let record = try await database.record(for: id)
        return try summary(from: record)
    }

    // MARK: - Writing

    func submit(_ payload: MatchPayload, expectedPredecessor: Int, for matchID: String) async throws {
        try await write(payload, expectedPredecessor: expectedPredecessor, for: matchID, finished: false)
    }

    func finish(_ payload: MatchPayload, outcome: Outcome, for matchID: String) async throws {
        try await write(
            payload, expectedPredecessor: payload.moveNumber - 1, for: matchID, finished: true
        )
    }

    func forfeit(matchID: String) async throws {
        // Nothing to forfeit to: the record simply stops being played. Removing the zone
        // would take the match away from the opponent mid-read, so it is left alone.
        throw CloudKitMatchError.notSupported
    }

    private func write(
        _ payload: MatchPayload,
        expectedPredecessor: Int,
        for matchID: String,
        finished: Bool
    ) async throws {
        let (database, record) = try await locate(matchID)
        let stored = (record[Field.moveNumber] as? Int) ?? 0
        guard stored == expectedPredecessor else {
            throw CloudKitMatchError.conflict(expected: expectedPredecessor, found: stored)
        }

        record[Field.payload] = try MatchCodec.encode(payload) as CKRecordValue
        record[Field.moveNumber] = payload.moveNumber as CKRecordValue
        record[Field.finished] = (finished ? 1 : 0) as CKRecordValue

        // The server's own copy has to be the one we read, or two devices that both
        // passed the check above would overwrite each other.
        let result = try await database.modifyRecords(
            saving: [record], deleting: [], savePolicy: .ifServerRecordUnchanged
        )
        for (_, outcome) in result.saveResults {
            do { _ = try outcome.get() } catch let error as CKError where error.code == .serverRecordChanged {
                throw CloudKitMatchError.conflict(expected: expectedPredecessor, found: stored)
            }
        }
    }

    private func locate(_ matchID: String) async throws -> (CKDatabase, CKRecord) {
        for scope in [CKDatabase.Scope.private, .shared] {
            let database = try cloudContainer().database(with: scope)
            let zones = (try? await database.allRecordZones()) ?? []
            guard let zone = zones.first(where: { $0.zoneID.zoneName == matchID }) else { continue }
            let id = CKRecord.ID(recordName: matchID, zoneID: zone.zoneID)
            if let record = try? await database.record(for: id) { return (database, record) }
        }
        throw CloudKitMatchError.matchNotFound
    }

    // MARK: - Updates

    nonisolated func matchUpdates() -> AsyncStream<OnlineMatchUpdate> {
        let broadcaster = broadcaster
        return AsyncStream { continuation in
            Task { await broadcaster.register(continuation) }
        }
    }

    /// Called from the app delegate when a CloudKit push arrives.
    func handleRemoteNotification(_ userInfo: [AnyHashable: Any]) async {
        guard let notification = CKNotification(fromRemoteNotificationDictionary: userInfo)
                as? CKDatabaseNotification
        else { return }
        await fetchChanges(in: notification.databaseScope)
    }

    private func fetchChanges(in scope: CKDatabase.Scope) async {
        guard let database = try? cloudContainer().database(with: scope) else { return }
        let changes = try? await database.databaseChanges(since: databaseTokens[scope])
        guard let changes else { return }
        databaseTokens[scope] = changes.changeToken
        for zone in changes.modifications {
            await broadcast(.matchChanged(id: zone.zoneID.zoneName))
        }
        for zone in changes.deletions {
            await broadcast(.matchEnded(id: zone.zoneID.zoneName))
        }
    }

    private func broadcast(_ update: OnlineMatchUpdate) async {
        await broadcaster.send(update)
    }

    private func ensureSubscriptions() async {
        for scope in [CKDatabase.Scope.private, .shared] {
            let id = "banana-chess-\(scope == .private ? "private" : "shared")"
            let subscription = CKDatabaseSubscription(subscriptionID: id)
            let info = CKSubscription.NotificationInfo()
            // Silent: the board updates itself, there is nothing to alert about.
            info.shouldSendContentAvailable = true
            subscription.notificationInfo = info
            _ = try? await try cloudContainer().database(with: scope).save(subscription)
        }
    }

    // MARK: - Shaping

    private func requireUser() async throws -> String {
        if let localUserRecordName { return localUserRecordName }
        _ = try await authenticateLocalPlayer()
        guard let localUserRecordName else { throw CloudKitMatchError.noAccount }
        return localUserRecordName
    }

    private func summary(from record: CKRecord) throws -> OnlineMatchSummary {
        guard let data = record[Field.payload] as? Data else { throw CloudKitMatchError.matchNotFound }
        let payload = try MatchCodec.decode(data)
        let owner = record[Field.ownerName] as? String
        let isOwner = owner != nil && owner == localUserRecordName
        return summary(
            payload: payload,
            localPlayer: isOwner ? .one : .two,
            shareURL: nil,
            isOwner: isOwner,
            finished: (record[Field.finished] as? Int ?? 0) == 1,
            lastActivity: record.modificationDate ?? Date()
        )
    }

    private func summary(
        payload: MatchPayload,
        localPlayer: PlayerID,
        shareURL: URL?,
        isOwner: Bool,
        finished: Bool = false,
        lastActivity: Date = Date()
    ) -> OnlineMatchSummary {
        let status: OnlineMatchStatus
        if finished || payload.state.status.isOver {
            status = .completed
        } else {
            status = payload.state.activePlayer == localPlayer ? .yourTurn : .waitingForOpponent
        }
        return OnlineMatchSummary(
            id: payload.matchID,
            opponentName: isOwner ? "Your opponent" : "Your host",
            localPlayer: localPlayer,
            status: status,
            lastActivity: lastActivity,
            outcome: payload.state.outcome,
            payload: payload,
            shareURL: shareURL
        )
    }
}

enum CloudKitMatchError: Error, LocalizedError, Equatable {
    case notConfigured
    case noAccount
    case matchNotFound
    case notSupported
    case conflict(expected: Int, found: Int)

    var errorDescription: String? {
        switch self {
        case .notConfigured:
            "This build isn't set up for iCloud play yet."
        case .noAccount:
            "Sign in to iCloud in Settings to play online."
        case .matchNotFound:
            "That match is no longer available."
        case .notSupported:
            "That isn't available in an iCloud match."
        case .conflict:
            "Your opponent moved first. The board has been brought up to date."
        }
    }
}
