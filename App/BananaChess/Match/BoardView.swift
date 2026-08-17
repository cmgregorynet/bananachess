import BananaChessEngine
import SwiftUI

/// The painted 6×6 board. Squares below, pieces floating above them so a piece can
/// travel between squares rather than blinking from one to the next.
struct BoardView: View {
    let viewModel: MatchViewModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Read from the match, not from a constant: where the spawns are depends on which
    /// board this match was dealt.
    private var blenderHomes: Set<Position> { viewModel.displayState.allSpawnSquares }

    var body: some View {
        GeometryReader { proxy in
            let side = min(proxy.size.width, proxy.size.height)
            let cell = side / CGFloat(Board.size)

            ZStack(alignment: .topLeading) {
                boardGround(side: side)
                squares(cell: cell)
                knockedOutBlenders(cell: cell)
                pieces(cell: cell)
            }
            .frame(width: side, height: side)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            // Pulp and labels sit outside the clip so a burst on an edge square can
            // throw droplets past the border instead of being sliced off by it.
            .overlay(alignment: .topLeading) {
                ZStack(alignment: .topLeading) {
                    bursts(cell: cell)
                    flashLabel(cell: cell)
                }
                .frame(width: side, height: side, alignment: .topLeading)
            }
            .overlay {
                // The printed border from the shin-hanga half of the bible.
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(Palette.ink.opacity(0.7), lineWidth: 2)
                    .padding(-6)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .aspectRatio(1, contentMode: .fit)
    }

    // MARK: - Ground

    private func boardGround(side: CGFloat) -> some View {
        RoundedRectangle(cornerRadius: 6, style: .continuous)
            .fill(Palette.squareLight)
            .frame(width: side, height: side)
            .paperGrain(opacity: 0.4)
    }

    // MARK: - Squares

    /// Real layout rather than offsets: an offset square renders in the right place but
    /// keeps its accessibility frame at the origin, which stacks all thirty-six squares
    /// on top of each other for VoiceOver. Pieces still use offsets — they need to
    /// travel — but nothing tappable does.
    private func squares(cell: CGFloat) -> some View {
        VStack(spacing: 0) {
            ForEach(0..<Board.size, id: \.self) { displayRow in
                HStack(spacing: 0) {
                    ForEach(0..<Board.size, id: \.self) { displayColumn in
                        square(at: boardPosition(displayRow: displayRow, displayColumn: displayColumn), cell: cell)
                    }
                }
            }
        }
    }

    private func square(at position: Position, cell: CGFloat) -> some View {
        SquareView(
            position: position,
            isDark: position.isDarkSquare,
            isBlenderHome: blenderHomes.contains(position),
            deliveryEdge: deliveryEdge(at: position),
            isLegalDestination: viewModel.legalDestinations.contains(position),
            isSelectedOrigin: selectedPosition == position
        )
        .frame(width: cell, height: cell)
        .contentShape(Rectangle())
        .onTapGesture { viewModel.tap(position) }
        .accessibilityElement(children: .ignore)
        .accessibilityIdentifier("square_\(position.row)_\(position.column)")
        .accessibilityLabel(accessibilityLabel(for: position))
        .accessibilityAddTraits(.isButton)
    }

    /// Which player's fruit scores by reaching this square — drawn as a soft hatch band
    /// so the two scoring edges are never in doubt.
    private func deliveryEdge(at position: Position) -> PlayerSeat? {
        if position.row == PlayerID.one.deliveryRow { return viewModel.seat(for: .one) }
        if position.row == PlayerID.two.deliveryRow { return viewModel.seat(for: .two) }
        return nil
    }

    // MARK: - Pieces

    private func pieces(cell: CGFloat) -> some View {
        ForEach(viewModel.displayState.pieces.filter(\.isOnBoard)) { piece in
            pieceView(piece, cell: cell)
        }
    }

    @ViewBuilder
    private func pieceView(_ piece: Piece, cell: CGFloat) -> some View {
        let isSelected: Bool = viewModel.selectedPieceID == piece.id
        let square: Position = piece.position ?? Position(row: 0, column: 0)

        PieceArtwork(
            kind: piece.kind,
            seat: viewModel.seat(for: piece.owner),
            isGolden: viewModel.displayState.wildcards.isGolden(piece.id)
        )
            .frame(width: cell, height: cell)
            .scaleEffect(isSelected ? 1.08 : 1)
            .overlay {
                if isSelected {
                    Circle()
                        .strokeBorder(Palette.amber, lineWidth: 3)
                        .frame(width: cell * 0.94, height: cell * 0.94)
                }
            }
            .offset(offset(for: square, cell: cell))
            .transition(reduceMotion ? .pulpedCalmly : .pulped)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }

    /// What a missing blender leaves behind. A smoothie victim shows a countdown on its
    /// own spawn; a combat loss shows a price on *every* spawn its owner could buy it
    /// back onto, since either one will do.
    private func knockedOutBlenders(cell: CGFloat) -> some View {
        ZStack(alignment: .topLeading) {
            ForEach(viewModel.displayState.destroyedBlenders.filter(\.isAwaitingRespawn)) { blender in
                if let home = blender.home {
                    marker(
                        seat: viewModel.seat(for: blender.owner),
                        isPurchasable: false,
                        turnsUntilReturn: viewModel.displayState.turnsUntilRespawn(of: blender),
                        at: home,
                        cell: cell
                    )
                }
            }
            ForEach(viewModel.purchasableSpawns, id: \.self) { spawn in
                marker(
                    seat: viewModel.seat(for: viewModel.displayState.activePlayer),
                    isPurchasable: true,
                    turnsUntilReturn: nil,
                    at: spawn,
                    cell: cell
                )
            }
        }
    }

    private func marker(
        seat: PlayerSeat,
        isPurchasable: Bool,
        turnsUntilReturn: Int?,
        at square: Position,
        cell: CGFloat
    ) -> some View {
        DestroyedBlenderMarker(
            seat: seat,
            isPurchasable: isPurchasable,
            turnsUntilReturn: turnsUntilReturn
        )
        .frame(width: cell, height: cell)
        .offset(offset(for: square, cell: cell))
        // Purely decorative: the square underneath owns the tap, so there is only ever
        // one gesture per square.
        .allowsHitTesting(false)
        .transition(.opacity)
        .accessibilityHidden(true)
    }

    private func bursts(cell: CGFloat) -> some View {
        ForEach(viewModel.blendBursts) { burst in
            BlendBurstView(kind: burst.kind, seed: burst.seed, reduceMotion: reduceMotion)
                .frame(width: cell, height: cell)
                .offset(offset(for: burst.position, cell: cell))
                // Reported up to the match screen so a splatter knows where on screen
                // its fruit was standing.
                .anchorPreference(key: BlendOriginKey.self, value: .bounds) { [burst.id: $0] }
                .task {
                    try? await Task.sleep(for: .milliseconds(750))
                    viewModel.retireBurst(burst.id)
                }
        }
    }

    private func flashLabel(cell: CGFloat) -> some View {
        Group {
            if let flash = viewModel.flash {
                Text(flash.text)
                    .font(.system(.caption, design: .serif).weight(.semibold))
                    .foregroundStyle(Palette.ink)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background {
                        Capsule().fill(Palette.cream)
                        Capsule().strokeBorder(Palette.amber, lineWidth: 1.5)
                    }
                    .fixedSize()
                    .offset(offset(for: flash.position, cell: cell))
                    .offset(x: cell * 0.5, y: -cell * 0.15)
                    .transition(.opacity.combined(with: .offset(y: 8)))
                    .allowsHitTesting(false)
            }
        }
        .animation(.easeOut(duration: 0.25), value: viewModel.flash)
    }

    // MARK: - Geometry

    private var selectedPosition: Position? {
        guard let id = viewModel.selectedPieceID else { return nil }
        return viewModel.displayState.piece(with: id)?.position
    }

    /// A half-turn rotation when the local player sits at the bottom as player two, so
    /// each device always sees its own side nearest to it.
    private func boardPosition(displayRow: Int, displayColumn: Int) -> Position {
        guard viewModel.isRotated else {
            return Position(row: displayRow, column: displayColumn)
        }
        return Position(row: Board.size - 1 - displayRow, column: Board.size - 1 - displayColumn)
    }

    private func offset(for position: Position, cell: CGFloat) -> CGSize {
        let row = viewModel.isRotated ? Board.size - 1 - position.row : position.row
        let column = viewModel.isRotated ? Board.size - 1 - position.column : position.column
        return CGSize(width: CGFloat(column) * cell, height: CGFloat(row) * cell)
    }

    private func accessibilityLabel(for position: Position) -> String {
        let square = "row \(position.row + 1), column \(position.column + 1)"
        guard let piece = viewModel.displayState.piece(at: position) else {
            var description = "Empty square, \(square)"
            if blenderHomes.contains(position) { description += ", blender home" }
            if let waiting = viewModel.displayState.destroyedBlenders
                .first(where: { $0.isAwaitingRespawn && $0.home == position }) {
                let owner = viewModel.mode.name(for: waiting.owner)
                let turns = viewModel.displayState.turnsUntilRespawn(of: waiting) ?? 0
                description += ", \(owner) blender blown up by a smoothie, "
                    + "back in \(turns) \(turns == 1 ? "turn" : "turns")"
            }
            if viewModel.restorableBlender(at: position) != nil {
                description += ", a destroyed blender can come back here, "
                    + "tap to buy it back for \(RulesEngine.blenderRestoreCost) points"
            }
            return description + legalMoveSuffix(for: position)
        }

        let owner = viewModel.mode.name(for: piece.owner)
        let golden = viewModel.displayState.wildcards.isGolden(piece.id) ? "golden " : ""
        let described = piece.isBlender
            ? "\(owner) blender, \(square)"
            : "\(owner) \(golden)\(piece.kind.rawValue), \(square), value \(piece.kind.baseValue)"
        return described + legalMoveSuffix(for: position)
    }

    /// Blenders can now land on occupied squares, so an occupied square may well be a
    /// legal destination — and what it costs depends on whose fruit is standing there.
    /// Saying so out loud is the only way that reaches a VoiceOver player.
    private func legalMoveSuffix(for position: Position) -> String {
        guard viewModel.legalDestinations.contains(position) else { return "" }
        guard let selectedID = viewModel.selectedPieceID,
              let selected = viewModel.displayState.piece(with: selectedID),
              let occupant = viewModel.displayState.piece(at: position)
        else { return ", legal move" }

        if occupant.isBlender {
            return ", legal move, knocks both blenders out"
        }
        let value = occupant.kind.baseValue
        return occupant.owner == selected.owner
            ? ", legal move, blends your own \(occupant.kind.rawValue), minus \(value)"
            : ", legal move, blends for plus \(value)"
    }
}

/// The gap a destroyed blender left, drawn on the spawn it can be bought back to. Amber
/// when its owner can actually afford the turn to do it.
struct DestroyedBlenderMarker: View {
    let seat: PlayerSeat
    let isPurchasable: Bool
    /// Set only for a smoothie blackout, which ends on its own.
    var turnsUntilReturn: Int?

    var body: some View {
        GeometryReader { proxy in
            let side = min(proxy.size.width, proxy.size.height)
            ZStack {
                Circle()
                    .fill(Palette.tray(for: seat).opacity(0.28))
                // The ghost of the blender that is missing, so a destroyed one never
                // reads as an ordinary vacated spawn.
                PieceArtwork(kind: .blender, seat: seat, showsTray: false)
                    .padding(side * 0.16)
                    .opacity(isPurchasable ? 0.4 : 0.28)
                    .grayscale(1)
                Circle()
                    .strokeBorder(
                        isPurchasable ? Palette.amber : Palette.trayShade(for: seat).opacity(0.65),
                        style: StrokeStyle(lineWidth: side * 0.04, dash: [side * 0.09, side * 0.07])
                    )
                if let turns = turnsUntilReturn {
                    Text("\(turns)")
                        .font(.system(size: side * 0.38, weight: .semibold, design: .serif))
                        .foregroundStyle(Palette.ink.opacity(0.85))
                        .contentTransition(.numericText(countsDown: true))
                } else if isPurchasable {
                    Text("−\(RulesEngine.blenderRestoreCost)")
                        .font(.system(size: side * 0.28, weight: .bold, design: .serif))
                        .foregroundStyle(Palette.ink)
                        .padding(.horizontal, side * 0.08)
                        .background { Capsule().fill(Palette.cream.opacity(0.9)) }
                }
            }
            .frame(width: side * 0.82, height: side * 0.82)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

/// Carries each blend burst's frame up to the match screen, which is the only view that
/// spans the whole window and can therefore host a screen-wide splatter.
struct BlendOriginKey: PreferenceKey {
    static let defaultValue: [UUID: Anchor<CGRect>] = [:]

    static func reduce(value: inout [UUID: Anchor<CGRect>], nextValue: () -> [UUID: Anchor<CGRect>]) {
        value.merge(nextValue()) { $1 }
    }
}

/// One square: two weights of the same warm paper, a marked blender home that stays
/// visible when vacated, and the amber legal-move dot.
struct SquareView: View {
    let position: Position
    let isDark: Bool
    let isBlenderHome: Bool
    let deliveryEdge: PlayerSeat?
    let isLegalDestination: Bool
    let isSelectedOrigin: Bool

    var body: some View {
        ZStack {
            Rectangle()
                .fill(isDark ? Palette.squareDark : Palette.squareLight)

            if let deliveryEdge {
                DeliveryHatch(seat: deliveryEdge)
                    .clipped()
                    .allowsHitTesting(false)
            }

            Rectangle()
                .strokeBorder(Palette.ink.opacity(0.18), lineWidth: 0.7)

            if isBlenderHome {
                Circle()
                    .strokeBorder(Palette.ink.opacity(0.45), style: StrokeStyle(lineWidth: 1.4, dash: [3, 3]))
                    .padding(6)
            }

            if isSelectedOrigin {
                Rectangle().fill(Palette.amber.opacity(0.16))
            }

            if isLegalDestination {
                Circle()
                    .fill(Palette.amber.opacity(0.85))
                    .frame(width: 12, height: 12)
                    .overlay { Circle().strokeBorder(Palette.ink.opacity(0.4), lineWidth: 1) }
            }
        }
    }
}

/// Soft pencil hatching along a scoring edge, warm for the bottom seat and cool for the
/// top one, so the two delivery rows read differently without relying on colour.
struct DeliveryHatch: View {
    let seat: PlayerSeat

    var body: some View {
        GeometryReader { proxy in
            let size = proxy.size
            Path { path in
                let step: CGFloat = 7
                var offset = -size.height
                while offset < size.width {
                    path.move(to: CGPoint(x: offset, y: size.height))
                    path.addLine(to: CGPoint(x: offset + size.height, y: 0))
                    offset += step
                }
            }
            .stroke(
                (seat == .bottom ? Palette.sand : Palette.fogWater).opacity(0.75),
                lineWidth: seat == .bottom ? 1.2 : 0.8
            )
        }
    }
}
