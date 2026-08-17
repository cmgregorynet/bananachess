import BananaChessEngine
import SwiftUI

// MARK: - Droplets

/// One thrown blob of pulp. Laid out once from a seed, so a burst keeps the same shape
/// for the whole of its own animation instead of reshuffling itself every frame.
struct Droplet: Identifiable, Equatable {
    let id: Int
    /// Radians, measured the usual screen way — y grows downward.
    let angle: Double
    /// How far it travels, as a fraction of the view it is thrown across.
    let distance: Double
    /// Blob diameter, same units as `distance`.
    let size: Double
    let spin: Double
    let shapeSeed: UInt64
}

extension Droplet {
    /// The tight spray at the square where a fruit was blended.
    static func burst(seed: UInt64, count: Int = 11) -> [Droplet] {
        var generator = SeededGenerator(seed: seed)
        return (0..<count).map { index in
            // Spread evenly around the circle, then jitter, so no burst ends up with a
            // bald patch on one side.
            let base = Double(index) / Double(count) * 2 * .pi
            return Droplet(
                id: index,
                angle: base + .random(in: -0.30...0.30, using: &generator),
                distance: .random(in: 0.55...1.15, using: &generator),
                size: .random(in: 0.13...0.30, using: &generator),
                spin: .random(in: -1.2...1.2, using: &generator),
                shapeSeed: generator.next()
            )
        }
    }

    /// The wider spray that occasionally reaches the screen.
    static func splatter(seed: UInt64, count: Int = 34) -> [Droplet] {
        var generator = SeededGenerator(seed: seed)
        return (0..<count).map { index in
            let base = Double(index) / Double(count) * 2 * .pi
            return Droplet(
                id: index,
                angle: base + .random(in: -0.45...0.45, using: &generator),
                // Squared so most of the pulp stays near the impact and a few blobs
                // carry all the way out — even spacing reads as a printed pattern.
                distance: pow(.random(in: 0.05...1.0, using: &generator), 1.7) * 0.95,
                size: .random(in: 0.015...0.085, using: &generator),
                spin: .random(in: -2...2, using: &generator),
                shapeSeed: generator.next()
            )
        }
    }
}

/// A closed blob with a wobbling edge — a splash of watercolour rather than a circle.
struct DropletShape: Shape {
    let seed: UInt64
    var lobes = 9

    func path(in rect: CGRect) -> Path {
        var generator = SeededGenerator(seed: seed)
        let centre = CGPoint(x: rect.midX, y: rect.midY)
        let radius = min(rect.width, rect.height) / 2

        let points: [CGPoint] = (0..<lobes).map { index in
            let angle = Double(index) / Double(lobes) * 2 * .pi
            let wobble = Double.random(in: 0.58...1.0, using: &generator)
            return CGPoint(
                x: centre.x + cos(angle) * radius * wobble,
                y: centre.y + sin(angle) * radius * wobble
            )
        }

        // Curve through the midpoints using each vertex as its control point: the edge
        // stays smooth but never lands on a true arc.
        var path = Path()
        guard let first = points.first, points.count > 2 else { return path }
        path.move(to: midpoint(points[points.count - 1], first))
        for index in points.indices {
            let current = points[index]
            let next = points[(index + 1) % points.count]
            path.addQuadCurve(to: midpoint(current, next), control: current)
        }
        path.closeSubpath()
        return path
    }

    private func midpoint(_ a: CGPoint, _ b: CGPoint) -> CGPoint {
        CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
    }
}

// MARK: - The burst at the square

/// What a fruit does when a blender gets it: a ring pushing outward and eleven blobs
/// thrown clear of the square.
///
/// Driven by `KeyframeAnimator` rather than `onAppear` plus `withAnimation`. State set
/// in `onAppear` gets folded into the same transaction that inserted the view, so the
/// burst arrives already finished — which is to say invisible.
struct BlendBurstView: View {
    let kind: PieceKind
    let seed: UInt64
    let reduceMotion: Bool

    private let droplets: [Droplet]

    init(kind: PieceKind, seed: UInt64, reduceMotion: Bool) {
        self.kind = kind
        self.seed = seed
        self.reduceMotion = reduceMotion
        droplets = Droplet.burst(seed: seed)
    }

    struct Values {
        var spread = 0.0
        var pulp = 0.0
        var ring = 0.0
    }

    private var tint: Color { Palette.wash(for: kind) }

