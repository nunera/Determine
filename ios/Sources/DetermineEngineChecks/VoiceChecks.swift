import Foundation
import DetermineEngine

/// Wake phrase and command parsing: what the wearer says is what gets done, and a pause is never a plain stop.
func voiceChecks(_ t: Harness) {
    func parse(_ s: String) -> VoicePhrases.Parse { VoicePhrases.parse(s) }

    t.test("voice: normalisation ignores case, punctuation and apostrophes") {
        t.eq("hey determine whats ahead", VoicePhrases.normalize("Hey, Determine! What's ahead?"))
        t.eq("hey determine whats ahead", VoicePhrases.normalize("hey determine what\u{2019}s ahead"))
        t.eq("", VoicePhrases.normalize(" ... "))
    }

    t.test("voice: every command is understood after the wake phrase") {
        t.eq(VoiceCommand.whatsAhead, parse("Hey Determine, what's ahead?").command)
        t.eq(VoiceCommand.whatsAhead, parse("hey determine what is in front of me").command)
        t.eq(VoiceCommand.whatsAhead, parse("ok determine describe").command)
        t.eq(VoiceCommand.readText, parse("Hey Determine read text").command)
        t.eq(VoiceCommand.readText, parse("hey determine, read this").command)
        t.eq(VoiceCommand.pauseWarnings, parse("Hey Determine pause warnings").command)
        t.eq(VoiceCommand.pauseWarnings, parse("hey determine pause").command)
        t.eq(VoiceCommand.resumeWarnings, parse("Hey Determine resume").command)
        t.eq(VoiceCommand.resumeWarnings, parse("hey determine unpause").command)
        t.eq(VoiceCommand.stopSpeaking, parse("Hey Determine stop reading").command)
        t.eq(VoiceCommand.stopSpeaking, parse("hey determine stop").command)
    }

    t.test("voice: stopping the warnings pauses them, a plain stop only cuts speech") {
        t.eq(VoiceCommand.pauseWarnings, parse("hey determine stop warnings").command)
        t.eq(VoiceCommand.pauseWarnings, parse("hey determine stop the warnings").command)
        t.eq(VoiceCommand.stopSpeaking, parse("hey determine stop talking").command)
        t.ne(VoiceCommand.pauseWarnings, parse("hey determine stop").command)
    }

    t.test("voice: without the wake phrase nothing is a command, and a wake phrase alone has none") {
        let a = parse("what's ahead")
        t.no(a.wakeHeard)
        t.isNil(a.command)
        t.isNil(parse("please pause the music").command)
        let b = parse("Hey Determine")
        t.yes(b.wakeHeard)
        t.isNil(b.command)
        t.no(parse("").wakeHeard)
    }

    t.test("voice: only words after the last wake phrase count") {
        t.eq(VoiceCommand.readText, parse("hey determine pause hey determine read text").command)
        t.isNil(parse("hey determine pause hey determine").command)
    }

    t.test("voice: phrases match whole words, so 'reading' and 'paused' are not commands") {
        t.isNil(parse("hey determine reading glasses").command)
        t.isNil(parse("hey determine paused").command)
        t.isNil(parse("hey determine stopwatch").command)
    }

    t.test("voice: the words spoken after a wake phrase on its own are read without it") {
        t.eq(VoiceCommand.whatsAhead, VoicePhrases.command(in: "What's ahead?"))
        t.eq(VoiceCommand.pauseWarnings, VoicePhrases.command(in: "pause warnings"))
        t.isNil(VoicePhrases.command(in: "nice weather today"))
    }

    t.test("voice: what was said after the last wake phrase is kept as plain words for the AI router") {
        t.eq("is the door open", VoicePhrases.utterance(afterWakeIn: "Hey Determine, is the door open?"))
        t.eq("what colour is that car", VoicePhrases.utterance(afterWakeIn: "hey determine pause hey determine what colour is that car"))
        t.eq("whats the weather", VoicePhrases.utterance(afterWakeIn: "ok determine what's the weather"))
        t.isNil(VoicePhrases.utterance(afterWakeIn: "Hey Determine"))
        t.isNil(VoicePhrases.utterance(afterWakeIn: "is the door open"))
    }
}
