import BananaChessEngine
import SwiftUI

struct MatchView: View {
    @State var viewModel: MatchViewModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("hasSeenTeachingOverlay") private var hasSeenTeaching = false

    @State private var isShowingRules = false
    @State private var isShowingTeaching = false
    @State private var isShowingForfeitConfirmation = false

    var body: some View {
        ZStack {
            PaperBackground()

            VStack(spacing: 14) {
                ScoreboardView(scoreboard: viewModel.scoreboard(for: viewModel.topPlayer), seat: .top)
                turnIndicator
                BoardView(viewModel: viewModel)
                    .padding(.horizontal, 10)
                ScoreboardView(scoreboard: viewModel.scoreboard(for: viewModel.bottomPlayer), seat: .bottom)
                linkHandoff
                controls
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)

            if viewModel.outcome != nil {
                ResultOverlay(viewModel: viewModel, onExit: { dismiss() })
                    .transition(.opacity)
            }
            if isShowingTeaching {
                TeachingOverlay(isPresented: $isShowingTeaching)
                    .transition(.opacity)
            }
        }
        .overlayPreferenceValue(BlendOriginKey.self) { anchors in
            splatterOverlay(anchors: anchors)
        }
        .animation(.easeInOut(duration: 0.3), value: viewModel.outcome)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // Appears only while the opponent owes a move, which is exactly when there is
            // something to send. In link play this is the whole transport, so it sits in
            // the toolbar rather than behind a menu.
            if let link = viewModel.linkToSend {
                ToolbarItem(placement: .topBarLeading) {
                    ShareLink(item: link, message: Text(viewModel.linkInvitation)) {
                        Label("Send move", systemImage: "square.and.arrow.up")
                    }
                    .tint(Palette.amber)
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Rules", systemImage: "book") { isShowingRules = true }
                    .tint(Palette.ink)
            }
        }
        .sheet(isPresented: $isShowingRules) { RulesView() }
        .task {
            if !hasSeenTeaching {
                hasSeenTeaching = true
                isShowingTeaching = true
            }
            await viewModel.start(reduceMotion: reduceMotion)
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await viewModel.sceneDidBecomeActive() }
            } else {
                viewModel.sceneDidResignActive()
            }
        }
        .confirmationDialog(
            "Restart this match?",
            isPresented: $viewModel.isShowingRestartConfirmation,
            titleVisibility: .visible
        ) {
            Button("Restart", role: .destructive) { viewModel.restart() }
            Button("Keep playing", role: .cancel) {}
        } message: {
            Text("The current board and both scores will be cleared.")
        }
        .confirmationDialog(
            viewModel.pendingSelfBlend?.prompt ?? "",
            isPresented: Binding(
                get: { viewModel.pendingSelfBlend != nil },
                set: { if !$0 { viewModel.cancelSelfBlend() } }
            ),
            titleVisibility: .visible
        ) {
            Button("Blend it", role: .destructive) { viewModel.confirmSelfBlend() }
            Button("Pick something else", role: .cancel) { viewModel.cancelSelfBlend() }
        } message: {
            Text(viewModel.pendingSelfBlend?.detail ?? "")
        }
        .confirmationDialog(
            "Forfeit this match?",
            isPresented: $isShowingForfeitConfirmation,
            titleVisibility: .visible
        ) {
            Button("Forfeit", role: .destructive) {
                viewModel.forfeitOnlineMatch()
                dismiss()
            }
            Button("Keep playing", role: .cancel) {}
        } message: {
            Text("Your opponent will win the match.")
        }
    }

    // MARK: - Splatter

    @ViewBuilder
    private func splatterOverlay(anchors: [UUID: Anchor<CGRect>]) -> some View {
        GeometryReader { proxy in
            if let splatter = viewModel.splatter {
                SplatterView(
                    kind: splatter.kind,
                    seed: splatter.seed,
                    origin: origin(of: splatter, anchors: anchors, in: proxy),
                    reduceMotion: reduceMotion
                )
                .id(splatter.id)
                .task(id: splatter.id) {
                    try? await Task.sleep(for: .milliseconds(1_700))
                    viewModel.retireSplatter(splatter.id)
                }
            }
        }
        .allowsHitTesting(false)
    }

    private func origin(
        of splatter: MatchViewModel.Splatter,
        anchors: [UUID: Anchor<CGRect>],
        in proxy: GeometryProxy
    ) -> UnitPoint {
        // The burst may already have been retired; the middle of the screen is a fine
        // place for pulp to have come from when we no longer know better.
        guard let anchor = anchors[splatter.burstID], proxy.size.width > 0, proxy.size.height > 0 else {
            return .center
        }
        let frame = proxy[anchor]
        return UnitPoint(x: frame.midX / proxy.size.width, y: frame.midY / proxy.size.height)
    }

    // MARK: - Turn indicator

    private var turnIndicator: some View {
        HStack(spacing: 8) {
            if viewModel.isOpponentThinking {
                ProgressView().controlSize(.small).tint(Palette.ink)
            }
            Text(viewModel.turnIndicatorText)
                .font(.system(.subheadline, design: .serif))
                .foregroundStyle(Palette.ink)
                .accessibilityIdentifier("turnIndicator")
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 6)
        .accessibilityAddTraits(.updatesFrequently)
        .overlay(alignment: .trailing) {
            if case .submissionFailed = viewModel.onlineStatus {
                retryControls
            }
        }
    }

    private var retryControls: some View {
        HStack(spacing: 8) {
            Button("Retry") { viewModel.retrySubmission() }
                .buttonStyle(.borderedProminent)
                .tint(Palette.amber)
            Button("Cancel") { viewModel.cancelSubmission() }
                .buttonStyle(.bordered)
                .tint(Palette.ink)
        }
        .font(.caption)
    }

    // MARK: - Controls

    /// In link play the share sheet is the whole transport, so it gets a real control
    /// under the board rather than only an icon in the toolbar — there is nothing else
    /// the player can do at this point, and nothing else to look at.
    @ViewBuilder
    private var linkHandoff: some View {
        if let link = viewModel.linkToSend {
            ShareLink(item: link, message: Text(viewModel.linkInvitation)) {
                HStack(spacing: 8) {
                    Image(systemName: "square.and.arrow.up")
                    Text(viewModel.linkHandoffTitle)
                }
                .font(.system(.callout, design: .serif).weight(.semibold))
                .foregroundStyle(Palette.ink)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 11)
                .background {
                    Capsule().fill(Palette.amber.opacity(0.3))
                    Capsule().strokeBorder(Palette.amber, lineWidth: 1.6)
                }
            }
            .accessibilityIdentifier("sendLink")
            .padding(.horizontal, 4)
        }
    }

    private var controls: some View {
        HStack(spacing: 12) {
            Button("Restart") { viewModel.isShowingRestartConfirmation = true }
                .buttonStyle(PaperButtonStyle())

            if viewModel.canForfeit {
                Button("Forfeit") { isShowingForfeitConfirmation = true }
                    .buttonStyle(PaperButtonStyle())
            }

            #if DEBUG
            // Development affordances from the PRD; never present in a release build.
            Button("Undo") { viewModel.undo() }
                .buttonStyle(PaperButtonStyle())
                .disabled(!viewModel.canUndo)

            // Turn count is the stall metric the PRD asks to watch during playtesting.
            Text("t\(viewModel.displayState.turnNumber)")
                .font(.caption2.monospacedDigit())
                .foregroundStyle(Palette.inkSoft)
            #endif
        }
        .padding(.bottom, 4)
    }
}