    var body: some View {
        GeometryReader { proxy in
            let side = min(proxy.size.width, proxy.size.height)
            KeyframeAnimator(initialValue: Values()) { value in
                ZStack {
                    Circle()
                        .stroke(tint.opacity(0.5), lineWidth: side * 0.09)
                        .scaleEffect(0.2 + value.ring * 1.35)
                        .opacity((1 - value.ring) * 0.85)

                    Circle()
                        .fill(tint.opacity(0.4))
                        .blur(radius: side * 0.09)
                        .scaleEffect(0.3 + value.ring * 0.8)
                        .opacity(1 - value.ring)

                    ForEach(droplets) { droplet in
                        DropletShape(seed: droplet.shapeSeed)
                            .fill(tint)
                            .frame(width: side * droplet.size, height: side * droplet.size)
                            .rotationEffect(.radians(droplet.spin * value.spread))
                            .offset(
                                x: cos(droplet.angle) * side * droplet.distance * value.spread,
                                y: sin(droplet.angle) * side * droplet.distance * value.spread
                            )
                            .scaleEffect(1 - 0.5 * value.spread)
                            .opacity(value.pulp)
                    }
                }
                .frame(width: side, height: side)
            } keyframes: { _ in
                KeyframeTrack(\.spread) {
                    // Reduce Motion keeps the pulp where the fruit was and simply fades
                    // it: the guideline is about travel, and a burst loses little by
                    // staying put.
                    if reduceMotion {
                        LinearKeyframe(0.0, duration: 0.40)
                    } else {
                        SpringKeyframe(1.0, duration: 0.55, spring: .bouncy)
                    }
                }
                KeyframeTrack(\.pulp) {
                    LinearKeyframe(1.0, duration: 0.06)
                    LinearKeyframe(1.0, duration: 0.16)
                    LinearKeyframe(0.0, duration: 0.36)
                }
                KeyframeTrack(\.ring) {
                    CubicKeyframe(1.0, duration: 0.50)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

// MARK: - The screen splatter

/// The one-in-three case where the pulp clears the board entirely. Purely decorative:
/// nothing here reaches the rules engine or the online payload, so two devices watching
/// the same match may splatter differently and neither is wrong.
struct SplatterView: View {
    let kind: PieceKind
    let seed: UInt64
    let origin: UnitPoint
    let reduceMotion: Bool

    private let droplets: [Droplet]
    /// Captured once, because the burst that fixed this position is retired long before
    /// the splatter finishes drying.
    @State private var lockedOrigin: UnitPoint?

    init(kind: PieceKind, seed: UInt64, origin: UnitPoint, reduceMotion: Bool) {
        self.kind = kind
        self.seed = seed
        self.origin = origin
        self.reduceMotion = reduceMotion
        droplets = Droplet.splatter(seed: seed)
    }

    struct Values {
        var reach = 0.0
        var ink = 0.0
    }

    var body: some View {
        GeometryReader { proxy in
            let size = proxy.size
            let anchor = lockedOrigin ?? origin
            let tint = Palette.wash(for: kind)

            KeyframeAnimator(initialValue: Values()) { value in
                ZStack {
                    ForEach(droplets) { droplet in
                        DropletShape(seed: droplet.shapeSeed)
                            .fill(tint)
                            .frame(width: size.width * droplet.size, height: size.width * droplet.size)
                            .rotationEffect(.radians(droplet.spin))
                            .position(
                                x: anchor.x * size.width
                                    + cos(droplet.angle) * droplet.distance * size.width * value.reach,
                                y: anchor.y * size.height
                                    + sin(droplet.angle) * droplet.distance * size.width * value.reach
                            )
                    }
                }
                .opacity(value.ink)
                // Pigment on paper, not a sticker on glass.
                .blendMode(.multiply)
            } keyframes: { _ in
                KeyframeTrack(\.reach) {
                    if reduceMotion {
                        // No throw — the stain is simply already there.
                        MoveKeyframe(1.0)
                        LinearKeyframe(1.0, duration: 1.4)
                    } else {
                        SpringKeyframe(1.0, duration: 0.26, spring: .snappy)
                        LinearKeyframe(1.0, duration: 1.2)
                    }
                }
                KeyframeTrack(\.ink) {
                    LinearKeyframe(0.5, duration: reduceMotion ? 0.30 : 0.14)
                    LinearKeyframe(0.5, duration: 0.42)
                    LinearKeyframe(0.0, duration: 0.90)
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onAppear { lockedOrigin = origin }
    }
}

// MARK: - The piece leaving the board

/// A blended fruit does not simply vanish — it gets wrung out and spun off.
struct PulpModifier: ViewModifier {
    let progress: Double

    func body(content: Content) -> some View {
        content
            .scaleEffect(1 - 0.78 * progress)
            .rotationEffect(.degrees(progress * 150))
            .opacity(1 - progress)
    }
}

extension AnyTransition {
    static var pulped: AnyTransition {
        .asymmetric(
            insertion: .scale(scale: 0.4).combined(with: .opacity),
            removal: .modifier(active: PulpModifier(progress: 1), identity: PulpModifier(progress: 0))
        )
    }

    /// Reduce Motion: no spin, no throw — the piece just goes.
    static var pulpedCalmly: AnyTransition {
        .scale(scale: 0.7).combined(with: .opacity)
    }
}

#Preview("Blend effects") {
    ZStack {
        PaperBackground()
        VStack(spacing: 40) {
            HStack(spacing: 20) {
                ForEach([PieceKind.banana, .strawberry, .pineapple], id: \.self) { kind in
                    BlendBurstView(kind: kind, seed: UInt64(kind.hashValue.magnitude), reduceMotion: false)
                        .frame(width: 64, height: 64)
                }
            }
            Text("Splatter")
                .font(.system(.headline, design: .serif))
                .foregroundStyle(Palette.ink)
        }
        SplatterView(kind: .strawberry, seed: 42, origin: UnitPoint(x: 0.5, y: 0.4), reduceMotion: false)
    }
}
