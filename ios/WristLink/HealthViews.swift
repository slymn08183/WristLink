import Charts
import SwiftUI

// The Health tab's cards, laid out like Samsung Health on a phone: today's rings, activity by
// hour, heart rate, sleep, workouts and the weekly report. The watch's own summary (protocol 6)
// comes first; what Apple Health has is the fallback for an older watch app or while the watch is
// away. Every card has a state for when there's nothing (yet) to show.

/// One day's numbers, from whichever source has them.
struct DayNumbers {
    var steps: Double?
    var activeMinutes: Double?
    var activeCalories: Double?
    var totalCalories: Double?
    /// Meters.
    var distance: Double?
    var floors: Double?
    /// Steps per hour from midnight; empty when unknown.
    var hourlySteps: [Double] = []
    var hourlyHeart: [HealthStats.HourRange] = []
    var restingHr: Double?
    /// From the watch's summary rather than Apple Health.
    var fromWatch = false

    var hasAny: Bool { steps != nil || activeMinutes != nil || activeCalories != nil }

    init(steps: Double?, activeMinutes: Double?, activeCalories: Double?, totalCalories: Double?,
         distance: Double?, floors: Double?, hourlySteps: [Double], hourlyHeart: [HealthStats.HourRange],
         restingHr: Double?, fromWatch: Bool) {
        self.steps = steps
        self.activeMinutes = activeMinutes
        self.activeCalories = activeCalories
        self.totalCalories = totalCalories
        self.distance = distance
        self.floors = floors
        self.hourlySteps = hourlySteps
        self.hourlyHeart = hourlyHeart
        self.restingHr = restingHr
        self.fromWatch = fromWatch
    }

    init(_ day: HealthSummary.Day) {
        let midnight = day.date ?? Calendar.current.startOfDay(for: Date())
        let lows = day.hourlyHrMin ?? []
        let highs = day.hourlyHrMax ?? []
        var heart: [HealthStats.HourRange] = []
        for hour in 0..<min(lows.count, highs.count, 24) {
            if let low = lows[hour], let high = highs[hour] {
                heart.append(HealthStats.HourRange(hour: midnight.addingTimeInterval(TimeInterval(hour * 3600)),
                                                   low: low, high: high))
            }
        }
        self.init(
            steps: day.steps,
            activeMinutes: day.activeMinutes,
            activeCalories: day.activeCalories,
            totalCalories: day.totalCalories,
            distance: day.distance,
            floors: day.floors,
            hourlySteps: (day.hourlySteps ?? []).map { $0 ?? 0 },
            hourlyHeart: heart,
            restingHr: day.restingHr,
            fromWatch: true
        )
    }
}

/// All the Health tab's cards.
struct HealthDashboard: View {
    @ObservedObject var summary: SummaryFeed
    @ObservedObject var workouts: WorkoutSync
    @EnvironmentObject private var stats: HealthStats
    let protocolVersion: Int
    let settingsProfile: WatchProfile?
    let latestHeartRate: WatchInfo.HeartRate?

    /// The settings carry the live profile (edited here or on the watch); the summary's copy is as
    /// old as the summary.
    private var profile: WatchProfile { settingsProfile ?? summary.latest?.profile ?? WatchProfile() }
    /// The watch is still answering the first request.
    private var collecting: Bool { summary.loading && summary.latest?.today == nil }

    private var today: DayNumbers {
        if let day = summary.latest?.today { return DayNumbers(day) }
        return DayNumbers(
            steps: stats.stepsToday,
            activeMinutes: nil,
            activeCalories: stats.activeCaloriesToday,
            totalCalories: nil,
            distance: stats.distanceToday,
            floors: stats.floorsToday,
            hourlySteps: stats.hourlySteps,
            hourlyHeart: stats.hourlyHeartRate,
            restingHr: stats.restingHeartRate,
            fromWatch: false
        )
    }

    private var latestReading: HeartRateCard.Reading? {
        if let bpm = latestHeartRate?.bpm, bpm > 0 {
            return HeartRateCard.Reading(bpm: bpm, date: latestHeartRate?.date)
        }
        if let bpm = stats.latestHeartRate {
            return HeartRateCard.Reading(bpm: bpm, date: stats.latestHeartRateDate)
        }
        return nil
    }

    private var restingBand: ClosedRange<Double>? {
        if let band = summary.latest?.restingBand { return band }
        guard summary.latest?.profile != nil || settingsProfile != nil else { return nil }
        let range = profile.restingRange
        return Double(range.lowerBound)...Double(range.upperBound)
    }

