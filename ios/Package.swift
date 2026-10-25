// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Determine",
    platforms: [.iOS(.v17)],
    products: [
        // xtool bundles the single library product as the native app.
        .library(name: "DetermineApp", targets: ["DetermineApp"]),
        // Linux/macOS headless phone: the Swift engine + UDP link, checked by tools/sim/e2e.py.
        .executable(name: "DeterminePhone", targets: ["DeterminePhone"]),
        // Executable assertions: protocol vectors and ported Kotlin core tests.
        .executable(name: "DetermineEngineChecks", targets: ["DetermineEngineChecks"]),
    ],
    targets: [
        // Pure decision engine (port of phone/core): Foundation only, no I/O, no clock reads.
        .target(name: "DetermineEngine"),
        // BSD-socket UDP transport + board addressing, shared by the iPhone app and the Linux cli.
        .target(name: "DetermineLink", dependencies: ["DetermineEngine"]),
        .target(name: "DetermineApp", dependencies: ["DetermineEngine", "DetermineLink"]),
        .executableTarget(name: "DeterminePhone", dependencies: ["DetermineEngine", "DetermineLink"]),
        .executableTarget(name: "DetermineEngineChecks", dependencies: ["DetermineEngine"]),
    ]
)
