import SwiftUI

/// Hand-authored bezier silhouettes in a unit square. Deliberately a little off-round —
/// the bible asks for brush and pencil imperfection, not perfect geometry.
private extension Path {
    mutating func move(_ x: CGFloat, _ y: CGFloat, in rect: CGRect) {
        move(to: CGPoint(x: rect.minX + x * rect.width, y: rect.minY + y * rect.height))
    }

    mutating func curve(
        _ x: CGFloat, _ y: CGFloat,
        _ c1x: CGFloat, _ c1y: CGFloat,
        _ c2x: CGFloat, _ c2y: CGFloat,
        in rect: CGRect
    ) {
        addCurve(
            to: CGPoint(x: rect.minX + x * rect.width, y: rect.minY + y * rect.height),
            control1: CGPoint(x: rect.minX + c1x * rect.width, y: rect.minY + c1y * rect.height),
            control2: CGPoint(x: rect.minX + c2x * rect.width, y: rect.minY + c2y * rect.height)
        )
    }
}

/// A crescent, not an oval: tips low-left and high-right with the belly sagging away
/// from the curve. Drawn as two arcs rather than a closed blob, or it reads as a lemon.
struct BananaShape: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(0.11, 0.70, in: rect)
        // Outer edge, sweeping up and over to the far tip.
        path.curve(0.87, 0.26, 0.19, 0.24, 0.57, 0.13, in: rect)
        // The little stem.
        path.curve(0.92, 0.20, 0.90, 0.23, 0.92, 0.21, in: rect)
        path.curve(0.86, 0.34, 0.90, 0.28, 0.88, 0.32, in: rect)
        // Inner edge, bowing the same way as the outer one but less — mirror it and the
        // shape closes into a leaf instead of a crescent.
        path.curve(0.11, 0.70, 0.62, 0.34, 0.30, 0.55, in: rect)
        path.closeSubpath()
        return path
    }
}

struct StrawberryShape: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(0.50, 0.93, in: rect)
        path.curve(0.12, 0.42, 0.25, 0.82, 0.10, 0.64, in: rect)
        path.curve(0.50, 0.24, 0.14, 0.24, 0.29, 0.21, in: rect)
        path.curve(0.88, 0.42, 0.71, 0.21, 0.86, 0.24, in: rect)
        path.curve(0.50, 0.93, 0.90, 0.64, 0.75, 0.82, in: rect)
        path.closeSubpath()
        return path
    }
}

struct PineappleShape: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(0.50, 0.95, in: rect)
        path.curve(0.17, 0.60, 0.27, 0.93, 0.15, 0.80, in: rect)
        path.curve(0.50, 0.34, 0.19, 0.42, 0.31, 0.34, in: rect)
        path.curve(0.83, 0.60, 0.69, 0.34, 0.81, 0.42, in: rect)
        path.curve(0.50, 0.95, 0.85, 0.80, 0.73, 0.93, in: rect)
        path.closeSubpath()
        return path
    }
}

/// Three leaves, the middle one tallest, all leaning slightly the same way.
struct PineappleCrownShape: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()

        path.move(0.44, 0.36, in: rect)
        path.curve(0.28, 0.10, 0.38, 0.26, 0.30, 0.17, in: rect)
        path.curve(0.46, 0.32, 0.36, 0.22, 0.44, 0.26, in: rect)
        path.closeSubpath()

        path.move(0.48, 0.36, in: rect)
        path.curve(0.53, 0.03, 0.47, 0.22, 0.50, 0.10, in: rect)
        path.curve(0.58, 0.33, 0.57, 0.12, 0.59, 0.22, in: rect)
        path.closeSubpath()

        path.move(0.58, 0.35, in: rect)
        path.curve(0.74, 0.12, 0.62, 0.27, 0.70, 0.18, in: rect)
        path.curve(0.60, 0.37, 0.70, 0.24, 0.64, 0.30, in: rect)
        path.closeSubpath()

        return path
    }
}

struct StrawberryCalyxShape: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(0.50, 0.30, in: rect)
        path.curve(0.20, 0.16, 0.38, 0.24, 0.27, 0.16, in: rect)
        path.curve(0.50, 0.30, 0.32, 0.24, 0.42, 0.29, in: rect)
        path.closeSubpath()

        path.move(0.50, 0.30, in: rect)
        path.curve(0.80, 0.16, 0.62, 0.24, 0.73, 0.16, in: rect)
        path.curve(0.50, 0.30, 0.68, 0.24, 0.58, 0.29, in: rect)
        path.closeSubpath()

        path.move(0.50, 0.30, in: rect)
        path.curve(0.52, 0.06, 0.46, 0.20, 0.48, 0.10, in: rect)
        path.curve(0.50, 0.30, 0.58, 0.12, 0.56, 0.22, in: rect)
        path.closeSubpath()

        return path
    }
}

struct BlenderJarShape: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(0.22, 0.26, in: rect)
        path.curve(0.31, 0.76, 0.23, 0.50, 0.29, 0.68, in: rect)
        path.curve(0.69, 0.76, 0.36, 0.80, 0.64, 0.80, in: rect)
        path.curve(0.78, 0.26, 0.71, 0.68, 0.77, 0.50, in: rect)
        // The pouring lip, leaning left the way a hand-drawn one would.
        path.curve(0.22, 0.26, 0.60, 0.22, 0.38, 0.22, in: rect)
        path.closeSubpath()
        return path
    }
}

struct BlenderBaseShape: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(0.28, 0.76, in: rect)
        path.curve(0.72, 0.76, 0.42, 0.74, 0.58, 0.74, in: rect)
        path.curve(0.76, 0.92, 0.75, 0.82, 0.77, 0.88, in: rect)
        path.curve(0.24, 0.92, 0.62, 0.96, 0.38, 0.96, in: rect)
        path.curve(0.28, 0.76, 0.23, 0.88, 0.25, 0.82, in: rect)
        path.closeSubpath()
        return path
    }
}

struct BlenderLidShape: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(0.19, 0.24, in: rect)
        path.curve(0.81, 0.24, 0.36, 0.19, 0.64, 0.19, in: rect)
        path.curve(0.19, 0.24, 0.66, 0.30, 0.34, 0.30, in: rect)
        path.closeSubpath()
        return path
    }
}
