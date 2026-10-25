#if canImport(Vision)
import Foundation
import Vision

/// Read text (display-only helper, never an input to hazard logic): on-device OCR of one camera JPEG with Vision.
/// The result is spoken as read by the caller.
enum TextRecognition {
    /// Recognised lines top to bottom joined by spaces; nil when nothing was found. Blocking: run off the engine
    /// queue and the main thread. Throws only for an undecodable image or a Vision failure.
    static func recognize(jpeg: [UInt8]) throws -> String? {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        request.automaticallyDetectsLanguage = true
        // Both frame sources deliver upright JPEGs (the iPhone camera is rotated to portrait before encoding).
        let handler = VNImageRequestHandler(data: Data(jpeg), orientation: .up, options: [:])
        try handler.perform([request])
        let lines = (request.results ?? [])
            .sorted { $0.boundingBox.midY > $1.boundingBox.midY }
            .compactMap { $0.topCandidates(1).first?.string.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        return lines.isEmpty ? nil : lines.joined(separator: " ")
    }
}
#endif
