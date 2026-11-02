import Foundation

/// The watch's settings as the companion link reports them (protocol 3, `RemoteSettings` on the
/// watch). Changing one sends `setSetting` and the watch answers with the full set again.
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
    var asleep: Bool
    var categories: [Category]

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
