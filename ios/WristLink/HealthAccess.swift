import Foundation
import HealthKit

/// Apple Health permissions. Writing comes in the next milestone; asking early lets the user
/// grant everything once.
@MainActor
final class HealthAccess: ObservableObject {

    enum Status: Equatable {
        case unavailable
        case notAsked
        case asked
        case failed(String)
    }

    @Published private(set) var status: Status

    private let store = HKHealthStore()

    /// What WristLink will write: the watch's daily activity, heart, sleep and workouts with
    /// their routes.
    private let writeTypes: Set<HKSampleType> = [
        HKQuantityType(.stepCount),
        HKQuantityType(.heartRate),
        HKQuantityType(.restingHeartRate),
        HKQuantityType(.activeEnergyBurned),
        HKQuantityType(.distanceWalkingRunning),
        HKQuantityType(.distanceCycling),
        HKQuantityType(.flightsClimbed),
        HKQuantityType(.oxygenSaturation),
        HKCategoryType(.sleepAnalysis),
        HKObjectType.workoutType(),
        HKSeriesType.workoutRoute()
    ]

    /// Which set of types the user was last asked about. Bump `currentVersion` whenever
    /// `writeTypes` grows, so the new ones get asked for too (iOS only shows those).
    private static let askedVersionKey = "healthAccessAskedVersion"
    private static let legacyAskedKey = "healthAccessAsked"
    private static let currentVersion = 2

    init() {
        if !HKHealthStore.isHealthDataAvailable() {
            status = .unavailable
        } else {
            // iOS never says whether write access was granted, only that the question was asked
            let defaults = UserDefaults.standard
            let asked = max(defaults.integer(forKey: Self.askedVersionKey),
                            defaults.bool(forKey: Self.legacyAskedKey) ? 1 : 0)
            status = asked >= Self.currentVersion ? .asked : .notAsked
        }
    }

    func requestAccess() async {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        do {
            // Reading back the same types is how duplicates get avoided later
            try await store.requestAuthorization(toShare: writeTypes, read: writeTypes)
            UserDefaults.standard.set(Self.currentVersion, forKey: Self.askedVersionKey)
            status = .asked
        } catch {
            status = .failed(error.localizedDescription)
        }
    }
}
