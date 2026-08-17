import CoreGraphics
import SwiftUI
import UIKit

/// Warm-paper grain, generated once and tiled. The bible asks for visible paper grain
/// on everything; a 128pt tile multiplied at low opacity gives it without shipping a
/// texture or costing a redraw.
enum PaperGrain {
    static let tile: Image = {
        let side = 128
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side))
        let image = renderer.image { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: side, height: side))

            // Deterministic speckle: the same paper every launch, so screenshots and
            // snapshot comparisons stay stable.
            var seed: UInt64 = 0x5EED_1234_ABCD_0001
            func next() -> Double {
                seed = seed &* 6364136223846793005 &+ 1442695040888963407
                return Double((seed >> 33) & 0xFFFF) / 65535
            }

            for _ in 0..<3200 {
                let x = next() * Double(side)
                let y = next() * Double(side)
                let size = 0.5 + next() * 1.4
                let alpha = 0.02 + next() * 0.06
                UIColor(white: 0.35, alpha: alpha).setFill()
                context.fill(CGRect(x: x, y: y, width: size, height: size))
            }
        }
        return Image(uiImage: image.resizableImage(withCapInsets: .zero, resizingMode: .tile))
    }()
}

struct PaperGrainOverlay: ViewModifier {
    var opacity: Double = 0.5

    func body(content: Content) -> some View {
        content.overlay {
            PaperGrain.tile
                .resizable(resizingMode: .tile)
                .opacity(opacity)
                .blendMode(.multiply)
                .allowsHitTesting(false)
        }
    }
}

extension View {
    func paperGrain(opacity: Double = 0.5) -> some View {
        modifier(PaperGrainOverlay(opacity: opacity))
    }
}

/// The washi panel the whole UI is built from: warm paper, a soft pencil border, no
/// hard shadow anywhere.
struct WashiPanel<Content: View>: View {
    var cornerRadius: CGFloat = 18
    var fill: Color = Palette.cream
    var isAccented = false
    @ViewBuilder var content: Content

    var body: some View {
        content
            .background {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(fill)
                    .paperGrain(opacity: 0.35)
            }
            .overlay {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(
                        isAccented ? Palette.amber : Palette.inkSoft,
                        lineWidth: isAccented ? 2.5 : 1.4
                    )
            }
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }
}

/// The full-screen ground: warm paper with a soft wash toward the edges, standing in
/// for the printed border in the shin-hanga half of the bible.
struct PaperBackground: View {
    var body: some View {
        ZStack {
            Palette.warmPaper
            RadialGradient(
                colors: [Palette.cream.opacity(0.9), .clear],
                center: .center,
                startRadius: 40,
                endRadius: 460
            )
            LinearGradient(
                colors: [.clear, Palette.sand.opacity(0.28)],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .paperGrain(opacity: 0.6)
        .ignoresSafeArea()
    }
}

/// A watercolour wash: a soft gradient body, a darker pool bleeding to one edge, and a
/// pencil outline that never quite closes cleanly. Used by every piece.
struct WatercolorWash<S: Shape>: View {
    let shape: S
    var tint: Color
    var outline: Color = Palette.ink
    var lineWidth: CGFloat = 1.6

    var body: some View {
        ZStack {
            shape
                .fill(
                    LinearGradient(
                        colors: [tint.opacity(0.72), tint, tint.opacity(0.92)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
            // The pooled edge, where the pigment settled.
            shape
                .fill(tint.opacity(0.55))
                .blur(radius: 2.2)
                .offset(x: 1.2, y: 2.0)
                .mask(shape)
            shape
                .stroke(outline.opacity(0.85), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
        }
    }
}
