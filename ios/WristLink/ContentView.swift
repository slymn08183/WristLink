import Charts
import CoreBluetooth
import SwiftUI
import UIKit

/// Three tabs: the watch at a glance, Apple Health, and the watch's settings.
struct ContentView: View {
    @EnvironmentObject private var link: WatchLink

    var body: some View {
        if link.state == .connected {
            TabView {
                WatchTab()
                    .tabItem { Label("Watch", systemImage: "applewatch") }
                HealthTab()
                    .tabItem { Label("Health", systemImage: "heart.fill") }
                SettingsTab()
                    .tabItem { Label("Settings", systemImage: "gearshape.fill") }
            }
        } else {
            ConnectView()
        }
    }
}

// MARK: - Not connected yet

private struct ConnectView: View {
    @EnvironmentObject private var link: WatchLink

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    Image(systemName: "applewatch.radiowaves.left.and.right")
                        .font(.system(size: 64, weight: .light))
                        .foregroundStyle(Theme.gradient)
                        .padding(.top, 40)
                    Text(title).font(.title2.bold()).multilineTextAlignment(.center)
                    Text(detail).foregroundStyle(.secondary).multilineTextAlignment(.center)

                    if link.state == .choosing && !link.candidates.isEmpty {
                        VStack(spacing: 10) {
                            ForEach(link.candidates, id: \.identifier) { peripheral in
                                Button { link.choose(peripheral) } label: {
                                    HStack {
                                        Image(systemName: "applewatch")
                                        Text(peripheral.name ?? "Unnamed device")
                                        Spacer()
                                        Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                                    }
                                    .padding()
                                    .background(Theme.card, in: .rect(cornerRadius: 16))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    if link.state == .connecting {
                        ProgressView()
                    }
                }
                .padding(.horizontal, 24)
            }
            .background(Theme.background)
            .refreshable { link.refreshCandidates() }
            .navigationTitle("WristLink")
        }
    }

    private var title: String {
        switch link.state {
        case .starting: return "Starting…"
        case .bluetoothOff: return "Bluetooth is off"
        case .notAllowed: return "Bluetooth not allowed"
        case .choosing: return link.candidates.isEmpty ? "No watch found" : "Which one is your watch?"
        case .connecting: return "Connecting to \(link.watchName ?? "your watch")"
        case .connected: return ""
        case .noCompanionService: return "Update WristLink on the watch"
        }
    }

    private var detail: String {
        switch link.state {
        case .bluetoothOff: return "Turn on Bluetooth in Control Center."
        case .notAllowed: return "Allow Bluetooth for WristLink in Settings."
        case .choosing:
            return link.candidates.isEmpty
                ? "Pair the watch in Settings → Bluetooth first (it shows up as WristLink), then pull down here."
                : "Pick the Galaxy Watch. It's listed under the name it had when you paired it."
        case .connecting: return "The watch needs to be nearby with WristLink running."
        case .noCompanionService: return "This watch runs an older WristLink without the iPhone link. Install the latest version on the watch."
        default: return ""
        }
    }
}

// MARK: - Watch tab

