import SwiftUI

/// The watch's body profile and goals (protocol 6). Each change goes to the watch as
/// `setSetting` with key `profile.<field>`; the watch clamps it to its own limits and answers with
/// the full settings, which this form then shows.
///
/// The ranges and steps are the watch's own Profile screen's (ProfileScreen.kt), so a value set
/// here can also be reached there and isn't clamped to something else on arrival.
struct ProfileSettingsView: View {
    @EnvironmentObject private var link: WatchLink

    private static let sleepGoals = Array(stride(from: 300, through: 660, by: 15))

    var body: some View {
        Form {
            if let error = link.settingsError {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                }
            }
            if let profile = link.settings?.profile {
                // Changes go to the watch: wait for it while it's away
                Group {
                    sections(profile)
                }
                .disabled(link.state != .connected)
            } else {
                Section {
                    Text("Reading the profile from the watch…").foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("Profile & goals")
        .refreshable { link.refreshSettings() }
    }

    @ViewBuilder private func sections(_ p: WatchProfile) -> some View {
        Section {
            Picker(selection: Binding(get: { p.isFemale ? "female" : "male" },
                                      set: { link.setProfile(\.sex, field: "sex", to: $0) })) {
                Text("Male").tag("male")
                Text("Female").tag("female")
            } label: {
                Label("Sex", systemImage: "person")
            }
            Picker(selection: Binding(get: { p.birthYear },
                                      set: { link.setProfile(\.birthYear, field: "birthYear", to: $0) })) {
                ForEach(Self.birthYears(current: p.birthYear), id: \.self) { year in
                    Text(String(year)).tag(year)
                }
            } label: {
                Label("Birth year", systemImage: "birthday.cake")
            }
            stepper("Height", "ruler", \.heightCm, "heightCm", p, range: 120...220, step: 1, unit: "cm")
            stepper("Weight", "scalemass", \.weightKg, "weightKg", p, range: 35...200, step: 1, unit: "kg")
        } header: {
            Text("Body")
        } footer: {
            Text("Used for calories burned, your resting heart-rate range and heart-rate zones (max \(p.maxHeartRate) bpm).")
        }

        Section {
            stepper("Steps", "figure.walk", \.stepGoal, "stepGoal", p, range: 1000...30000, step: 500, unit: "")
            stepper("Active time", "timer", \.activeMinutesGoal, "activeMinutesGoal", p, range: 10...300, step: 5, unit: "min")
            stepper("Active calories", "flame", \.activeCaloriesGoal, "activeCaloriesGoal", p, range: 100...2000, step: 50, unit: "kcal")
        } header: {
            Text("Daily goals")
        } footer: {
            Text("Goals for steps, active time and active calories. Which metric each of the three rings shows (floors, exercise time, …) is picked on the watch, and the Health tab follows it.")
        }

        Section {
            Picker(selection: Binding(get: { p.sleepGoalMinutes },
                                      set: { link.setProfile(\.sleepGoalMinutes, field: "sleepGoalMinutes", to: $0) })) {
                ForEach(Self.options(Self.sleepGoals, current: p.sleepGoalMinutes), id: \.self) { minutes in
                    Text(Format.minutes(Double(minutes))).tag(minutes)
                }
            } label: {
                Label("Sleep goal", systemImage: "bed.double")
            }
            DatePicker(selection: bedtime(p), displayedComponents: .hourAndMinute) {
                Label("Bedtime", systemImage: "moon")
            }
        } header: {
            Text("Sleep")
        } footer: {
            Text("The sleep score compares each night with these.")
        }

        Section {
            toggle("Heart-rate alerts", "heart", \.hrAlerts, "hrAlerts", p)
            if p.hrAlerts {
                stepper("High above", "arrow.up.heart", \.hrHigh, "hrHigh", p, range: 100...150, step: 5, unit: "bpm")
                stepper("Low below", "arrow.down.heart", \.hrLow, "hrLow", p, range: 40...60, step: 5, unit: "bpm")
            }
            toggle("Inactivity alert", "figure.stand", \.inactivityAlert, "inactivityAlert", p)
            toggle("Goal nudges", "target", \.goalNudges, "goalNudges", p)
        } header: {
            Text("Alerts on the watch")
        } footer: {
            Text("Heart-rate alerts buzz after 10 minutes at rest beyond the limit. The inactivity alert asks you to move after 50 still minutes.")
        }
    }

    private func stepper(_ title: String, _ icon: String, _ keyPath: WritableKeyPath<WatchProfile, Int>, _ field: String,
                         _ p: WatchProfile, range: ClosedRange<Int>, step: Int, unit: String) -> some View {
        Stepper(value: Binding(get: { p[keyPath: keyPath] },
                               set: { link.setProfile(keyPath, field: field, to: $0) }),
                in: range, step: step) {
            Label {
                HStack {
                    Text(title)
                    Spacer()
                    Text(unit.isEmpty ? Format.number(Double(p[keyPath: keyPath]))
                                      : "\(Format.number(Double(p[keyPath: keyPath]))) \(unit)")
                        .foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: icon)
            }
        }
    }

    private func toggle(_ title: String, _ icon: String, _ keyPath: WritableKeyPath<WatchProfile, Bool>,
                        _ field: String, _ p: WatchProfile) -> some View {
        Toggle(isOn: Binding(get: { p[keyPath: keyPath] }, set: { link.setProfile(keyPath, field: field, to: $0) })) {
            Label(title, systemImage: icon)
        }
    }

    /// Bedtime as a time of day today; changes go to the watch as minutes after midnight.
    private func bedtime(_ p: WatchProfile) -> Binding<Date> {
        Binding(
            get: {
                Calendar.current.startOfDay(for: Date()).addingTimeInterval(TimeInterval(p.bedtimeMinutes * 60))
            },
            set: { date in
                let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
                let minutes = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
                link.setProfile(\.bedtimeMinutes, field: "bedtimeMinutes", to: minutes)
            }
        )
    }

    private static func birthYears(current: Int) -> [Int] {
        // The watch's picker runs 1930–2020
        let last = min(2020, Calendar.current.component(.year, from: Date()) - 5)
        return options(Array((1930...last).reversed()), current: current).sorted(by: >)
    }

    /// The choices, plus whatever the watch is set to now if it isn't one of them.
    private static func options(_ choices: [Int], current: Int) -> [Int] {
        choices.contains(current) ? choices : (choices + [current]).sorted()
    }
}
