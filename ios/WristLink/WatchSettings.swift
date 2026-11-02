import Foundation

/// The watch's settings as the companion link reports them (protocol 3, `RemoteSettings` on the
/// watch). Changing one sends `setSetting` and the watch answers with the full set again; the
/// watch also sends them by itself when something changes there (protocol 4).
struct WatchSettings: Decodable, Equatable {

    struct Category: Decodable, Equatable, Identifiable {
        let id: Int
        let name: String
        var enabled: Bool
    }

    // WristLink
    var vibration: Bool
    var respectDnd: Bool
    var showPreExisting: Bool
    var showSilent: Bool
    var callsOnWatch: Bool
    var phoneBatteryAlert: Bool
    var statusNotification: Bool
    var sleepGuard: Bool
    var disconnectAlert: Bool
    var showPhoneInfo: Bool
    /// The watch thinks you're asleep (its own sleep detection where it has one).
    var asleep: Bool
    var categories: [Category]

    // Protocol 4 (optional so an older watch app still decodes)
    /// Quiet while asleep has turned on DND right now.
    var quietNow: Bool?
    /// "watch": the watch's own sleep detection; "steps": the 22:00–10:00 step-counter fallback.
    var sleepDetection: String?
    /// Quiet while asleep has the permissions it needs (granted by the installer).
    var sleepGuardReady: Bool?
    /// The watch has the phone permissions calls on the watch need.
    var canTalkOnWatch: Bool?

    // System (need the installer's adb permissions)
    var canChangeSystem: Bool
    var canChangeDnd: Bool
    var raiseToWake: Bool
    var screenTimeoutSec: Int
    var use24Hour: Bool
    var hideStatusIcons: Bool
    var doNotDisturb: Bool
    var brightness: Int
    var autoBrightness: Bool
    var alwaysOnDisplay: Bool
    var tiltToWakeAod: Bool
    var wakeByBezel: Bool
    var doubleTapToWake: Bool
    var touchSensitivity: Bool
    var hapticFeedback: Bool
    var fontScale: Double

    // Protocol 6
    /// Body profile and goals for the watch's health screens; change one with `profile.<field>`.
    var profile: WatchProfile?
}

/// The body profile and goals the watch's health screens are built on (protocol 6, `HealthProfile`
/// on the watch). It comes in the settings (`values.profile`) and in every summary; the iPhone
/// changes one field with `setSetting`, key `profile.<field>`. Every field falls back to the
/// watch's own default, so a profile with fields missing (or a newer one with more) still decodes.
struct WatchProfile: Decodable, Equatable {
    /// False until the user has looked at the profile once.
    var configured = false
    /// "male" or "female".
    var sex = "male"
    var birthYear = 1995
    var heightCm = 175
    var weightKg = 75
    /// What the watch uses: 220 − age unless overridden there.
    var maxHr: Int?
    var stepGoal = 6000
    var activeMinutesGoal = 90
    var activeCaloriesGoal = 500
    var exerciseMinutesGoal = 30
    var sleepGoalMinutes = 480
    /// Goal bedtime, minutes after midnight (23:00 by default).
    var bedtimeMinutes = 1380
    var hrAlerts = true
    var hrHigh = 120
    var hrLow = 40
    var inactivityAlert = true
    var goalNudges = true

    init() {}

    enum CodingKeys: String, CodingKey {
        case configured, sex, birthYear, heightCm, weightKg, maxHr, stepGoal, activeMinutesGoal
        case activeCaloriesGoal, exerciseMinutesGoal, sleepGoalMinutes, bedtimeMinutes
        case hrAlerts, hrHigh, hrLow, inactivityAlert, goalNudges
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = WatchProfile()
        // Whole numbers may arrive as 75 or 75.0; anything missing or unreadable keeps the default
        func int(_ key: CodingKeys) -> Int? {
            if let value = try? c.decodeIfPresent(Int.self, forKey: key) { return value }
            if let value = try? c.decodeIfPresent(Double.self, forKey: key) { return Int(value) }
            return nil
        }
        func bool(_ key: CodingKeys) -> Bool? {
            if let value = try? c.decodeIfPresent(Bool.self, forKey: key) { return value }
            return nil
        }
        configured = bool(.configured) ?? d.configured
        if let value = try? c.decodeIfPresent(String.self, forKey: .sex) { sex = value }
        birthYear = int(.birthYear) ?? d.birthYear
        heightCm = int(.heightCm) ?? d.heightCm
        weightKg = int(.weightKg) ?? d.weightKg
        maxHr = int(.maxHr)
        stepGoal = int(.stepGoal) ?? d.stepGoal
        activeMinutesGoal = int(.activeMinutesGoal) ?? d.activeMinutesGoal
        activeCaloriesGoal = int(.activeCaloriesGoal) ?? d.activeCaloriesGoal
        exerciseMinutesGoal = int(.exerciseMinutesGoal) ?? d.exerciseMinutesGoal
        sleepGoalMinutes = int(.sleepGoalMinutes) ?? d.sleepGoalMinutes
        bedtimeMinutes = int(.bedtimeMinutes) ?? d.bedtimeMinutes
        hrAlerts = bool(.hrAlerts) ?? d.hrAlerts
        hrHigh = int(.hrHigh) ?? d.hrHigh
        hrLow = int(.hrLow) ?? d.hrLow
        inactivityAlert = bool(.inactivityAlert) ?? d.inactivityAlert
        goalNudges = bool(.goalNudges) ?? d.goalNudges
    }

    var isFemale: Bool { sex.lowercased() == "female" }

    var age: Int {
        min(max(Calendar.current.component(.year, from: Date()) - birthYear, 5), 110)
    }

    /// The watch's max HR, or 220 − age when an older summary didn't say.
    var maxHeartRate: Int { maxHr ?? (220 - age) }

    /// Resting range for the age and sex, bpm (the table the watch uses), for when the summary
    /// doesn't carry one.
    var restingRange: ClosedRange<Int> {
        let male = !isFemale
        switch age {
        case ...8: return male ? 76...92 : 79...94
        case ...11: return male ? 70...86 : 76...91
        case ...15: return male ? 66...83 : 70...87
        case ...19: return male ? 61...78 : 69...85
        case ...39: return male ? 61...76 : 66...82
        case ...59: return male ? 61...77 : 64...79
        case ...79: return male ? 60...75 : 64...78
        default: return male ? 61...78 : 64...77
        }
    }
}
