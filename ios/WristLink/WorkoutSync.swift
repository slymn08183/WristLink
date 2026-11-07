import CoreLocation
import Foundation
import HealthKit

/// One workout as the watch sends it (protocol 6, `{"op":"workout"}`), which is also the local
/// copy the workout screens read, so they can show the map and charts without asking Apple Health.
struct WorkoutRecord: Codable, Identifiable {

    struct Lap: Codable {
        let n: Int?
        /// Start and end, ms.
        let s: Double?
        let e: Double?
        /// Meters.
        let d: Double?
        let hr: Double?
    }

    /// The watch's id for acknowledging it; ids can repeat across watch reinstalls, so the
    /// start time is what identifies a workout here.
    let watchId: Int
    /// run, walk, hike, bike, bike_indoor, treadmill, elliptical, rowing, other.
    let type: String?
    let start: Double
    let end: Double
    /// Time not paused, ms.
    let activeMs: Double?
    let distance: Double?
    let calories: Double?
    let steps: Double?
    let avgHr: Double?
    let maxHr: Double?
    let elevationGain: Double?
    let laps: [Lap]?
    /// [ms, lat, lon, alt, hr, speed]; any of the last five may be null. Not kept in the index.
    var track: [[Double?]]?
    /// The watch's extra numbers; only the football ones are read here.
    let summary: Summary?

    /// Football (SprintTracker on the watch): sprints at 20 km/h and up, meters, m/s, and the
    /// seconds and meters walking, jogging, running and sprinting.
    struct Summary: Codable {
        let sprints: Int?
        let sprintDistance: Double?
        let longestSprint: Double?
        let highIntensityDistance: Double?
        let maxSpeed: Double?
        let speedBands: [Double]?
        let speedBandDistance: [Double]?
    }

    enum CodingKeys: String, CodingKey {
        case watchId = "id"
        case type, start, end, activeMs, distance, calories, steps, avgHr, maxHr, elevationGain, laps, track, summary
    }

    var id: Int64 { Int64(start) }
    var kind: WorkoutKind { WorkoutKind(type) }
    var startDate: Date { Date(timeIntervalSince1970: start / 1000) }
    var endDate: Date { Date(timeIntervalSince1970: end / 1000) }

    /// Moving time: the active time when the watch sent it, else start to end.
    var duration: TimeInterval {
        if let activeMs, activeMs > 0 { return activeMs / 1000 }
        return max(0, (end - start) / 1000)
    }

    /// The track as points, in time order.
    var points: [TrackPoint] {
        guard let track else { return [] }
        var result: [TrackPoint] = []
        result.reserveCapacity(track.count)
        for entry in track {
            guard let ms = entry.first ?? nil else { continue }
            func value(_ index: Int) -> Double? { index < entry.count ? entry[index] : nil }
            result.append(TrackPoint(
                index: result.count,
                date: Date(timeIntervalSince1970: ms / 1000),
                minutes: (ms - start) / 60000,
                latitude: value(1),
                longitude: value(2),
                altitude: value(3),
                heartRate: value(4),
                speed: value(5)
            ))
        }
        return result.sorted { $0.date < $1.date }
    }

    /// The same workout without its track, for the index.
    var withoutTrack: WorkoutRecord {
        var copy = self
        copy.track = nil
        return copy
    }
}

/// One sample of a workout's track.
struct TrackPoint: Identifiable {
    let index: Int
    let date: Date
    /// Since the start of the workout.
    let minutes: Double
    let latitude: Double?
    let longitude: Double?
    let altitude: Double?
    let heartRate: Double?
    /// Meters per second.
    let speed: Double?

    var id: Int { index }

