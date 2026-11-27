import CoreBluetooth
import Foundation

/// What the watch reports about itself (CompanionGattServer on the watch, protocol 1).
struct WatchInfo: Decodable, Equatable {
    let protocolVersion: Int
    let app: String?
    let battery: Int
    let charging: Bool
    let connectedPct: Int?
    let drops: Int
    let medianDelayMs: Int?
    let notifications: Int
    // Protocol 2
    let model: String?
    let pending: Int?
    let sleepSupported: Bool?
    // Protocol 4: when the watch's forecast was fetched (Unix seconds, 0 for none)
    let weatherAt: Int64?
    // Protocol 6
    /// Workouts recorded on the watch that the iPhone hasn't confirmed yet.
    let workoutsPending: Int?
    /// The latest heart rate the watch measured.
    let hr: HeartRate?
    /// A notification that only carries what changes (the full info didn't fit one packet);
    /// the rest is kept from the last full read. Absent from reads and older watch apps.
    let compact: Bool?

    /// A compact notification on top of the info known so far.
    func merged(over old: WatchInfo?) -> WatchInfo {
        guard compact == true, let old else { return self }
        return WatchInfo(
            protocolVersion: protocolVersion,
            app: app ?? old.app,
            battery: battery,
            charging: charging,
            connectedPct: connectedPct ?? old.connectedPct,
            drops: drops,
            medianDelayMs: medianDelayMs ?? old.medianDelayMs,
            notifications: notifications,
            model: model ?? old.model,
            pending: pending ?? old.pending,
            sleepSupported: sleepSupported ?? old.sleepSupported,
            weatherAt: weatherAt ?? old.weatherAt,
            workoutsPending: workoutsPending ?? old.workoutsPending,
            hr: hr ?? old.hr,
            compact: nil
        )
    }

    struct HeartRate: Decodable, Equatable {
        let bpm: Double?
        /// When it was measured, ms.
        let t: Double?

        var date: Date? { t.map { Date(timeIntervalSince1970: $0 / 1000) } }
    }

    enum CodingKeys: String, CodingKey {
        case protocolVersion = "protocol"
        case app, battery, charging, connectedPct, drops, medianDelayMs, notifications
        case model, pending, sleepSupported, weatherAt
        case workoutsPending, hr
        case compact
    }
}

/// The link to the WristLink watch app.
///
/// The iPhone is already connected to the watch, because the watch gets notifications from iOS
/// itself. So instead of scanning, this asks iOS for the peripherals it is connected to and talks
/// to the watch's companion service over that same connection. The first time, the user picks the
/// watch from that list; after that its identifier is remembered.
final class WatchLink: NSObject, ObservableObject {

    static let serviceUUID = CBUUID(string: "57A1E1C0-0001-4B8E-9A1F-6F4B10000000")
    static let infoUUID = CBUUID(string: "57A1E1C0-0002-4B8E-9A1F-6F4B10000000")
    static let syncUUID = CBUUID(string: "57A1E1C0-0003-4B8E-9A1F-6F4B10000000")
    /// Every Bluetooth LE device has Generic Access, so this lists everything iOS is connected to.
    private static let genericAccessUUID = CBUUID(string: "1800")
    private static let savedWatchKey = "watchIdentifier"

    enum State: Equatable {
        case starting
        case bluetoothOff
        case notAllowed
        /// No watch remembered yet: the user picks from [candidates].
        case choosing
        case connecting
        case connected
        /// Connected, but the watch doesn't run a WristLink version with the companion service.
        case noCompanionService
    }

    @Published private(set) var state: State = .starting
    @Published private(set) var info: WatchInfo?
    @Published private(set) var candidates: [CBPeripheral] = []
    @Published private(set) var watchName: String?
    /// The watch's settings, once it has sent them (watch app with protocol 3 or later).
    @Published private(set) var settings: WatchSettings?
    /// The watch refused the last change (usually a missing adb permission).
    @Published var settingsError: String?

