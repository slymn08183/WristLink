import CoreBluetooth
import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var link: WatchLink
    @EnvironmentObject private var health: HealthAccess

    var body: some View {
        NavigationStack {
            List {
                connectionSection
                if let info = link.info {
                    watchSection(info)
                    linkSection(info)
                }
                healthSection
            }
            .navigationTitle("WristLink")
            .refreshable { link.refreshCandidates() }
        }
    }

    // MARK: - Sections

    @ViewBuilder
    private var connectionSection: some View {
        Section {
            switch link.state {
            case .starting:
                Label("Starting…", systemImage: "hourglass")
            case .bluetoothOff:
                Label("Turn on Bluetooth", systemImage: "antenna.radiowaves.left.and.right.slash")
            case .notAllowed:
                Label("Allow Bluetooth for WristLink in Settings", systemImage: "lock")
            case .choosing:
                chooser
            case .connecting:
                Label("Connecting to \(link.watchName ?? "the watch")…", systemImage: "applewatch.radiowaves.left.and.right")
            case .connected:
                Label("Connected to \(link.watchName ?? "the watch")", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
            case .noCompanionService:
                Label("Update WristLink on the watch", systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.orange)
            }
        } header: {
            Text("Watch")
        } footer: {
            if link.state == .connected || link.state == .noCompanionService {
                Button("Use a different watch", role: .destructive) { link.forgetWatch() }
                    .font(.footnote)
            }
        }
    }

    @ViewBuilder
    private var chooser: some View {
        if link.candidates.isEmpty {
            Text("Pair the watch in Settings → Bluetooth first, then pull down to refresh.")
                .foregroundStyle(.secondary)
        } else {
            Text("Which one is your watch?")
                .foregroundStyle(.secondary)
            ForEach(link.candidates, id: \.identifier) { peripheral in
                Button(peripheral.name ?? "Unnamed device") { link.choose(peripheral) }
            }
        }
    }

    private func watchSection(_ info: WatchInfo) -> some View {
        Section("Galaxy Watch") {
            LabeledContent("Battery") {
                Text("\(info.battery)%\(info.charging ? " · charging" : "")")
            }
            if let app = info.app {
                LabeledContent("WristLink on watch", value: app)
            }
        }
    }

    private func linkSection(_ info: WatchInfo) -> some View {
        Section("Last 24 hours") {
            LabeledContent("Notification delay") {
                Text(info.medianDelayMs.map(Self.formatDelay) ?? "–")
            }
            LabeledContent("Notifications", value: "\(info.notifications)")
            LabeledContent("Connected") {
                Text(info.connectedPct.map { "\($0)%" } ?? "–")
            }
            LabeledContent("Drops", value: "\(info.drops)")
        }
    }

    @ViewBuilder
    private var healthSection: some View {
        Section {
            switch health.status {
            case .unavailable:
                Text("Apple Health isn't available on this device")
            case .notAsked:
                Button("Allow Apple Health") { Task { await health.requestAccess() } }
            case .asked:
                HealthSyncRow(sync: link.health, pending: link.info?.pending) { link.syncHealth() }
            case .failed(let message):
                Text("Apple Health: \(message)").foregroundStyle(.red)
            }
        } header: {
            Text("Apple Health")
        } footer: {
            Text("Steps, distance, heart rate and sleep from the watch. It syncs by itself when the watch has new data; workouts come later.")
        }
    }

    private static func formatDelay(_ ms: Int) -> String {
        ms < 1000 ? "under 1 s" : "\(Int((Double(ms) / 1000).rounded())) s"
    }
}

/// Last sync, what's waiting on the watch, and a manual sync button.
private struct HealthSyncRow: View {
    @ObservedObject var sync: HealthSync
    let pending: Int?
    let onSync: () -> Void

    var body: some View {
        if sync.syncing {
            Label("Syncing…", systemImage: "arrow.triangle.2.circlepath")
        } else {
            Button("Sync now", action: onSync)
        }
        if let pending {
            LabeledContent("Waiting on the watch", value: "\(pending)")
        }
        if let lastSync = sync.lastSync {
            LabeledContent("Last sync") {
                Text(lastSync, style: .relative) + Text(" ago")
            }
            LabeledContent("Samples saved", value: "\(sync.lastCount)")
        }
        if let error = sync.lastError {
            Text(error).foregroundStyle(.orange).font(.footnote)
        }
    }
}
