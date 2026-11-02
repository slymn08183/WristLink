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

    /// What WristLink will write: the watch's daily activity, heart, sleep and workouts.
    private let writeTypes: Set<HKSampleType> = [
        HKQuantityType(.stepCount),
        HKQuantityType(.heartRate),
        HKQuantityType(.restingHeartRate),
        HKQuantityType(.activeEnergyBurned),
        HKQuantityType(.distanceWalkingRunning),
        HKQuantityType(.oxygenSaturation),
        HKCategoryType(.sleepAnalysis),
        HKObjectType.workoutType()
    ]

    init() {
        status = HKHealthStore.isHealthDataAvailable() ? .notAsked : .unavailable
    }

    func requestAccess() async {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        do {
            // Reading back the same types is how duplicates get avoided later
            try await store.requestAuthorization(toShare: writeTypes, read: writeTypes)
            status = .asked
        } catch {
            status = .failed(error.localizedDescription)
        }
    }
}
