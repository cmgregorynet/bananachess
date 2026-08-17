import Foundation

/// How often a blend throws pulp past the board. Injected rather than reached for, so a
/// preview or a UI test can force the splatter on or off instead of rolling dice.
struct BlendEffectPolicy: Sendable {
    var splatterChance: Double
    var roll: @Sendable () -> Double

    init(splatterChance: Double = 0.3, roll: @escaping @Sendable () -> Double = { Double.random(in: 0..<1) }) {
        self.splatterChance = splatterChance
        self.roll = roll
    }

    /// Roughly one blend in three.
    static let standard = BlendEffectPolicy()
    static let always = BlendEffectPolicy(splatterChance: 1, roll: { 0 })
    static let never = BlendEffectPolicy(splatterChance: 0, roll: { 1 })
}