    var body: some View {
        let numbers = today
        VStack(spacing: 16) {
            TodayCard(numbers: numbers, profile: profile,
                      week: Array((summary.latest?.days ?? []).suffix(7)),
                      streak: summary.latest?.streak.map { Int($0) },
                      collecting: collecting)
            HourlyStepsCard(hourly: numbers.hourlySteps, collecting: collecting)
            HeartRateCard(hours: numbers.hourlyHeart, restingBand: restingBand,
                          restingHr: numbers.restingHr, latest: latestReading, collecting: collecting)
            sleepCard
            WorkoutsCard(workouts: workouts, supported: protocolVersion >= 6, maxHeartRate: profile.maxHeartRate)
            weeklyCard
        }
    }

    @ViewBuilder private var sleepCard: some View {
        if let latest = summary.latest, let night = latest.lastNight {
            NavigationLink {
                SleepDetailView(summary: latest, night: night, profile: profile)
            } label: {
                SleepCard(night: night, fallback: nil, collecting: false)
            }
            .buttonStyle(.plain)
        } else {
            let fallback = stats.sleepLastNight.map {
                SleepCard.Basic(asleep: $0, start: stats.sleepStart, end: stats.sleepEnd)
            }
            SleepCard(night: nil, fallback: fallback, collecting: collecting)
        }
    }

    @ViewBuilder private var weeklyCard: some View {
        let report = WeeklyReport(summary: summary.latest, workouts: workouts.records)
        if report.isEmpty {
            WeeklyReportCard(report: report, supported: protocolVersion >= 6)
        } else {
            NavigationLink {
                WeeklyReportView(report: report, profile: profile)
            } label: {
                WeeklyReportCard(report: report, supported: true)
            }
            .buttonStyle(.plain)
        }
    }
}

// MARK: - Shared pieces

/// A card's title row: a colored icon, the title, and an optional note or chevron on the right.
struct CardHeader: View {
    let title: String
    let icon: String
    let color: Color
    var detail: String? = nil
    var chevron = false

    var body: some View {
        HStack {
            Label {
                Text(title)
            } icon: {
                Image(systemName: icon).foregroundStyle(color)
            }
            .font(.subheadline.weight(.semibold))
            Spacer()
            if let detail {
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
            if chevron {
                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
            }
        }
    }
}

/// What a card shows when it has nothing to show yet.
struct EmptyCardState: View {
    let icon: String
    let text: String
    var busy = false

    var body: some View {
        VStack(spacing: 10) {
            if busy {
                ProgressView()
            } else {
                Image(systemName: icon).font(.title2).foregroundStyle(.tertiary)
            }
            Text(text)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, minHeight: 110)
    }
}

/// Colors for the watch's score bands (sleep and energy).
enum Band {
    static func color(_ band: String?) -> Color {
        switch band {
        case "Excellent": return .green
        case "Good": return .teal
        case "Fair": return .orange
        case "Attention": return .red
        default: return .secondary
        }
    }
}

private struct BandPill: View {
    let band: String

    var body: some View {
        Text(band)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(Band.color(band).opacity(0.15), in: .capsule)
            .foregroundStyle(Band.color(band))
    }
}

/// A value at a point in time, for the charts.
private struct TimedValue: Identifiable {
    let date: Date
    let value: Double
    var id: Date { date }
}

private enum ChartDay {
    static var start: Date { Calendar.current.startOfDay(for: Date()) }
    static var end: Date { start.addingTimeInterval(24 * 3600) }

