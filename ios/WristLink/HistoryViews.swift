import Charts
import HealthKit
import SwiftUI

// The history screens behind the Health tab's cards: activity, heart rate and sleep over a day, a
// week or a month, going back as far as this app has kept the watch's summaries. A day's fine
// detail (every heart-rate reading, steps per 15 minutes) comes from Apple Health, where the
// watch's samples are synced to.

// MARK: - Period

enum HistoryRange: String, CaseIterable, Identifiable {
    case day = "Day"
    case week = "Week"
    case month = "Month"

    var id: String { rawValue }

    var days: Int {
        switch self {
        case .day: return 1
        case .week: return 7
        case .month: return 30
        }
    }
}

/// What a history screen shows: a range ending on the anchor's day.
struct HistoryPeriod: Equatable {
    var range: HistoryRange
    var anchor = Date()

    var interval: DateInterval {
        let calendar = Calendar.current
        let day = calendar.startOfDay(for: anchor)
        let end = calendar.date(byAdding: .day, value: 1, to: day) ?? day.addingTimeInterval(86400)
        let start = calendar.date(byAdding: .day, value: -(range.days - 1), to: day) ?? day
        return DateInterval(start: start, end: end)
    }

    var isCurrent: Bool { Calendar.current.isDateInToday(anchor) }

    /// Every day in the period, oldest first.
    var dayStarts: [Date] {
        let calendar = Calendar.current
        let start = interval.start
        return (0..<range.days).compactMap { calendar.date(byAdding: .day, value: $0, to: start) }
    }

    mutating func step(_ direction: Int) {
        let moved = Calendar.current.date(byAdding: .day, value: direction * range.days, to: anchor) ?? anchor
        anchor = min(moved, Date())
    }

    var title: String {
        let calendar = Calendar.current
        if range == .day {
            if calendar.isDateInToday(anchor) { return "Today" }
            if calendar.isDateInYesterday(anchor) { return "Yesterday" }
            return anchor.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))
        }
        let last = interval.end.addingTimeInterval(-1)
        let from = interval.start.formatted(.dateTime.day().month(.abbreviated))
        let to = last.formatted(.dateTime.day().month(.abbreviated))
        return "\(from) – \(to)"
    }
}

/// Day / Week / Month and the arrows to go back and forth.
struct PeriodPicker: View {
    @Binding var period: HistoryPeriod
    var ranges: [HistoryRange] = HistoryRange.allCases

    var body: some View {
        VStack(spacing: 12) {
            Picker("Range", selection: $period.range) {
                ForEach(ranges) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            HStack {
                Button { period.step(-1) } label: {
                    Image(systemName: "chevron.left").frame(width: 28, height: 28)
                }
                Spacer()
                Text(period.title).font(.headline)
                Spacer()
                Button { period.step(1) } label: {
                    Image(systemName: "chevron.right").frame(width: 28, height: 28)
                }
                .disabled(period.isCurrent)
            }
            .buttonStyle(.bordered)
            .buttonBorderShape(.circle)
        }
        .card()
    }
}

/// The value box shown above a tapped bar or point.
struct SelectionBubble: View {
    let title: String
    let lines: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption2).foregroundStyle(.secondary)
            ForEach(lines, id: \.self) { Text($0).font(.caption.weight(.semibold)) }
        }
        .padding(.horizontal, 8).padding(.vertical, 6)
        .background(Theme.card, in: .rect(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.secondary.opacity(0.25)))
    }
}

private let bubbleOverflow = AnnotationOverflowResolution(x: .fit(to: .chart), y: .disabled)

private func sameDay(_ a: Date, _ b: Date) -> Bool { Calendar.current.isDate(a, inSameDayAs: b) }

private func average(_ values: [Double]) -> Double? {
    values.isEmpty ? nil : values.reduce(0, +) / Double(values.count)
}

// MARK: - Activity

enum ActivityMetric: String, CaseIterable, Identifiable {
    case steps = "Steps"
    case activeTime = "Active time"
    case activeCalories = "Active kcal"
    case distance = "Distance"
    case floors = "Floors"

    var id: String { rawValue }

    var color: Color {
        switch self {
        case .steps: return .green
        case .activeTime: return .cyan
        case .activeCalories: return .pink
        case .distance: return .blue
        case .floors: return .orange
        }
    }

    func value(_ day: HealthSummary.Day) -> Double? {
        switch self {
        case .steps: return day.steps
        case .activeTime: return day.activeMinutes
        case .activeCalories: return day.activeCalories
        case .distance: return day.distance
        case .floors: return day.floors
        }
    }

