#if canImport(SwiftUI)
import Foundation

enum TranscriptKind: String {
    case description, reading
}

/// Session-memory only: camera answers and OCR text are not written to disk.
struct TranscriptEntry: Identifiable {
    let id: UUID
    let date: Date
    let kind: TranscriptKind
    let question: String?
    var text: String
    var interrupted: Bool
}
#endif