private struct WatchTab: View {
    @EnvironmentObject private var link: WatchLink
    @EnvironmentObject private var stats: HealthStats

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    if let info = link.info {
                        header(info)
                        WeatherCard(weather: link.weather, watchSupportsWeather: info.protocolVersion >= 4)
                        todayGrid(info)
                        connectionCard(info)
                    } else {
                        ProgressView().padding(.top, 80)
                    }
                    if let settings = link.settings {
                        quickToggles(settings)
                    }
                }
                .padding()
            }
            .background(Theme.background)
            .navigationTitle(link.watchName ?? "Watch")
            .refreshable {
                link.refreshSettings()
                link.weather.updateIfNeeded(force: true)
                await stats.refresh()
            }
            .task { await stats.refresh() }
        }
    }

    private func header(_ info: WatchInfo) -> some View {
        HStack(spacing: 20) {
            Gauge(value: Double(info.battery), in: 0...100) {
                Image(systemName: info.charging ? "bolt.fill" : "battery.100")
            } currentValueLabel: {
                Text("\(info.battery)")
            }
            .gaugeStyle(.accessoryCircularCapacity)
            .tint(batteryColor(info.battery))
            .scaleEffect(1.4)
            .frame(width: 80, height: 80)

            VStack(alignment: .leading, spacing: 6) {
                ConnectedPill()
                Text(info.model.map { "Galaxy Watch · \($0)" } ?? "Galaxy Watch")
                    .font(.headline)
                Text(info.charging ? "Charging · \(info.battery)%" : "Battery \(info.battery)%")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
        }
        .card()
    }

    private func todayGrid(_ info: WatchInfo) -> some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
            StatTile(icon: "figure.walk", color: .green, title: "Steps today",
                     value: stats.stepsToday.map { $0.formatted(.number.precision(.fractionLength(0))) } ?? "–")
            StatTile(icon: "heart.fill", color: .pink, title: "Heart rate",
                     value: stats.latestHeartRate.map { "\(Int($0)) bpm" } ?? "–",
                     detail: stats.latestHeartRateDate.map { $0.formatted(.relative(presentation: .named)) })
            StatTile(icon: "bed.double.fill", color: .indigo, title: "Sleep last night",
                     value: stats.sleepLastNight.map(Format.duration) ?? "–",
                     detail: link.settings?.asleep == true ? "Asleep now" : nil)
            StatTile(icon: "bell.badge.fill", color: .orange, title: "Notification delay",
                     value: info.medianDelayMs.map(Format.delay) ?? "–",
                     detail: "\(info.notifications) in 24 h")
        }
    }

    private func connectionCard(_ info: WatchInfo) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Connection, last 24 hours", systemImage: "antenna.radiowaves.left.and.right")
                .font(.subheadline.weight(.semibold))
            HStack {
                Metric(value: info.connectedPct.map { "\($0)%" } ?? "–", label: "connected")
                Divider().frame(height: 36)
                Metric(value: "\(info.drops)", label: info.drops == 1 ? "drop" : "drops")
                Divider().frame(height: 36)
                Metric(value: info.app ?? "–", label: "watch app")
            }
        }
        .card()
    }

    private func quickToggles(_ settings: WatchSettings) -> some View {
        VStack(spacing: 0) {
            Toggle(isOn: Binding(get: { settings.doNotDisturb },
                                 set: { link.set(\.doNotDisturb, key: "doNotDisturb", to: $0) })) {
                Label("Do Not Disturb", systemImage: "moon.fill")
            }
            .disabled(!settings.canChangeDnd)
            Divider().padding(.vertical, 10)
            Toggle(isOn: Binding(get: { settings.sleepGuard },
                                 set: { link.set(\.sleepGuard, key: "sleepGuard", to: $0) })) {
                Label("Quiet while asleep", systemImage: "bed.double")
            }
        }
        .card()
    }

    private func batteryColor(_ level: Int) -> Color {
        level <= 15 ? .red : level <= 30 ? .orange : .green
    }
}

// MARK: - Weather

/// The forecast the watch shows, or what's needed before it can get one.
private struct WeatherCard: View {
    @ObservedObject var weather: WeatherFeed
    let watchSupportsWeather: Bool

    var body: some View {
        if weather.enabled {
            Group {
                if !watchSupportsWeather {
                    prompt("Update WristLink on the watch to get the weather there.")
                } else {
                    switch weather.access {
                    case .notAsked:
                        prompt("Allow location so WristLink can send the weather for where you are.",
                               button: "Allow location") { weather.requestAccess() }
                    case .denied:
                        prompt("Location is off for WristLink. Turn it on in Settings to send the weather.",
                               button: "Open Settings") {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        }
                    case .allowed:
                        if let summary = weather.latest {
                            forecast(summary)
                        } else {
                            prompt(weather.lastError ?? "Getting the forecast…")
                        }
                    }
                }
            }
            .card()
        }
    }

