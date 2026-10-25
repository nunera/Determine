import DetermineCore
import Foundation

// Executable assertions against the public model, not hardware, networking, or audio.
func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
    precondition(condition(), "Smoke failed: \(message)")
}

func driveThresholds(_ session: inout PrototypeSession, left: Int?, right: Int?) {
    var answers = 0
    while session.calibration?.step == .threshold {
        guard let calibration = session.calibration else { preconditionFailure("Missing calibration") }
        expect(calibration.ceilingDb == -10 && calibration.levelDb <= -10, "synthetic stimulus ceiling")
        expect([500, 1000, 2000].contains(calibration.frequencyHz), "three synthetic frequency bands")
        let threshold = calibration.ear == .left ? left : right
        session.answerHeard(threshold.map { calibration.levelDb >= $0 } ?? false)
        answers += 1
        expect(answers <= 480, "bounded staircase termination")
    }
    expect(session.calibration?.step == .level, "thresholds advance to level")
    print("Synthetic thresholds completed in \(answers) answers; no stimulus was played.")
}

func beginDemoCalibration(_ session: inout PrototypeSession) {
    session.beginCalibration()
    expect(session.calibration?.step == .fit && session.paused, "fit pauses demo warnings")
    session.fitCalibration(weakerEar: .right)
    expect(session.calibration?.step == .threshold, "fit advances to threshold")
}

var session = PrototypeSession()
expect(!session.running && session.state == .stopped && session.scenario == .noHardware, "safe initial state")
expect(session.calibration == nil && session.mode == .stereo && session.hapticMax == 255, "initial settings")
session.beginCalibration()
expect(session.calibration == nil, "calibration cannot begin stopped")
session.start()
expect(session.state == .unavailable && !session.headsetReady && !session.cueBoardReady && !session.cameraBoardReady,
       "no hardware never ready")
expect(session.bandLabel == nil && session.distanceMm == nil, "no false warning or distance")
print("Default start: \(session.state.label) — \(session.reason)")
session.setHapticMax(0)
expect(session.hapticMax == 1, "haptics cannot be disabled")
session.setHapticMax(256)
expect(session.hapticMax == 255, "haptics clamp high")
session.setMode(.hapticOnly)
expect(session.state == .unavailable, "haptics-only still requires boards")
session.stop()
session.selectScenario(.far)
expect(!session.running && session.state == .stopped && session.distanceMm == 1050, "scenario selection does not start")
session.start()
expect(session.state == .ready && session.bandLabel?.contains("far") == true, "far synthetic band")
expect(session.cueBoardReady && session.cameraBoardReady && session.headsetReady, "synthetic routes")
session.selectScenario(.mid)
expect(session.distanceMm == 700 && session.bandLabel?.contains("mid") == true, "mid synthetic band")
session.selectScenario(.near)
expect(session.distanceMm == 350 && session.bandLabel?.contains("near") == true, "near synthetic band")
session.describeEnabled = true
session.requestDescription()
expect(session.describeMessage?.contains("No image was captured or uploaded") == true, "Describe fail-closed")
session.selectScenario(.far)
session.requestDescription()
session.selectScenario(.near)
expect(session.describeMessage == nil, "near hazard removes the previous Describe message")
session.requestDescription()
session.selectScenario(.mid)
expect(session.describeMessage == nil, "mid hazard removes the previous Describe message")
session.selectScenario(.unknown)
expect(session.state == .degraded && session.distanceMm == nil && session.bandLabel == nil,
       "unknown is not clear or far")
expect(session.describeMessage == nil, "closing hazard clears description")
session.selectScenario(.boardLost)
expect(session.state == .unavailable && !session.cueBoardReady && session.bandLabel == nil,
       "board loss removes synthetic warning")
session.selectScenario(.headsetLost)
expect(session.distanceMm == 700 && session.state == .ready && !session.headsetReady,
       "haptics-only does not need synthetic headset")
session.beginCalibration()
expect(session.calibration == nil, "haptic warning mode still needs headset to calibrate")
session.setMode(.stereo)
expect(session.state == .unavailable && session.bandLabel == nil, "sound cannot fall back to phone speaker")
session.selectScenario(.near)
session.setPaused(true)
expect(session.state == .paused && session.bandLabel == nil, "pause suppresses warning")
session.setPaused(false)
expect(session.state == .ready && session.bandLabel != nil, "resume restores synthetic status")
print("Demo scenarios: far 1050, mid 700, near 350; unknown degraded, board/headset loss gated.")

beginDemoCalibration(&session)
expect(session.bandLabel == nil && session.calibration?.ceilingDb == -10, "calibration suppresses warnings")
let beforeReplay = session.calibration?.levelDb
session.replayCalibration()
expect(session.calibration?.levelDb == beforeReplay, "replay is silent and deterministic")
driveThresholds(&session, left: -50, right: -45)
session.setSensationLevel(9)
expect(session.calibration?.step == .level, "invalid low sensation level ignored")
session.setSensationLevel(21)
expect(session.calibration?.step == .level, "invalid high sensation level ignored")
session.setSensationLevel(15)
expect(session.calibration?.step == .balance, "usable ears advance to balance")
session.nudgeBalance(Int.max)
expect(session.calibration?.balanceDb == 12, "balance clamps safely at +12")
session.nudgeBalance(Int.min)
expect(session.calibration?.balanceDb == -12, "balance clamps safely at -12")
session.nudgeBalance(14)
expect(session.calibration?.balanceDb == 2, "balance responds within limits")
session.confirmBalance()
expect(session.calibration?.balanceConfirms == 1, "first balance confirmation")
session.nudgeBalance(2)
session.confirmBalance()
expect(session.calibration?.balanceDb == 3 && session.calibration?.step == .verify,
       "two confirmations average to +3")