    /// Apple Health sync, fed by the sync characteristic.
    let health = HealthSync()
    /// Forecasts for the watch's weather tile and complications.
    let weather = WeatherFeed()
    /// App icons and album art the watch asks for.
    let images = ImageFetcher()
    /// The watch's own daily numbers for the Health tab (protocol 6).
    let summary = SummaryFeed()
    /// Workouts recorded on the watch, into Apple Health (protocol 6).
    let workouts = WorkoutSync()

    private var central: CBCentralManager!
    private var watch: CBPeripheral?
    private var syncCharacteristic: CBCharacteristic?
    /// Sync messages are newline-terminated JSON split across notifications.
    private var syncBuffer = Data()
    /// Pieces waiting for the link to take more writes without response.
    private var outbox: [Data] = []
    /// The outbox was cleared, maybe in the middle of a message: the next one starts with a
    /// newline, which ends the partial line the watch may hold (it ignores the junk line).
    private var leadWithNewline = false
    /// Failed attempts to turn on notifications, per characteristic, for the retry backoff.
    private var subscribeFailures: [CBUUID: Int] = [:]
    /// [info] came from the watch over the current connection (not kept from an earlier one).
    private var infoIsCurrent = false
    /// The summary is asked for once per connection by itself; after that on refresh and syncs.
    private var summaryAsked = false
    /// The last sample sync saved something, so the summary is out of date once workouts are done.
    private var summaryStale = false
    /// The running sample sync came from Sync now, so workouts shouldn't wait out a retry pause.
    private var userAskedSync = false

    /// The companion protocol the watch speaks (0 until its info has been read).
    var protocolVersion: Int { info?.protocolVersion ?? 0 }

    /// Replies to requests arrive as notifications: nothing is asked before they are on.
    private var syncReady: Bool { syncCharacteristic?.isNotifying == true }

    override init() {
        super.init()
        health.send = { [weak self] message in self?.sendSync(message) }
        weather.send = { [weak self] message in self?.sendSync(message) }
        images.send = { [weak self] message in self?.sendSync(message) }
        summary.send = { [weak self] message in self?.sendSync(message) }
        workouts.send = { [weak self] message in self?.sendSync(message) }
        health.onFinished = { [weak self] saved in self?.healthSyncFinished(saved: saved) }
        workouts.onFinished = { [weak self] savedAny in self?.workoutSyncFinished(savedAny: savedAny) }
        central = CBCentralManager(
            delegate: self,
            queue: nil,
            // Lets iOS relaunch the app in the background and hand back the connection
            options: [CBCentralManagerOptionRestoreIdentifierKey: "io.github.slymn08183.wristlink.central"]
        )
    }

    /// Re-list the devices the iPhone is connected to (pull to refresh on the picker).
    func refreshCandidates() {
        guard central.state == .poweredOn else { return }
        candidates = central.retrieveConnectedPeripherals(withServices: [Self.genericAccessUUID])
            .sorted { ($0.name ?? "") < ($1.name ?? "") }
    }

    func choose(_ peripheral: CBPeripheral) {
        UserDefaults.standard.set(peripheral.identifier.uuidString, forKey: Self.savedWatchKey)
        attach(peripheral)
    }

    /// Forget the remembered watch and go back to the picker.
    func forgetWatch() {
        if let watch { central.cancelPeripheralConnection(watch) }
        UserDefaults.standard.removeObject(forKey: Self.savedWatchKey)
        watch = nil
        info = nil
        settings = nil
        watchName = nil
        summaryAsked = false
        state = .choosing
        refreshCandidates()
    }

    private func start() {
        if let saved = UserDefaults.standard.string(forKey: Self.savedWatchKey),
           let id = UUID(uuidString: saved),
           let peripheral = central.retrievePeripherals(withIdentifiers: [id]).first {
            attach(peripheral)
            return
        }
        // Already set up on another install? A watch offering the companion service is the one
        if let known = central.retrieveConnectedPeripherals(withServices: [Self.serviceUUID]).first {
            choose(known)
            return
        }
        state = .choosing
        refreshCandidates()
    }

