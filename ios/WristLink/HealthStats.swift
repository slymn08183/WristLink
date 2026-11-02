import Foundation
import HealthKit

/// Today's numbers and the last week, read back from Apple Health for the dashboard. Reads all
/// sources (watch and iPhone), the way the Health app shows them.
@MainActor
final class HealthStats: ObservableObject {

    struct Day: Identifiable {
        let date: Date
        let steps: Double
        var id: Date { date }
    }

    /// One hour's lowest and highest heart rate.
    struct HourRange: Identifiable {
        let hour: Date
        let low: Double
        let high: Double
        var id: Date { hour }
    }

    @Published private(set) var stepsToday: Double?
    @Published private(set) var latestHeartRate: Double?
    @Published private(set) var latestHeartRateDate: Date?
    @Published private(set) var restingHeartRate: Double?
    @Published private(set) var sleepLastNight: TimeInterval?
    @Published private(set) var week: [Day] = []
    // For the Health tab when the watch's own summary isn't there (older watch app, or away)
    @Published private(set) var hourlySteps: [Double] = []
    @Published private(set) var hourlyHeartRate: [HourRange] = []
    @Published private(set) var activeCaloriesToday: Double?
    @Published private(set) var distanceToday: Double?
    @Published private(set) var floorsToday: Double?
    /// When last night's sleep began and ended.
    @Published private(set) var sleepStart: Date?
    @Published private(set) var sleepEnd: Date?

    private let store = HKHealthStore()

    func refresh() async {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        let calendar = Calendar.current
        let now = Date()
        let startOfToday = calendar.startOfDay(for: now)

        stepsToday = await sum(.stepCount, unit: .count(), from: startOfToday, to: now)
        activeCaloriesToday = await sum(.activeEnergyBurned, unit: .kilocalorie(), from: startOfToday, to: now)
        distanceToday = await sum(.distanceWalkingRunning, unit: .meter(), from: startOfToday, to: now)
        floorsToday = await sum(.flightsClimbed, unit: .count(), from: startOfToday, to: now)
        if let latest = await latest(.heartRate) {
            latestHeartRate = latest.quantity.doubleValue(for: Self.bpm)
            latestHeartRateDate = latest.endDate
        }
        restingHeartRate = await latest(.restingHeartRate)?.quantity.doubleValue(for: Self.bpm)
        // "Last night": anything asleep from 18:00 yesterday on
        let evening = calendar.date(byAdding: .hour, value: -6, to: startOfToday) ?? startOfToday
        let night = await asleep(from: evening, to: now)
        sleepLastNight = night?.total
        sleepStart = night?.start
        sleepEnd = night?.end
        week = await dailySteps(days: 7, until: now)
        let endOfToday = startOfToday.addingTimeInterval(24 * 3600)
        hourlySteps = await hourlyStepCounts(from: startOfToday, to: endOfToday)
        hourlyHeartRate = await hourlyHeartRanges(from: startOfToday, to: endOfToday)
    }

    private static let bpm = HKUnit.count().unitDivided(by: .minute())

    /// Steps in each hour of the day, 24 values (fewer on a daylight-saving day).
    private func hourlyStepCounts(from start: Date, to end: Date) async -> [Double] {
        await withCheckedContinuation { continuation in
            let query = HKStatisticsCollectionQuery(
                quantityType: HKQuantityType(.stepCount),
                quantitySamplePredicate: HKQuery.predicateForSamples(withStart: start, end: end),
                options: .cumulativeSum,
                anchorDate: start,
                intervalComponents: DateComponents(hour: 1)
            )
            query.initialResultsHandler = { _, collection, _ in
                var result: [Double] = []
                collection?.enumerateStatistics(from: start, to: end) { stats, _ in
                    result.append(stats.sumQuantity()?.doubleValue(for: .count()) ?? 0)
                }
                continuation.resume(returning: result)
            }
            store.execute(query)
        }
    }

