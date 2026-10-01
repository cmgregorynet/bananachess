// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "BananaChessEngine",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [
        .library(name: "BananaChessEngine", targets: ["BananaChessEngine"]),
        .executable(name: "ConformanceFixtures", targets: ["ConformanceFixtures"]),
    ],
    targets: [
        .target(
            name: "BananaChessEngine",
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        // Writes the fixtures the web port is tested against. The Swift engine is the
        // specification; anything claiming to be Banana Chess has to reproduce this.
        .executableTarget(
            name: "ConformanceFixtures",
            dependencies: ["BananaChessEngine"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "BananaChessEngineTests",
            dependencies: ["BananaChessEngine"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
    ]
)
