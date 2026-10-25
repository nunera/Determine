import Foundation

enum FileManagerShim {
    static func read(_ path: String) -> String? {
        guard let d = FileManager.default.contents(atPath: path) else { return nil }
        return String(decoding: d, as: UTF8.self)
    }
}
