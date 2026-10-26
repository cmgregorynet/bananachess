import BananaChessEngine
import SwiftUI

@main
struct BananaChessApp: App {
    @UIApplicationDelegateAdaptor(BananaChessAppDelegate.self) private var appDelegate
    @State private var inbox: CloudKitInbox
    @State private var home: HomeViewModel

    init() {
        let inbox = CloudKitInbox(service: CloudKitMatchService())
        _inbox = State(initialValue: inbox)
        _home = State(initialValue: HomeViewModel(gameCenter: GameCenterMatchService(), inbox: inbox))
    }

    var body: some Scene {
        WindowGroup {
            HomeView(viewModel: home)
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