    static func hours(_ values: [Double]) -> [TimedValue] {
        values.prefix(24).enumerated().map { hour, value in
            TimedValue(date: start.addingTimeInterval(TimeInterval(hour * 3600)), value: value)
        }
    }
}

// MARK: - Today

private struct TodayCard: View {
    let numbers: DayNumbers
    let profile: WatchProfile
    let week: [HealthSummary.Day]
    let streak: Int?
    let collecting: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            CardHeader(title: "Today", icon: "flame.fill", color: .green,
                       detail: numbers.hasAny ? (numbers.fromWatch ? "From the watch" : "From Apple Health") : nil)
            if numbers.hasAny {
                HStack {
                    Spacer()
                    ZStack {
                        ActivityRings(progress: [
                            progress(numbers.steps, profile.stepGoal),
                            progress(numbers.activeMinutes, profile.activeMinutesGoal),
                            progress(numbers.activeCalories, profile.activeCaloriesGoal)
                        ], colors: [.green, .cyan, .pink])
                        center
                    }
                    .frame(width: 190, height: 190)
                    Spacer()
                }
                VStack(spacing: 10) {
                    RingRow(color: .green, icon: "figure.walk", title: "Steps",
                            value: numbers.steps, goal: profile.stepGoal, unit: "")
                    RingRow(color: .cyan, icon: "timer", title: "Active time",
                            value: numbers.activeMinutes, goal: profile.activeMinutesGoal, unit: "min")
                    RingRow(color: .pink, icon: "flame", title: "Active calories",
                            value: numbers.activeCalories, goal: profile.activeCaloriesGoal, unit: "kcal")
                }
                HStack {
                    Metric(value: numbers.distance.map(Format.distance) ?? "–", label: "distance")
                    Divider().frame(height: 36)
                    Metric(value: numbers.floors.map(Format.number) ?? "–", label: "floors")
                    Divider().frame(height: 36)
                    Metric(value: numbers.totalCalories.map(Format.number) ?? "–", label: "total kcal")
                }
                if !week.isEmpty {
                    goalDots
                }
            } else {
                EmptyCardState(icon: "figure.walk",
                               text: collecting ? "Getting today's numbers from the watch…"
                                                : "Nothing yet today. The watch fills this in as you move.",
                               busy: collecting)
            }
        }
        .card()
    }

    private var center: some View {
        VStack(spacing: 0) {
            Text(numbers.steps.map(Format.number) ?? "–")
                .font(.title3.weight(.bold))
                .lineLimit(1).minimumScaleFactor(0.6)
            Text("/ \(Format.number(Double(profile.stepGoal)))")
                .font(.caption).foregroundStyle(.secondary)
            Text("steps").font(.caption2).foregroundStyle(.secondary)
        }
        .frame(width: 84)
    }

    private var goalDots: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Step goal this week").font(.caption).foregroundStyle(.secondary)
                Spacer()
                if let streak, streak > 0 {
                    Label("\(streak)-day streak", systemImage: "flame.fill")
                        .font(.caption.weight(.semibold)).foregroundStyle(.orange)
                }
            }
            HStack {
                ForEach(Array(week.enumerated()), id: \.offset) { _, day in
                    VStack(spacing: 4) {
                        Circle()
                            .fill(day.stepGoalMet == true ? Color.green : Color.gray.opacity(0.25))
                            .frame(width: 14, height: 14)
                        Text(day.date.map { $0.formatted(.dateTime.weekday(.narrow)) } ?? "–")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
        }
    }

    private func progress(_ value: Double?, _ goal: Int) -> Double {
        guard let value, goal > 0 else { return 0 }
        return value / Double(goal)
    }
}

/// Three concentric rings, outer first.
private struct ActivityRings: View {
    let progress: [Double]
    let colors: [Color]
    var lineWidth: CGFloat = 14

    var body: some View {
        ZStack {
            ForEach(Array(progress.enumerated()), id: \.offset) { index, value in
                RingTrack(progress: value, color: colors[index % colors.count], lineWidth: lineWidth)
                    .padding(CGFloat(index) * (lineWidth + 4))
            }
        }
    }
}

private struct RingTrack: View {
    let progress: Double
    let color: Color
    let lineWidth: CGFloat

    var body: some View {
        ZStack {
            Circle().stroke(color.opacity(0.18), lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: CGFloat(min(max(progress, 0), 1)))
                .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .padding(lineWidth / 2)
    }
}

private struct RingRow: View {
    let color: Color
    let icon: String
    let title: String
    let value: Double?
    let goal: Int
    let unit: String

    var body: some View {
        HStack {
            Image(systemName: icon)
                .font(.caption.weight(.bold))
                .foregroundStyle(color)
                .frame(width: 26, height: 26)
                .background(color.opacity(0.15), in: .circle)
            Text(title).font(.subheadline)
            Spacer()
            Text(value.map(Format.number) ?? "–").font(.subheadline.weight(.semibold))
            + Text(" / \(Format.number(Double(goal)))\(unit.isEmpty ? "" : " \(unit)")")
                .font(.subheadline).foregroundColor(.secondary)
        }
    }
}

// MARK: - Activity by hour

private struct HourlyStepsCard: View {
    let hourly: [Double]
    let collecting: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Activity by time of day", icon: "chart.bar.fill", color: .green)
            if hourly.contains(where: { $0 > 0 }) {
                Chart(ChartDay.hours(hourly)) { hour in
                    BarMark(
                        x: .value("Hour", hour.date, unit: .hour),
                        y: .value("Steps", hour.value)
                    )
                    .foregroundStyle(Color.green.gradient)
                    .cornerRadius(3)
                }
                .chartXScale(domain: ChartDay.start...ChartDay.end)
                .chartXAxis {
                    AxisMarks(values: .stride(by: .hour, count: 6)) { _ in
                        AxisGridLine()
                        AxisValueLabel(format: .dateTime.hour())
                    }
                }
                .frame(height: 140)
            } else {
                EmptyCardState(icon: "chart.bar",
                               text: collecting ? "Getting today's numbers from the watch…" : "No steps yet today.",
                               busy: collecting)
            }
        }
        .card()
    }
}