    /// Each hour's lowest and highest heart rate, for the hours that have any.
    private func hourlyHeartRanges(from start: Date, to end: Date) async -> [HourRange] {
        // A local, so the results handler (not on the main actor) doesn't touch Self.bpm
        let bpm = HKUnit.count().unitDivided(by: .minute())
        return await withCheckedContinuation { continuation in
            let query = HKStatisticsCollectionQuery(
                quantityType: HKQuantityType(.heartRate),
                quantitySamplePredicate: HKQuery.predicateForSamples(withStart: start, end: end),
                options: [.discreteMin, .discreteMax],
                anchorDate: start,
                intervalComponents: DateComponents(hour: 1)
            )
            query.initialResultsHandler = { _, collection, _ in
                var result: [HourRange] = []
                collection?.enumerateStatistics(from: start, to: end) { stats, _ in
                    if let low = stats.minimumQuantity()?.doubleValue(for: bpm),
                       let high = stats.maximumQuantity()?.doubleValue(for: bpm) {
                        result.append(HourRange(hour: stats.startDate, low: low, high: high))
                    }
                }
                continuation.resume(returning: result)
            }
            store.execute(query)
        }
    }

    private func sum(_ id: HKQuantityTypeIdentifier, unit: HKUnit, from: Date, to: Date) async -> Double? {
        await withCheckedContinuation { continuation in
            let query = HKStatisticsQuery(
                quantityType: HKQuantityType(id),
                quantitySamplePredicate: HKQuery.predicateForSamples(withStart: from, end: to),
                options: .cumulativeSum
            ) { _, result, _ in
                continuation.resume(returning: result?.sumQuantity()?.doubleValue(for: unit))
            }
            store.execute(query)
        }
    }

    private func latest(_ id: HKQuantityTypeIdentifier) async -> HKQuantitySample? {
        await withCheckedContinuation { continuation in
            let query = HKSampleQuery(
                sampleType: HKQuantityType(id),
                predicate: nil,
                limit: 1,
                sortDescriptors: [NSSortDescriptor(key: HKSampleSortIdentifierEndDate, ascending: false)]
            ) { _, samples, _ in
                continuation.resume(returning: samples?.first as? HKQuantitySample)
            }
            store.execute(query)
        }
    }

    /// Time asleep in the window, and when the first stretch began and the last one ended.
    private func asleep(from: Date, to: Date) async -> (total: TimeInterval, start: Date, end: Date)? {
        await withCheckedContinuation { continuation in
            let query = HKSampleQuery(
                sampleType: HKCategoryType(.sleepAnalysis),
                predicate: HKQuery.predicateForSamples(withStart: from, end: to),
                limit: HKObjectQueryNoLimit,
                sortDescriptors: nil
            ) { _, samples, _ in
                let asleep = (samples as? [HKCategorySample] ?? []).filter {
                    HKCategoryValueSleepAnalysis.allAsleepValues.contains(HKCategoryValueSleepAnalysis(rawValue: $0.value) ?? .awake)
                }
                // Several sources can record the same night; count overlapping time once
                let intervals = asleep.map { ($0.startDate, $0.endDate) }.sorted { $0.0 < $1.0 }
                var total: TimeInterval = 0
                var current: (Date, Date)?
                for interval in intervals {
                    if let c = current, interval.0 <= c.1 {
                        current = (c.0, max(c.1, interval.1))
                    } else {
                        if let c = current { total += c.1.timeIntervalSince(c.0) }
                        current = interval
                    }
                }
                if let c = current { total += c.1.timeIntervalSince(c.0) }
                guard total > 0, let first = intervals.first?.0,
                      let last = intervals.map({ $0.1 }).max() else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: (total: total, start: first, end: last))
            }
            store.execute(query)
        }
    }

    private func dailySteps(days: Int, until now: Date) async -> [Day] {
        let calendar = Calendar.current
        let end = calendar.startOfDay(for: now).addingTimeInterval(24 * 3600)
        guard let start = calendar.date(byAdding: .day, value: -days, to: end) else { return [] }
        return await withCheckedContinuation { continuation in
            let query = HKStatisticsCollectionQuery(
                quantityType: HKQuantityType(.stepCount),
                quantitySamplePredicate: HKQuery.predicateForSamples(withStart: start, end: end),
                options: .cumulativeSum,
                anchorDate: start,
                intervalComponents: DateComponents(day: 1)
            )
            query.initialResultsHandler = { _, collection, _ in
                var result: [Day] = []
                collection?.enumerateStatistics(from: start, to: end) { stats, _ in
                    result.append(Day(date: stats.startDate, steps: stats.sumQuantity()?.doubleValue(for: .count()) ?? 0))
                }
                continuation.resume(returning: result)
            }
            store.execute(query)
        }
    }
}
