import BananaChessEngine
import SwiftUI

struct OnlineLobbyView: View {
    @State var viewModel: OnlineLobbyViewModel
    /// Which service is behind this lobby, for the wording only — the screen itself does
    /// not care whether matches come from Game Center or iCloud.
    var title = "Game Center"
    @State private var openMatch: OnlineMatchSummary?

    var body: some View {
        ZStack {
            PaperBackground()

            if viewModel.isSignedIn {
                matchList
            } else {
                signInPrompt
            }
        }
        .navigationTitle("Play Online")
        .navigationBarTitleDisplayMode(.inline)
        .task { viewModel.start() }
        .refreshable { await viewModel.refresh() }
        .navigationDestination(item: $openMatch) { summary in
            MatchView(viewModel: viewModel.makeMatchViewModel(for: summary))
        }
        .alert(title, isPresented: .constant(viewModel.error != nil)) {
            Button("OK") { viewModel.error = nil }
        } message: {
            Text(viewModel.error ?? "")
        }
    }

    private var signInPrompt: some View {
        VStack(spacing: 16) {
            if viewModel.isAuthenticating {
                ProgressView().tint(Palette.ink)
                Text("Signing in to Game Center…")
                    .font(.system(.subheadline, design: .serif))
                    .foregroundStyle(Palette.inkSoft)
            } else {
                Text("Sign in to Game Center to play online.")
                    .font(.system(.headline, design: .serif))
                    .foregroundStyle(Palette.ink)
                Text("Local and computer matches work without it.")
                    .font(.system(.subheadline, design: .serif))
                    .foregroundStyle(Palette.inkSoft)
                Button("Try again") { viewModel.start() }
                    .buttonStyle(PaperButtonStyle(isPrimary: true))
            }
        }
        .multilineTextAlignment(.center)
        .padding(32)
    }

    private var matchList: some View {
        ScrollView {
            VStack(spacing: 18) {
                HStack(spacing: 12) {
                    Button("Invite a friend") {
                        viewModel.inviteFriend { openMatch = $0 }
                    }
                    .buttonStyle(PaperButtonStyle(isPrimary: true))

                    Button("Find opponent") {
                        viewModel.startAutomatch { openMatch = $0 }
                    }
                    .buttonStyle(PaperButtonStyle())
                }
                .padding(.top, 8)

                section("Your turn", viewModel.yourTurnMatches)
                section("Waiting", viewModel.waitingMatches)
                section("Finished", viewModel.finishedMatches)

                if viewModel.matches.isEmpty && !viewModel.isLoading {
                    Text("No matches yet. Invite a friend or find an opponent.")
                        .font(.system(.subheadline, design: .serif))
                        .foregroundStyle(Palette.inkSoft)
                        .padding(.top, 24)
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 32)
        }
    }

    @ViewBuilder
    private func section(_ title: String, _ matches: [OnlineMatchSummary]) -> some View {
        if !matches.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text(title)
                    .font(.system(.subheadline, design: .serif).weight(.semibold))
                    .foregroundStyle(Palette.inkSoft)
                    .frame(maxWidth: .infinity, alignment: .leading)

                ForEach(matches) { summary in
                    Button { openMatch = summary } label: {
                        MatchRow(row: viewModel.row(for: summary))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

struct MatchRow: View {
    let row: OnlineLobbyViewModel.Row

    var body: some View {
        WashiPanel(cornerRadius: 14, isAccented: row.isYourTurn) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(row.title)
                        .font(.system(.body, design: .serif).weight(.medium))
                        .foregroundStyle(Palette.ink)
                    Text(row.detail)
                        .font(.caption)
                        .foregroundStyle(Palette.inkSoft)
                }
                Spacer()
                if let score = row.score {
                    Text(score)
                        .font(.system(.body, design: .serif).weight(.semibold))
                        .foregroundStyle(Palette.ink)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
        }
    }
}