// MARK: - Heart rate

struct HeartRateCard: View {
    struct Reading {
        let bpm: Double
        let date: Date?
    }

    let hours: [HealthStats.HourRange]
    /// The resting range for the age and sex.
    let restingBand: ClosedRange<Double>?
    let restingHr: Double?
    let latest: Reading?
    let collecting: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Heart rate", icon: "heart.fill", color: .pink)
            if let latest {
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text("\(Int(latest.bpm.rounded()))")
                        .font(.system(size: 34, weight: .bold, design: .rounded))
                    Text("bpm").font(.subheadline).foregroundStyle(.secondary)
                    Spacer()
                    if let date = latest.date {
                        Text(date, format: .relative(presentation: .named))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            if hours.isEmpty {
                EmptyCardState(icon: "waveform.path.ecg",
                               text: collecting ? "Getting today's heart rate from the watch…"
                                                : "No heart rate yet today. The watch measures it by itself while you wear it.",
                               busy: collecting)
            } else {
                chart
                HStack(spacing: 16) {
                    if let restingBand {
                        legend(color: .pink.opacity(0.25),
                               text: "Resting range \(Int(restingBand.lowerBound))–\(Int(restingBand.upperBound))")
                    }
                    if let restingHr {
                        legend(color: .secondary, text: "Resting \(Int(restingHr.rounded())) bpm")
                    }
                }
                if let verdict {
                    Text(verdict).font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .card()
    }

    private var chart: some View {
        let lowest = hours.map(\.low).min() ?? 60
        let highest = hours.map(\.high).max() ?? 120
        let floor = min(40, (lowest - 5).rounded(.down))
        let top = min(220, max(120, (highest + 10).rounded(.up)))
        return Chart {
            if let band = restingBand {
                RectangleMark(
                    xStart: .value("From", ChartDay.start),
                    xEnd: .value("To", ChartDay.end),
                    yStart: .value("Low", band.lowerBound),
                    yEnd: .value("High", band.upperBound)
                )
                .foregroundStyle(Color.pink.opacity(0.12))
            }
            ForEach(hours) { hour in
                BarMark(
                    x: .value("Hour", hour.hour, unit: .hour),
                    yStart: .value("Lowest", hour.low),
                    yEnd: .value("Highest", max(hour.high, hour.low + 1)),
                    width: .ratio(0.5)
                )
                .foregroundStyle(Color.pink.gradient)
                .cornerRadius(3)
            }
            if let restingHr {
                RuleMark(y: .value("Resting", restingHr))
                    .foregroundStyle(Color.secondary)
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
            }
        }
        .chartXScale(domain: ChartDay.start...ChartDay.end)
        .chartYScale(domain: floor...top)
        .chartXAxis {
            AxisMarks(values: .stride(by: .hour, count: 6)) { _ in
                AxisGridLine()
                AxisValueLabel(format: .dateTime.hour())
            }
        }
        .frame(height: 170)
    }

    private var verdict: String? {
        guard let restingHr, let restingBand else { return nil }
        if restingHr < restingBand.lowerBound { return "Your resting heart rate is lower than the usual range for your age." }
        if restingHr > restingBand.upperBound { return "Your resting heart rate is higher than the usual range for your age." }
        return "Your resting heart rate is within the usual range for your age."
    }

    private func legend(color: Color, text: String) -> some View {
        HStack(spacing: 6) {
            RoundedRectangle(cornerRadius: 2).fill(color).frame(width: 12, height: 8)
            Text(text).font(.caption).foregroundStyle(.secondary)
        }
    }
}

// MARK: - Sleep

private struct SleepCard: View {
    /// Apple Health's view of last night, when the watch's summary has none.
    struct Basic {
        let asleep: TimeInterval
        let start: Date?
        let end: Date?
    }

    let night: HealthSummary.Night?
    let fallback: Basic?
    let collecting: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Sleep", icon: "bed.double.fill", color: .indigo, chevron: night != nil)
            if let night {
                scored(night)
            } else if let fallback {
                HStack(alignment: .firstTextBaseline) {
                    Text(Format.duration(fallback.asleep)).font(.title2.weight(.bold))
                    Text("asleep").foregroundStyle(.secondary)
                    Spacer()
                }
                if let start = fallback.start, let end = fallback.end {
                    times(start, end)
                }
                Text("The sleep score comes with the latest WristLink on the watch.")
                    .font(.footnote).foregroundStyle(.tertiary)
            } else {
                EmptyCardState(icon: "moon.zzz",
                               text: collecting ? "Getting last night from the watch…"
                                                : "No sleep recorded last night. Wear the watch to bed; it notices when you fall asleep.",
                               busy: collecting)
            }
        }
        .card()
    }

    private func scored(_ night: HealthSummary.Night) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 16) {
                if let score = night.score {
                    VStack(spacing: 2) {
                        Text("\(Int(score.rounded()))")
                            .font(.system(size: 40, weight: .bold, design: .rounded))
                            .foregroundStyle(Band.color(night.band))
                        Text("score").font(.caption2).foregroundStyle(.secondary)
                    }
                }
                VStack(alignment: .leading, spacing: 4) {
                    if let band = night.band { BandPill(band: band) }
                    Text("\(night.asleepMin.map(Format.minutes) ?? "–") asleep")
                        .font(.headline)
                    Text("\(night.inBedMin.map(Format.minutes) ?? "–") in bed")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
            }
            if night.score == nil {
                Text("Too short to score: the score needs at least an hour asleep.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            if let start = night.startDate, let end = night.endDate {
                times(start, end)
            }
        }
    }