/// Score, remaining fruit, and whose turn it is, for one side of the board.
struct ScoreboardView: View {
    let scoreboard: MatchViewModel.Scoreboard
    let seat: PlayerSeat

    var body: some View {
        WashiPanel(cornerRadius: 14, fill: Palette.panelTint(for: seat).opacity(0.65), isAccented: scoreboard.isActive) {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(scoreboard.name)
                            .font(.system(.subheadline, design: .serif).weight(.semibold))
                            .foregroundStyle(Palette.ink)
                        remainingFruit
                    }
                    Spacer(minLength: 8)
                    Text("\(scoreboard.score)")
                        .font(.system(size: 30, weight: .semibold, design: .serif))
                        .foregroundStyle(Palette.ink)
                        .contentTransition(.numericText())
                }
                if let wildcards = scoreboard.wildcards {
                    WildcardTrackerView(standing: wildcards, seat: seat)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(scoreboard.accessibilityLabel)
    }

    private var remainingFruit: some View {
        HStack(spacing: 3) {
            let fruit = scoreboard.remainingFruit
            if fruit.isEmpty {
                Text("no fruit left")
                    .font(.caption2)
                    .foregroundStyle(Palette.inkSoft)
            } else {
                ForEach(fruit) { piece in
                    PieceArtwork(kind: piece.kind, seat: seat, showsTray: false)
                        .frame(width: 18, height: 18)
                }
                if scoreboard.isOnLastFruit {
                    Text("· last fruit — ends the match")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(Palette.amber)
                } else {
                    Text("· \(scoreboard.pointsOnBoard) pts on the board")
                        .font(.caption2)
                        .foregroundStyle(Palette.inkSoft)
                }
            }
        }
    }
}

