import Charts
import CoreLocation
import MapKit
import SwiftUI

// Workouts from the watch: the list and one workout in full (route map, pace, heart-rate and
// elevation charts, splits, laps and heart-rate zones). Everything comes from the local copy
// WorkoutSync keeps, so it works without reading Apple Health back.

/// One workout in a list: type, when, and the headline numbers.
struct WorkoutRow: View {
    let record: WorkoutRecord

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: record.kind.symbol)
                .font(.headline)
                .foregroundStyle(.orange)
                .frame(width: 40, height: 40)
                .background(Color.orange.opacity(0.15), in: .circle)
            VStack(alignment: .leading, spacing: 2) {
                Text(record.kind.title).font(.subheadline.weight(.semibold))
                Text(record.startDate.formatted(date: .abbreviated, time: .shortened))
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(record.distance.flatMap { $0 > 0 ? Format.distance($0) : nil } ?? Format.elapsed(record.duration))
                    .font(.subheadline.weight(.semibold))
                if (record.distance ?? 0) > 0 {
                    Text(Format.elapsed(record.duration)).font(.caption).foregroundStyle(.secondary)
                }
            }
            Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }
}

/// Every workout kept on this iPhone, newest first.
struct WorkoutListView: View {
    @ObservedObject var workouts: WorkoutSync
    let maxHeartRate: Int

    var body: some View {
        List(workouts.records) { record in
            NavigationLink {
                WorkoutDetailView(record: record, workouts: workouts, maxHeartRate: maxHeartRate)
            } label: {
                WorkoutRow(record: record)
            }
        }
        .overlay {
            if workouts.records.isEmpty {
                ContentUnavailableView("No workouts yet", systemImage: "figure.run",
                                       description: Text("Workouts recorded with WristLink on the watch show up here once they sync."))
            }
        }
        .navigationTitle("Workouts")
    }
}

/// What the charts and tables need, worked out once from the track.
struct WorkoutAnalysis {

    /// A value at a point in the workout, minutes from the start.
    struct Sample: Identifiable {
        let index: Int
        let minutes: Double
        let value: Double
        var id: Int { index }
    }

    struct Split: Identifiable {
        let number: Int
        /// Meters covered: 1000 except for the last, partial one.
        let distance: Double
        let seconds: TimeInterval
        let heartRate: Double?
        /// Meters up (+) or down (−).
        let elevation: Double?
        var id: Int { number }
    }

    struct Zone: Identifiable {
        let number: Int
        let name: String
        let bpm: String
        let seconds: TimeInterval
        var id: Int { number }

        var color: Color {
            switch number {
            case 1: return .gray
            case 2: return .blue
            case 3: return .green
            case 4: return .orange
            default: return .red
            }
        }
    }

    let coordinates: [CLLocationCoordinate2D]
    let heartRate: [Sample]
    /// Minutes per km on foot, km/h on a bike.
    let pace: [Sample]
    let elevation: [Sample]
    let splits: [Split]
    let zones: [Zone]

    var hasCharts: Bool { !heartRate.isEmpty || !pace.isEmpty || !elevation.isEmpty }

    /// Zone bounds as a share of max HR, Samsung's five zones.
    private static let zoneBounds: [(name: String, low: Double, high: Double)] = [
        ("Low intensity", 0.50, 0.60),
        ("Weight control", 0.61, 0.70),
        ("Aerobic", 0.71, 0.80),
        ("Anaerobic", 0.81, 0.90),
        ("Maximum", 0.91, 1.00)
    ]

