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

    enum CodingKeys: String, CodingKey {
        case protocolVersion = "protocol"
        case app, battery, charging, connectedPct, drops, medianDelayMs, notifications
        case model, pending, sleepSupported
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

    /// Apple Health sync, fed by the sync characteristic.
    let health = HealthSync()

    private var central: CBCentralManager!
    private var watch: CBPeripheral?
    private var syncCharacteristic: CBCharacteristic?
    /// Sync messages are newline-terminated JSON split across notifications.
    private var syncBuffer = Data()

    override init() {
        super.init()
        health.send = { [weak self] message in self?.sendSync(message) }
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
        watchName = nil
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

    /// Pull what the watch has for Apple Health now (the app also does this on its own).
    func syncHealth() {
        health.start()
    }

    private func sendSync(_ message: [String: Any]) {
        guard let watch, let syncCharacteristic,
              let data = try? JSONSerialization.data(withJSONObject: message) else { return }
        watch.writeValue(data, for: syncCharacteristic, type: .withResponse)
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
        syncCharacteristic = nil
        syncBuffer.removeAll()
        health.linkLost()
        state = .connecting
        central.connect(peripheral)
    }
}

extension WatchLink: CBPeripheralDelegate {

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }) else {
            state = .noCompanionService
            return
        }
        peripheral.discoverCharacteristics([Self.infoUUID, Self.syncUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        // The watch app was updated or restarted and its services changed: look again
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
            guard let decoded = try? JSONDecoder().decode(WatchInfo.self, from: data) else { return }
            info = decoded
            health.deviceModel = decoded.model
            health.watchAppVersion = decoded.app
            // The watch announces waiting samples through info; fetch them while iOS lets us run
            if (decoded.pending ?? 0) > 0 && syncCharacteristic != nil {
                health.start()
            }
        case Self.syncUUID:
            syncBuffer.append(data)
            while let newline = syncBuffer.firstIndex(of: 0x0A) {
                let message = syncBuffer[syncBuffer.startIndex..<newline]
                syncBuffer.removeSubrange(syncBuffer.startIndex...newline)
                health.handle(Data(message))
            }
        default:
            break
        }
    }
}
