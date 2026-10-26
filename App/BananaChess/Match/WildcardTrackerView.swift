import BananaChessEngine
import SwiftUI

/// The wildcard threats, kept where both players can read them. The rules only work if
/// an alternate win is something you can see coming and go and stop, so nothing here is
/// hidden from either side.
struct WildcardTrackerView: View {
    let standing: WildcardStanding
    let seat: PlayerSeat

    /// Blenderama appears once a player has started one — a 0/6 counter on every match
    /// would be noise.
    ///
    /// It used to appear the moment it was countered too, back when being countered was
    /// rare and worth announcing. Under the shutout rule it is the *normal* state within
    /// about forty turns of every match, so that would pin a dead row to the screen all
    /// game. The warning now lives where the decision is actually made: the self-blend
    /// confirmation says outright when Blenderama is already off.
    private var showsBlenderama: Bool { standing.ownFruitBlended > 0 }

    var body: some View {
        HStack(spacing: 14) {
            track(
                label: "Smoothie",
                isComplete: standing.hasFiredSmoothie
            ) {
                ForEach([PieceKind.banana, .strawberry, .pineapple], id: \.self) { kind in
                    PieceArtwork(kind: kind, seat: seat, showsTray: false)
                        .frame(width: 15, height: 15)
                        .opacity(standing.smoothie.has(kind) ? 1 : 0.22)
                        .grayscale(standing.smoothie.has(kind) ? 0 : 1)
                }
            }

            track(
                label: "Split",
                isComplete: standing.isBananaSplitComplete,
                isBlocked: standing.isBananaSplitBlocked
            ) {
                ForEach(0..<standing.bananaSplitTarget, id: \.self) { index in
                    PieceArtwork(kind: .banana, seat: seat, showsTray: false)
                        .frame(width: 15, height: 15)
                        .opacity(index < standing.bananasDelivered ? 1 : 0.22)
                        .grayscale(index < standing.bananasDelivered ? 0 : 1)
                }
            }

            if showsBlenderama {
                track(
                    label: standing.isBlenderamaCountered ? "Countered" : "Blenderama",
                    isComplete: false,
                    isBlocked: standing.isBlenderamaBlocked
                ) {
                    Text("\(standing.ownFruitBlended)/\(standing.blenderamaTarget)")
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
            "Smoothie \(standing.smoothie.collectedCount) of 3"
                + (standing.hasFiredSmoothie ? ", already poured" : ""),
            "Banana Split \(standing.bananasDelivered) of \(standing.bananaSplitTarget)"
                + (standing.isBananaSplitBlocked ? ", no longer possible" : ""),
        ]
        if showsBlenderama {
            parts.append(
                "Blenderama \(standing.ownFruitBlended) of \(standing.blenderamaTarget)"
                    + (standing.isBlenderamaCountered
                        ? ", countered"
                        : standing.isBlenderamaBlocked ? ", no longer possible" : "")
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
        WildcardTrackerView(standing: state.wildcardStanding(for: .one), seat: .bottom)
            .padding()
    }
}