    private func times(_ start: Date, _ end: Date) -> some View {
        HStack(spacing: 16) {
            Label(Format.time(start), systemImage: "bed.double")
            Label(Format.time(end), systemImage: "alarm")
            Spacer()
        }
        .font(.subheadline).foregroundStyle(.secondary)
    }
}

/// Last night in full: the score, the time breakdown, movement through the night, sleeping heart
/// rate, the week, and the sleep animal.
struct SleepDetailView: View {
    let summary: HealthSummary
    let night: HealthSummary.Night
    let profile: WatchProfile

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                scoreCard
                breakdownCard
                movementCard
                stagesNote
                heartCard
                weekCard
                animalCard
            }
            .padding()
        }
        .background(Theme.background)
        .navigationTitle("Sleep")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var scoreCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center, spacing: 16) {
                Text(night.score.map { "\(Int($0.rounded()))" } ?? "–")
                    .font(.system(size: 54, weight: .bold, design: .rounded))
                    .foregroundStyle(Band.color(night.band))
                VStack(alignment: .leading, spacing: 4) {
                    if let band = night.band { BandPill(band: band) }
                    Text("Sleep score").font(.headline)
                }
                Spacer()
            }
            Text(night.score == nil
                 ? "Too short to score: the score needs at least an hour asleep."
                 : "WristLink's own score from how long, how efficiently and how regularly you slept, and when. It isn't Samsung's score: this watch doesn't measure sleep stages.")
                .font(.footnote).foregroundStyle(.secondary)
        }
        .card()
    }

    private var breakdownCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Time", icon: "clock.fill", color: .indigo)
            if let start = night.startDate, let end = night.endDate {
                HStack {
                    Metric(value: Format.time(start), label: "bedtime")
                    Divider().frame(height: 36)
                    Metric(value: Format.time(end), label: "woke up")
                }
            }
            HStack {
                Metric(value: night.inBedMin.map(Format.minutes) ?? "–", label: "in bed")
                Divider().frame(height: 36)
                Metric(value: night.asleepMin.map(Format.minutes) ?? "–", label: "asleep")
                Divider().frame(height: 36)
                Metric(value: night.awakeMin.map(Format.minutes) ?? "–", label: "awake")
                Divider().frame(height: 36)
                Metric(value: night.efficiency.map { "\(Int(($0 * 100).rounded()))%" } ?? "–", label: "efficiency")
            }
        }
        .card()
    }

    private struct Gap: Identifiable {
        let start: Date
        let end: Date
        var id: Date { start }
    }

    private var gaps: [Gap] {
        (night.gaps ?? []).compactMap { pair in
            guard pair.count >= 2, let s = pair[0], let e = pair[1], e > s else { return nil }
            return Gap(start: HealthSummary.date(s), end: HealthSummary.date(e))
        }
    }

    private var movement: [TimedValue] {
        guard let start = night.startDate else { return [] }
        return (night.movement ?? []).enumerated().map { index, value in
            TimedValue(date: start.addingTimeInterval(TimeInterval(index * 600)), value: value ?? 0)
        }
    }

    private var movementCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Movement", icon: "waveform", color: .indigo)
            let buckets = movement
            if buckets.isEmpty && gaps.isEmpty {
                EmptyCardState(icon: "waveform", text: "No movement recorded for this night.")
            } else {
                let top = max(10, buckets.map(\.value).max() ?? 10)
                Chart {
                    ForEach(gaps) { gap in
                        RectangleMark(
                            xStart: .value("Awake from", gap.start),
                            xEnd: .value("Awake to", gap.end),
                            yStart: .value("Bottom", 0.0),
                            yEnd: .value("Top", top)
                        )
                        .foregroundStyle(Color.orange.opacity(0.25))
                    }
                    ForEach(buckets) { bucket in
                        BarMark(
                            x: .value("Time", bucket.date),
                            y: .value("Steps", bucket.value),
                            width: .fixed(3)
                        )
                        .foregroundStyle(Color.indigo)
                    }
                }
                .chartYScale(domain: 0...top)
                .chartYAxis(.hidden)
                .chartXAxis {
                    AxisMarks(values: .stride(by: .hour, count: 2)) { _ in
                        AxisGridLine()
                        AxisValueLabel(format: .dateTime.hour())
                    }
                }
                .frame(height: 120)
                HStack(spacing: 16) {
                    Label("Movement", systemImage: "square.fill").foregroundStyle(Color.indigo)
                    Label("Awake", systemImage: "square.fill").foregroundStyle(Color.orange.opacity(0.5))
                }
                .font(.caption)
            }
        }
        .card()
    }

    private var stagesNote: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "info.circle.fill").foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 4) {
                Text("Sleep stages aren't measured on this watch").font(.subheadline.weight(.semibold))
                Text("Light, deep and REM sleep need Samsung's own sensor access, which other apps don't get. You see time asleep, awake stretches and movement instead.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .card()
    }

    private var heartCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Sleeping heart rate", icon: "heart.fill", color: .pink)
            if night.avgHr == nil && night.minHr == nil {
                EmptyCardState(icon: "heart", text: "No heart rate was measured during this night.")
            } else {
                HStack {
                    Metric(value: night.avgHr.map { "\(Int($0.rounded())) bpm" } ?? "–", label: "average")
                    Divider().frame(height: 36)
                    Metric(value: night.minHr.map { "\(Int($0.rounded())) bpm" } ?? "–", label: "lowest")
                }
            }
        }
        .card()
    }

    private struct NightBar: Identifiable {
        let date: Date
        let hours: Double
        let metGoal: Bool
        var id: Date { date }
    }

    private var nights: [NightBar] {
        let goal = Double(profile.sleepGoalMinutes)
        return (summary.sleep ?? []).compactMap { night in
            guard let end = night.endDate, let asleep = night.asleepMin else { return nil }
            return NightBar(date: Calendar.current.startOfDay(for: end), hours: asleep / 60,
                            metGoal: asleep >= goal - 30)
        }
    }

    private var weekCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "This week", icon: "calendar", color: .indigo)
            let bars = nights
            if bars.count < 2 {
                EmptyCardState(icon: "calendar", text: "Collecting: the week fills in after a few nights.")
            } else {
                let goalHours = Double(profile.sleepGoalMinutes) / 60
                let met = bars.filter { $0.metGoal }.count
                let goal = Format.minutes(Double(profile.sleepGoalMinutes))
                Chart {
                    ForEach(bars) { bar in
                        BarMark(
                            x: .value("Night", bar.date, unit: .day),
                            y: .value("Hours", bar.hours)
                        )
                        .foregroundStyle(bar.metGoal ? Color.indigo : Color.indigo.opacity(0.4))
                        .cornerRadius(4)
                    }
                    RuleMark(y: .value("Goal", goalHours))
                        .foregroundStyle(Color.secondary)
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                }
                .chartXAxis {
                    AxisMarks(values: .stride(by: .day)) { _ in
                        AxisValueLabel(format: .dateTime.weekday(.narrow))
                    }
                }
                .frame(height: 130)
                Text("\(met) of \(bars.count) nights near your \(goal) goal")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .card()
    }

    private var animalCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Your sleep animal", icon: "pawprint.fill", color: .brown)
            if let animal = summary.animal, let name = animal.name {
                Text(name).font(.title2.weight(.bold))
                Text(Self.describe(animal)).font(.subheadline).foregroundStyle(.secondary)
                HStack(spacing: 8) {
                    trait("Long enough", ok: animal.short != true)
                    trait("Few wake-ups", ok: animal.wakes != true)
                    trait("Regular", ok: animal.irregular != true)
                }
            } else {
                EmptyCardState(icon: "pawprint", text: "Collecting: your sleep animal shows up after three nights.")
            }
        }
        .card()
    }

    private func trait(_ text: String, ok: Bool) -> some View {
        Label(text, systemImage: ok ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
            .font(.caption.weight(.medium))
            .foregroundStyle(ok ? Color.green : Color.orange)
    }

    static func describe(_ animal: HealthSummary.Animal) -> String {
        var problems: [String] = []
        if animal.short == true { problems.append("sleep on the short side") }
        if animal.wakes == true { problems.append("waking up often") }
        if animal.irregular == true { problems.append("irregular sleep times") }
        if problems.isEmpty { return "Long, steady sleep at regular times." }
        return "Lately: " + problems.joined(separator: ", ") + "."
    }
}