    private func forecast(_ s: WeatherFeed.Summary) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(s.place ?? "Weather", systemImage: "location.fill")
                .font(.subheadline.weight(.semibold))
            HStack(spacing: 14) {
                Image(systemName: Sky.symbol(s.code, isDay: s.isDay))
                    .symbolRenderingMode(.multicolor)
                    .font(.system(size: 42))
                VStack(alignment: .leading, spacing: 2) {
                    Text(Format.temperature(s.temperature))
                        .font(.system(size: 38, weight: .semibold, design: .rounded))
                    Text(summaryLine(s)).font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
            }
            HStack(spacing: 14) {
                if let sunrise = s.sunrise {
                    Label(sunrise.formatted(date: .omitted, time: .shortened), systemImage: "sunrise.fill")
                }
                if let sunset = s.sunset {
                    Label(sunset.formatted(date: .omitted, time: .shortened), systemImage: "sunset.fill")
                }
                Spacer()
                Text("Sent \(s.fetchedAt, format: .relative(presentation: .named))")
            }
            .font(.footnote).foregroundStyle(.secondary)
            if let error = weather.lastError {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote).foregroundStyle(.orange)
            }
        }
    }

    /// "Partly cloudy · 21° / 12°"
    private func summaryLine(_ s: WeatherFeed.Summary) -> String {
        var parts = [Sky.label(s.code, isDay: s.isDay)]
        if let high = s.high, let low = s.low {
            parts.append("\(Format.temperature(high)) / \(Format.temperature(low))")
        }
        return parts.joined(separator: " · ")
    }

    private func prompt(_ text: String, button: String? = nil, action: @escaping () -> Void = {}) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Weather on the watch", systemImage: "cloud.sun.fill")
                .font(.headline)
                .symbolRenderingMode(.multicolor)
            Text(text).font(.subheadline).foregroundStyle(.secondary)
            if let button {
                Button(action: action) { Text(button).frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent)
            }
        }
    }
}

// MARK: - Health tab

private struct HealthTab: View {
    @EnvironmentObject private var link: WatchLink
    @EnvironmentObject private var access: HealthAccess
    @EnvironmentObject private var stats: HealthStats

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    if access.status == .notAsked {
                        accessCard
                    }
                    SyncCard(sync: link.health, pending: link.info?.pending) { link.syncHealth() }
                    stepsChart
                    whatSyncs
                }
                .padding()
            }
            .background(Theme.background)
            .navigationTitle("Apple Health")
            .refreshable { await stats.refresh() }
            .task { await stats.refresh() }
        }
    }

    private var accessCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Connect to Apple Health", systemImage: "heart.text.square.fill")
                .font(.headline).foregroundStyle(.pink)
            Text("Allow WristLink to save steps, distance, heart rate and sleep from the watch.")
                .font(.subheadline).foregroundStyle(.secondary)
            Button {
                Task {
                    await access.requestAccess()
                    await stats.refresh()
                    link.syncHealth()
                }
            } label: {
                Text("Allow Apple Health").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(.pink)
            if case .failed(let message) = access.status {
                Text(message).font(.footnote).foregroundStyle(.red)
            }
        }
        .card()
    }

    private var stepsChart: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Steps, last 7 days", systemImage: "chart.bar.fill")
                .font(.subheadline.weight(.semibold))
            if stats.week.isEmpty {
                Text("No data yet").foregroundStyle(.secondary).frame(maxWidth: .infinity, minHeight: 140)
            } else {
                Chart(stats.week) { day in
                    BarMark(
                        x: .value("Day", day.date, unit: .day),
                        y: .value("Steps", day.steps)
                    )
                    .foregroundStyle(Theme.gradient)
                    .cornerRadius(6)
                }
                .chartXAxis {
                    AxisMarks(values: .stride(by: .day)) { _ in
                        AxisValueLabel(format: .dateTime.weekday(.narrow))
                    }
                }
                .frame(height: 160)
            }
        }
        .card()
    }

    private var whatSyncs: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("From the watch", systemImage: "arrow.down.heart")
                .font(.subheadline.weight(.semibold))
            ForEach([("figure.walk", "Steps and distance"),
                     ("heart", "Heart rate"),
                     ("bed.double", "Sleep (the watch's own sleep detection)")], id: \.1) { icon, text in
                Label(text, systemImage: icon).font(.subheadline).foregroundStyle(.secondary)
            }
            Text("The watch keeps everything until the iPhone has saved it, and sends it on its own every 15 minutes or so.")
                .font(.footnote).foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }
}

private struct SyncCard: View {
    @ObservedObject var sync: HealthSync
    let pending: Int?
    let onSync: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label(sync.syncing ? "Syncing…" : "Sync", systemImage: "arrow.triangle.2.circlepath")
                    .font(.headline)
                Spacer()
                if let lastSync = sync.lastSync {
                    Text(lastSync, format: .relative(presentation: .named))
                        .font(.subheadline).foregroundStyle(.secondary)
                }
            }
            HStack {
                Metric(value: pending.map(String.init) ?? "–", label: "waiting on watch")
                Divider().frame(height: 36)
                Metric(value: "\(sync.lastCount)", label: "saved this session")
            }
            if let error = sync.lastError {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote).foregroundStyle(.orange)
            }
            Button(action: onSync) {
                Text(sync.syncing ? "Syncing…" : "Sync now").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(sync.syncing)
        }
        .card()
    }
}

