import DetermineEngine
import DetermineLink
import Foundation

/// Headless phone options; identical to the Kotlin cli's `CliOptions` (phone/cli Main.kt).
struct CliOptions {
    var port: UInt16 = 47000
    var bind: String = "0.0.0.0"
    var cam: Endpoint?
    var cue: Endpoint?
    var durationS: Double?
    var ceilingDb: Int = -20
    var mode: HearingMode = .stereo
    var spearcons: Bool = true
}

struct UsageError: Error {
    let message: String
}

let usage = """
determine-phone [options]
  --port N            UDP port to bind (default 47000)
  --bind ADDR         bind address (default 0.0.0.0)
  --cam HOST:PORT     camera board address (default: learn from its first BEAT)
  --cue HOST:PORT     cue board address (default: learn from its first BEAT)
  --duration S        exit after S seconds (default: run until killed)
  --ceiling DB        local audio ceiling in dBFS (default -20; never above the -10 hard cap)
  --mode M            STEREO | SSD | HAPTIC_ONLY (default STEREO)
  --no-spearcons      disable spoken words after cues
  --help
"""

func parseArgs(_ args: [String]) throws -> CliOptions {
    var o = CliOptions()
    var i = 0
    func value() throws -> String {
        i += 1
        guard i < args.count else { throw UsageError(message: "missing value for \(args[i - 1])") }
        return args[i]
    }
    func int(_ s: String) throws -> Int {
        guard let v = Int(s) else { throw UsageError(message: "For input string: \"\(s)\"") }
        return v
    }
    func addr(_ s: String) throws -> Endpoint {
        guard let idx = s.lastIndex(of: ":"), idx != s.startIndex else {
            throw UsageError(message: "expected HOST:PORT, got \(s)")
        }
        guard let port = UInt16(s[s.index(after: idx)...]) else {
            throw UsageError(message: "bad port in \(s)")
        }
        guard let ep = Endpoint.resolve(host: String(s[..<idx]), port: port) else {
            throw UsageError(message: "cannot resolve \(s[..<idx])")
        }
        return ep
    }
    while i < args.count {
        let a = args[i]
        switch a {
        case "--port":
            let p = try int(try value())
            guard (0...65535).contains(p) else { throw UsageError(message: "port out of range: \(p)") }
            o.port = UInt16(p)
        case "--bind": o.bind = try value()
        case "--cam": o.cam = try addr(try value())
        case "--cue": o.cue = try addr(try value())
        case "--duration":
            let s = try value()
            guard let d = Double(s) else { throw UsageError(message: "For input string: \"\(s)\"") }
            o.durationS = d
        case "--ceiling": o.ceilingDb = try int(try value())
        case "--mode":
            let s = try value()
            switch s.uppercased() {
            case "STEREO": o.mode = .stereo
            case "SSD": o.mode = .ssd
            case "HAPTIC_ONLY": o.mode = .hapticOnly
            default: throw UsageError(message: "No enum constant HearingMode.\(s.uppercased())")
            }
        case "--no-spearcons": o.spearcons = false
        default: throw UsageError(message: "unknown option \(a)")
        }
        i += 1
    }
    guard o.ceilingDb <= 0 else { throw UsageError(message: "ceiling is dBFS and must be <= 0") }
    return o
}