    func goal(_ profile: WatchProfile) -> Double? {
        switch self {
        case .steps: return Double(profile.stepGoal)
        case .activeTime: return Double(profile.activeMinutesGoal)
        case .activeCalories: return Double(profile.activeCaloriesGoal)
        case .distance, .floors: return nil
        }
    }

    func format(_ value: Double) -> String {
        switch self {
        case .steps, .floors: return Format.number(value)
        case .activeTime: return Format.minutes(value)
        case .activeCalories: return "\(Format.number(value)) kcal"
        case .distance: return Format.distance(value)
        }
    }

    /// Where Apple Health keeps it, for the day view's 15-minute detail. Active time is the
    /// watch's own number and isn't there.
    var healthKit: (id: HKQuantityTypeIdentifier, unit: HKUnit)? {
        switch self {
        case .steps: return (.stepCount, .count())
        case .activeCalories: return (.activeEnergyBurned, .kilocalorie())
        case .distance: return (.distanceWalkingRunning, .meter())
        case .floors: return (.flightsClimbed, .count())
        case .activeTime: return nil
        }
    }
}

/// Steps, active time, calories, distance and floors: a day in 15-minute bars, or a week or a
/// month in daily bars against the goal.
struct ActivityHistoryView: View {
    @ObservedObject var feed: SummaryFeed
    let profile: WatchProfile
    @EnvironmentObject private var stats: HealthStats

    @State private var period = HistoryPeriod(range: .week)
    @State private var metric: ActivityMetric = .steps
    @State private var selected: Date?
    @State private var buckets: [HealthStats.Bucket] = []
    @State private var loadingBuckets = false

    private struct DayValue: Identifiable {
        let date: Date
        let value: Double?
        var id: Date { date }
    }