// MARK: - Settings tab

private struct SettingsTab: View {
    @EnvironmentObject private var link: WatchLink

    private static let timeouts = [5, 10, 15, 30, 60, 120, 300]
    private static let fontScales: [Double] = [0.85, 1.0, 1.15, 1.3]

    var body: some View {
        NavigationStack {
            Group {
                if let settings = link.settings {
                    form(settings)
                } else {
                    VStack(spacing: 12) {
                        ProgressView()
                        Text("Reading settings from the watch…").foregroundStyle(.secondary)
                        Text("Needs WristLink 0.1 with protocol 3 or later on the watch.")
                            .font(.footnote).foregroundStyle(.tertiary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Theme.background)
                }
            }
            .navigationTitle("Watch settings")
        }
    }

    private func form(_ s: WatchSettings) -> some View {
        Form {
            if let error = link.settingsError {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                }
            }

            // Names and descriptions match the watch's own settings screen
            Section {
                toggle("Vibration", "iphone.radiowaves.left.and.right", \.vibration, "vibration", s,
                       detail: "For notifications. Calls always buzz")
                toggle("Hide during DND", "moon", \.respectDnd, "respectDnd", s,
                       detail: "While the watch is on Do Not Disturb, only calls show")
                toggle("Show older notifications", "clock.arrow.circlepath", \.showPreExisting, "showPreExisting", s,
                       detail: "Include ones from before connecting")
                toggle("Show silent notifications", "bell.slash", \.showSilent, "showSilent", s,
                       detail: "Ones iOS delivers without a sound")
                NavigationLink {
                    CategoriesView()
                } label: {
                    Label("Categories", systemImage: "square.grid.2x2")
                }
            } header: {
                Text("Notifications")
            }

            Section {
                toggle("Calls on watch", "phone.fill", \.callsOnWatch, "callsOnWatch", s,
                       detail: "Talk through the watch", enabled: s.canTalkOnWatch != false)
            } header: {
                Text("Calls")
            } footer: {
                Text(s.canTalkOnWatch == false
                     ? "Allow phone access on the watch first: open WristLink there, Settings, Calls on watch."
                     : "Call audio goes to the watch when it's connected as the iPhone's Bluetooth audio.")
            }

            Section {
                toggle("Battery alert", "battery.25", \.phoneBatteryAlert, "phoneBatteryAlert", s,
                       detail: "Buzz when the iPhone is below 20%")
                toggle("Disconnect alert", "iphone.slash", \.disconnectAlert, "disconnectAlert", s,
                       detail: "Buzz when the iPhone is gone for a minute")
                toggle("Phone info on tile", "rectangle.on.rectangle", \.showPhoneInfo, "showPhoneInfo", s,
                       detail: "Name and battery on the tile")
            } header: {
                Text("iPhone")
            }

            Section {
                toggle("Quiet while asleep", "bed.double", \.sleepGuard, "sleepGuard", s,
                       detail: "DND and no wrist wake while you sleep")
            } header: {
                Text("Sleep")
            } footer: {
                Text(sleepFooter(s))
            }

            WeatherSection(weather: link.weather)

            Section {
                toggle("Auto brightness", "sun.max", \.autoBrightness, "autoBrightness", s, enabled: s.canChangeSystem)
                if !s.autoBrightness {
                    BrightnessSlider(value: s.brightness) { link.set(\.brightness, key: "brightness", to: $0) }
                        .disabled(!s.canChangeSystem)
                }
                toggle("Always On Display", "circle.lefthalf.filled", \.alwaysOnDisplay, "alwaysOnDisplay", s, enabled: s.canChangeSystem)
                if s.alwaysOnDisplay {
                    toggle("Tilt to brighten", "arrow.up.and.down.circle", \.tiltToWakeAod, "tiltToWakeAod", s, enabled: s.canChangeSystem)
                }
                Picker(selection: Binding(get: { s.fontScale },
                                          set: { link.set(\.fontScale, key: "fontScale", to: $0) })) {
                    ForEach(Self.options(Self.fontScales, current: s.fontScale), id: \.self) { Text(Format.fontScale($0)).tag($0) }
                } label: {
                    Label("Font size", systemImage: "textformat.size")
                }
                .disabled(!s.canChangeSystem)
            } header: {
                Text("Display")
            }

            Section {
                toggle("Raise to wake", "hand.raised", \.raiseToWake, "raiseToWake", s, enabled: s.canChangeSystem)
                toggle("Turn bezel to wake", "dial.low", \.wakeByBezel, "wakeByBezel", s, enabled: s.canChangeSystem)
                toggle("Double tap to wake", "hand.tap", \.doubleTapToWake, "doubleTapToWake", s, enabled: s.canChangeSystem)
                toggle("Touch sensitivity (gloves)", "hand.point.up", \.touchSensitivity, "touchSensitivity", s, enabled: s.canChangeSystem)
                toggle("Touch vibration", "waveform", \.hapticFeedback, "hapticFeedback", s, enabled: s.canChangeSystem)
            } header: {
                Text("Wake & touch")
            }

            Section {
                toggle("Do Not Disturb", "moon.fill", \.doNotDisturb, "doNotDisturb", s, enabled: s.canChangeDnd)
                Picker(selection: Binding(get: { s.screenTimeoutSec },
                                          set: { link.set(\.screenTimeoutSec, key: "screenTimeoutSec", to: $0) })) {
                    ForEach(Self.options(Self.timeouts, current: s.screenTimeoutSec), id: \.self) { Text(Format.timeout($0)).tag($0) }
                } label: {
                    Label("Screen timeout", systemImage: "timer")
                }
                .disabled(!s.canChangeSystem)
                toggle("24-hour time", "clock", \.use24Hour, "use24Hour", s, enabled: s.canChangeSystem)
                toggle("Hide status icons", "eye.slash", \.hideStatusIcons, "hideStatusIcons", s, enabled: s.canChangeSystem)
            } header: {
                Text("Watch")
            } footer: {
                if !s.canChangeSystem {
                    Text("Run the WristLink installer once to allow changing these from the iPhone.")
                } else {
                    Text("Hide status icons removes the \"Disconnected from phone\" icon Samsung shows on the watch face.")
                }
            }

            Section {
                toggle("Status notification", "pin", \.statusNotification, "statusNotification", s)
            } header: {
                Text("Advanced")
            } footer: {
                Text("Keeps the connection service in the foreground. Off hides its notification but Android may pause it more often.")
            }

            Section {
                if let app = link.info?.app { LabeledContent("Watch app", value: app) }
                LabeledContent("iPhone app", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "–")
                Button("Use a different watch", role: .destructive) { link.forgetWatch() }
            } header: {
                Text("About")
            }
        }
        .refreshable { link.refreshSettings() }
    }