    /// Pull what the watch has for Apple Health now (the app also does this on its own). Workouts
    /// follow the samples on a watch with protocol 6.
    func syncHealth() {
        if health.syncing { return }
        userAskedSync = true
        health.start()
    }

    /// The app came to the front: settings may have changed on the watch meanwhile.
    func appBecameActive() {
        guard state == .connected else { return }
        refreshSettings()
        if (info?.protocolVersion ?? 0) >= 4 { weather.updateIfNeeded() }
        refreshSummary()
    }

    /// Ask the watch for its summary (protocol 6): everything it keeps the first time, then two weeks.
    func refreshSummary() {
        guard protocolVersion >= 6, syncReady else { return }
        summaryAsked = true
        summary.request()
    }

    /// Change one field of the watch's body profile and goals (protocol 6). Shown right away; the
    /// watch's reply confirms or corrects it.
    func setProfile<Value>(_ keyPath: WritableKeyPath<WatchProfile, Value>, field: String, to value: Value) {
        settings?.profile?[keyPath: keyPath] = value
        settingsError = nil
        sendSync(["op": "setSetting", "key": "profile.\(field)", "value": value])
    }

    /// Samples are in; workouts come next, then a fresh summary if anything new arrived.
    private func healthSyncFinished(saved: Int?) {
        let asked = userAskedSync
        userAskedSync = false
        guard protocolVersion >= 6, syncReady else { return }
        if (saved ?? 0) > 0 { summaryStale = true }
        if saved != nil { workouts.start(automatic: !asked) }
        if !workouts.syncing && summaryStale {
            summaryStale = false
            refreshSummary()
        }
    }

    private func workoutSyncFinished(savedAny: Bool) {
        if savedAny || summaryStale {
            summaryStale = false
            refreshSummary()
        }
    }

    /// Ask the watch for its settings again.
    func refreshSettings() {
        sendSync(["op": "getSettings"])
    }

    /// Change one watch setting. Shown right away; the watch's reply confirms or corrects it.
    func set<Value>(_ keyPath: WritableKeyPath<WatchSettings, Value>, key: String, to value: Value) {
        settings?[keyPath: keyPath] = value
        settingsError = nil
        sendSync(["op": "setSetting", "key": key, "value": value])
    }

    func setCategory(_ id: Int, enabled: Bool) {
        if let index = settings?.categories.firstIndex(where: { $0.id == id }) {
            settings?.categories[index].enabled = enabled
        }
        sendSync(["op": "setSetting", "key": "category.\(id)", "value": enabled])
    }