struct ResultOverlay: View {
    let viewModel: MatchViewModel
    let onExit: () -> Void

    var body: some View {
        ZStack {
            Palette.ink.opacity(0.28).ignoresSafeArea()
            WashiPanel(cornerRadius: 22, isAccented: true) {
                VStack(spacing: 18) {
                    VStack(spacing: 6) {
                        Text(viewModel.resultText ?? "")
                            .font(.system(.title2, design: .serif).weight(.semibold))
                            .foregroundStyle(Palette.ink)
                        if let detail = viewModel.resultDetail {
                            Text(detail)
                                .font(.system(.footnote, design: .serif))
                                .foregroundStyle(Palette.inkSoft)
                        }
                    }
                    .multilineTextAlignment(.center)

                    HStack(spacing: 20) {
                        ForEach([viewModel.bottomPlayer, viewModel.topPlayer], id: \.self) { player in
                            let scoreboard = viewModel.scoreboard(for: player)
                            VStack(spacing: 2) {
                                Text(scoreboard.name)
                                    .font(.caption)
                                    .foregroundStyle(Palette.inkSoft)
                                Text("\(scoreboard.score)")
                                    .font(.system(.title3, design: .serif).weight(.semibold))
                                    .foregroundStyle(Palette.ink)
                            }
                        }
                    }

                    HStack(spacing: 12) {
                        if viewModel.canPlayAgain {
                            Button("Play again") { viewModel.restart() }
                                .buttonStyle(PaperButtonStyle(isPrimary: true))
                        }
                        Button("Done", action: onExit)
                            .buttonStyle(PaperButtonStyle())
                    }
                }
                .padding(26)
            }
            .padding(32)
        }
    }
}

/// Warm paper, ink serif text, an amber frame when it is the one thing to press. No
/// glossy or plastic controls anywhere in the app.
struct PaperButtonStyle: ButtonStyle {
    var isPrimary = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(.callout, design: .serif).weight(.medium))
            .foregroundStyle(Palette.ink)
            .padding(.horizontal, 18)
            .padding(.vertical, 10)
            .background {
                Capsule().fill(isPrimary ? Palette.amber.opacity(0.35) : Palette.cream)
            }
            .overlay {
                Capsule().strokeBorder(
                    isPrimary ? Palette.amber : Palette.inkSoft,
                    lineWidth: isPrimary ? 2 : 1.2
                )
            }
            .opacity(configuration.isPressed ? 0.7 : 1)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

#Preview("Local match") {
    NavigationStack {
        MatchView(viewModel: MatchViewModel(mode: .localVersus))
    }
}
