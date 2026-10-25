import DetermineEngine
import Foundation

/// Minimal assertion harness: every `test` is one named check; any failed assertion or thrown error fails it.
final class Harness {
    private(set) var testsPassed = 0
    private(set) var testsFailed = 0
    private(set) var assertions = 0
    private var failures: [String] = []

    func test(_ name: String, _ body: () throws -> Void) {
        failures = []
        let before = assertions
        do { try body() } catch { failures.append("threw \(error)") }
        let n = assertions - before
        if failures.isEmpty {
            testsPassed += 1
            print("ok    \(name) (\(n) assertions)")
        } else {
            testsFailed += 1
            print("FAIL  \(name)")
            for f in failures { print("        \(f)") }
        }
    }

    private func fail(_ msg: String, _ file: StaticString, _ line: UInt) {
        failures.append("\(file):\(line): \(msg)")
    }

    func eq<T: Equatable>(_ expected: T, _ actual: T, _ msg: @autoclosure () -> String = "", file: StaticString = #fileID, line: UInt = #line) {
        assertions += 1
        if expected != actual { fail("expected \(expected) but was \(actual) \(msg())", file, line) }
    }

    func ne<T: Equatable>(_ unexpected: T, _ actual: T, _ msg: @autoclosure () -> String = "", file: StaticString = #fileID, line: UInt = #line) {
        assertions += 1
        if unexpected == actual { fail("unexpected \(actual) \(msg())", file, line) }
    }

    func yes(_ cond: Bool, _ msg: @autoclosure () -> String = "", file: StaticString = #fileID, line: UInt = #line) {
        assertions += 1
        if !cond { fail("expected true \(msg())", file, line) }
    }

    func no(_ cond: Bool, _ msg: @autoclosure () -> String = "", file: StaticString = #fileID, line: UInt = #line) {
        assertions += 1
        if cond { fail("expected false \(msg())", file, line) }
    }

    func isNil<T>(_ v: T?, _ msg: @autoclosure () -> String = "", file: StaticString = #fileID, line: UInt = #line) {
        assertions += 1
        if let v = v { fail("expected nil but was \(v) \(msg())", file, line) }
    }

    func notNil<T>(_ v: T?, _ msg: @autoclosure () -> String = "", file: StaticString = #fileID, line: UInt = #line) {
        assertions += 1
        if v == nil { fail("expected non-nil \(msg())", file, line) }
    }

    func throwsError(_ msg: @autoclosure () -> String = "", file: StaticString = #fileID, line: UInt = #line, _ body: () throws -> Void) {
        assertions += 1
        do { try body(); fail("expected an error \(msg())", file, line) } catch {}
    }
}

// MARK: helpers

func hexToBytes(_ s: String) -> [UInt8] {
    let chars = Array(s.utf8)
    var out: [UInt8] = []
    out.reserveCapacity(chars.count / 2)
    func nib(_ c: UInt8) -> UInt8 { c <= 57 ? c - 48 : (c | 0x20) - 97 + 10 }
    var i = 0
    while i + 1 < chars.count { out.append(nib(chars[i]) << 4 | nib(chars[i + 1])); i += 2 }
    return out
}

func toHex(_ b: [UInt8]) -> String {
    let digits = Array("0123456789abcdef")
    var s = ""
    s.reserveCapacity(b.count * 2)
    for x in b { s.append(digits[Int(x >> 4)]); s.append(digits[Int(x & 15)]) }
    return s
}

/// Kotlin `ByteArray.copyOf(n)`: truncate, or zero-pad.
func copyOf(_ a: [UInt8], _ n: Int) -> [UInt8] {
    n <= a.count ? Array(a.prefix(n)) : a + [UInt8](repeating: 0, count: n - a.count)
}

/// JSON subset used by the golden vectors: objects, arrays, strings, integers, booleans, null.
indirect enum Json {
    case object([String: Json])
    case array([Json])
    case string(String)
    case int(Int64)
    case bool(Bool)
    case null

    struct ParseError: Error { let message: String }

    static func parse(_ text: String) throws -> Json {
        var p = Parser(Array(text.utf8))
        let v = try p.value()
        p.ws()
        guard p.i == p.s.count else { throw ParseError(message: "trailing data at \(p.i)") }
        return v
    }

    private struct Parser {
        let s: [UInt8]
        var i = 0
        init(_ s: [UInt8]) { self.s = s }

        mutating func ws() { while i < s.count, s[i] == 32 || s[i] == 10 || s[i] == 13 || s[i] == 9 { i += 1 } }

        mutating func value() throws -> Json {
            ws()
            guard i < s.count else { throw ParseError(message: "unexpected end") }
            let c = s[i]
            switch c {
            case UInt8(ascii: "{"):
                i += 1; ws()
                var m: [String: Json] = [:]
                if s[i] == UInt8(ascii: "}") { i += 1; return .object(m) }
                while true {
                    ws(); let k = try str(); ws()
                    guard s[i] == UInt8(ascii: ":") else { throw ParseError(message: "expected : at \(i)") }
                    i += 1
                    m[k] = try value(); ws()
                    let d = s[i]; i += 1
                    if d == UInt8(ascii: "}") { break }
                    guard d == UInt8(ascii: ",") else { throw ParseError(message: "expected , at \(i)") }
                }
                return .object(m)
            case UInt8(ascii: "["):
                i += 1; ws()
                var l: [Json] = []
                if s[i] == UInt8(ascii: "]") { i += 1; return .array(l) }
                while true {
                    l.append(try value()); ws()
                    let d = s[i]; i += 1
                    if d == UInt8(ascii: "]") { break }
                    guard d == UInt8(ascii: ",") else { throw ParseError(message: "expected , at \(i)") }
                }
                return .array(l)
            case UInt8(ascii: "\""): return .string(try str())
            case UInt8(ascii: "t"): i += 4; return .bool(true)
            case UInt8(ascii: "f"): i += 5; return .bool(false)
            case UInt8(ascii: "n"): i += 4; return .null
            default:
                let st = i
                if s[i] == UInt8(ascii: "-") { i += 1 }
                while i < s.count, s[i] >= 48, s[i] <= 57 { i += 1 }
                let text = String(decoding: s[st..<i], as: UTF8.self)
                if let v = Int64(text) { return .int(v) }
                if let u = UInt64(text) { return .int(Int64(bitPattern: u)) }
                throw ParseError(message: "bad number '\(text)' at \(st)")
            }
        }

        mutating func str() throws -> String {
            guard s[i] == UInt8(ascii: "\"") else { throw ParseError(message: "expected string at \(i)") }
            i += 1
            var b: [UInt8] = []
            while s[i] != UInt8(ascii: "\"") {
                if s[i] == UInt8(ascii: "\\") {
                    i += 1
                    b.append(s[i] == UInt8(ascii: "n") ? 10 : s[i])
                } else {
                    b.append(s[i])
                }
                i += 1
            }
            i += 1
            return String(decoding: b, as: UTF8.self)
        }
    }

    subscript(key: String) -> Json? {
        if case .object(let m) = self { return m[key] }
        return nil
    }

    var stringValue: String? { if case .string(let v) = self { return v }; return nil }
    var intValue: Int64? { if case .int(let v) = self { return v }; return nil }
    var arrayValue: [Json]? { if case .array(let v) = self { return v }; return nil }
}
