import BananaChessEngine
import SwiftUI

/// The wildcard threats, kept where both players can read them. The rules only work if
/// an alternate win is something you can see coming and go and stop, so nothing here is
/// hidden from either side.
struct WildcardTrackerView: View {
    let state: GameState
    let player: PlayerID
    let seat: PlayerSeat

    private var smoothie: SmoothieProgress { state.wildcards.smoothie[player] }
    private var bananas: Int { state.wildcards.bananasDelivered[player] }
    private var blenderama: Int { state.wildcards.ownFruitBlended[player] }

    /// Blenderama appears once a player has started one — a 0/6 counter on every match
    /// would be noise.
    ///
    /// It used to appear the moment it was countered too, back when being countered was
    /// rare and worth announcing. Under the shutout rule it is the *normal* state within
    /// about forty turns of every match, so that would pin a dead row to the screen all
    /// game. The warning now lives where the decision is actually made: the self-blend
    /// confirmation says outright when Blenderama is already off.
    private var showsBlenderama: Bool { blenderama > 0 }

    /// Bananas recycle, so a Split only dies when the player has no banana left at all
    /// to run across — every one of theirs has been blended. A player chasing a dead
    /// objective is exactly what the wildcard brief's "visible and counterable"
    /// principle is meant to prevent.
    private var isSplitBlocked: Bool {
        bananas < GameSetup.bananaDeliveriesToWin
            && !state.fruit(ownedBy: player).contains { $0.kind == .banana }
    }

    /// A Blenderama dies if the opponent steals one of the self-blends — the hard
    /// counter, which the engine records explicitly — or if any of your fruit leaves by
    /// any other route.
    private var isBlenderamaBlocked: Bool {
        state.wildcards.blenderamaCountered[player]
            || blenderama + state.fruit(ownedBy: player).count < GameSetup.fruitCount
    }

    var body: some View {
        HStack(spacing: 14) {
            track(
                label: "Smoothie",
                isComplete: state.wildcards.smoothieFired[player]
            ) {
                ForEach([PieceKind.banana, .strawberry, .pineapple], id: \.self) { kind in
                    PieceArtwork(kind: kind, seat: seat, showsTray: false)
                        .frame(width: 15, height: 15)
                        .opacity(smoothie.has(kind) ? 1 : 0.22)
                        .grayscale(smoothie.has(kind) ? 0 : 1)
                }
            }

            track(
                label: "Split",
                isComplete: bananas >= GameSetup.bananaDeliveriesToWin,
                isBlocked: isSplitBlocked
            ) {
                ForEach(0..<GameSetup.bananaDeliveriesToWin, id: \.self) { index in
                    PieceArtwork(kind: .banana, seat: seat, showsTray: false)
                        .frame(width: 15, height: 15)
                        .opacity(index < bananas ? 1 : 0.22)
                        .grayscale(index < bananas ? 0 : 1)
                }
            }

            if showsBlenderama {
                track(
                    label: state.wildcards.blenderamaCountered[player] ? "Countered" : "Blenderama",
                    isComplete: false,
                    isBlocked: isBlenderamaBlocked
                ) {
                    Text("\(blenderama)/\(GameSetup.fruitCount)")
                        .font(.system(size: 11, weight: .semibold, design: .serif))
                        .foregroundStyle(Palette.ink)
                        .monospacedDigit()
                }
            }

            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityDescription)
    }

    @ViewBuilder
    private func track(
        label: String,
        isComplete: Bool,
        isBlocked: Bool = false,
        @ViewBuilder content: () -> some View
    ) -> some View {
        HStack(spacing: 4) {
            Text(label)
                .font(.system(size: 9, design: .serif))
                .foregroundStyle(Palette.inkSoft)
                .strikethrough(isBlocked, color: Palette.inkSoft)
            HStack(spacing: 2) { content() }
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 3)
        .background {
            Capsule().fill(Palette.cream.opacity(isComplete ? 0.9 : 0.45))
        }
        .overlay {
            Capsule().strokeBorder(
                isComplete ? Palette.amber : Palette.inkFaint,
                lineWidth: isComplete ? 1.6 : 0.8
            )
        }
        // Ruled out rather than hidden: knowing a threat is dead is as useful as
        // knowing it is live, to both players.
        .opacity(isBlocked ? 0.42 : 1)
    }

    private var accessibilityDescription: String {
        var parts = [
            "Smoothie \(smoothie.collectedCount) of 3"
                + (state.wildcards.smoothieFired[player] ? ", already poured" : ""),
            "Banana Split \(bananas) of \(GameSetup.bananaDeliveriesToWin)"
                + (isSplitBlocked ? ", no longer possible" : ""),
        ]
        if showsBlenderama {
            parts.append(
                "Blenderama \(blenderama) of \(GameSetup.fruitCount)"
                    + (state.wildcards.blenderamaCountered[player]
                        ? ", countered"
                        : isBlenderamaBlocked ? ", no longer possible" : "")
            )
        }
        return parts.joined(separator: ", ")
    }
}

#Preview("Trackers") {
    var state = GameSetup.newGame(ruleSet: .wildcard)
    state.wildcards.smoothie[.one].banana = true
    state.wildcards.smoothie[.one].pineapple = true
    state.wildcards.bananasDelivered[.one] = 2
    state.wildcards.ownFruitBlended[.one] = 3

    return ZStack {
        PaperBackground()
        WildcardTrackerView(state: state, player: .one, seat: .bottom)
            .padding()
    }
}
