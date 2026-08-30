import BananaChessEngine
import SwiftUI

@main
struct BananaChessApp: App {
    @UIApplicationDelegateAdaptor(BananaChessAppDelegate.self) private var appDelegate
    @State private var onlineService = GameCenterMatchService()
    @State private var inbox = CloudKitInbox(service: CloudKitMatchService())

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environment(\.onlineMatchService, onlineService)
                .environment(inbox)
                .task {
                    // The delegate and the view tree have to share one service, or an
                    // accepted invitation would land in an object nothing is watching.
                    BananaChessAppDelegate.inbox = inbox
                }
                // The board is a painted object on warm paper; it does not have a dark
                // counterpart, and inverting it would break the bible.
                .preferredColorScheme(.light)
                .tint(Palette.amber)
        }
    }
}

private struct OnlineMatchServiceKey: @preconcurrency EnvironmentKey {
    @MainActor static let defaultValue: any OnlineMatchService = GameCenterMatchService()
}

extension EnvironmentValues {
    var onlineMatchService: any OnlineMatchService {
        get { self[OnlineMatchServiceKey.self] }
        set { self[OnlineMatchServiceKey.self] = newValue }
    }
}