    init(record: WorkoutRecord, maxHeartRate: Int) {
        let points = record.points
        coordinates = points.compactMap(\.coordinate)

        // Charts don't need every 3-second point
        let step = max(1, points.count / 400)
        let thinned = stride(from: 0, to: points.count, by: step).map { points[$0] }

        heartRate = thinned.compactMap { point in
            guard let hr = point.heartRate, hr > 0 else { return nil }
            return Sample(index: point.index, minutes: point.minutes, value: hr)
        }
        elevation = thinned.compactMap { point in
            guard let altitude = point.altitude else { return nil }
            return Sample(index: point.index, minutes: point.minutes, value: altitude)
        }
        let showsSpeed = record.kind.showsSpeed
        let rawPace: [Sample] = thinned.compactMap { point in
            guard let speed = point.speed, speed > 0.3 else { return nil }
            let value = showsSpeed ? speed * 3.6 : min(30, 1000 / speed / 60)
            return Sample(index: point.index, minutes: point.minutes, value: value)
        }
        pace = Self.smoothed(rawPace)
        splits = Self.splits(points)
        zones = Self.zones(points, maxHeartRate: maxHeartRate)
    }

    /// A short moving average, so GPS jitter doesn't swamp the pace line.
    private static func smoothed(_ samples: [Sample], window: Int = 5) -> [Sample] {
        guard samples.count > window else { return samples }
        return samples.indices.map { i in
            let from = max(0, i - window / 2)
            let to = min(samples.count - 1, i + window / 2)
            let slice = samples[from...to]
            let mean = slice.reduce(0) { $0 + $1.value } / Double(slice.count)
            return Sample(index: samples[i].index, minutes: samples[i].minutes, value: mean)
        }
    }

    /// Per-kilometer splits, from the GPS positions where there are some, else from the speed.
    private static func splits(_ points: [TrackPoint]) -> [Split] {
        guard points.count >= 2 else { return [] }
        var result: [Split] = []
        var covered = 0.0
        var splitStart = points[0]
        var splitDistance = 0.0
        var hrSum = 0.0
        var hrCount = 0

        func close(at point: TrackPoint) {
            let elevation: Double? = {
                guard let a = splitStart.altitude, let b = point.altitude else { return nil }
                return b - a
            }()
            result.append(Split(
                number: result.count + 1,
                distance: splitDistance,
                seconds: point.date.timeIntervalSince(splitStart.date),
                heartRate: hrCount > 0 ? hrSum / Double(hrCount) : nil,
                elevation: elevation
            ))
            splitStart = point
            splitDistance = 0
            hrSum = 0
            hrCount = 0
        }

        for i in 1..<points.count {
            let previous = points[i - 1]
            let point = points[i]
            var step = 0.0
            if let a = previous.coordinate, let b = point.coordinate {
                step = CLLocation(latitude: a.latitude, longitude: a.longitude)
                    .distance(from: CLLocation(latitude: b.latitude, longitude: b.longitude))
            } else if let speed = point.speed, speed > 0 {
                step = speed * point.date.timeIntervalSince(previous.date)
            }
            // A GPS jump isn't distance covered
            if step > 200 { step = 0 }
            covered += step
            splitDistance += step
            if let hr = point.heartRate, hr > 0 {
                hrSum += hr
                hrCount += 1
            }
            if splitDistance >= 1000 {
                close(at: point)
            }
        }
        if splitDistance >= 100, let last = points.last {
            close(at: last)
        }
        return covered >= 1000 ? result : []
    }

    /// Time in each heart-rate zone (by share of max HR).
    private static func zones(_ points: [TrackPoint], maxHeartRate: Int) -> [Zone] {
        guard maxHeartRate > 0, points.count >= 2 else { return [] }
        var seconds = [TimeInterval](repeating: 0, count: zoneBounds.count)
        var any = false
        for i in 0..<(points.count - 1) {
            guard let hr = points[i].heartRate, hr > 0 else { continue }
            // A gap in the track (a pause) doesn't count as time in a zone
            let dt = min(10, points[i + 1].date.timeIntervalSince(points[i].date))
            guard dt > 0 else { continue }
            let share = hr / Double(maxHeartRate)
            guard let zone = zoneBounds.lastIndex(where: { share >= $0.low }) else { continue }
            seconds[zone] += dt
            any = true
        }
        guard any else { return [] }
        return zoneBounds.enumerated().map { index, bound in
            let low = Int((Double(maxHeartRate) * bound.low).rounded())
            let high = Int((Double(maxHeartRate) * bound.high).rounded())
            return Zone(number: index + 1, name: bound.name, bpm: "\(low)–\(high)", seconds: seconds[index])
        }
    }
}

