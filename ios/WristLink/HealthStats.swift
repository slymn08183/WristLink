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

    @Published private(set) var stepsToday: Double?
    @Published private(set) var latestHeartRate: Double?
    @Published private(set) var latestHeartRateDate: Date?
    @Published private(set) var restingHeartRate: Double?
    @Published private(set) var sleepLastNight: TimeInterval?
    @Published private(set) var week: [Day] = []

    private let store = HKHealthStore()

    func refresh() async {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        let calendar = Calendar.current
        let now = Date()
        let startOfToday = calendar.startOfDay(for: now)

        stepsToday = await sum(.stepCount, unit: .count(), from: startOfToday, to: now)
        if let latest = await latest(.heartRate) {
            latestHeartRate = latest.quantity.doubleValue(for: Self.bpm)
            latestHeartRateDate = latest.endDate
        }
        restingHeartRate = await latest(.restingHeartRate)?.quantity.doubleValue(for: Self.bpm)
        // "Last night": anything asleep from 18:00 yesterday on
        let evening = calendar.date(byAdding: .hour, value: -6, to: startOfToday) ?? startOfToday
        sleepLastNight = await asleepDuration(from: evening, to: now)
        week = await dailySteps(days: 7, until: now)
    }

    private static let bpm = HKUnit.count().unitDivided(by: .minute())

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

    private func asleepDuration(from: Date, to: Date) async -> TimeInterval? {
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
                continuation.resume(returning: total > 0 ? total : nil)
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