    private var dayValues: [DayValue] {
        period.dayStarts.map { start in DayValue(date: start, value: feed.day(for: start).flatMap(metric.value)) }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                PeriodPicker(period: $period)
                metricPicker
                if period.range == .day {
                    dayChart
                    dayTotals
                } else {
                    rangeChart
                    rangeStats
                    dayList
                }
            }
            .padding()
        }
        .background(Theme.background)
        .navigationTitle("Activity")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: period) { selected = nil }
        .onChange(of: metric) { selected = nil }
        .task(id: "\(period.range.rawValue)|\(period.interval.start.timeIntervalSince1970)|\(metric.rawValue)") {
            await loadBuckets()
        }
    }

    private var metricPicker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(ActivityMetric.allCases) { m in
                    Button { metric = m } label: {
                        Text(m.rawValue)
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 14).padding(.vertical, 8)
                            .background(metric == m ? m.color.opacity(0.2) : Theme.card, in: .capsule)
                            .foregroundStyle(metric == m ? m.color : .primary)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private func loadBuckets() async {
        guard period.range == .day, let type = metric.healthKit else {
            buckets = []
            return
        }
        loadingBuckets = true
        let interval = period.interval
        buckets = await stats.buckets(type.id, unit: type.unit, from: interval.start, to: interval.end, minutes: 15)
        loadingBuckets = false
    }

    // MARK: Day

    /// Apple Health's 15-minute buckets, or the watch's hourly steps when Health has none.
    private var dayBars: (bars: [HealthStats.Bucket], minutes: Int) {
        if buckets.contains(where: { $0.value > 0 }) { return (buckets, 15) }
        guard metric == .steps, let day = feed.day(for: period.anchor), let start = day.date else { return ([], 60) }
        let hourly = (day.hourlySteps ?? []).prefix(24).enumerated().map { hour, value in
            HealthStats.Bucket(start: start.addingTimeInterval(TimeInterval(hour * 3600)), value: value ?? 0)
        }
        return (hourly, 60)
    }

    private var dayChart: some View {
        VStack(alignment: .leading, spacing: 12) {
            let detail = dayBars
            let bars = detail.bars
            let minutes = detail.minutes
            CardHeader(title: "\(metric.rawValue) through the day", icon: "chart.bar.fill", color: metric.color,
                       detail: bars.isEmpty ? nil : "per \(minutes == 15 ? "15 minutes" : "hour")")
            if bars.contains(where: { $0.value > 0 }) {
                let interval = period.interval
                let picked = selected.flatMap { s in bars.last { $0.start <= s } }
                Chart {
                    ForEach(bars) { bar in
                        BarMark(
                            x: .value("Time", bar.start, unit: minutes == 15 ? .minute : .hour),
                            y: .value(metric.rawValue, bar.value),
                            width: .ratio(0.8)
                        )
                        .foregroundStyle(metric.color.opacity(picked == nil || picked?.id == bar.id ? 1 : 0.35))
                        .cornerRadius(2)
                    }
                    if let picked {
                        RuleMark(x: .value("Selected", picked.start.addingTimeInterval(TimeInterval(minutes * 30))))
                            .foregroundStyle(Color.secondary.opacity(0.3))
                            .annotation(position: .top, spacing: 4, overflowResolution: bubbleOverflow) {
                                SelectionBubble(
                                    title: "\(Format.time(picked.start))–\(Format.time(picked.start.addingTimeInterval(TimeInterval(minutes * 60))))",
                                    lines: [metric.format(picked.value)]
                                )
                            }
                    }
                }
                .chartXScale(domain: interval.start...interval.end)
                .chartXSelection(value: $selected)
                .chartXAxis {
                    AxisMarks(values: .stride(by: .hour, count: 3)) { _ in
                        AxisGridLine()
                        AxisValueLabel(format: .dateTime.hour())
                    }
                }
                .frame(height: 200)
                if let busiest = bars.max(by: { $0.value < $1.value }), busiest.value > 0 {
                    Text("Most active around \(Format.time(busiest.start)): \(metric.format(busiest.value)).")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            } else {
                EmptyCardState(icon: "chart.bar",
                               text: loadingBuckets ? "Reading Apple Health…"
                                   : metric == .activeTime ? "Active time comes as a daily total. Pick Steps to see the day in detail."
                                   : "Nothing recorded on this day.",
                               busy: loadingBuckets)
            }
        }
        .card()
    }

    private var dayTotals: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Totals", icon: "sum", color: .green)
            if let day = feed.day(for: period.anchor) {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 14) {
                    ForEach(ActivityMetric.allCases) { m in
                        VStack(spacing: 2) {
                            Text(m.value(day).map(m.format) ?? "–")
                                .font(.headline).lineLimit(1).minimumScaleFactor(0.6)
                                .foregroundStyle(goalMet(m, day) ? m.color : .primary)
                            Text(m.rawValue.lowercased()).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    VStack(spacing: 2) {
                        Text(day.totalCalories.map { "\(Format.number($0)) kcal" } ?? "–")
                            .font(.headline).lineLimit(1).minimumScaleFactor(0.6)
                        Text("total burned").font(.caption).foregroundStyle(.secondary)
                    }
                }
                HStack {
                    Metric(value: day.exerciseMinutes.map(Format.minutes) ?? "–", label: "exercise")
                    Divider().frame(height: 36)
                    Metric(value: day.raisedHrMinutes.map(Format.minutes) ?? "–", label: "raised heart rate")
                    Divider().frame(height: 36)
                    Metric(value: day.activeHours.map { "\(Int($0))" } ?? "–", label: "active hours")
                }
            } else {
                EmptyCardState(icon: "calendar", text: "The watch's numbers for this day aren't here.")
            }
        }
        .card()
    }

    private func goalMet(_ metric: ActivityMetric, _ day: HealthSummary.Day) -> Bool {
        guard let goal = metric.goal(profile), let value = metric.value(day) else { return false }
        return value >= goal
    }

    // MARK: Week and month

    private var rangeChart: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: metric.rawValue, icon: "chart.bar.fill", color: metric.color)
            let values = dayValues
            if values.contains(where: { ($0.value ?? 0) > 0 }) {
                let goal = metric.goal(profile)
                let mean = average(values.compactMap(\.value))
                let picked = selected.flatMap { s in values.first { sameDay($0.date, s) } }
                Chart {
                    ForEach(values) { day in
                        BarMark(
                            x: .value("Day", day.date, unit: .day),
                            y: .value(metric.rawValue, day.value ?? 0)
                        )
                        .foregroundStyle(barColor(day.value, goal: goal)
                            .opacity(picked == nil || picked?.id == day.id ? 1 : 0.35))
                        .cornerRadius(period.range == .week ? 6 : 2)
                    }
                    if let goal {
                        RuleMark(y: .value("Goal", goal))
                            .foregroundStyle(Color.secondary)
                            .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                            .annotation(position: .top, alignment: .trailing) {
                                Text("goal").font(.caption2).foregroundStyle(.secondary)
                            }
                    }
                    if let mean, picked == nil {
                        RuleMark(y: .value("Average", mean))
                            .foregroundStyle(metric.color.opacity(0.6))
                            .lineStyle(StrokeStyle(lineWidth: 1))
                            .annotation(position: .bottom, alignment: .leading) {
                                Text("avg \(metric.format(mean))").font(.caption2).foregroundStyle(metric.color)
                            }
                    }
                    if let picked {
                        RuleMark(x: .value("Selected", picked.date, unit: .day))
                            .foregroundStyle(Color.secondary.opacity(0.3))
                            .annotation(position: .top, spacing: 4, overflowResolution: bubbleOverflow) {
                                SelectionBubble(
                                    title: picked.date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated)),
                                    lines: [picked.value.map(metric.format) ?? "No data"]
                                )
                            }
                    }
                }
                .chartXSelection(value: $selected)
                .chartXAxis {
                    if period.range == .week {
                        AxisMarks(values: .stride(by: .day)) { _ in
                            AxisValueLabel(format: .dateTime.weekday(.narrow))
                        }
                    } else {
                        AxisMarks(values: .stride(by: .day, count: 7)) { _ in
                            AxisGridLine()
                            AxisValueLabel(format: .dateTime.day().month(.abbreviated))
                        }
                    }
                }
                .frame(height: 220)
                Text("Tap a bar for its value; a day in the list opens it in detail.")
                    .font(.caption).foregroundStyle(.tertiary)
            } else {
                EmptyCardState(icon: "calendar",
                               text: "Nothing kept for these days yet. The history fills in from the watch as days go by.")
            }
        }
        .card()
    }

    private func barColor(_ value: Double?, goal: Double?) -> Color {
        guard let goal, let value else { return metric.color }
        return value >= goal ? metric.color : metric.color.opacity(0.55)
    }

    private var rangeStats: some View {
        let values = dayValues.compactMap(\.value)
        let goal = metric.goal(profile)
        let best = dayValues.filter { $0.value != nil }.max { ($0.value ?? 0) < ($1.value ?? 0) }
        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                Metric(value: average(values).map(metric.format) ?? "–", label: "daily average")
                Divider().frame(height: 36)
                Metric(value: values.isEmpty ? "–" : metric.format(values.reduce(0, +)), label: "total")
                Divider().frame(height: 36)
                Metric(value: best?.value.map(metric.format) ?? "–", label: "best day")
            }
            if let goal, !values.isEmpty {
                let met = values.filter { $0 >= goal }.count
                ProgressView(value: Double(met), total: Double(values.count)) {
                    Text("Goal reached on \(met) of \(values.count) days").font(.subheadline)
                }
                .tint(metric.color)
            }
            if let best {
                Text("Best: \(best.date.formatted(.dateTime.weekday(.wide).day().month(.wide))).")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .card()
    }

    private var dayList: some View {
        VStack(alignment: .leading, spacing: 4) {
            CardHeader(title: "Day by day", icon: "list.bullet", color: metric.color)
            ForEach(dayValues.reversed()) { day in
                Button {
                    period = HistoryPeriod(range: .day, anchor: day.date)
                } label: {
                    HStack {
                        Text(day.date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated)))
                            .font(.subheadline)
                        Spacer()
                        if let value = day.value, let goal = metric.goal(profile), value >= goal {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(metric.color).font(.caption)
                        }
                        Text(day.value.map(metric.format) ?? "–").font(.subheadline.weight(.semibold))
                        Image(systemName: "chevron.right").font(.caption2).foregroundStyle(.tertiary)
                    }
                    .padding(.vertical, 6)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(day.value == nil)
                if day.id != dayValues.first?.id { Divider() }
            }
        }
        .card()
    }
}

