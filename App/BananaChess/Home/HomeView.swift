import BananaChessEngine
import SwiftUI

struct HomeView: View {
    @State var viewModel: HomeViewModel
    @State private var isShowingRules = false
    @State private var isShowingCPUOptions = false

    var body: some View {
        NavigationStack {
            ZStack {
                PaperBackground()

                // Scrolls, because it has to. Four ways to play, two board dimensions
                // and two rules opt-ins no longer fit a phone between the title and the
                // bottom edge — the fixed layout simply pushed "How to play" off the
                // screen where nothing could reach it.
                ScrollView {
                    VStack(spacing: 16) {
                        title
                            .padding(.top, 12)
                        menu
                        BoardOptionsPanel(setup: $viewModel.boardSetup, wrap: $viewModel.boardWrap)
                        WildcardRulesToggle(isOn: $viewModel.usesWildcardRules)
                        RulesCheckbox(
                            isOn: $viewModel.blenderBattles,
                            title: "Blender battles",
                            detail: "Blenders fight for a square instead of just taking it. "
                                + "Half your standing fruit, plus one for attacking, plus a die each.",
                            hint: "Blender collisions are decided by fruit on the board and a dice roll"
                        )
                        Button("How to play") { isShowingRules = true }
                            .buttonStyle(PaperButtonStyle())
                            .padding(.top, 4)
                            .padding(.bottom, 24)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, 28)
                }
                .scrollBounceBehavior(.basedOnSize)
            }
            .navigationDestination(item: $viewModel.route) { route in
                destination(for: route)
            }
            .onChange(of: viewModel.acceptedInvitationID) { _, accepted in
                guard accepted != nil else { return }
                viewModel.openAcceptedInvitation()
            }
            .onOpenURL { viewModel.open($0) }
            .alert(
                "Can't open that match",
                isPresented: Binding(
                    get: { viewModel.linkError != nil },
                    set: { if !$0 { viewModel.linkError = nil } }
                )
            ) {
                Button("OK", role: .cancel) { viewModel.linkError = nil }
            } message: {
                Text(viewModel.linkError ?? "")
            }
            .sheet(isPresented: $isShowingRules) { RulesView() }
            .sheet(isPresented: $isShowingCPUOptions) {
                CPUOptionsSheet(
                    difficulty: $viewModel.difficulty,
                    humanPlaysFirst: $viewModel.humanPlaysFirst,
                    onStart: {
                        isShowingCPUOptions = false
                        viewModel.playComputer()
                    }
                )
                .presentationDetents([.medium])
            }
        }
    }

    @ViewBuilder
    private func destination(for route: HomeViewModel.Route) -> some View {
        if let match = viewModel.makeMatchViewModel(for: route) {
            MatchView(viewModel: match)
        } else if let lobby = viewModel.makeLobbyViewModel(for: route) {
            if route == .cloudLobby {
                OnlineLobbyView(viewModel: lobby, title: "iCloud")
            } else {
                OnlineLobbyView(viewModel: lobby)
            }
        }
    }

    private var title: some View {
        VStack(spacing: 6) {
            HStack(spacing: 6) {
                PieceArtwork(kind: .banana, seat: .bottom).frame(width: 46, height: 46)
                PieceArtwork(kind: .blender, seat: .top).frame(width: 46, height: 46)
                PieceArtwork(kind: .strawberry, seat: .bottom).frame(width: 46, height: 46)
            }
            Text("Banana Chess")
                .font(.system(size: 40, weight: .semibold, design: .serif))
                .foregroundStyle(Palette.ink)
            Text("Race the fruit. Mind the blenders.")
                .font(.system(.subheadline, design: .serif))
                .foregroundStyle(Palette.inkSoft)
        }
    }

    private var menu: some View {
        VStack(spacing: 14) {
            MenuButton(
                title: "Local Two Player",
                detail: "Share one phone and take turns.",
                isPrimary: true
            ) { viewModel.playLocal() }

            MenuButton(
                title: "Play Computer",
                detail: "\(viewModel.difficulty.displayName) · \(viewModel.difficulty.blurb)"
            ) { isShowingCPUOptions = true }

            MenuButton(
                title: "Play Online",
                detail: "Game Center. Their move arrives on its own."
            ) { viewModel.playOnline() }

            MenuButton(
                title: "Invite by Link",
                detail: "Send a friend a link and play. Live over iCloud."
            ) { viewModel.invite() }
        }
    }
}

/// The rules switch. A checkbox rather than a segmented control: standard is the game,
/// wildcard is the game plus four extra things, and a tick reads that way.
struct WildcardRulesToggle: View {
    @Binding var isOn: Bool

    var body: some View {
        RulesCheckbox(
            isOn: $isOn,
            title: "Wildcard rules",
            detail: "Golden Pineapple, Banana Split, Tropical Smoothie, Blenderama.",
            hint: "Adds four extra rules, including two ways to win outright"
        )
    }
}

/// A rules opt-in. Two of these now sit on the start screen, so the panel, the checkbox
/// and — importantly — the accessibility shape are shared rather than written twice.
struct RulesCheckbox: View {
    @Binding var isOn: Bool
    let title: String
    let detail: String
    let hint: String