    var coordinate: CLLocationCoordinate2D? {
        guard let latitude, let longitude, abs(latitude) <= 90, abs(longitude) <= 180,
              latitude != 0 || longitude != 0 else { return nil }
        return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

/// The watch's workout types and what they are in Apple Health.
enum WorkoutKind: String {
    case run, walk, hike, bike
    case bikeIndoor = "bike_indoor"
    case treadmill, elliptical, rowing, football, other

    init(_ raw: String?) {
        self = WorkoutKind(rawValue: raw ?? "") ?? .other
    }

    var title: String {
        switch self {
        case .run: return "Running"
        case .walk: return "Walking"
        case .hike: return "Hiking"
        case .bike: return "Cycling"
        case .bikeIndoor: return "Indoor cycling"
        case .treadmill: return "Treadmill"
        case .elliptical: return "Elliptical"
        case .rowing: return "Rowing"
        case .football: return "Football"
        case .other: return "Workout"
        }
    }

    var symbol: String {
        switch self {
        case .run, .treadmill: return "figure.run"
        case .walk: return "figure.walk"
        case .hike: return "figure.hiking"
        case .bike: return "figure.outdoor.cycle"
        case .bikeIndoor: return "figure.indoor.cycle"
        case .elliptical: return "figure.elliptical"
        case .rowing: return "figure.rower"
        case .football: return "figure.soccer"
        case .other: return "figure.mixed.cardio"
        }
    }

    var indoor: Bool {
        switch self {
        case .bikeIndoor, .treadmill, .elliptical, .rowing: return true
        default: return false
        }
    }

    /// Cycling and football show speed; on foot shows pace.
    var showsSpeed: Bool { self == .bike || self == .bikeIndoor || self == .football }

    var activityType: HKWorkoutActivityType {
        switch self {
        case .run, .treadmill: return .running
        case .walk: return .walking
        case .hike: return .hiking
        case .bike, .bikeIndoor: return .cycling
        case .elliptical: return .elliptical
        case .rowing: return .rowing
        case .football: return .soccer
        case .other: return .other
        }
    }

    /// The distance type Apple Health keeps for this activity, if any.
    var distanceType: HKQuantityTypeIdentifier? {
        switch self {
        case .run, .walk, .hike, .treadmill, .football: return .distanceWalkingRunning
        case .bike, .bikeIndoor: return .distanceCycling
        default: return nil
        }
    }
}

/// Moves workouts from the watch into Apple Health, one at a time, oldest first.
///
/// The watch keeps a workout until it's acknowledged. Each one is saved as an `HKWorkout` with its
/// heart rate, energy and distance, plus the GPS route, and carries a sync identifier
/// (`wristlink:workout:<start>`): a workout that was saved but whose acknowledgement got lost is
/// found again and only acknowledged, not saved twice. A copy is also kept in the app's documents
/// for the workout screens.
final class WorkoutSync: ObservableObject {

    @Published private(set) var syncing = false
    @Published private(set) var lastError: String?
    @Published private(set) var savedCount = 0
    /// Local copies without their tracks, newest first.
    @Published private(set) var records: [WorkoutRecord] = []

    /// Sends a message to the watch's sync characteristic (set by WatchLink).
    var send: (([String: Any]) -> Void)?
    /// Called when a run ends; true when it saved at least one workout.
    var onFinished: ((Bool) -> Void)?
    var deviceModel: String?
    var watchAppVersion: String?

    private let store = HKHealthStore()
    private var savedThisRun = 0
    /// Workouts acknowledged in this run: the watch offering one again means it didn't take the
    /// acknowledgement, and asking on would loop.
    private var ackedThisRun: Set<Int> = []
    private var timeout: DispatchWorkItem?
    private var lastFailure: Date?

    private static var folder: URL { URL.documentsDirectory.appending(path: "workouts", directoryHint: .isDirectory) }
    private static var indexURL: URL { folder.appending(path: "index.json") }
    private static func fileURL(_ start: Int64) -> URL { folder.appending(path: "workout-\(start).json") }

    init() {
        if let data = try? Data(contentsOf: Self.indexURL),
           let saved = try? JSONDecoder().decode([WorkoutRecord].self, from: data) {
            records = saved.sorted { $0.start > $1.start }
        }
    }

    /// Ask the watch for the oldest workout it hasn't had confirmed, unless a run is going.
    /// `automatic` runs (the watch's info, background syncs) wait a few minutes after a failed
    /// one, so a locked iPhone doesn't pull the same long track over and over.
    func start(automatic: Bool = false) {
        guard !syncing, HKHealthStore.isHealthDataAvailable(), send != nil else { return }
        if automatic, let lastFailure, Date().timeIntervalSince(lastFailure) < 300 { return }
        syncing = true
        lastError = nil
        savedThisRun = 0
        ackedThisRun = []
        requestNext()
    }

    /// The whole workout with its track, from the local copy.
    func full(_ record: WorkoutRecord) -> WorkoutRecord? {
        guard let data = try? Data(contentsOf: Self.fileURL(record.id)) else { return nil }
        return try? JSONDecoder().decode(WorkoutRecord.self, from: data)
    }

    /// A `workout` message arrived on the sync channel.
    func handle(_ data: Data) {
        timeout?.cancel()
        struct Marker: Decodable {
            let isNone: Bool?
            enum CodingKeys: String, CodingKey { case isNone = "none" }
        }
        if let marker = try? JSONDecoder().decode(Marker.self, from: data), marker.isNone == true {
            finish(error: nil)
            return
        }
        guard let workout = try? JSONDecoder().decode(WorkoutRecord.self, from: data) else {
            finish(error: "Couldn't read a workout from the watch")
            return
        }
        guard !ackedThisRun.contains(workout.watchId) else {
            finish(error: nil)
            return
        }
        syncing = true
        archive(workout)
        Task {
            do {
                try await self.saveToHealth(workout)
                DispatchQueue.main.async {
                    self.ackedThisRun.insert(workout.watchId)
                    self.savedThisRun += 1
                    self.savedCount += 1
                    self.send?(["op": "workoutAck", "id": workout.watchId])
                    self.requestNext()
                }
            } catch {
                DispatchQueue.main.async {
                    // Usually the iPhone is locked; the watch keeps it, so it's tried again later
                    self.finish(error: error.localizedDescription)
                }
            }
        }
    }

    /// The link dropped mid-run; the watch still has everything that wasn't acknowledged.
    func linkLost() {
        if syncing { finish(error: "Watch disconnected") }
    }

    private func requestNext() {
        guard let send else {
            finish(error: nil)
            return
        }
        send(["op": "nextWorkout"])
        // A long track takes a while over Bluetooth, but a watch that never answers mustn't
        // leave this running forever
        timeout?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.finish(error: "The watch didn't send the workout") }
        timeout = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 120, execute: work)
    }

