import BananaChessEngine
import SwiftUI
import UIKit

/// The art chokepoint, the same idea as Cherry Coast's `PlaceholderArt.gd`: if a
/// produced watercolour sprite has been dropped into the asset catalog under the
/// expected name it is used; otherwise the vector artwork below draws it. New art can
/// therefore ship as a file drop, and art never blocks the build.
enum ArtCatalog {
    static func spriteName(for kind: PieceKind, seat: PlayerSeat) -> String {
        "piece_\(kind.rawValue)_\(seat == .bottom ? "p1" : "p2")"
    }

    static func sprite(for kind: PieceKind, seat: PlayerSeat) -> Image? {
        guard let image = UIImage(named: spriteName(for: kind, seat: seat)) else { return nil }
        return Image(uiImage: image)
    }
}

/// One piece: an ownership tray, then the fruit or blender on top of it. Ownership is
/// carried three ways over — warm versus cool tray, single versus double pencil ring,
/// and the seat glyph — so it survives colour blindness and greyscale.
struct PieceArtwork: View {
    let kind: PieceKind
    let seat: PlayerSeat
    /// Off for the small tallies beside each score, where a tray at 18pt turns the
    /// fruit into a smudge and ownership is already stated by the panel it sits in.
    var showsTray = true
    /// The wildcard rules' Golden Pineapple: worth three times base delivered and twice
    /// base to whoever blends it, so it has to be unmistakable on the board.
    var isGolden = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isBreathing = false

