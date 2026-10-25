// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Determine",
    platforms: [.iOS(.v17)],
    products: [
        // xtool bundles the single library product as the native app.
        .library(name: "DetermineApp", targets: ["DetermineApp"]),
        .executable(name: "DetermineSmoke", targets: ["DetermineSmoke"]),
    ],
    targets: [
        .target(name: "DetermineCore"),
        .target(name: "DetermineApp", dependencies: ["DetermineCore"]),
        .executableTarget(name: "DetermineSmoke", dependencies: ["DetermineCore"]),
    ]
)