// MARK: - Heart rate

/// A day's every reading, or each day's range and resting heart rate over a week or a month.
struct HeartHistoryView: View {
    @ObservedObject var feed: SummaryFeed
    let restingBand: ClosedRange<Double>?
    @EnvironmentObject private var stats: HealthStats

    @State private var period = HistoryPeriod(range: .day)
    @State private var selected: Date?
    @State private var points: [HealthStats.HeartPoint] = []
    @State private var loading = false

    /// A reading and the stretch of wear it belongs to, so the line breaks where the watch was off.
    private struct Segmented: Identifiable {
        let point: HealthStats.HeartPoint
        let segment: Int
        var id: Date { point.date }
    }

    private var segmented: [Segmented] {
        var segment = 0
        var previous: Date?
        return points.map { point in
            if let previous, point.date.timeIntervalSince(previous) > 15 * 60 { segment += 1 }
            previous = point.date
            return Segmented(point: point, segment: segment)
        }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                PeriodPicker(period: $period)
                if period.range == .day {
                    dayChart
                    dayStats
                } else {
                    rangeChart
                    restingChart
                }
                if let restingBand {
                    Text("The shaded band is the usual resting range for your age and sex, \(Int(restingBand.lowerBound))–\(Int(restingBand.upperBound)) bpm.")
                        .font(.footnote).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding()
        }
        .background(Theme.background)
        .navigationTitle("Heart rate")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: period) { selected = nil }
        .task(id: "\(period.range.rawValue)|\(period.interval.start.timeIntervalSince1970)") {
            guard period.range == .day else { return }
            loading = true
            let interval = period.interval
            points = await stats.heartRatePoints(from: interval.start, to: interval.end)
            loading = false
        }
    }

    private func yDomain(_ values: [Double]) -> ClosedRange<Double> {
        let low = min(values.min() ?? 50, restingBand?.lowerBound ?? 60)
        let high = max(values.max() ?? 120, 100)
        return max(30, (low - 8).rounded(.down))...min(220, (high + 10).rounded(.up))
    }

    // MARK: Day

    private var dayChart: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Through the day", icon: "waveform.path.ecg", color: .pink,
                       detail: points.isEmpty ? nil : "\(points.count) readings")
            let interval = period.interval
            if !points.isEmpty {
                let day = feed.day(for: period.anchor)
                let picked = selected.flatMap { s in
                    points.min { abs($0.date.timeIntervalSince(s)) < abs($1.date.timeIntervalSince(s)) }
                }
                Chart {
                    if let band = restingBand {
                        RectangleMark(
                            xStart: .value("From", interval.start), xEnd: .value("To", interval.end),
                            yStart: .value("Low", band.lowerBound), yEnd: .value("High", band.upperBound)
                        )
                        .foregroundStyle(Color.pink.opacity(0.08))
                    }
                    ForEach(segmented) { item in
                        LineMark(
                            x: .value("Time", item.point.date),
                            y: .value("bpm", item.point.bpm),
                            series: .value("Stretch", item.segment)
                        )
                        .interpolationMethod(.monotone)
                        .foregroundStyle(Color.pink)
                        .lineStyle(StrokeStyle(lineWidth: 1.5))
                    }
                    if let resting = day?.restingHr {
                        RuleMark(y: .value("Resting", resting))
                            .foregroundStyle(Color.secondary)
                            .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                    }
                    if let picked {
                        RuleMark(x: .value("Selected", picked.date))
                            .foregroundStyle(Color.secondary.opacity(0.4))
                            .annotation(position: .top, spacing: 4, overflowResolution: bubbleOverflow) {
                                SelectionBubble(title: Format.time(picked.date),
                                                lines: ["\(Int(picked.bpm.rounded())) bpm"])
                            }
                        PointMark(x: .value("Time", picked.date), y: .value("bpm", picked.bpm))
                            .foregroundStyle(Color.pink)
                            .symbolSize(60)
                    }
                }
                .chartXScale(domain: interval.start...interval.end)
                .chartYScale(domain: yDomain(points.map(\.bpm)))
                .chartXSelection(value: $selected)
                .chartXAxis {
                    AxisMarks(values: .stride(by: .hour, count: 3)) { _ in
                        AxisGridLine()
                        AxisValueLabel(format: .dateTime.hour())
                    }
                }
                .frame(height: 240)
                Text("Drag across the chart to read the heart rate at any time. Gaps are when the watch was off your wrist.")
                    .font(.caption).foregroundStyle(.tertiary)
            } else if let day = feed.day(for: period.anchor), !hourlyRanges(day).isEmpty {
                // Apple Health doesn't have this day's readings (not synced): the watch's hourly ranges
                let hours = hourlyRanges(day)
                Chart(hours) { hour in
                    BarMark(
                        x: .value("Hour", hour.hour, unit: .hour),
                        yStart: .value("Lowest", hour.low),
                        yEnd: .value("Highest", max(hour.high, hour.low + 1)),
                        width: .ratio(0.5)
                    )
                    .foregroundStyle(Color.pink.gradient)
                    .cornerRadius(3)
                }
                .chartXScale(domain: interval.start...interval.end)
                .chartYScale(domain: yDomain(hours.flatMap { [$0.low, $0.high] }))
                .frame(height: 200)
                Text("Hourly lowest and highest. Every reading shows here once the day is synced to Apple Health.")
                    .font(.caption).foregroundStyle(.tertiary)
            } else {
                EmptyCardState(icon: "heart", text: loading ? "Reading Apple Health…" : "No heart rate recorded on this day.",
                               busy: loading)
            }
        }
        .card()
    }

    private func hourlyRanges(_ day: HealthSummary.Day) -> [HealthStats.HourRange] {
        DayNumbers(day).hourlyHeart
    }

    private var dayStats: some View {
        let values = points.map(\.bpm)
        let day = feed.day(for: period.anchor)
        let low = values.min() ?? day?.hrMin
        let high = values.max() ?? day?.hrMax
        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                Metric(value: day?.restingHr.map { "\(Int($0))" } ?? "–", label: "resting")
                Divider().frame(height: 36)
                Metric(value: average(values).map { "\(Int($0.rounded()))" } ?? "–", label: "average")
                Divider().frame(height: 36)
                Metric(value: low.map { "\(Int($0.rounded()))" } ?? "–", label: "lowest")
                Divider().frame(height: 36)
                Metric(value: high.map { "\(Int($0.rounded()))" } ?? "–", label: "highest")
            }
            if let raised = day?.raisedHrMinutes, raised > 0 {
                Text("\(Format.minutes(raised)) with a raised heart rate (61% of your maximum or more).")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .card()
    }

    // MARK: Week and month

    private struct DayRange: Identifiable {
        let date: Date
        let low: Double
        let high: Double
        let resting: Double?
        var id: Date { date }
    }

    private var dayRanges: [DayRange] {
        period.dayStarts.compactMap { start in
            guard let day = feed.day(for: start), let low = day.hrMin, let high = day.hrMax else { return nil }
            return DayRange(date: start, low: low, high: high, resting: day.restingHr)
        }
    }

    private var rangeChart: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Daily range", icon: "heart.fill", color: .pink)
            let ranges = dayRanges
            if ranges.isEmpty {
                EmptyCardState(icon: "calendar", text: "Nothing kept for these days yet.")
            } else {
                let picked = selected.flatMap { s in ranges.first { sameDay($0.date, s) } }
                Chart {
                    if let band = restingBand {
                        RectangleMark(
                            xStart: .value("From", period.interval.start), xEnd: .value("To", period.interval.end),
                            yStart: .value("Low", band.lowerBound), yEnd: .value("High", band.upperBound)
                        )
                        .foregroundStyle(Color.pink.opacity(0.08))
                    }
                    ForEach(ranges) { day in
                        BarMark(
                            x: .value("Day", day.date, unit: .day),
                            yStart: .value("Lowest", day.low),
                            yEnd: .value("Highest", max(day.high, day.low + 1)),
                            width: .ratio(period.range == .week ? 0.35 : 0.6)
                        )
                        .foregroundStyle(Color.pink.gradient.opacity(picked == nil || picked?.id == day.id ? 1 : 0.35))
                        .cornerRadius(4)
                        if let resting = day.resting {
                            PointMark(x: .value("Day", day.date, unit: .day), y: .value("Resting", resting))
                                .foregroundStyle(Color.primary)
                                .symbolSize(period.range == .week ? 30 : 14)
                        }
                    }
                    if let picked {
                        RuleMark(x: .value("Selected", picked.date, unit: .day))
                            .foregroundStyle(Color.secondary.opacity(0.3))
                            .annotation(position: .top, spacing: 4, overflowResolution: bubbleOverflow) {
                                SelectionBubble(
                                    title: picked.date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated)),
                                    lines: ["\(Int(picked.low))–\(Int(picked.high)) bpm"]
                                        + (picked.resting.map { ["resting \(Int($0))"] } ?? [])
                                )
                            }
                    }
                }
                .chartYScale(domain: yDomain(ranges.flatMap { [$0.low, $0.high] }))
                .chartXSelection(value: $selected)
                .chartXAxis {
                    if period.range == .week {
                        AxisMarks(values: .stride(by: .day)) { _ in
                            AxisValueLabel(format: .dateTime.weekday(.narrow))
                        }
                    } else {
                        AxisMarks(values: .stride(by: .day, count: 7)) { _ in
                            AxisGridLine()
                            AxisValueLabel(format: .dateTime.day().month(.abbreviated))
                        }
                    }
                }
                .frame(height: 220)
                HStack(spacing: 16) {
                    Label("Lowest–highest", systemImage: "square.fill").foregroundStyle(Color.pink)
                    Label("Resting", systemImage: "circle.fill").foregroundStyle(Color.primary)
                }
                .font(.caption)
            }
        }
        .card()
    }

    private var restingChart: some View {
        let ranges = dayRanges.filter { $0.resting != nil }
        let restings = ranges.compactMap(\.resting)
        return VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Resting heart rate", icon: "bed.double.fill", color: .pink)
            if ranges.count < 2 {
                EmptyCardState(icon: "chart.line.uptrend.xyaxis", text: "The trend shows once there are a few days.")
            } else {
                Chart(ranges) { day in
                    LineMark(x: .value("Day", day.date, unit: .day), y: .value("Resting", day.resting ?? 0))
                        .interpolationMethod(.monotone)
                        .foregroundStyle(Color.pink)
                    PointMark(x: .value("Day", day.date, unit: .day), y: .value("Resting", day.resting ?? 0))
                        .foregroundStyle(Color.pink)
                        .symbolSize(20)
                }
                .chartYScale(domain: ((restings.min() ?? 50) - 5)...((restings.max() ?? 70) + 5))
                .frame(height: 140)
                HStack {
                    Metric(value: average(restings).map { "\(Int($0.rounded())) bpm" } ?? "–", label: "average")
                    Divider().frame(height: 36)
                    Metric(value: restings.min().map { "\(Int($0)) bpm" } ?? "–", label: "lowest")
                    Divider().frame(height: 36)
                    Metric(value: trend(restings), label: "trend")
                }
            }
        }
        .card()
    }

    /// First half against the second half of the period.
    private func trend(_ values: [Double]) -> String {
        guard values.count >= 4 else { return "–" }
        let half = values.count / 2
        guard let first = average(Array(values.prefix(half))), let last = average(Array(values.suffix(half))) else { return "–" }
        let change = last - first
        if abs(change) < 1 { return "steady" }
        return change > 0 ? "↑ \(Int(change.rounded()))" : "↓ \(Int((-change).rounded()))"
    }
}

