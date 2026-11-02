import Foundation

/// The watch's own numbers for the last days (protocol 6, `{"op":"summary"}`).
///
/// The watch computes everything derived (rings, resting HR, sleep score, the animal, the energy
/// score) so this app never re-implements the formulas; it only shows them. Every field is
/// optional: a value the watch couldn't compute yet comes as null or not at all, and the screens
/// show a collecting state for it instead of failing the whole summary.
struct HealthSummary: Decodable {

    struct Day: Decodable {
        /// "2026-11-03"
        let day: String?
        /// Local midnight, ms.
        let start: Double?
        let steps: Double?
        /// Meters.
        let distance: Double?
        let floors: Double?
        let activeCalories: Double?
        let totalCalories: Double?
        let activeMinutes: Double?
        let exerciseMinutes: Double?
        let raisedHrMinutes: Double?
        let activeHours: Double?
        let restingHr: Double?
        let hrMin: Double?
        let hrMax: Double?
        let stepGoalMet: Bool?
        let allGoalsMet: Bool?
        /// 24 values, one per hour of the day.
        let hourlySteps: [Double?]?
        let hourlyHrMin: [Double?]?
        let hourlyHrMax: [Double?]?

        var date: Date? { start.map(HealthSummary.date) }
    }

    struct Night: Decodable {
        /// The day the night ends on, "2026-11-03".
        let night: String?
        let start: Double?
        let end: Double?
        let inBedMin: Double?
        let asleepMin: Double?
        let awakeMin: Double?
        /// 0–1.
        let efficiency: Double?
        /// Null when the night is too short to score (under an hour asleep).
        let score: Double?
        /// "Excellent", "Good", "Fair" or "Attention".
        let band: String?
        let avgHr: Double?
        let minHr: Double?
        /// Awake stretches, [[start ms, end ms], ...].
        let gaps: [[Double?]]?
        /// Steps per 10-minute bucket from `start`.
        let movement: [Double?]?

        var startDate: Date? { start.map(HealthSummary.date) }
        var endDate: Date? { end.map(HealthSummary.date) }
    }

    struct Animal: Decodable {
        let name: String?
        let short: Bool?
        let wakes: Bool?
        let irregular: Bool?
    }

    struct Energy: Decodable {
        let score: Double?
        let band: String?
        /// Always true on this watch: computed without the HRV factor.
        let reduced: Bool?
    }

    let at: Double?
    let profile: WatchProfile?
    /// [low, high] bpm for the age and sex.
    let restingRange: [Double]?
    /// Oldest first; the last one is today.
    let days: [Day]?
    /// Oldest first; the last one is last night.
    let sleep: [Night]?
    /// Null until there are three nights.
    let animal: Animal?
    /// Null without last night's sleep.
    let energy: Energy?
    let streak: Double?

    static func date(_ ms: Double) -> Date { Date(timeIntervalSince1970: ms / 1000) }

    var updated: Date? { at.map(Self.date) }

    /// Today's entry, if the summary is from today (a cached one may be from yesterday).
    var today: Day? {
        guard let last = days?.last, let date = last.date, Calendar.current.isDateInToday(date) else { return nil }
        return last
    }

    /// Last night, if it ended within the last day.
    var lastNight: Night? {
        guard let last = sleep?.last, let end = last.endDate, Date().timeIntervalSince(end) < 24 * 3600 else { return nil }
        return last
    }

    var restingBand: ClosedRange<Double>? {
        guard let range = restingRange, range.count == 2, range[0] <= range[1] else { return nil }
        return range[0]...range[1]
    }
}

/// Asks the watch for its summary and keeps the last one, also on disk so the Health tab has
/// something to show before the watch answers (or while it's away).
final class SummaryFeed: ObservableObject {

    @Published private(set) var latest: HealthSummary?
    /// A request is out and the watch hasn't answered yet.
    @Published private(set) var loading = false

    /// Sends a message to the watch's sync characteristic (set by WatchLink).
    var send: (([String: Any]) -> Void)?

    private var timeout: DispatchWorkItem?
    private static var cacheURL: URL { URL.documentsDirectory.appending(path: "summary.json") }

    init() {
        if let data = try? Data(contentsOf: Self.cacheURL) {
            latest = try? JSONDecoder().decode(HealthSummary.self, from: data)
        }
    }

    /// Ask for the last `days` days (the watch allows 1–14).
    func request(days: Int = 7) {
        guard let send, !loading else { return }
        loading = true
        send(["op": "summary", "days": days])
        // A watch that never answers mustn't block the next request
        timeout?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.loading = false }
        timeout = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 20, execute: work)
    }

    /// The watch's reply arrived on the sync channel.
    func handle(_ data: Data) {
        timeout?.cancel()
        loading = false
        guard let summary = try? JSONDecoder().decode(HealthSummary.self, from: data) else { return }
        latest = summary
        try? data.write(to: Self.cacheURL, options: .atomic)
    }

    func linkLost() {
        timeout?.cancel()
        loading = false
    }
}
