import BananaChessEngine
import CloudKit
import SwiftUI
import UIKit

/// Where an accepted CloudKit invitation lands.
///
/// A `CKShare` URL does not arrive through `onOpenURL` like an ordinary link — the system
/// hands it to the scene delegate already resolved into metadata — so the app needs a
/// delegate purely for this one callback. Silent pushes arrive through the app delegate
/// for the same reason: there is no SwiftUI route to either.
@MainActor
@Observable
final class CloudKitInbox {
    /// Set when an invitation has been accepted and the match is ready to open.
    var acceptedMatchID: String?
    var error: String?

    @ObservationIgnored let service: CloudKitMatchService

    init(service: CloudKitMatchService) {
        self.service = service
    }

    func accept(_ metadata: CKShare.Metadata) async {
        do {
            acceptedMatchID = try await service.accept(metadata)
        } catch {
            self.error = error.localizedDescription
        }
    }
}

final class BananaChessAppDelegate: NSObject, UIApplicationDelegate {
    /// Assigned by the app at launch so the delegate and the SwiftUI tree talk to the
    /// same service rather than two copies of it.
    @MainActor static var inbox: CloudKitInbox?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // CloudKit subscriptions are delivered as silent pushes, so the app has to be
        // registered even though it never shows a notification of its own.
        application.registerForRemoteNotifications()
        return true
    }

    func application(
        _ application: UIApplication,
        configurationForConnecting session: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(
            name: nil, sessionRole: session.role
        )
        configuration.delegateClass = BananaChessSceneDelegate.self
        return configuration
    }

    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any]
    ) async -> UIBackgroundFetchResult {
        guard let inbox = await BananaChessAppDelegate.inbox else { return .noData }
        await inbox.service.handleRemoteNotification(userInfo)
        return .newData
    }
}

final class BananaChessSceneDelegate: NSObject, UIWindowSceneDelegate {
    func windowScene(
        _ windowScene: UIWindowScene,
        userDidAcceptCloudKitShareWith metadata: CKShare.Metadata
    ) {
        Task { @MainActor in
            await BananaChessAppDelegate.inbox?.accept(metadata)
        }
    }
}
