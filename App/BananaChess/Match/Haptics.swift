import BananaChessEngine
import UIKit

/// Light, sparing feedback that matches the calm the art bible asks for: a tap on
/// selection, a knock on collision, a soft rise on a score. Nothing buzzes.
@MainActor
enum Haptics {
    static func selection() {
        UISelectionFeedbackGenerator().selectionChanged()
    }

    static func play(for event: GameEvent) {
        switch event {
        case .moved:
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        case .blenderDestroyed, .smoothie:
            UIImpactFeedbackGenerator(style: .rigid).impactOccurred(intensity: 0.9)
        case .blenderRestored, .bananaReturned, .blenderRespawned:
            UIImpactFeedbackGenerator(style: .soft).impactOccurred()
        case .blended:
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        case .delivered:
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        case .gameEnded:
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        default:
            break
        }
    }
}
