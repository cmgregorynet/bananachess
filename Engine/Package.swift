// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "BananaChessEngine",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [
        .library(name: "BananaChessEngine", targets: ["BananaChessEngine"])
    ],
    targets: [
        .target(
            name: "BananaChessEngine",
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "BananaChessEngineTests",
            dependencies: ["BananaChessEngine"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
    ]
)