// MARK: - Sleep

/// Every night kept: how long, how well, and when, over a week or a month.
struct SleepHistoryView: View {
    @ObservedObject var feed: SummaryFeed
    let profile: WatchProfile

    @State private var period = HistoryPeriod(range: .week)
    @State private var selected: Date?

    private struct NightValue: Identifiable {
        let date: Date
        let night: HealthSummary.Night
        var id: Date { date }
        var hours: Double { (night.asleepMin ?? 0) / 60 }
    }

    private var nights: [NightValue] {
        period.dayStarts.compactMap { day in feed.night(endingOn: day).map { NightValue(date: day, night: $0) } }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                PeriodPicker(period: $period, ranges: [.week, .month])
                durationChart
                scheduleChart
                stats
                nightList
            }
            .padding()
        }
        .background(Theme.background)
        .navigationTitle("Sleep history")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: period) { selected = nil }
    }

    private var durationChart: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Time asleep", icon: "bed.double.fill", color: .indigo)
            let values = nights
            if values.isEmpty {
                EmptyCardState(icon: "moon.zzz", text: "No nights kept for these days.")
            } else {
                let goal = Double(profile.sleepGoalMinutes) / 60
                let picked = selected.flatMap { s in values.first { sameDay($0.date, s) } }
                Chart {
                    ForEach(values) { item in
                        BarMark(x: .value("Night", item.date, unit: .day), y: .value("Hours", item.hours))
                            .foregroundStyle(Band.color(item.night.band)
                                .opacity(picked == nil || picked?.id == item.id ? 1 : 0.35))
                            .cornerRadius(period.range == .week ? 6 : 2)
                    }
                    RuleMark(y: .value("Goal", goal))
                        .foregroundStyle(Color.secondary)
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                    if let picked {
                        RuleMark(x: .value("Selected", picked.date, unit: .day))
                            .foregroundStyle(Color.secondary.opacity(0.3))
                            .annotation(position: .top, spacing: 4, overflowResolution: bubbleOverflow) {
                                SelectionBubble(
                                    title: picked.date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated)),
                                    lines: [Format.minutes(picked.night.asleepMin ?? 0) + " asleep",
                                            picked.night.score.map { "score \(Int($0))" } ?? "not scored"]
                                )
                            }
                    }
                }
                .chartXScale(domain: period.interval.start...period.interval.end)
                .chartXSelection(value: $selected)
                .chartYAxis {
                    AxisMarks { value in
                        AxisGridLine()
                        AxisValueLabel { Text("\(value.as(Double.self).map { Int($0) } ?? 0)h") }
                    }
                }
                .chartXAxis { dayAxis }
                .frame(height: 200)
                HStack(spacing: 12) {
                    ForEach(["Excellent", "Good", "Fair", "Attention"], id: \.self) { band in
                        Label(band, systemImage: "square.fill").foregroundStyle(Band.color(band))
                    }
                }
                .font(.caption2)
            }
        }
        .card()
    }

    /// Minutes after 18:00, so a night reads as one stretch on the chart.
    private func eveningMinutes(_ date: Date) -> Double {
        let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
        let minute = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
        return Double((minute - 18 * 60 + 1440) % 1440)
    }

    private var scheduleChart: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Bedtime and wake-up", icon: "clock.fill", color: .indigo)
            let values = nights.filter { $0.night.startDate != nil && $0.night.endDate != nil }
            if values.isEmpty {
                EmptyCardState(icon: "clock", text: "No nights kept for these days.")
            } else {
                let bedtimeGoal = Double((profile.bedtimeMinutes - 18 * 60 + 1440) % 1440)
                Chart {
                    ForEach(values) { item in
                        BarMark(
                            x: .value("Night", item.date, unit: .day),
                            yStart: .value("Bedtime", eveningMinutes(item.night.startDate!)),
                            yEnd: .value("Wake-up", eveningMinutes(item.night.endDate!)),
                            width: .ratio(period.range == .week ? 0.4 : 0.7)
                        )
                        .foregroundStyle(Color.indigo.gradient)
                        .cornerRadius(4)
                    }
                    RuleMark(y: .value("Bedtime goal", bedtimeGoal))
                        .foregroundStyle(Color.secondary)
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                }
                .chartXScale(domain: period.interval.start...period.interval.end)
                .chartYScale(domain: .automatic(includesZero: false, reversed: true))
                .chartYAxis {
                    AxisMarks(values: .stride(by: 120)) { value in
                        AxisGridLine()
                        AxisValueLabel {
                            Text(value.as(Double.self).map { Format.clock(minutesAfterMidnight: Int($0) + 18 * 60) } ?? "")
                        }
                    }
                }
                .chartXAxis { dayAxis }
                .frame(height: 200)
                Text("Each bar runs from falling asleep to waking up. The dashed line is your bedtime goal.")
                    .font(.caption).foregroundStyle(.tertiary)
            }
        }
        .card()
    }

    @AxisContentBuilder private var dayAxis: some AxisContent {
        if period.range == .week {
            AxisMarks(values: .stride(by: .day)) { _ in
                AxisValueLabel(format: .dateTime.weekday(.narrow))
            }
        } else {
            AxisMarks(values: .stride(by: .day, count: 7)) { _ in
                AxisGridLine()
                AxisValueLabel(format: .dateTime.day().month(.abbreviated))
            }
        }
    }

    private var stats: some View {
        let values = nights.map(\.night)
        let scores = values.compactMap(\.score)
        let bedtimes = values.compactMap(\.startDate).map(eveningMinutes)
        let wakes = values.compactMap(\.endDate).map(eveningMinutes)
        let goal = Double(profile.sleepGoalMinutes)
        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                Metric(value: average(values.compactMap(\.asleepMin)).map(Format.minutes) ?? "–", label: "avg asleep")
                Divider().frame(height: 36)
                Metric(value: average(scores).map { "\(Int($0.rounded()))" } ?? "–", label: "avg score")
                Divider().frame(height: 36)
                Metric(value: average(values.compactMap(\.efficiency)).map { "\(Int(($0 * 100).rounded()))%" } ?? "–",
                       label: "efficiency")
            }
            HStack {
                Metric(value: average(bedtimes).map { Format.clock(minutesAfterMidnight: Int($0) + 18 * 60) } ?? "–",
                       label: "avg bedtime")
                Divider().frame(height: 36)
                Metric(value: average(wakes).map { Format.clock(minutesAfterMidnight: Int($0) + 18 * 60) } ?? "–",
                       label: "avg wake-up")
                Divider().frame(height: 36)
                Metric(value: values.isEmpty ? "–" : "\(values.filter { ($0.asleepMin ?? 0) >= goal - 30 }.count)/\(values.count)",
                       label: "near goal")
            }
        }
        .card()
    }

    private var nightList: some View {
        VStack(alignment: .leading, spacing: 4) {
            CardHeader(title: "Night by night", icon: "list.bullet", color: .indigo)
            if nights.isEmpty {
                EmptyCardState(icon: "moon.zzz", text: "No nights kept for these days.")
            }
            ForEach(nights.reversed()) { item in
                NavigationLink {
                    if let summary = feed.latest {
                        SleepDetailView(summary: summary, night: item.night, profile: profile, feed: feed)
                    }
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated)))
                                .font(.subheadline)
                            if let start = item.night.startDate, let end = item.night.endDate {
                                Text("\(Format.time(start)) – \(Format.time(end))")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        Text(Format.minutes(item.night.asleepMin ?? 0)).font(.subheadline.weight(.semibold))
                        Text(item.night.score.map { "\(Int($0))" } ?? "–")
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(Band.color(item.night.band))
                            .frame(width: 32, alignment: .trailing)
                        Image(systemName: "chevron.right").font(.caption2).foregroundStyle(.tertiary)
                    }
                    .padding(.vertical, 6)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(feed.latest == nil)
                if item.id != nights.first?.id { Divider() }
            }
        }
        .card()
    }
}
