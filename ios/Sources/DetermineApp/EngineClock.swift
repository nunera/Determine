import Foundation

/// The engine's phone clock: monotonic µs that keep counting while the device sleeps (PROTOCOL.md "Clocks").
/// `ContinuousClock` is `mach_continuous_time` on iOS, unlike `mach_absolute_time`, which stops in sleep.
/// Origin is the first read in this process; only differences matter to the engine.
enum EngineClock {
    private static let origin = ContinuousClock.now

    static func nowUs() -> Int64 {
        let d = ContinuousClock.now - origin
        let c = d.components
        return c.seconds * 1_000_000 + c.attoseconds / 1_000_000_000_000
    }
}