    private func toggle(_ title: String, _ icon: String, _ keyPath: WritableKeyPath<WatchSettings, Bool>,
                        _ key: String, _ s: WatchSettings, detail: String? = nil, enabled: Bool = true) -> some View {
        Toggle(isOn: Binding(get: { s[keyPath: keyPath] }, set: { link.set(keyPath, key: key, to: $0) })) {
            Label {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                    if let detail {
                        Text(detail).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            } icon: {
                Image(systemName: icon)
            }
        }
        .disabled(!enabled)
    }

    /// The picker's choices, plus whatever the watch is set to now if it isn't one of them.
    private static func options<T: Comparable & Hashable>(_ choices: [T], current: T) -> [T] {
        choices.contains(current) ? choices : (choices + [current]).sorted()
    }

    private func sleepFooter(_ s: WatchSettings) -> String {
        if s.sleepGuard && s.sleepGuardReady == false {
            return "The watch can't change Do Not Disturb or raise to wake yet. Run the WristLink installer once."
        }
        if s.quietNow == true {
            return "Quiet now: Do Not Disturb is on (alarms still ring) and raising the wrist doesn't wake the screen."
        }
        let when = s.sleepDetection == "steps"
            ? "Between 22:00 and 10:00, once the watch has been still for a while,"
            : "Whenever the watch detects that you're asleep, naps included,"
        return "\(when) it turns on Do Not Disturb (alarms still ring) and turns off raise to wake, and undoes both when you wake up."
    }
}

private struct WeatherSection: View {
    @ObservedObject var weather: WeatherFeed