    var body: some View {
        Button {
            withAnimation(.easeOut(duration: 0.18)) { isOn.toggle() }
            Haptics.selection()
        } label: {
            WashiPanel(cornerRadius: 14, isAccented: isOn) {
                HStack(alignment: .top, spacing: 12) {
                    checkbox
                    VStack(alignment: .leading, spacing: 3) {
                        Text(title)
                            .font(.system(.callout, design: .serif).weight(.semibold))
                            .foregroundStyle(Palette.ink)
                        Text(detail)
                            .font(.system(size: 11, design: .serif))
                            .foregroundStyle(Palette.inkSoft)
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                }
                .padding(14)
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityHint(hint)
        .accessibilityAddTraits(isOn ? [.isButton, .isSelected] : .isButton)
        .accessibilityValue(isOn ? "on" : "off")
    }

    private var checkbox: some View {
        RoundedRectangle(cornerRadius: 5, style: .continuous)
            .strokeBorder(isOn ? Palette.amber : Palette.inkSoft, lineWidth: isOn ? 2 : 1.4)
            .background {
                RoundedRectangle(cornerRadius: 5, style: .continuous)
                    .fill(isOn ? Palette.amber.opacity(0.28) : .clear)
            }
            .frame(width: 22, height: 22)
            .overlay {
                if isOn {
                    Image(systemName: "checkmark")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(Palette.ink)
                }
            }
    }
}

struct MenuButton: View {
    let title: String
    let detail: String
    var isPrimary = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            WashiPanel(cornerRadius: 18, isAccented: isPrimary) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.system(.title3, design: .serif).weight(.semibold))
                        .foregroundStyle(Palette.ink)
                    Text(detail)
                        .font(.system(.footnote, design: .serif))
                        .foregroundStyle(Palette.inkSoft)
                        .multilineTextAlignment(.leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(18)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title). \(detail)")
    }
}

struct CPUOptionsSheet: View {
    @Binding var difficulty: CPUDifficulty
    @Binding var humanPlaysFirst: Bool
    let onStart: () -> Void

    var body: some View {
        ZStack {
            PaperBackground()
            VStack(alignment: .leading, spacing: 20) {
                Text("Play Computer")
                    .font(.system(.title2, design: .serif).weight(.semibold))
                    .foregroundStyle(Palette.ink)

                VStack(alignment: .leading, spacing: 8) {
                    Text("Difficulty")
                        .font(.system(.subheadline, design: .serif))
                        .foregroundStyle(Palette.inkSoft)
                    Picker("Difficulty", selection: $difficulty) {
                        ForEach(CPUDifficulty.allCases) { level in
                            Text(level.displayName).tag(level)
                        }
                    }
                    .pickerStyle(.segmented)
                    Text(difficulty.blurb)
                        .font(.system(.footnote, design: .serif))
                        .foregroundStyle(Palette.inkSoft)
                }

                Toggle(isOn: $humanPlaysFirst) {
                    Text("You move first")
                        .font(.system(.body, design: .serif))
                        .foregroundStyle(Palette.ink)
                }
                .tint(Palette.amber)

                Button("Start match", action: onStart)
                    .buttonStyle(PaperButtonStyle(isPrimary: true))
                    .frame(maxWidth: .infinity)

                Spacer()
            }
            .padding(28)
        }
    }
}

#Preview {
    HomeView(
        viewModel: HomeViewModel(
            gameCenter: GameCenterMatchService(),
            inbox: CloudKitInbox(service: CloudKitMatchService())
        )
    )
}


/// The two board dimensions in one panel. Kept together because they are one decision
/// in the player's head — "what board am I playing on" — and because two full-width
/// panels stacked would push the start screen off a small phone.
struct BoardOptionsPanel: View {
    @Binding var setup: BoardSetup
    @Binding var wrap: BoardWrap

    var body: some View {
        WashiPanel(cornerRadius: 14) {
            VStack(alignment: .leading, spacing: 10) {
                BoardOptionRow(title: "Board", selection: $setup)
                BoardOptionRow(title: "Edges", selection: $wrap)
            }
            .padding(14)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Board options")
    }
}

/// A segmented choice with a blurb that follows the selection. Generic over the two
/// board dimensions rather than written twice: "Random" and "All" both mean nothing
/// without a sentence under them, and that sentence has to change with the tap.
struct BoardOptionRow<Option: BoardOption>: View where Option.AllCases: RandomAccessCollection {
    let title: String
    @Binding var selection: Option

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(.callout, design: .serif).weight(.semibold))
                .foregroundStyle(Palette.ink)

            HStack(spacing: 6) {
                ForEach(Option.allCases) { option in
                    let isOn = option.rawValue == selection.rawValue
                    Button {
                        withAnimation(.easeOut(duration: 0.18)) { selection = option }
                        Haptics.selection()
                    } label: {
                        Text(option.displayName)
                            .font(.system(size: 12, design: .serif).weight(.medium))
                            .foregroundStyle(Palette.ink)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 7)
                            .background {
                                Capsule().fill(isOn ? Palette.amber.opacity(0.28) : .clear)
                                    .overlay {
                                        Capsule().strokeBorder(
                                            isOn ? Palette.amber : Palette.inkFaint,
                                            lineWidth: isOn ? 2 : 1
                                        )
                                    }
                            }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(option.displayName)
                    .accessibilityHint(option.blurb)
                    .accessibilityAddTraits(isOn ? [.isButton, .isSelected] : .isButton)
                    // Stated as a value as well as a trait: the trait alone does not
                    // survive the ForEach into the accessibility tree, which left the
                    // selection unreadable to VoiceOver and untestable.
                    .accessibilityValue(isOn ? "selected" : "not selected")
                }
            }

            Text(selection.blurb)
                .font(.system(size: 11, design: .serif))
                .foregroundStyle(Palette.inkSoft)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
