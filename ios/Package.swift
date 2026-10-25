// swift-tools-version: 6.0
import Foundation
import PackageDescription

// Siri and Shortcuts read an index of the app's App Intents that Xcode normally writes after compiling; xtool on
// Linux does not. `scripts/add-siri-metadata.sh` sets DETERMINE_APPINTENTS_PROTOCOLS to the protocol list so the
// compiler also emits `.swiftconstvalues` for the app target, then generates the index from them. Unset, the
// build is exactly the normal one.
let constValuesSettings: [SwiftSetting] = ProcessInfo.processInfo.environment["DETERMINE_APPINTENTS_PROTOCOLS"].map {
    [.unsafeFlags(["-Xfrontend", "-const-gather-protocols-file", "-Xfrontend", $0, "-emit-const-values"])]
} ?? []

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
        .target(name: "DetermineApp", dependencies: ["DetermineEngine", "DetermineLink"], swiftSettings: constValuesSettings),
        .executableTarget(name: "DeterminePhone", dependencies: ["DetermineEngine", "DetermineLink"]),
        .executableTarget(name: "DetermineEngineChecks", dependencies: ["DetermineEngine"]),
    ]
)