    /// Route one complete message from the sync channel by its "op".
    private func handleMessage(_ data: Data) {
        struct Envelope: Decodable { let op: String; let error: String? }
        guard let envelope = try? JSONDecoder().decode(Envelope.self, from: data) else {
            // Usually the empty or junk line the watch ends a cut-off message with. A workout
            // garbled on the way is worth saying, instead of waiting out the timeout in silence.
            if data.range(of: Data("\"op\":\"workout\"".utf8)) != nil {
                workouts.handleUnreadable()
            }
            return
        }
        switch envelope.op {
        case "batch":
            health.handle(data)
        case "summary":
            summary.handle(data)
        case "workout":
            workouts.handle(data)
        case "fetch":
            if let request = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                images.handle(request)
            }
        case "settings":
            struct Reply: Decodable { let values: WatchSettings }
            if let reply = try? JSONDecoder().decode(Reply.self, from: data) {
                settings = reply.values
            }
            settingsError = envelope.error
        default:
            break
        }
    }

    /// Newline-terminated JSON, in pieces that each fit one write (a forecast or an image
    /// doesn't). The watch joins them again. Watch apps from protocol 5 take writes without
    /// response, several per connection interval, which an image needs; iOS keeps them in order
    /// as long as only one kind is used.
    private func sendSync(_ message: [String: Any]) {
        guard let watch, let syncCharacteristic,
              let json = try? JSONSerialization.data(withJSONObject: message) else { return }
        var data = Data()
        if leadWithNewline {
            leadWithNewline = false
            data.append(0x0A)
        }
        data.append(json)
        data.append(0x0A)
        let size = max(20, watch.maximumWriteValueLength(for: .withoutResponse))
        var pieces: [Data] = []
        var offset = 0
        while offset < data.count {
            let end = min(offset + size, data.count)
            pieces.append(data.subdata(in: offset..<end))
            offset = end
        }
        if syncCharacteristic.properties.contains(.writeWithoutResponse) {
            outbox.append(contentsOf: pieces)
            pumpOutbox()
        } else {
            pieces.forEach { watch.writeValue($0, for: syncCharacteristic, type: .withResponse) }
        }
    }

    /// Hands queued pieces to iOS while it has room; peripheralIsReady calls this again.
    private func pumpOutbox() {
        guard let watch, let syncCharacteristic else { return }
        while !outbox.isEmpty && watch.canSendWriteWithoutResponse {
            watch.writeValue(outbox.removeFirst(), for: syncCharacteristic, type: .withoutResponse)
        }
    }

    /// Forget half-received and half-sent messages: after a drop, a service change or a new
    /// subscription neither side continues where it was.
    private func resetSyncStreams() {
        syncBuffer.removeAll()
        if !outbox.isEmpty { outbox.removeAll() }
        // Even an empty outbox may have stopped mid-message (iOS took part of it already)
        leadWithNewline = true
    }

    /// Tell the running syncs that bytes are arriving, so a long reply that is still coming in
    /// (a workout queued behind the summary) isn't given up on.
    private func noteSyncActivity() {
        let now = ProcessInfo.processInfo.systemUptime
        health.noteActivity(at: now)
        workouts.noteActivity(at: now)
        summary.noteActivity(at: now)
    }

    /// Start what the watch's info says is waiting, once replies can arrive.
    private func startSyncsIfNeeded(_ info: WatchInfo) {
        guard syncReady else { return }
        // The watch announces waiting samples through info; fetch them while iOS lets us run.
        // Workouts follow the samples; on their own only when no samples are waiting.
        if (info.pending ?? 0) > 0 {
            health.start()
        } else if info.protocolVersion >= 6 && (info.workoutsPending ?? 0) > 0 && !health.syncing {
            workouts.start(automatic: true)
        }
        if info.protocolVersion >= 6 && !summaryAsked {
            refreshSummary()
        }
        // Same for the weather: info tells how old the watch's forecast is
        if info.protocolVersion >= 4 {
            weather.watchReported(weatherAt: info.weatherAt)
        }
    }

    /// The sync characteristic is gone (link down, or the watch's services changed): what was
    /// asked over it won't be answered.
    private func syncChannelLost() {
        syncCharacteristic = nil
        resetSyncStreams()
        health.linkLost()
        workouts.linkLost()
        summary.linkLost()
        summaryAsked = false
        infoIsCurrent = false
    }

    private func attach(_ peripheral: CBPeripheral) {
        watch = peripheral
        watchName = peripheral.name
        peripheral.delegate = self
        state = .connecting
        // A pending connect never times out: iOS completes it whenever the watch is in range,
        // even with the app in the background
        central.connect(peripheral)
    }
}