// MARK: - Workouts

private struct WorkoutsCard: View {
    @ObservedObject var workouts: WorkoutSync
    let supported: Bool
    let maxHeartRate: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Workouts", icon: "figure.run", color: .orange,
                       detail: workouts.syncing ? "Syncing…" : nil)
            if workouts.records.isEmpty {
                EmptyCardState(icon: "figure.run",
                               text: supported
                                ? "No workouts yet. Start one in WristLink on the watch; it shows up here with its route once it syncs."
                                : "Workouts need the latest WristLink on the watch.",
                               busy: workouts.syncing)
            } else {
                ForEach(workouts.records.prefix(3)) { record in
                    NavigationLink {
                        WorkoutDetailView(record: record, workouts: workouts, maxHeartRate: maxHeartRate)
                    } label: {
                        WorkoutRow(record: record)
                    }
                    .buttonStyle(.plain)
                }
                if workouts.records.count > 3 {
                    NavigationLink {
                        WorkoutListView(workouts: workouts, maxHeartRate: maxHeartRate)
                    } label: {
                        Text("All workouts").font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity)
                    }
                }
            }
            if let error = workouts.lastError {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote).foregroundStyle(.orange)
            }
        }
        .card()
    }
}

// MARK: - Weekly report

/// The last seven days at a glance, from the watch's summary and the workouts kept here.
struct WeeklyReport {
    let days: [HealthSummary.Day]
    let nights: [HealthSummary.Night]
    let workouts: [WorkoutRecord]
    let energy: HealthSummary.Energy?
    let streak: Int?

