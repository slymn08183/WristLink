import Foundation

/// The watch's own numbers for the last days (protocol 6, `{"op":"summary"}`).
///
/// The watch computes everything derived (rings, resting HR, sleep score, the animal, the energy
/// score) so this app never re-implements the formulas; it only shows them. Every field is
/// optional: a value the watch couldn't compute yet comes as null or not at all, and the screens
/// show a collecting state for it instead of failing the whole summary.
struct HealthSummary: Decodable {

    struct Day: Codable {
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

    struct Night: Codable {
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
    /// Set instead of everything else when the watch failed to build the summary.
    let error: String?

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
///
/// Every day and night of every summary is also kept here for good, so the history screens can
/// go back months; the watch itself only keeps 90 days.
final class SummaryFeed: ObservableObject {

    @Published private(set) var latest: HealthSummary?
    /// A request is out and the watch hasn't answered yet.
    @Published private(set) var loading = false
    /// Every day seen so far, oldest first.
    @Published private(set) var days: [HealthSummary.Day] = []
    /// Every night seen so far, oldest first.
    @Published private(set) var nights: [HealthSummary.Night] = []
    /// Why the last summary didn't come through, if it didn't.
    @Published private(set) var lastError: String?

    /// Sends a message to the watch's sync characteristic (set by WatchLink).
    var send: (([String: Any]) -> Void)?

    private var timeout: DispatchWorkItem?
    private static var cacheURL: URL { URL.documentsDirectory.appending(path: "summary.json") }
    private static var historyURL: URL { URL.documentsDirectory.appending(path: "health-history.json") }
    /// Set once a long summary filled the history, so later requests can be short.
    private static let backfilledKey = "summaryBackfilled"

    private struct History: Codable {
        var days: [HealthSummary.Day]
        var nights: [HealthSummary.Night]
    }

    init() {
        if let data = try? Data(contentsOf: Self.cacheURL) {
            latest = try? JSONDecoder().decode(HealthSummary.self, from: data)
        }
        if let data = try? Data(contentsOf: Self.historyURL),
           let history = try? JSONDecoder().decode(History.self, from: data) {
            days = history.days
            nights = history.nights
        }
    }

    /// Ask for recent days: all the watch keeps the first time, two weeks after that.
    func request() {
        let backfilled = UserDefaults.standard.bool(forKey: Self.backfilledKey)
        request(days: backfilled ? 14 : 90)
    }

    /// Ask for the last `days` days (the watch allows 1–90).
    func request(days: Int) {
        guard let send, !loading else { return }
        loading = true
        send(["op": "summary", "days": days])
        // A watch that never answers mustn't block the next request. 90 days are ~100 KB,
        // which takes a while over Bluetooth.
        timeout?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.loading = false
            self?.lastError = "The watch didn't send its numbers."
        }
        timeout = work
        DispatchQueue.main.asyncAfter(deadline: .now() + (days > 14 ? 60 : 20), execute: work)
    }

    /// The watch's reply arrived on the sync channel.
    func handle(_ data: Data) {
        timeout?.cancel()
        loading = false
        let summary: HealthSummary
        do {
            summary = try JSONDecoder().decode(HealthSummary.self, from: data)
        } catch {
            lastError = "Couldn't read the watch's numbers: \(error.localizedDescription)"
            return
        }
        if let error = summary.error {
            lastError = "The watch couldn't put its numbers together (\(error))."
            return
        }
        lastError = nil
        latest = summary
        try? data.write(to: Self.cacheURL, options: .atomic)
        merge(summary)
        if (summary.days?.count ?? 0) > 14 {
            UserDefaults.standard.set(true, forKey: Self.backfilledKey)
        }
    }

    /// Newer copies of a day or night replace older ones (today keeps growing until midnight).
    private func merge(_ summary: HealthSummary) {
        var byDay = Dictionary(days.compactMap { d in d.day.map { ($0, d) } }, uniquingKeysWith: { _, new in new })
        for day in summary.days ?? [] {
            if let key = day.day { byDay[key] = day }
        }
        var byNight = Dictionary(nights.compactMap { n in n.night.map { ($0, n) } }, uniquingKeysWith: { _, new in new })
        for night in summary.sleep ?? [] {
            if let key = night.night { byNight[key] = night }
        }
        days = byDay.values.sorted { ($0.start ?? 0) < ($1.start ?? 0) }
        nights = byNight.values.sorted { ($0.start ?? 0) < ($1.start ?? 0) }
        if let data = try? JSONEncoder().encode(History(days: days, nights: nights)) {
            try? data.write(to: Self.historyURL, options: .atomic)
        }
    }

    func day(for date: Date) -> HealthSummary.Day? {
        days.last { $0.date.map { Calendar.current.isDate($0, inSameDayAs: date) } ?? false }
    }

    /// The night that ended on this day.
    func night(endingOn date: Date) -> HealthSummary.Night? {
        nights.last { $0.endDate.map { Calendar.current.isDate($0, inSameDayAs: date) } ?? false }
    }

    func linkLost() {
        timeout?.cancel()
        loading = false
    }
}