    var body: some View {
        Section {
            Toggle(isOn: $weather.enabled) {
                Label("Send weather to the watch", systemImage: "cloud.sun")
            }
        } header: {
            Text("Weather")
        } footer: {
            Text("From Open-Meteo for where your iPhone is, about every 30 minutes while the watch is nearby. "
                 + "To see it on the watch face, touch and hold the face, tap Customize, pick a spot and choose "
                 + "WristLink's Weather or Sunrise & sunset. There is also a Weather tile.")
        }
    }
}

private struct CategoriesView: View {
    @EnvironmentObject private var link: WatchLink

    var body: some View {
        Form {
            Section {
                ForEach(link.settings?.categories ?? []) { category in
                    Toggle(category.name, isOn: Binding(
                        get: { category.enabled },
                        set: { link.setCategory(category.id, enabled: $0) }
                    ))
                }
            } footer: {
                Text("iOS sorts every notification into one of these. Turned off, that kind never reaches the watch.")
            }
        }
        .navigationTitle("Categories")
    }
}

// MARK: - Building blocks

enum Theme {
    static let gradient = LinearGradient(
        colors: [Color(red: 0.31, green: 0.76, blue: 0.97), Color(red: 0.49, green: 0.30, blue: 1.0)],
        startPoint: .topLeading, endPoint: .bottomTrailing
    )
    static let background = Color(.systemGroupedBackground)
    static let card = Color(.secondarySystemGroupedBackground)
}

private extension View {
    func card() -> some View {
        padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.card, in: .rect(cornerRadius: 22))
    }
}

private struct ConnectedPill: View {
    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(.green).frame(width: 8, height: 8)
            Text("Connected").font(.caption.weight(.semibold))
        }
        .padding(.horizontal, 10).padding(.vertical, 4)
        .background(.green.opacity(0.15), in: .capsule)
        .foregroundStyle(.green)
    }
}

private struct StatTile: View {
    let icon: String
    let color: Color
    let title: String
    let value: String
    var detail: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: icon)
                .font(.headline)
                .foregroundStyle(color)
                .frame(width: 34, height: 34)
                .background(color.opacity(0.15), in: .circle)
            Text(value).font(.title2.weight(.bold)).lineLimit(1).minimumScaleFactor(0.6)
            Text(title).font(.caption).foregroundStyle(.secondary)
            if let detail {
                Text(detail).font(.caption2).foregroundStyle(.tertiary).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Theme.card, in: .rect(cornerRadius: 20))
    }
}

private struct Metric: View {
    let value: String
    let label: String

    var body: some View {
        VStack(spacing: 2) {
            Text(value).font(.title3.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.6)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

enum Format {
    static func duration(_ seconds: TimeInterval) -> String {
        let minutes = Int(seconds / 60)
        return "\(minutes / 60)h \(minutes % 60)m"
    }

    static func delay(_ ms: Int) -> String {
        ms < 1000 ? "<1 s" : "\(Int((Double(ms) / 1000).rounded())) s"
    }

    static func temperature(_ value: Double) -> String {
        "\(Int(value.rounded()))°"
    }

    static func timeout(_ seconds: Int) -> String {
        seconds < 60 ? "\(seconds) seconds" : "\(seconds / 60) minute\(seconds >= 120 ? "s" : "")"
    }

    static func fontScale(_ scale: Double) -> String {
        switch scale {
        case ..<0.95: return "Small"
        case ..<1.1: return "Default"
        case ..<1.25: return "Large"
        default: return "Largest"
        }
    }
}

/// Brightness changes go to the watch when the finger lifts, not on every step of the drag.
private struct BrightnessSlider: View {
    let value: Int
    let onCommit: (Int) -> Void
    @State private var draft: Double = 50

    var body: some View {
        HStack {
            Image(systemName: "sun.min").foregroundStyle(.secondary)
            Slider(value: $draft, in: 1...100) { editing in
                if !editing { onCommit(Int(draft)) }
            }
            Image(systemName: "sun.max.fill").foregroundStyle(.secondary)
        }
        .onAppear { draft = Double(value) }
        .onChange(of: value) { _, newValue in draft = Double(newValue) }
    }
}