for trial in 0..<5 {
    guard let stimulusEar = session.calibration?.verificationEar else { preconditionFailure("Missing virtual side") }
    expect(session.calibration?.verifyTrials == trial && (session.calibration?.levelDb ?? 0) <= -10,
           "five capped virtual side checks")
    session.answerSide(stimulusEar)
}
expect(session.calibration?.verifyCorrect == 5 && session.calibration?.resultMode == .stereo,
       "five correctly identified sides allow stereo demo profile")
expect(session.paused && session.bandLabel == nil, "finished but unapplied still pauses warnings")
session.applyCalibration()
expect(session.calibration == nil && !session.paused && session.mode == .stereo,
       "apply restores prior unpaused state")
expect(session.profileSummary.contains("Demo profile") && session.profileSummary.contains("5 of 5"),
       "applied demo profile is labelled synthetic")
print("Stereo demo: eight-reversal staircase, balance +3 dB, 5/5 synthetic side checks, applied.")

session.setPaused(true)
beginDemoCalibration(&session)
expect(session.paused, "calibration preserves prior pause")
session.cancelCalibration()
expect(session.calibration == nil && session.paused, "cancel restores prior paused state")
session.setPaused(false)
beginDemoCalibration(&session)
session.chooseHapticOnly()
expect(session.calibration?.step == .done && session.calibration?.resultMode == .hapticOnly,
       "haptics choice creates an unapplied result")
session.selectScenario(.headsetLost)
expect(session.calibration?.step == .failed && session.calibration?.resultMode == nil,
       "route loss revokes even completed unapplied result")
session.applyCalibration()
expect(session.mode == .stereo && session.profileSummary.contains("5 of 5"),
       "failed result cannot overwrite existing profile")
session.answerHeard(false)
expect(session.calibration?.step == .failed, "route failure cannot count as not-heard")
session.selectScenario(.near)
expect(session.state == .ready && session.bandLabel?.contains("near") == true,
       "restored pre-calibration warning state resumes after synthetic route recovery")
session.cancelCalibration()
expect(!session.paused && session.calibration == nil, "cancel after failure restores previous pause")
print("Route failure: completed result revoked; no unheard answer or profile committed.")

session.selectScenario(.mid)
beginDemoCalibration(&session)
driveThresholds(&session, left: -50, right: nil)
session.setSensationLevel(10)
expect(session.calibration?.resultMode == .singleEar, "unusable side selects single-ear mode")
session.applyCalibration()
expect(session.mode == .singleEar && session.profileSummary.contains("Demo profile"), "single-ear result applied")
print("One-sided synthetic ceiling failure: single-ear demo profile applied.")

beginDemoCalibration(&session)
driveThresholds(&session, left: -50, right: -45)
session.setSensationLevel(12)
session.confirmBalance()
session.confirmBalance()
for _ in 0..<5 {
    guard let stimulus = session.calibration?.verificationEar else { preconditionFailure("Missing virtual side") }
    session.answerSide(stimulus == .left ? .right : .left)
}
expect(session.calibration?.verifyCorrect == 0 && session.calibration?.resultMode == .singleEar,
       "incorrect synthetic side checks cannot yield stereo")
session.cancelCalibration()

beginDemoCalibration(&session)
driveThresholds(&session, left: -50, right: -24)
session.setSensationLevel(20)
expect(session.calibration?.step == .balance, "both audible virtual ears require balance")
session.confirmBalance()
session.confirmBalance()
for _ in 0..<5 {
    guard let stimulus = session.calibration?.verificationEar else { preconditionFailure("Missing virtual side") }
    session.answerSide(stimulus)
}
expect(session.calibration?.verifyCorrect == 5 && session.calibration?.resultMode == .singleEar,
       "ceiling-limited synthetic level rejects stereo despite five correct checks")
session.cancelCalibration()
print("Stereo exclusions: 0/5 virtual sides and ceiling-limited virtual level select single-ear.")

session.setPaused(true)
beginDemoCalibration(&session)
session.selectScenario(.boardLost)
expect(session.calibration?.step == .failed && session.paused && session.state == .unavailable,
       "board loss during threshold revokes result and restores prior pause")
session.cancelCalibration()
session.selectScenario(.mid)
expect(session.state == .paused, "restored pre-calibration pause survives route recovery")
session.setPaused(false)
beginDemoCalibration(&session)
session.chooseHapticOnly()
session.applyCalibration()
expect(session.mode == .hapticOnly && session.calibration == nil && !session.paused,
       "explicit haptics choice commits mode through normal apply")
print("Mid-calibration board loss restores pause; explicit haptics-only apply changes output mode.")

beginDemoCalibration(&session)
driveThresholds(&session, left: nil, right: nil)
session.setSensationLevel(20)
expect(session.calibration?.resultMode == .hapticOnly, "both unusable select haptics-only")
session.applyCalibration()
expect(session.mode == .hapticOnly && session.hapticMax >= 1, "haptics-only is actual output mode")
session.stop()
expect(session.profileSummary.contains("not calibrated") && session.calibration == nil && session.describeMessage == nil,
       "stop clears per-session profile/calibration/description")
session.selectScenario(.near)
session.start()
beginDemoCalibration(&session)
session.enterBackground()
expect(!session.running && session.state == .stopped && session.calibration == nil && session.bandLabel == nil,
       "background stops and invalidates calibration without auto resume")
print("Both-sided ceiling failure: haptics-only applied; stop/background erase demo profile and calibration.")
print("DetermineSmoke: all synthetic model assertions passed; no hardware, sound, or network exercised.")