extension WatchLink: CBCentralManagerDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            if let watch { attach(watch) } else { start() }
        case .poweredOff:
            state = .bluetoothOff
        case .unauthorized:
            state = .notAllowed
        default:
            break
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let restored = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral])?.first {
            watch = restored
            watchName = restored.name
            restored.delegate = self
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        watchName = peripheral.name
        peripheral.discoverServices([Self.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        state = .connecting
        central.connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard peripheral == watch else { return }
        syncChannelLost()
        subscribeFailures.removeAll()
        state = .connecting
        central.connect(peripheral)
    }
}

extension WatchLink: CBPeripheralDelegate {

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        let uuid = characteristic.uuid
        guard uuid == Self.syncUUID || uuid == Self.infoUUID else { return }
        if uuid == Self.syncUUID {
            // A message half received before the change won't be finished after it
            resetSyncStreams()
        }
        if let error {
            retrySubscribing(peripheral, characteristic, error: error)
            return
        }
        subscribeFailures[uuid] = nil
        // Replies to requests arrive as notifications, so ask for settings only once they are on.
        // Say first what this app can do: it fetches icons and album art for the watch.
        if uuid == Self.syncUUID && characteristic.isNotifying {
            sendSync(["op": "hello", "features": ["images"]])
            refreshSettings()
            // If the info read came back first, the syncs it asked for waited for this
            if infoIsCurrent, let info { startSyncsIfNeeded(info) }
            if !summaryAsked { refreshSummary() }
        }
    }

    /// Turning on notifications failed (the watch app restarting, a busy link): try again a few
    /// times, waiting longer each time. Without them no reply would ever arrive.
    private func retrySubscribing(_ peripheral: CBPeripheral, _ characteristic: CBCharacteristic, error: Error) {
        let uuid = characteristic.uuid
        let failures = (subscribeFailures[uuid] ?? 0) + 1
        subscribeFailures[uuid] = failures
        guard failures <= 6 else { return }
        let delay = Double(1 << min(failures - 1, 5))
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, peripheral == self.watch, peripheral.state == .connected,
                  !characteristic.isNotifying else { return }
            // A rediscovery meanwhile brings its own characteristic and subscribes it
            let current = uuid == Self.syncUUID ? self.syncCharacteristic : characteristic.service?.characteristics?
                .first(where: { $0.uuid == uuid })
            guard current === characteristic else { return }
            peripheral.setNotifyValue(true, for: characteristic)
        }
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        pumpOutbox()
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }) else {
            state = .noCompanionService
            return
        }
        peripheral.discoverCharacteristics([Self.infoUUID, Self.syncUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        // The watch app was updated or restarted and its services changed: look again. Nothing
        // asked over the old characteristic will be answered, and a half message is junk now.
        if invalidatedServices.contains(where: { $0.uuid == Self.serviceUUID }) {
            syncChannelLost()
            subscribeFailures.removeAll()
        }
        peripheral.discoverServices([Self.serviceUUID])
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let infoCharacteristic = service.characteristics?.first(where: { $0.uuid == Self.infoUUID }) else {
            state = .noCompanionService
            return
        }
        state = .connected
        // Sync first, so it's ready by the time the info read reports pending samples
        if let sync = service.characteristics?.first(where: { $0.uuid == Self.syncUUID }) {
            syncCharacteristic = sync
            peripheral.setNotifyValue(true, for: sync)
        }
        peripheral.readValue(for: infoCharacteristic)
        peripheral.setNotifyValue(true, for: infoCharacteristic)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value else { return }
        switch characteristic.uuid {
        case Self.infoUUID:
            guard let received = try? JSONDecoder().decode(WatchInfo.self, from: data) else {
                // The info didn't fit one notification: the watch only says it changed
                struct Changed: Decodable { let changed: Bool? }
                if (try? JSONDecoder().decode(Changed.self, from: data))?.changed == true {
                    peripheral.readValue(for: characteristic)
                }
                return
            }
            // A compact notification carries only what changes; before any full read, read it
            if received.compact == true && info == nil {
                peripheral.readValue(for: characteristic)
            }
            let decoded = received.merged(over: info)
            info = decoded
            infoIsCurrent = true
            health.deviceModel = decoded.model
            health.watchAppVersion = decoded.app
            workouts.deviceModel = decoded.model
            workouts.watchAppVersion = decoded.app
            startSyncsIfNeeded(decoded)
        case Self.syncUUID:
            noteSyncActivity()
            // Only the new bytes are searched: a summary is ~100 KB in a few hundred pieces
            var start = data.startIndex
            while let newline = data[start...].firstIndex(of: 0x0A) {
                syncBuffer.append(data[start..<newline])
                let message = syncBuffer
                syncBuffer = Data()
                start = data.index(after: newline)
                handleMessage(message)
            }
            if start < data.endIndex {
                syncBuffer.append(data[start...])
            }
        default:
            break
        }
    }
}
