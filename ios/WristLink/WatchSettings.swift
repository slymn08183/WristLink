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
}