    var body: some View {
        GeometryReader { proxy in
            let side = min(proxy.size.width, proxy.size.height)
            ZStack {
                if isGolden { goldenHalo(side: side) }
                if showsTray { tray(side: side) }
                if let sprite = ArtCatalog.sprite(for: kind, seat: seat) {
                    sprite
                        .resizable()
                        .scaledToFit()
                        .padding(side * (showsTray ? 0.10 : 0))
                } else {
                    vectorArtwork
                        .padding(side * (showsTray ? 0.13 : 0.02))
                }
            }
            .frame(width: side, height: side)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    // MARK: - Ownership tray

    private func tray(side: CGFloat) -> some View {
        ZStack {
            Circle()
                .fill(
                    RadialGradient(
                        colors: [Palette.tray(for: seat), Palette.trayShade(for: seat)],
                        center: .topLeading,
                        startRadius: 0,
                        endRadius: side * 0.9
                    )
                )
            Circle()
                .strokeBorder(Palette.ink.opacity(0.75), lineWidth: side * 0.030)
            // Player two's tray gets a second, inset ring, drawn light so it reads
            // against the dark stone the way the dark ring reads against the board.
            // Ownership never rests on colour alone.
            if seat == .top {
                Circle()
                    .strokeBorder(Palette.cream.opacity(0.5), lineWidth: side * 0.018)
                    .padding(side * 0.075)
            }
        }
        .paperGrain(opacity: 0.3)
        .clipShape(Circle())
    }

    /// Lantern glow rather than a ring: the selection ring is already amber, and two
    /// amber rings on one square would be a puzzle rather than a signal. It breathes
    /// slowly, the way the lantern does in the art bible — enough to catch the eye
    /// across the board without ever being the loudest thing on screen.
    private func goldenHalo(side: CGFloat) -> some View {
        ZStack {
            Circle()
                .fill(Palette.amber.opacity(0.75))
                .blur(radius: side * 0.16)
                .scaleEffect(1.26)
            Circle()
                .fill(Palette.amber.opacity(0.4))
                .blur(radius: side * 0.05)
                .scaleEffect(1.08)
        }
        .scaleEffect(isBreathing ? 1.06 : 0.97)
        .opacity(isBreathing ? 1 : 0.72)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 1.8).repeatForever(autoreverses: true)) {
                isBreathing = true
            }
        }
    }

    // MARK: - Vector fallback

    @ViewBuilder
    private var vectorArtwork: some View {
        switch kind {
        case .banana: bananaArt
        case .strawberry: strawberryArt
        case .pineapple: pineappleArt
        case .blender: blenderArt
        }
    }

    private var bananaArt: some View {
        WatercolorWash(shape: BananaShape(), tint: Palette.bananaWash)
            .overlay {
                // The bruised inner curve, where the wash pooled.
                BananaShape()
                    .fill(Palette.sand.opacity(0.35))
                    .scaleEffect(0.86, anchor: .bottomLeading)
                    .blur(radius: 2)
                    .mask(BananaShape())
            }
    }

    private var strawberryArt: some View {
        ZStack {
            WatercolorWash(shape: StrawberryShape(), tint: Palette.strawberryWash)
            seeds
            WatercolorWash(shape: StrawberryCalyxShape(), tint: Palette.sageLight, lineWidth: 1.1)
        }
    }

    private var seeds: some View {
        GeometryReader { proxy in
            let width = proxy.size.width
            let height = proxy.size.height
            Path { path in
                let spots: [(CGFloat, CGFloat)] = [
                    (0.36, 0.46), (0.58, 0.44), (0.47, 0.58),
                    (0.30, 0.62), (0.66, 0.60), (0.50, 0.75),
                ]
                for spot in spots {
                    let rect = CGRect(
                        x: spot.0 * width - width * 0.022,
                        y: spot.1 * height - height * 0.032,
                        width: width * 0.044,
                        height: height * 0.064
                    )
                    path.addEllipse(in: rect)
                }
            }
            .fill(Palette.cream.opacity(0.85))
        }
        .mask(StrawberryShape())
    }

    private var pineappleArt: some View {
        ZStack {
            WatercolorWash(shape: PineappleShape(), tint: isGolden ? Palette.amber : Palette.pineappleWash)
            crosshatch.mask(PineappleShape())
            WatercolorWash(shape: PineappleCrownShape(), tint: Palette.sage, lineWidth: 1.1)
        }
    }

    private var crosshatch: some View {
        GeometryReader { proxy in
            let size = proxy.size
            Path { path in
                let step = size.width * 0.16
                var offset = -size.width
                while offset < size.width * 2 {
                    path.move(to: CGPoint(x: offset, y: 0))
                    path.addLine(to: CGPoint(x: offset + size.height, y: size.height))
                    path.move(to: CGPoint(x: offset, y: size.height))
                    path.addLine(to: CGPoint(x: offset + size.height, y: 0))
                    offset += step
                }
            }
            .stroke(Palette.ink.opacity(0.28), lineWidth: 0.8)
        }
    }

    private var blenderArt: some View {
        ZStack {
            WatercolorWash(shape: BlenderBaseShape(), tint: seat == .bottom ? Palette.sand : Palette.slate)
            WatercolorWash(shape: BlenderJarShape(), tint: Palette.blenderGlass.opacity(0.9))
            blade
            WatercolorWash(shape: BlenderLidShape(), tint: seat == .bottom ? Palette.driftwood : Palette.fogWater, lineWidth: 1.2)
        }
    }

    private var blade: some View {
        GeometryReader { proxy in
            let size = proxy.size
            Path { path in
                path.move(to: CGPoint(x: size.width * 0.38, y: size.height * 0.60))
                path.addLine(to: CGPoint(x: size.width * 0.62, y: size.height * 0.68))
                path.move(to: CGPoint(x: size.width * 0.62, y: size.height * 0.60))
                path.addLine(to: CGPoint(x: size.width * 0.38, y: size.height * 0.68))
            }
            .stroke(Palette.ink.opacity(0.6), style: StrokeStyle(lineWidth: 1.6, lineCap: .round))
        }
    }
}

#Preview("Pieces") {
    ZStack {
        PaperBackground()
        VStack(spacing: 24) {
            ForEach([PlayerSeat.bottom, .top], id: \.self) { seat in
                HStack(spacing: 16) {
                    ForEach(PieceKind.allCases, id: \.self) { kind in
                        PieceArtwork(kind: kind, seat: seat)
                            .frame(width: 64, height: 64)
                    }
                }
            }
        }
    }
}
