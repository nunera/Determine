import Foundation
import DetermineEngine

/// cloud/API.md §1: the request the phone sends and how it reads the answer.
func describeChecks(_ t: Harness) {
    func object(_ data: Data) throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: data) as! [String: Any]
    }

    t.test("describe request: unredacted frame states its opt-in and carries the JPEG, size and locale") {
        let jpeg: [UInt8] = [0xFF, 0xD8, 0x01, 0x02, 0xFF, 0xD9]
        let body = try object(try DescribeWire.requestBody(requestId: "req_abcd1234", jpeg: jpeg, width: 640, height: 480))
        t.eq("req_abcd1234", body["request_id"] as? String)
        t.eq(false, body["redacted"] as? Bool)
        t.eq(true, body["unredacted_opt_in"] as? Bool)
        t.eq(640, body["width"] as? Int)
        t.eq(480, body["height"] as? Int)
        t.eq("en", body["locale"] as? String)
        t.eq(jpeg, Array(Data(base64Encoded: body["image_jpeg_b64"] as! String)!))
    }

    t.test("describe response: 200 gives trimmed text, an empty or missing text is a bad response") {
        t.eq(DescribeResult.text("A door is ahead."), DescribeWire.parse(status: 200, body: Data(#"{"request_id":"x","text":"  A door is ahead.\n"}"#.utf8)))
        t.eq(DescribeResult.failed(code: "bad_response", retryable: false), DescribeWire.parse(status: 200, body: Data(#"{"text":"   "}"#.utf8)))
        t.eq(DescribeResult.failed(code: "bad_response", retryable: false), DescribeWire.parse(status: 200, body: Data(#"{"sentences":[]}"#.utf8)))
        t.eq(DescribeResult.failed(code: "bad_response", retryable: false), DescribeWire.parse(status: 200, body: Data("not json".utf8)))
    }

    t.test("describe response: the API's error code and retryable flag are kept, other failures fall back to the status") {
        t.eq(DescribeResult.failed(code: "unauthorized", retryable: false), DescribeWire.parse(status: 401, body: Data(#"{"request_id":null,"error":"unauthorized","retryable":false}"#.utf8)))
        t.eq(DescribeResult.failed(code: "timeout", retryable: true), DescribeWire.parse(status: 504, body: Data(#"{"request_id":"x","error":"timeout","retryable":true}"#.utf8)))
        t.eq(DescribeResult.failed(code: "http_502", retryable: true), DescribeWire.parse(status: 502, body: Data("<html>".utf8)))
        t.eq(DescribeResult.failed(code: "http_404", retryable: false), DescribeWire.parse(status: 404, body: Data()))
    }

    t.test("describe request ids match the API rule 8-64 [A-Za-z0-9_-] and differ between requests") {
        let a = DescribeWire.makeRequestId(), b = DescribeWire.makeRequestId()
        t.ne(a, b)
        t.yes((8...64).contains(a.count))
        t.yes(a.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "_" || $0 == "-") })
    }

    t.test("describe request: a question is added, trimmed to 300 characters, and absent when there is none") {
        let jpeg: [UInt8] = [0xFF, 0xD8, 0xFF, 0xD9]
        let with = try object(try DescribeWire.requestBody(requestId: "req_abcd1234", jpeg: jpeg, width: 640, height: 480, question: "is the door open?"))
        t.eq("is the door open?", with["question"] as? String)
        let long = try object(try DescribeWire.requestBody(requestId: "req_abcd1234", jpeg: jpeg, width: 640, height: 480, question: String(repeating: "a", count: 400)))
        t.eq(300, (long["question"] as? String)?.count)
        let none = try object(try DescribeWire.requestBody(requestId: "req_abcd1234", jpeg: jpeg, width: 640, height: 480))
        t.isNil(none["question"])
        let empty = try object(try DescribeWire.requestBody(requestId: "req_abcd1234", jpeg: jpeg, width: 640, height: 480, question: ""))
        t.isNil(empty["question"])
    }

    t.test("voice request: only the words, the request id and the locale are sent") {
        let body = try object(try DescribeWire.voiceRequestBody(requestId: "req_abcd1234", transcript: "is the door open"))
        t.eq(Set(["request_id", "transcript", "locale"]), Set(body.keys))
        t.eq("is the door open", body["transcript"] as? String)
        t.eq("en", body["locale"] as? String)
    }

    t.test("voice decision: a known command or a real question, anything else is do nothing") {
        func ok(_ json: String) -> VoiceDecision { DescribeWire.parseVoice(status: 200, body: Data(json.utf8)) }
        t.eq(VoiceDecision.command(.pauseWarnings), ok(#"{"action":"command","command":"pauseWarnings"}"#))
        t.eq(VoiceDecision.command(.whatsAhead), ok(#"{"action":"command","command":"whatsAhead","model":"x"}"#))
        t.eq(VoiceDecision.ask("is the door open?"), ok(#"{"action":"ask","question":"  is the door open? "}"#))
        t.eq(VoiceDecision.none, ok(#"{"action":"command","command":"shutdown"}"#))
        t.eq(VoiceDecision.none, ok(#"{"action":"command"}"#))
        t.eq(VoiceDecision.none, ok(#"{"action":"ask","question":"   "}"#))
        t.eq(VoiceDecision.none, ok(#"{"action":"none"}"#))
        t.eq(VoiceDecision.none, ok(#"{"action":"launch"}"#))
        t.eq(VoiceDecision.none, ok("not json"))
    }

    t.test("voice decision: API errors keep their code and retryable flag") {
        t.eq(VoiceDecision.failed(code: "unavailable", retryable: true), DescribeWire.parseVoice(status: 503, body: Data(#"{"request_id":"x","error":"unavailable","retryable":true}"#.utf8)))
        t.eq(VoiceDecision.failed(code: "http_502", retryable: true), DescribeWire.parseVoice(status: 502, body: Data("<html>".utf8)))
        t.eq(VoiceDecision.failed(code: "unauthorized", retryable: false), DescribeWire.parseVoice(status: 401, body: Data(#"{"error":"unauthorized","retryable":false}"#.utf8)))
    }
}
