import BananaChessEngine
import SwiftUI

/// The locked palette, straight from the Alligator Island art direction by way of the
/// Cherry Coast art bible. Nothing in this app names a colour that is not in here, and
/// nothing here is saturated or pure black — the darkest value is the warm ink.
enum Palette {
    // Papers and woods
    static let warmPaper = Color(hex: 0xE7D8D0)
    static let cream = Color(hex: 0xF8E2D5)
    static let driftwood = Color(hex: 0xF1D7C5)
    static let sand = Color(hex: 0xBDA99D)

    // Cools
    static let fogWater = Color(hex: 0xB1B3B3)
    static let slate = Color(hex: 0xBEB7BB)

    // Ink — the darkest value in the whole game
    static let ink = Color(hex: 0x61524D)
    static let inkSoft = Color(hex: 0x61524D).opacity(0.55)
    static let inkFaint = Color(hex: 0x61524D).opacity(0.22)

    // Character sage
    static let sage = Color(hex: 0x6E7A4E)
    static let sageLight = Color(hex: 0x8A9469)

    /// The one precious accent. Selection, legal destinations, lantern glow — and
    /// nothing else, on any screen.
    static let amber = Color(hex: 0xE8A24A)

    // Fruit washes: muted, never bright. The wash says what a piece *is*; the tray
    // underneath says whose it is.
    static let bananaWash = Color(hex: 0xDFC77E)
    static let strawberryWash = Color(hex: 0xC4776F)
    static let pineappleWash = Color(hex: 0xD1AC63)
    static let blenderGlass = Color(hex: 0xD5DAD6)

    // Board squares — two weights of the same warm paper, not two different colours.
    static let squareLight = Color(hex: 0xF4E4D8)
    static let squareDark = Color(hex: 0xE0CCBE)

    // Ownership trays: warm clay against dusty indigo. Indigo is the one saturated
    // colour the root art direction already sanctions — the "deep indigo bottle" among
    // its precious accents, and the colour of Cherry Coast's chef otter — so the two
    // sides oppose each other in hue while both stay lighter than the ink. Amber is
    // still the only accent that means anything; this pair is identity, not emphasis.
    static let clayLight = Color(hex: 0xEFD3B6)
    static let clayDeep = Color(hex: 0xCFA987)
    static let indigoLight = Color(hex: 0x8398B5)
    static let indigoDeep = Color(hex: 0x556C90)

    static func tray(for player: PlayerSeat) -> Color {
        player == .bottom ? clayLight : indigoLight
    }

    static func trayShade(for player: PlayerSeat) -> Color {
        player == .bottom ? clayDeep : indigoDeep
    }

    /// The pigment a piece spills when it is blended.
    static func wash(for kind: PieceKind) -> Color {
        switch kind {
        case .banana: bananaWash
        case .strawberry: strawberryWash
        case .pineapple: pineappleWash
        case .blender: blenderGlass
        }
    }

    /// Panels stay light for both players — a dark scoreboard would fight the paper.
    /// Ownership there is carried by the tint and the name, not by the tray value.
    static func panelTint(for player: PlayerSeat) -> Color {
        player == .bottom ? driftwood : Color(hex: 0xD9E1EC)
    }
}

/// Which end of the board a player sits at. The engine's `PlayerID` is about rules;
/// this is about the screen, and the two are mapped once, in one place.
enum PlayerSeat: Hashable, Sendable {
    case bottom
    case top
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}
