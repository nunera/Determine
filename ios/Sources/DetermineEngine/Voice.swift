import Foundation

/// What a spoken command can do. Each maps to an action already on Main; none reaches the hazard decision.
public enum VoiceCommand: String, CaseIterable, Sendable {
    case whatsAhead
    case readText
    case pauseWarnings
    case resumeWarnings
    case stopSpeaking
}

/// Turns recognised speech into a wake phrase and a command. Pure text handling; the speech recognizer, the
/// microphone and every action live in the app. Recognisers drop apostrophes and punctuation unpredictably, so
/// everything is compared as lowercase words.
public enum VoicePhrases {
    public struct Parse: Equatable, Sendable {
        /// The wake phrase is in the text.
        public var wakeHeard: Bool
        /// A command after the last wake phrase, or nil.
        public var command: VoiceCommand?
    }

    /// Accepted spellings of the wake phrase, as normalised words.
    public static let wakePhrases = [
        "hey determine", "hey determined", "hey determines", "hay determine",
        "okay determine", "ok determine",
    ]

    /// Lowercase words separated by single spaces: punctuation becomes a space, apostrophes vanish ("what's" is
    /// "whats").
    public static func normalize(_ text: String) -> String {
        var out = ""
        for ch in text.lowercased() {
            if ch == "'" || ch == "\u{2019}" { continue }
            out.append(ch.isLetter || ch.isNumber ? ch : " ")
        }
        return out.split(separator: " ").joined(separator: " ")
    }

    /// Wake phrase, then the command that follows the last one. Text without a wake phrase never has a command here;
    /// use `command(in:)` for the words spoken in the window after a wake phrase on its own.
    public static func parse(_ text: String) -> Parse {
        guard let tail = tail(afterWakeIn: text) else { return Parse(wakeHeard: false, command: nil) }
        return Parse(wakeHeard: true, command: match(tail))
    }

    /// The words after the last wake phrase, normalised and padded with a space at each end; nil without a wake
    /// phrase.
    private static func tail(afterWakeIn text: String) -> String? {
        let words = " " + normalize(text) + " "
        var tailStart: String.Index?
        for phrase in wakePhrases {
            var search = words.startIndex..<words.endIndex
            while let r = words.range(of: " \(phrase) ", range: search) {
                // Keep the trailing space so the next phrase can start there.
                let after = words.index(before: r.upperBound)
                if tailStart == nil || after > tailStart! { tailStart = after }
                search = after..<words.endIndex
            }
        }
        return tailStart.map { String(words[$0...]) }
    }

    /// What was said after the last wake phrase, as plain words ("is the door open"), or nil when there is no wake
    /// phrase or nothing follows it. This is what goes to the AI router when no fixed command matched.
    public static func utterance(afterWakeIn text: String) -> String? {
        guard let tail = tail(afterWakeIn: text) else { return nil }
        let words = tail.trimmingCharacters(in: .whitespaces)
        return words.isEmpty ? nil : words
    }

    /// The command in text spoken without a wake phrase.
    public static func command(in text: String) -> VoiceCommand? {
        match(" " + normalize(text) + " ")
    }

    /// `padded` is normalised words with one space at each end, so a phrase matches only on word boundaries.
    private static func match(_ padded: String) -> VoiceCommand? {
        func has(_ phrases: String...) -> Bool { phrases.contains { padded.contains(" \($0) ") } }
        // A pause must never be mistaken for a plain "stop", which only cuts speech.
        if has("pause", "pause warnings", "pause the warnings", "stop warnings", "stop the warnings", "mute warnings") {
            return .pauseWarnings
        }
        if has("resume", "unpause", "resume warnings", "start warnings", "start the warnings", "turn warnings on") {
            return .resumeWarnings
        }
        if has("whats ahead", "what is ahead", "whats in front", "what is in front", "whats in front of me",
               "what is in front of me", "describe", "describe ahead") {
            return .whatsAhead
        }
        if has("read text", "read the text", "read this", "read that", "read it", "read the sign", "read") {
            return .readText
        }
        if has("stop", "stop reading", "stop talking", "be quiet", "quiet", "cancel") {
            return .stopSpeaking
        }
        return nil
    }
}
