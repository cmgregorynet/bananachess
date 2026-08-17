import BananaChessEngine
import SwiftUI

struct HomeView: View {
    @Environment(\.onlineMatchService) private var onlineService

    @AppStorage("lastCPUDifficulty") private var storedDifficulty = CPUDifficulty.medium.rawValue
    @AppStorage("lastCPUHumanPlaysFirst") private var humanPlaysFirst = true
    @AppStorage("wildcardRulesEnabled") private var wildcardRules = false
    @AppStorage("boardSetup") private var storedBoardSetup = BoardSetup.standard.rawValue

    @State private var route: Route?
    @State private var isShowingRules = false
    @State private var isShowingCPUOptions = false

    private enum Route: Hashable {
        case local
        case computer(CPUDifficulty, PlayerID)
        case online
    }

    private var difficulty: CPUDifficulty {
        CPUDifficulty(rawValue: storedDifficulty) ?? .medium
    }

    private var ruleSet: RuleSet { wildcardRules ? .wildcard : .standard }

    private var boardSetup: BoardSetup {
        BoardSetup(rawValue: storedBoardSetup) ?? .standard
    }

    /// One blend in three throws pulp at the screen. `-alwaysSplatter` pins it on for
    /// development and screenshots; like Undo, it does not exist in a release build.
    private var blendEffects: BlendEffectPolicy {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("-alwaysSplatter") ? .always : .standard
        #else
        .standard
        #endif
    }

    var body: some View {
        NavigationStack {
            ZStack {
                PaperBackground()

                VStack(spacing: 0) {
                    Spacer(minLength: 12)
                    title
                    Spacer(minLength: 16)
                    menu
                    Spacer(minLength: 18)
                    BoardSetupPicker(selection: $storedBoardSetup)
                    Spacer(minLength: 10)
                    WildcardRulesToggle(isOn: $wildcardRules)
                    Spacer(minLength: 12)
                    Button("How to play") { isShowingRules = true }
                        .buttonStyle(PaperButtonStyle())
                        .padding(.bottom, 24)
                }
                .padding(.horizontal, 28)
            }
            .navigationDestination(item: $route) { route in
                switch route {
                case .local:
                    MatchView(
                        viewModel: MatchViewModel(
                            mode: .localVersus,
                            state: GameSetup.newGame(ruleSet: ruleSet, boardSetup: boardSetup),
                            blendEffects: blendEffects
                        )
                    )
                case .computer(let difficulty, let humanPlays):
                    MatchView(
                        viewModel: MatchViewModel(
                            mode: .computer(difficulty: difficulty, humanPlays: humanPlays),
                            state: GameSetup.newGame(
                                startingPlayer: .one,
                                ruleSet: ruleSet,
                                boardSetup: boardSetup
                            ),
                            blendEffects: blendEffects
                        )
                    )
                case .online:
                    OnlineLobbyView(
                        viewModel: OnlineLobbyViewModel(
                            service: onlineService,
                            ruleSet: ruleSet,
                            boardSetup: boardSetup
                        )
                    )
                }
            }
            .sheet(isPresented: $isShowingRules) { RulesView() }
            .sheet(isPresented: $isShowingCPUOptions) {
                CPUOptionsSheet(
                    difficulty: Binding(
                        get: { difficulty },
                        set: { storedDifficulty = $0.rawValue }
                    ),
                    humanPlaysFirst: $humanPlaysFirst,
                    onStart: {
                        isShowingCPUOptions = false
                        route = .computer(difficulty, humanPlaysFirst ? .one : .two)
                    }
                )
                .presentationDetents([.medium])
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
            ) { route = .local }

            MenuButton(
                title: "Play Computer",
                detail: "\(difficulty.displayName) · \(difficulty.blurb)"
            ) { isShowingCPUOptions = true }

            MenuButton(
                title: "Play Online",
                detail: "Take a turn, put the phone down, come back later."
            ) { route = .online }
        }
    }
}

/// The rules switch. A checkbox rather than a segmented control: standard is the game,
/// wildcard is the game plus four extra things, and a tick reads that way.
struct WildcardRulesToggle: View {
    @Binding var isOn: Bool

    var body: some View {
        Button {
            withAnimation(.easeOut(duration: 0.18)) { isOn.toggle() }
            Haptics.selection()
        } label: {
            WashiPanel(cornerRadius: 14, isAccented: isOn) {
                HStack(alignment: .top, spacing: 12) {
                    checkbox
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Wildcard rules")
                            .font(.system(.callout, design: .serif).weight(.semibold))
                            .foregroundStyle(Palette.ink)
                        Text("Golden Pineapple, Banana Split, Tropical Smoothie, Blenderama.")
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
        .accessibilityLabel("Wildcard rules")
        .accessibilityHint("Adds four extra rules, including two ways to win outright")
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
    HomeView()
}


/// Three boards, one row. A segmented control rather than a checkbox because the choice
/// is three-way and mutually exclusive, and because the blurb under it has to change to
/// explain whichever is selected — "Random" means nothing without a sentence.
struct BoardSetupPicker: View {
    @Binding var selection: String

    private var setup: BoardSetup { BoardSetup(rawValue: selection) ?? .standard }

    var body: some View {
        WashiPanel(cornerRadius: 14) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Board")
                    .font(.system(.callout, design: .serif).weight(.semibold))
                    .foregroundStyle(Palette.ink)

                HStack(spacing: 6) {
                    ForEach(BoardSetup.allCases) { option in
                        Button {
                            withAnimation(.easeOut(duration: 0.18)) { selection = option.rawValue }
                            Haptics.selection()
                        } label: {
                            Text(option.displayName)
                                .font(.system(size: 12, design: .serif).weight(.medium))
                                .foregroundStyle(Palette.ink)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 7)
                                .background {
                                    let isOn = option == setup
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
                        .accessibilityAddTraits(
                            option == setup ? [.isButton, .isSelected] : .isButton
                        )
                        // Stated as a value as well as a trait, matching the wildcard
                        // checkbox. The trait alone does not survive the ForEach into
                        // the accessibility tree reliably, which left the selection
                        // unreadable to VoiceOver and untestable.
                        .accessibilityValue(option == setup ? "selected" : "not selected")
                    }
                }

                Text(setup.blurb)
                    .font(.system(size: 11, design: .serif))
                    .foregroundStyle(Palette.inkSoft)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(14)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Board arrangement")
    }
}
