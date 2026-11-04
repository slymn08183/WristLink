import Foundation
import HealthKit

/// Moves health samples from the watch into Apple Health.
///
/// The watch keeps samples until they're acknowledged, so this can run whenever iOS gives the app
/// a moment (a Bluetooth event in the background, or the app being opened) and simply stops when
/// it can't finish; nothing is lost. Every sample carries a sync identifier, so a batch that gets
/// written twice (say the acknowledgement didn't reach the watch) replaces itself instead of
/// doubling up in Apple Health.
final class HealthSync: ObservableObject {

    struct Item: Decodable {
        let id: Int64
        let t: String
        let s: Int64
        let e: Int64
        let v: Double
    }

    struct Batch: Decodable {
        let op: String
        let items: [Item]
        let more: Bool
    }

    @Published private(set) var syncing = false
    @Published private(set) var lastSync: Date?
    @Published private(set) var lastCount = 0
    @Published private(set) var lastError: String?

    /// Sends a message to the watch's sync characteristic (set by WatchLink).
    var send: (([String: Any]) -> Void)?
    /// Called when a sync ends: how many samples it saved, or nil when it stopped on an error.
    var onFinished: ((Int?) -> Void)?
    var deviceModel: String?
    var watchAppVersion: String?

    private let store = HKHealthStore()
    private static let batchSize = 100
    private var savedThisRun = 0
    /// Set when Apple Health refused some samples this run, shown once the run ends.
    private var refusedNote: String?

    /// Ask the watch for what it has, unless a sync is already running.
    func start() {
        guard !syncing, HKHealthStore.isHealthDataAvailable(), let send else { return }
        syncing = true
        lastError = nil
        savedThisRun = 0
        refusedNote = nil
        send(["op": "pull", "after": 0, "max": Self.batchSize])
    }

    /// A complete message arrived on the sync channel.
    func handle(_ data: Data) {
        guard let batch = try? JSONDecoder().decode(Batch.self, from: data), batch.op == "batch" else {
            if syncing { finish(error: "Couldn't read the samples the watch sent.") }
            return
        }
        guard let lastId = batch.items.last?.id else {
            finish(error: nil)
            return
        }
        let samples = batch.items.compactMap(sample(for:))
        let save: (@escaping (Bool, Error?) -> Void) -> Void = { done in
            if samples.isEmpty { done(true, nil) } else { self.store.save(samples, withCompletion: done) }
        }
        save { ok, error in
            DispatchQueue.main.async {
                if ok {
                    self.accept(batch, upTo: lastId, saved: samples.count, refused: nil)
                } else if (error as? HKError)?.code == .errorDatabaseInaccessible {
                    // The iPhone is locked (Health data is encrypted then); the next sync retries
                    self.finish(error: "Unlock the iPhone to save to Apple Health.")
                } else {
                    // One sample (or one type Health doesn't let WristLink write) fails the whole
                    // batch, and the watch would send the same one forever. Save them one by one.
                    self.saveEach(samples) { saved, refused in
                        let blocked = refused.first.map(Self.isNotAllowed) ?? false
                        if saved == 0 && !refused.isEmpty && !blocked {
                            // Nothing went in for a reason that may pass; keep them on the watch
                            self.finish(error: refused.first?.localizedDescription ?? "Couldn't save to Apple Health")
                        } else {
                            self.accept(batch, upTo: lastId, saved: saved, refused: refused.first)
                        }
                    }
                }
            }
        }
    }

    /// The batch is in Apple Health (apart from what it refused): the watch can drop it.
    private func accept(_ batch: Batch, upTo lastId: Int64, saved: Int, refused: Error?) {
        lastCount += saved
        savedThisRun += saved
        if let refused {
            refusedNote = Self.isNotAllowed(refused)
                ? "Apple Health doesn't let WristLink write some data. Allow it in Health → Sharing → Apps → WristLink."
                : "Apple Health refused some samples: \(refused.localizedDescription)"
        }
        send?(["op": "ack", "upTo": lastId])
        if batch.more {
            send?(["op": "pull", "after": lastId, "max": Self.batchSize])
        } else {
            finish(error: nil)
        }
    }

    private func saveEach(_ samples: [HKSample], completion: @escaping (Int, [Error]) -> Void) {
        let group = DispatchGroup()
        let lock = NSLock()
        var saved = 0
        var refused: [Error] = []
        for sample in samples {
            group.enter()
            store.save(sample) { ok, error in
                lock.lock()
                if ok { saved += 1 } else { refused.append(error ?? HKError(.errorInvalidArgument)) }
                lock.unlock()
                group.leave()
            }
        }
        group.notify(queue: .main) { completion(saved, refused) }
    }

    private static func isNotAllowed(_ error: Error) -> Bool {
        guard let code = (error as? HKError)?.code else { return false }
        return code == .errorAuthorizationDenied || code == .errorAuthorizationNotDetermined
    }

    /// The link dropped mid-sync; the watch still has everything that wasn't acknowledged.
    func linkLost() {
        if syncing { finish(error: "Watch disconnected") }
    }

    private func finish(error: String?) {
        syncing = false
        lastError = error ?? refusedNote
        if error == nil { lastSync = Date() }
        onFinished?(error == nil ? savedThisRun : nil)
    }

    private lazy var device = HKDevice(
        name: deviceModel.map { "Galaxy Watch (\($0))" } ?? "Galaxy Watch",
        manufacturer: "Samsung",
        model: deviceModel,
        hardwareVersion: nil,
        firmwareVersion: nil,
        softwareVersion: watchAppVersion,
        localIdentifier: nil,
        udiDeviceIdentifier: nil
    )

    private func sample(for item: Item) -> HKSample? {
        let start = Date(timeIntervalSince1970: TimeInterval(item.s) / 1000)
        let end = Date(timeIntervalSince1970: TimeInterval(item.e) / 1000)
        let metadata: [String: Any] = [
            HKMetadataKeySyncIdentifier: "wristlink:\(item.t):\(item.s)",
            HKMetadataKeySyncVersion: 1
        ]

        func quantity(_ id: HKQuantityTypeIdentifier, _ unit: HKUnit) -> HKSample {
            HKQuantitySample(
                type: HKQuantityType(id),
                quantity: HKQuantity(unit: unit, doubleValue: item.v),
                start: start, end: end, device: device, metadata: metadata
            )
        }

        switch item.t {
        case "steps":
            return quantity(.stepCount, .count())
        case "distance":
            return quantity(.distanceWalkingRunning, .meter())
        case "hr":
            return quantity(.heartRate, HKUnit.count().unitDivided(by: .minute()))
        case "sleep":
            return HKCategorySample(
                type: HKCategoryType(.sleepAnalysis),
                value: HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue,
                start: start, end: end, device: device, metadata: metadata
            )
        // Protocol 6
        case "floors":
            return quantity(.flightsClimbed, .count())
        case "activeCalories":
            return quantity(.activeEnergyBurned, .kilocalorie())
        case "awake":
            // An awake stretch inside a night, next to the asleep ones
            return HKCategorySample(
                type: HKCategoryType(.sleepAnalysis),
                value: HKCategoryValueSleepAnalysis.awake.rawValue,
                start: start, end: end, device: device, metadata: metadata
            )
        case "restingHr":
            // The day's resting heart rate, stamped at that day's local midnight
            return quantity(.restingHeartRate, HKUnit.count().unitDivided(by: .minute()))
        default:
            // A type from a newer watch app this version doesn't know yet
            return nil
        }
    }
}
