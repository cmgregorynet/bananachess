import BananaChessEngine
import SwiftUI

struct RulesView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                PaperBackground()
                ScrollView {
                    VStack(alignment: .leading, spacing: 22) {
                        section("The turn") {
                            Text("Move one of your pieces each turn. That is the whole turn — no quotas, no forced moves.")
                        }

                        section("How pieces move") {
                            ruleRow(.banana, "Fruit moves one square up, down, left, or right. Forwards and backwards both count.")
                            ruleRow(.blender, "Blenders move one square diagonally, in any direction.")
                            Text("Nothing moves off the board or through another piece. Fruit never captures.")
                        }

                        section("Scoring") {
                            valueRow(.banana)
                            valueRow(.strawberry)
                            valueRow(.pineapple)
                            Text("Get fruit to the opposite edge for double its value. Blending only ever pays the plain value — never double.")
                        }

                        section("Blending cuts both ways") {
                            Text("A blender can move onto any fruit at all. Blend theirs and you gain its value. Blend your own and you lose it.")
                            Text("Scores can go below zero. Wrecking your own fruit is sometimes still worth it — to clear a path, or to deny a blend that was coming anyway.")
                        }

                        section("Blender combat") {
                            Text("Run your blender into another and it destroys that one outright and takes the square. Nobody scores for it, and you get nothing else out of it — no extra move. It works on your own blenders too.")
                            Text("A blender standing on either of its owner's spawn squares cannot be taken by anything. Home is safe, so a blender you have just bought back cannot be knocked straight off again — though it is only safe while it stays there, and a Tropical Smoothie reaches it anyway.")
                            Text("Blenders never come back on their own. Spend a whole turn and three points and a destroyed blender returns to its spawn — tap the dashed circle where it used to be. Your score may go below zero paying for it.")
                        }

                        section("Fruit is the clock") {
                            Text("The match ends the moment either player runs out of fruit on the board — not when the board is empty. Whoever is ahead at that moment wins; level scores are a draw.")
                            Text("Fruit still standing counts for nothing. Points only exist once a fruit has actually scored, so an unplayed pineapple is worth exactly zero.")
                            Text("That cuts both ways. Ahead, you may want to run the clock down — even by blending your own last fruit at a price. Behind, you need to keep enough on the board to cash in.")
                        }

                        wildcardSection
                    }
                    .font(.system(.body, design: .serif))
                    .foregroundStyle(Palette.ink)
                    .padding(24)
                }
            }
            .navigationTitle("How to play")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }.tint(Palette.ink)
                }
            }
        }
    }

    /// Optional, and described as optional: these only apply if the box on the start
    /// screen is ticked.
    private var wildcardSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            Divider().background(Palette.inkFaint)

            VStack(alignment: .leading, spacing: 6) {
                Text("Wildcard rules")
                    .font(.system(.title3, design: .serif).weight(.semibold))
                Text("Off by default. Tick the box on the start screen to play with them. They exist so a points lead never makes the rest of a match a formality.")
                    .font(.system(.subheadline, design: .serif))
                    .foregroundStyle(Palette.inkSoft)
            }

            wildcardRule(
                "Golden Pineapple",
                "You start with two. Lose one to a blender and the other turns golden: three times its value if you get it across, and double to anyone who blends it. Both players can be carrying one."
            )
            wildcardRule(
                "Banana delivery",
                "A delivered banana scores as usual and then goes back to its own starting square, so it can be run across again. Deliveries add up across the match, and the first player to three wins outright, whatever the score. Blending one of their bananas is what stops it."
            )
            wildcardRule(
                "Tropical Smoothie",
                "Blend at least one of every fruit type — with at least one of them taken off your opponent — and both of their blenders blow up on the spot. They play one turn without them, then get them back for nothing: unlike combat, a smoothie costs a turn, not the blenders. Three of your own fruit is not a recipe, so a smoothie always means going and taking something. Both players can see the progress."
            )
            wildcardRule(
                "Blenderama",
                "Blend every last one of your own fruit yourself and you win outright, whatever the score. It costs you points the whole way, and it only stands as a shutout: the moment your opponent scores at all — a fruit across, or one of yours through their blenders — your Blenderama is off for the rest of the match. Keeping them off the board entirely is the hard part, not the blending. The board says so the moment it happens."
            )
        }
    }

    private func wildcardRule(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.system(.body, design: .serif).weight(.semibold))
            Text(body)
                .font(.system(.subheadline, design: .serif))
                .foregroundStyle(Palette.inkSoft)
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func section(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.system(.title3, design: .serif).weight(.semibold))
                .foregroundStyle(Palette.ink)
            content()
        }
    }

    private func ruleRow(_ kind: PieceKind, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            PieceArtwork(kind: kind, seat: .bottom)
                .frame(width: 40, height: 40)
            Text(text)
        }
    }

    private func valueRow(_ kind: PieceKind) -> some View {
        HStack(spacing: 12) {
            PieceArtwork(kind: kind, seat: .bottom)
                .frame(width: 36, height: 36)
            Text(kind.rawValue.capitalized)
            Spacer()
            Text("blend \(kind.baseValue) · deliver \(kind.deliveryValue)")
                .foregroundStyle(Palette.inkSoft)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            "\(kind.rawValue), blended for \(kind.baseValue) points, delivered for \(kind.deliveryValue)"
        )
    }
}

/// First-launch teaching. Teaches the reset rule but deliberately does not point out
/// the self-blend tactic — the PRD wants to watch whether players find it.
struct TeachingOverlay: View {
    @Binding var isPresented: Bool
    @State private var page = 0

    private let pages: [(title: String, body: String, kind: PieceKind)] = [
        ("Move one piece", "Fruit steps one square straight. Blenders step one square diagonally.", .banana),
        ("Reach the far edge", "Fruit that reaches the opposite edge scores double its value and leaves the board.", .strawberry),
        ("Blending cuts both ways", "A blender can take any fruit. Theirs pays you its value. Yours costs you the same.", .blender),
        ("Blenders knock out", "Run two blenders together and both explode. They are gone for two turns, then return to their spawns.", .pineapple),
    ]

    var body: some View {
        ZStack {
            Palette.ink.opacity(0.3).ignoresSafeArea()
            WashiPanel(cornerRadius: 22, isAccented: true) {
                VStack(spacing: 18) {
                    PieceArtwork(kind: pages[page].kind, seat: .bottom)
                        .frame(width: 76, height: 76)
                    Text(pages[page].title)
                        .font(.system(.title3, design: .serif).weight(.semibold))
                    Text(pages[page].body)
                        .font(.system(.subheadline, design: .serif))
                        .multilineTextAlignment(.center)
                        .foregroundStyle(Palette.inkSoft)

                    HStack(spacing: 6) {
                        ForEach(pages.indices, id: \.self) { index in
                            Circle()
                                .fill(index == page ? Palette.amber : Palette.inkFaint)
                                .frame(width: 7, height: 7)
                        }
                    }

                    Button(page == pages.count - 1 ? "Play" : "Next") {
                        if page == pages.count - 1 {
                            isPresented = false
                        } else {
                            withAnimation(.easeInOut(duration: 0.2)) { page += 1 }
                        }
                    }
                    .buttonStyle(PaperButtonStyle(isPrimary: true))
                }
                .foregroundStyle(Palette.ink)
                .padding(26)
            }
            .padding(36)
        }
    }
}

#Preview("Rules") { RulesView() }