/// One workout in full.
struct WorkoutDetailView: View {
    let record: WorkoutRecord
    @ObservedObject var workouts: WorkoutSync
    let maxHeartRate: Int

    @State private var analysis: WorkoutAnalysis?
    @State private var loaded = false

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                summaryCard
                if let football = record.summary, let sprints = football.sprints {
                    footballCard(football, sprints: sprints)
                }
                if let analysis {
                    if analysis.coordinates.count >= 2 {
                        RouteMap(coordinates: analysis.coordinates)
                    }
                    if !analysis.pace.isEmpty {
                        chartCard(record.kind.showsSpeed ? "Speed" : "Pace",
                                  icon: "speedometer", color: .blue,
                                  unit: record.kind.showsSpeed ? "km/h" : "min/km",
                                  samples: analysis.pace)
                    }
                    if !analysis.heartRate.isEmpty {
                        chartCard("Heart rate", icon: "heart.fill", color: .pink, unit: "bpm",
                                  samples: analysis.heartRate)
                    }
                    if !analysis.elevation.isEmpty {
                        chartCard("Elevation", icon: "mountain.2.fill", color: .green, unit: "m",
                                  samples: analysis.elevation, area: true)
                    }
                    if !analysis.splits.isEmpty {
                        splitsCard(analysis.splits)
                    }
                    lapsCard
                    zonesCard(analysis.zones)
                    if analysis.coordinates.count < 2 && !analysis.hasCharts {
                        EmptyCardState(icon: "point.topleft.down.to.point.bottomright.curvepath",
                                       text: "This workout has no track: no route, pace or heart-rate chart to show.")
                            .card()
                    }
                } else if loaded {
                    EmptyCardState(icon: "exclamationmark.triangle", text: "Couldn't open this workout's details.")
                        .card()
                } else {
                    ProgressView().padding(.top, 40)
                }
            }
            .padding()
        }
        .background(Theme.background)
        .navigationTitle(record.kind.title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard !loaded else { return }
            let full = workouts.full(record) ?? record
            analysis = WorkoutAnalysis(record: full, maxHeartRate: maxHeartRate)
            loaded = true
        }
    }

    /// Sprints, top speed and how the time split between walking, jogging, running and sprinting.
    private func footballCard(_ s: WorkoutRecord.Summary, sprints: Int) -> some View {
        let labels = ["Walk", "Jog", "Run", "Sprint"]
        let colors: [Color] = [.gray, .cyan, .green, .orange]
        let seconds = s.speedBands ?? []
        let meters = s.speedBandDistance ?? []
        let total = max(1, seconds.reduce(0, +))
        return VStack(alignment: .leading, spacing: 14) {
            Label("Match", systemImage: "figure.soccer")
                .font(.subheadline.weight(.semibold))
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 14) {
                Metric(value: "\(sprints)", label: "sprints")
                Metric(value: s.maxSpeed.map { String(format: "%.1f", $0 * 3.6) } ?? "–", label: "top km/h")
                Metric(value: s.longestSprint.map { Format.distance($0) } ?? "–", label: "longest sprint")
                Metric(value: s.sprintDistance.map { Format.distance($0) } ?? "–", label: "sprinting")
                Metric(value: s.highIntensityDistance.map { Format.distance($0) } ?? "–", label: "over 15 km/h")
                Metric(value: record.distance.map { Format.distance($0) } ?? "–", label: "total")
            }
            if !seconds.isEmpty {
                VStack(spacing: 6) {
                    ForEach(0..<min(labels.count, seconds.count), id: \.self) { i in
                        HStack(spacing: 8) {
                            Text(labels[i]).font(.caption).foregroundStyle(.secondary).frame(width: 46, alignment: .leading)
                            GeometryReader { geo in
                                Capsule().fill(Color.secondary.opacity(0.15))
                                    .overlay(alignment: .leading) {
                                        Capsule().fill(colors[i]).frame(width: geo.size.width * seconds[i] / total)
                                    }
                            }
                            .frame(height: 8)
                            Text(Format.elapsed(seconds[i])).font(.caption.monospacedDigit()).frame(width: 52, alignment: .trailing)
                            Text(i < meters.count ? Format.distance(meters[i]) : "").font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary).frame(width: 60, alignment: .trailing)
                        }
                    }
                }
            }
            Text("A sprint is a second or more at 20 km/h and up.")
                .font(.caption2).foregroundStyle(.secondary)
        }
        .card()
    }

    private var summaryCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: record.kind.symbol)
                    .font(.title2)
                    .foregroundStyle(.orange)
                    .frame(width: 48, height: 48)
                    .background(Color.orange.opacity(0.15), in: .circle)
                VStack(alignment: .leading, spacing: 2) {
                    Text(record.kind.title).font(.headline)
                    Text("\(record.startDate.formatted(date: .abbreviated, time: .shortened)) – \(Format.time(record.endDate))")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
            }
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 14) {
                Metric(value: Format.elapsed(record.duration), label: "time")
                Metric(value: record.distance.flatMap { $0 > 0 ? Format.distance($0) : nil } ?? "–", label: "distance")
                Metric(value: paceText, label: record.kind.showsSpeed ? "avg speed" : "avg pace")
                Metric(value: record.calories.map { Format.number($0) } ?? "–", label: "kcal")
                Metric(value: record.avgHr.map { "\(Int($0.rounded()))" } ?? "–", label: "avg bpm")
                Metric(value: record.maxHr.map { "\(Int($0.rounded()))" } ?? "–", label: "max bpm")
                Metric(value: record.elevationGain.map { "\(Int($0.rounded())) m" } ?? "–", label: "climbed")
                Metric(value: record.steps.flatMap { $0 > 0 ? Format.number($0) : nil } ?? "–", label: "steps")
            }
        }
        .card()
    }

    private var paceText: String {
        guard let distance = record.distance, distance > 0, record.duration > 0 else { return "–" }
        if record.kind.showsSpeed {
            return String(format: "%.1f km/h", distance / record.duration * 3.6)
        }
        return Format.pace(secondsPerKm: record.duration / (distance / 1000))
    }

    private func chartCard(_ title: String, icon: String, color: Color, unit: String,
                           samples: [WorkoutAnalysis.Sample], area: Bool = false) -> some View {
        let values = samples.map(\.value)
        let low = values.min() ?? 0
        let high = values.max() ?? 1
        let padding = max(1, (high - low) * 0.1)
        let average = values.isEmpty ? 0 : values.reduce(0, +) / Double(values.count)
        let detail: String
        switch unit {
        case "min/km": detail = "avg \(Format.pace(secondsPerKm: average * 60))"
        case "km/h": detail = String(format: "avg %.1f km/h", average)
        default: detail = "avg \(Int(average.rounded())) \(unit)"
        }
        return VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: title, icon: icon, color: color, detail: detail)
            Chart(samples) { sample in
                if area {
                    AreaMark(
                        x: .value("Minutes", sample.minutes),
                        yStart: .value("Base", low - padding),
                        yEnd: .value(title, sample.value)
                    )
                    .foregroundStyle(color.opacity(0.25))
                }
                LineMark(
                    x: .value("Minutes", sample.minutes),
                    y: .value(title, sample.value)
                )
                .foregroundStyle(color)
                .interpolationMethod(.catmullRom)
            }
            .chartYScale(domain: (low - padding)...(high + padding))
            .chartXAxisLabel("minutes")
            .frame(height: 150)
        }
        .card()
    }

    private func splitsCard(_ splits: [WorkoutAnalysis.Split]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            CardHeader(title: "Splits", icon: "flag.fill", color: .blue)
            HStack {
                Text("km").frame(width: 36, alignment: .leading)
                Text(record.kind.showsSpeed ? "speed" : "pace").frame(maxWidth: .infinity, alignment: .leading)
                Text("bpm").frame(width: 50, alignment: .trailing)
                Text("elev").frame(width: 56, alignment: .trailing)
            }
            .font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            ForEach(splits) { split in
                HStack {
                    Text(split.distance >= 999 ? "\(split.number)" : String(format: "%.2f", Double(split.number - 1) + split.distance / 1000))
                        .frame(width: 36, alignment: .leading)
                    Text(splitPace(split)).frame(maxWidth: .infinity, alignment: .leading)
                    Text(split.heartRate.map { "\(Int($0.rounded()))" } ?? "–").frame(width: 50, alignment: .trailing)
                    Text(split.elevation.map { String(format: "%+.0f m", $0) } ?? "–").frame(width: 56, alignment: .trailing)
                }
                .font(.subheadline.monospacedDigit())
            }
        }
        .card()
    }

    private func splitPace(_ split: WorkoutAnalysis.Split) -> String {
        guard split.distance > 0, split.seconds > 0 else { return "–" }
        if record.kind.showsSpeed {
            return String(format: "%.1f km/h", split.distance / split.seconds * 3.6)
        }
        return Format.pace(secondsPerKm: split.seconds / (split.distance / 1000))
    }

    @ViewBuilder private var lapsCard: some View {
        let laps = record.laps ?? []
        if !laps.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                CardHeader(title: "Laps", icon: "repeat", color: .teal)
                ForEach(Array(laps.enumerated()), id: \.offset) { index, lap in
                    HStack {
                        Text("\(lap.n ?? index + 1)").frame(width: 28, alignment: .leading)
                        Text(lap.d.map(Format.distance) ?? "–").frame(maxWidth: .infinity, alignment: .leading)
                        Text(lapTime(lap)).frame(width: 70, alignment: .trailing)
                        Text(lap.hr.map { "\(Int($0.rounded())) bpm" } ?? "–").frame(width: 70, alignment: .trailing)
                    }
                    .font(.subheadline.monospacedDigit())
                }
            }
            .card()
        }
    }

    private func lapTime(_ lap: WorkoutRecord.Lap) -> String {
        guard let s = lap.s, let e = lap.e, e > s else { return "–" }
        return Format.elapsed((e - s) / 1000)
    }

    private func zonesCard(_ zones: [WorkoutAnalysis.Zone]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Heart-rate zones", icon: "heart.text.square.fill", color: .red,
                       detail: "max \(maxHeartRate) bpm")
            if zones.isEmpty {
                EmptyCardState(icon: "heart.text.square", text: "No heart rate was recorded during this workout.")
            } else {
                let total = max(1, zones.reduce(0) { $0 + $1.seconds })
                ForEach(zones.reversed()) { zone in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text("Zone \(zone.number) · \(zone.name)").font(.subheadline)
                            Spacer()
                            Text(Format.elapsed(zone.seconds)).font(.subheadline.monospacedDigit())
                        }
                        GeometryReader { geometry in
                            ZStack(alignment: .leading) {
                                Capsule().fill(zone.color.opacity(0.15))
                                Capsule().fill(zone.color)
                                    .frame(width: max(4, geometry.size.width * zone.seconds / total))
                            }
                        }
                        .frame(height: 8)
                        Text("\(zone.bpm) bpm").font(.caption2).foregroundStyle(.secondary)
                    }
                }
                Text("Zones are shares of your max heart rate, set in Profile & goals.")
                    .font(.footnote).foregroundStyle(.tertiary)
            }
        }
        .card()
    }
}

/// The route on a map, start and finish marked.
private struct RouteMap: View {
    /// At least two points.
    let coordinates: [CLLocationCoordinate2D]

    var body: some View {
        Map(initialPosition: .automatic) {
            MapPolyline(coordinates: coordinates)
                .stroke(Color.orange, lineWidth: 4)
            Marker("Start", systemImage: "flag.fill", coordinate: coordinates[0])
                .tint(Color.green)
            Marker("Finish", systemImage: "flag.checkered", coordinate: coordinates[coordinates.count - 1])
                .tint(Color.red)
        }
        .frame(height: 260)
        .clipShape(.rect(cornerRadius: 22))
    }
}
