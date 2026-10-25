import DetermineEngine
import Foundation

// Executable assertions for the Swift engine: every golden protocol vector plus the ported Kotlin core tests
// (WireTest, ClockIngestRangeTest, PolicyCueTest, EngineTest, FallbackTest). Exit status 0 only if all pass.
// Usage: swift run DetermineEngineChecks [path/to/vectors.json]   (default ../protocol/vectors/vectors.json from ios/)

let vectorsPath = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "../protocol/vectors/vectors.json"
let harness = Harness()

let vectors: [Vector]
do {
    vectors = try loadVectors(vectorsPath)
} catch {
    print("FAIL  cannot load vectors from \(vectorsPath): \(error)")
    exit(2)
}

wireChecks(harness, vectors: vectors)
clockIngestRangeChecks(harness)
policyCueChecks(harness)
engineChecks(harness)
tuningChecks(harness)
fallbackChecks(harness)
describeChecks(harness)
voiceChecks(harness)

print("")
print("vectors: \(vectors.count) loaded from \(vectorsPath)")
print("checks: \(harness.testsPassed) passed, \(harness.testsFailed) failed, \(harness.assertions) assertions")
exit(harness.testsFailed == 0 ? 0 : 1)
