import BananaChessEngine
import SwiftUI

@main
struct BananaChessApp: App {
    @State private var onlineService = GameCenterMatchService()

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environment(\.onlineMatchService, onlineService)
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