    init(summary: HealthSummary?, workouts: [WorkoutRecord]) {
        days = Array((summary?.days ?? []).suffix(7))
        nights = Array((summary?.sleep ?? []).suffix(7))
        let weekAgo = Date().addingTimeInterval(-7 * 24 * 3600)
        self.workouts = workouts.filter { $0.startDate >= weekAgo }
        energy = summary?.energy
        streak = summary?.streak.map { Int($0) }
    }

    var isEmpty: Bool { days.isEmpty && nights.isEmpty && workouts.isEmpty }

    var averageSteps: Double? { Self.average(days.compactMap(\.steps)) }
    var averageDistance: Double? { Self.average(days.compactMap(\.distance)) }
    var averageActiveCalories: Double? { Self.average(days.compactMap(\.activeCalories)) }
    var averageTotalCalories: Double? { Self.average(days.compactMap(\.totalCalories)) }
    var averageSleepMinutes: Double? { Self.average(nights.compactMap(\.asleepMin)) }
    var workoutTime: TimeInterval { workouts.reduce(0) { $0 + $1.duration } }

    /// Average wake-up time, minutes after midnight.
    var averageWake: Int? {
        let minutes = nights.compactMap(\.endDate).map(Self.minutesAfterMidnight)
        return Self.average(minutes).map { Int($0.rounded()) }
    }

    /// Average bedtime, minutes after midnight; times after midnight count as late, not early.
    var averageBedtime: Int? {
        let minutes = nights.compactMap(\.startDate).map(Self.minutesAfterMidnight).map { $0 < 720 ? $0 + 1440 : $0 }
        return Self.average(minutes).map { Int($0.rounded()) % 1440 }
    }

    private static func minutesAfterMidnight(_ date: Date) -> Double {
        let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
        return Double((parts.hour ?? 0) * 60 + (parts.minute ?? 0))
    }

    private static func average(_ values: [Double]) -> Double? {
        values.isEmpty ? nil : values.reduce(0, +) / Double(values.count)
    }
}