    private func finish(error: String?) {
        timeout?.cancel()
        guard syncing else { return }
        syncing = false
        lastError = error
        lastFailure = error == nil ? nil : Date()
        onFinished?(savedThisRun > 0)
    }

    // MARK: Local copy

    private func archive(_ workout: WorkoutRecord) {
        let manager = FileManager.default
        try? manager.createDirectory(at: Self.folder, withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(workout) {
            try? data.write(to: Self.fileURL(workout.id), options: .atomic)
        }
        var index = records.filter { $0.id != workout.id }
        index.append(workout.withoutTrack)
        index.sort { $0.start > $1.start }
        records = index
        if let data = try? JSONEncoder().encode(index) {
            try? data.write(to: Self.indexURL, options: .atomic)
        }
    }

    // MARK: Apple Health

    private enum SaveError: LocalizedError {
        case healthKit
        var errorDescription: String? { "Couldn't save the workout to Apple Health" }
    }

    private func makeDevice() -> HKDevice {
        HKDevice(
            name: deviceModel.map { "Galaxy Watch (\($0))" } ?? "Galaxy Watch",
            manufacturer: "Samsung",
            model: deviceModel,
            hardwareVersion: nil,
            firmwareVersion: nil,
            softwareVersion: watchAppVersion,
            localIdentifier: nil,
            udiDeviceIdentifier: nil
        )
    }

    private func saveToHealth(_ record: WorkoutRecord) async throws {
        let syncId = "wristlink:workout:\(record.id)"
        if try await alreadySaved(syncId) { return }

        let kind = record.kind
        let configuration = HKWorkoutConfiguration()
        configuration.activityType = kind.activityType
        configuration.locationType = kind.indoor ? .indoor : .outdoor
        let device = makeDevice()
        let builder = HKWorkoutBuilder(healthStore: store, configuration: configuration, device: device)
        let start = record.startDate
        let end = max(record.endDate, start.addingTimeInterval(1))
        let points = record.points

        do {
            try await perform { builder.beginCollection(withStart: start, completion: $0) }
            let samples = workoutSamples(record, points: points, syncId: syncId, start: start, end: end, device: device)
            if !samples.isEmpty {
                try await perform { builder.add(samples, completion: $0) }
            }
            let laps = lapEvents(record, start: start, end: end)
            if !laps.isEmpty {
                try await perform { builder.addWorkoutEvents(laps, completion: $0) }
            }
            var metadata: [String: Any] = [
                HKMetadataKeySyncIdentifier: syncId,
                HKMetadataKeySyncVersion: 1
            ]
            if let gain = record.elevationGain, gain > 0 {
                metadata[HKMetadataKeyElevationAscended] = HKQuantity(unit: .meter(), doubleValue: gain)
            }
            try await perform { builder.addMetadata(metadata, completion: $0) }
            try await perform { builder.endCollection(withEnd: end, completion: $0) }
        } catch {
            builder.discardWorkout()
            throw error
        }
        let workout = try await finishWorkout(builder)

        // The workout is in; a route that fails to save shouldn't make it save again
        let locations = routeLocations(points)
        if !kind.indoor && locations.count >= 2 {
            try? await saveRoute(locations, for: workout, device: device)
        }
    }

    private func workoutSamples(_ record: WorkoutRecord, points: [TrackPoint], syncId: String,
                                start: Date, end: Date, device: HKDevice) -> [HKSample] {
        func metadata(_ part: String) -> [String: Any] {
            [HKMetadataKeySyncIdentifier: "\(syncId):\(part)", HKMetadataKeySyncVersion: 1]
        }
        var samples: [HKSample] = []
        let bpm = HKUnit.count().unitDivided(by: .minute())
        // One sample per moment: two points at the same ms would share a sync identifier
        var seen = Set<Int64>()
        for point in points {
            guard let hr = point.heartRate, hr > 0, point.date >= start, point.date <= end else { continue }
            let ms = Int64(point.date.timeIntervalSince1970 * 1000)
            guard seen.insert(ms).inserted else { continue }
            samples.append(HKQuantitySample(
                type: HKQuantityType(.heartRate),
                quantity: HKQuantity(unit: bpm, doubleValue: hr),
                start: point.date, end: point.date, device: device,
                metadata: metadata("hr:\(ms)")
            ))
        }
        if let calories = record.calories, calories > 0 {
            samples.append(HKQuantitySample(
                type: HKQuantityType(.activeEnergyBurned),
                quantity: HKQuantity(unit: .kilocalorie(), doubleValue: calories),
                start: start, end: end, device: device, metadata: metadata("energy")
            ))
        }
        if let type = record.kind.distanceType, let distance = record.distance, distance > 0 {
            samples.append(HKQuantitySample(
                type: HKQuantityType(type),
                quantity: HKQuantity(unit: .meter(), doubleValue: distance),
                start: start, end: end, device: device, metadata: metadata("distance")
            ))
        }
        return samples
    }

    private func lapEvents(_ record: WorkoutRecord, start: Date, end: Date) -> [HKWorkoutEvent] {
        (record.laps ?? []).compactMap { lap in
            guard let s = lap.s, let e = lap.e else { return nil }
            let lapStart = max(start, Date(timeIntervalSince1970: s / 1000))
            let lapEnd = min(end, Date(timeIntervalSince1970: e / 1000))
            guard lapEnd > lapStart else { return nil }
            return HKWorkoutEvent(type: .lap, dateInterval: DateInterval(start: lapStart, end: lapEnd), metadata: nil)
        }
    }

    private func routeLocations(_ points: [TrackPoint]) -> [CLLocation] {
        points.compactMap { point in
            guard let coordinate = point.coordinate else { return nil }
            return CLLocation(
                coordinate: coordinate,
                altitude: point.altitude ?? 0,
                horizontalAccuracy: 5,
                verticalAccuracy: point.altitude == nil ? -1 : 10,
                course: -1,
                speed: point.speed ?? -1,
                timestamp: point.date
            )
        }
    }

    private func alreadySaved(_ syncId: String) async throws -> Bool {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Bool, Error>) in
            let query = HKSampleQuery(
                sampleType: HKObjectType.workoutType(),
                predicate: HKQuery.predicateForObjects(withMetadataKey: HKMetadataKeySyncIdentifier, allowedValues: [syncId]),
                limit: 1,
                sortDescriptors: nil
            ) { _, samples, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: !(samples ?? []).isEmpty)
                }
            }
            store.execute(query)
        }
    }

    private func finishWorkout(_ builder: HKWorkoutBuilder) async throws -> HKWorkout {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<HKWorkout, Error>) in
            builder.finishWorkout { workout, error in
                if let workout {
                    continuation.resume(returning: workout)
                } else {
                    continuation.resume(throwing: error ?? SaveError.healthKit)
                }
            }
        }
    }

    private func saveRoute(_ locations: [CLLocation], for workout: HKWorkout, device: HKDevice) async throws {
        let routeBuilder = HKWorkoutRouteBuilder(healthStore: store, device: device)
        try await perform { routeBuilder.insertRouteData(locations, completion: $0) }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            routeBuilder.finishRoute(with: workout, metadata: nil) { route, error in
                if route != nil {
                    continuation.resume()
                } else {
                    continuation.resume(throwing: error ?? SaveError.healthKit)
                }
            }
        }
    }

    /// Runs one of HealthKit's `(Bool, Error?)` completion-handler calls as async.
    private func perform(_ call: (@escaping (Bool, Error?) -> Void) -> Void) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            call { ok, error in
                if ok {
                    continuation.resume()
                } else {
                    continuation.resume(throwing: error ?? SaveError.healthKit)
                }
            }
        }
    }
}