private struct WeeklyReportCard: View {
    let report: WeeklyReport
    let supported: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Weekly report", icon: "doc.text.magnifyingglass", color: .purple, chevron: !report.isEmpty)
            if report.isEmpty {
                EmptyCardState(icon: "calendar",
                               text: supported ? "Collecting: your first report shows up after a few days."
                                               : "The weekly report needs the latest WristLink on the watch.")
            } else {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                    EnergyTile(energy: report.energy)
                    tile("Avg sleep", report.averageSleepMinutes.map(Format.minutes))
                    tile("Avg steps", report.averageSteps.map(Format.number))
                    tile("Workouts", report.workouts.isEmpty ? "None" : Format.minutes(report.workoutTime / 60))
                }
            }
        }
        .card()
    }

    private func tile(_ title: String, _ value: String?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(value ?? "–").font(.title3.weight(.bold)).lineLimit(1).minimumScaleFactor(0.6)
            Text(title).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct EnergyTile: View {
    let energy: HealthSummary.Energy?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(energy?.score.map { "\(Int($0.rounded()))" } ?? "–")
                    .font(.title3.weight(.bold))
                    .foregroundStyle(Band.color(energy?.band))
                if let band = energy?.band {
                    Text(band).font(.caption2).foregroundStyle(.secondary)
                }
            }
            Text(energy?.reduced == false ? "Energy score" : "Energy score (reduced)")
                .font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The weekly report in full.
struct WeeklyReportView: View {
    let report: WeeklyReport
    let profile: WatchProfile

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                energyCard
                sleepCard
                activityCard
                workoutsCard
            }
            .padding()
        }
        .background(Theme.background)
        .navigationTitle("Last 7 days")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var energyCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            CardHeader(title: "Energy score", icon: "bolt.heart.fill", color: .yellow)
            if let score = report.energy?.score {
                HStack(spacing: 12) {
                    Text("\(Int(score.rounded()))")
                        .font(.system(size: 44, weight: .bold, design: .rounded))
                        .foregroundStyle(Band.color(report.energy?.band))
                    if let band = report.energy?.band { BandPill(band: band) }
                    Spacer()
                }
                Text("A reduced score: it's computed from your sleep and activity without heart-rate variability (HRV), which this watch doesn't give other apps.")
                    .font(.footnote).foregroundStyle(.secondary)
            } else {
                EmptyCardState(icon: "bolt.heart", text: "Collecting: the energy score needs last night's sleep.")
            }
        }
        .card()
    }

    private var sleepCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Sleep", icon: "bed.double.fill", color: .indigo)
            if report.nights.isEmpty {
                EmptyCardState(icon: "moon.zzz", text: "No nights recorded this week.")
            } else {
                HStack {
                    Metric(value: report.averageSleepMinutes.map(Format.minutes) ?? "–", label: "avg asleep")
                    Divider().frame(height: 36)
                    Metric(value: report.averageBedtime.map { Format.clock(minutesAfterMidnight: $0) } ?? "–", label: "avg bedtime")
                    Divider().frame(height: 36)
                    Metric(value: report.averageWake.map { Format.clock(minutesAfterMidnight: $0) } ?? "–", label: "avg wake-up")
                }
            }
        }
        .card()
    }

    private struct DayBar: Identifiable {
        let date: Date
        let steps: Double
        var id: Date { date }
    }

    private var activityCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Activity", icon: "figure.walk", color: .green)
            if report.days.isEmpty {
                EmptyCardState(icon: "figure.walk", text: "No activity recorded this week.")
            } else {
                HStack {
                    Metric(value: report.averageSteps.map(Format.number) ?? "–", label: "avg steps")
                    Divider().frame(height: 36)
                    Metric(value: report.averageDistance.map(Format.distance) ?? "–", label: "avg distance")
                    Divider().frame(height: 36)
                    Metric(value: report.averageActiveCalories.map(Format.number) ?? "–", label: "avg active kcal")
                }
                let bars = report.days.compactMap { day -> DayBar? in
                    guard let date = day.date else { return nil }
                    return DayBar(date: date, steps: day.steps ?? 0)
                }
                if !bars.isEmpty {
                    Chart {
                        ForEach(bars) { bar in
                            BarMark(
                                x: .value("Day", bar.date, unit: .day),
                                y: .value("Steps", bar.steps)
                            )
                            .foregroundStyle(Theme.gradient)
                            .cornerRadius(6)
                        }
                        RuleMark(y: .value("Goal", Double(profile.stepGoal)))
                            .foregroundStyle(Color.secondary)
                            .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                    }
                    .chartXAxis {
                        AxisMarks(values: .stride(by: .day)) { _ in
                            AxisValueLabel(format: .dateTime.weekday(.narrow))
                        }
                    }
                    .frame(height: 150)
                }
                if let total = report.averageTotalCalories {
                    Text("About \(Format.number(total)) kcal burned a day in total, resting included.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                if let streak = report.streak, streak > 0 {
                    Label("\(streak)-day step goal streak", systemImage: "flame.fill")
                        .font(.subheadline.weight(.semibold)).foregroundStyle(.orange)
                }
            }
        }
        .card()
    }

    private var workoutsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Workouts", icon: "figure.run", color: .orange)
            if report.workouts.isEmpty {
                EmptyCardState(icon: "figure.run", text: "No workouts this week.")
            } else {
                HStack {
                    Metric(value: "\(report.workouts.count)", label: report.workouts.count == 1 ? "workout" : "workouts")
                    Divider().frame(height: 36)
                    Metric(value: Format.minutes(report.workoutTime / 60), label: "total time")
                    Divider().frame(height: 36)
                    Metric(value: Format.distance(report.workouts.compactMap(\.distance).reduce(0, +)), label: "distance")
                }
            }
        }
        .card()
    }
}
