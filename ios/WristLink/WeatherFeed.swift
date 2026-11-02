import CoreLocation
import Foundation
import UIKit

/// Weather for the watch. The watch has no internet of its own, so the iPhone fetches a forecast
/// for where it is (Open-Meteo: free, no account or key) and sends it over the link; the watch
/// shows it in a tile and in watch face complications (WeatherStore on the watch).
///
/// The forecast carries the coming hours and days, so the watch works out the current hour and
/// the next sunrise or sunset by itself and a new one is only needed about every half hour. The
/// watch reports how old its forecast is in its info; when that's too old, this sends a new one,
/// in the background too (the app gets woken for Bluetooth events anyway).
///
/// "While Using" location access is enough: in the background it uses the last known position.
final class WeatherFeed: NSObject, ObservableObject {

    enum Access: Equatable { case notAsked, allowed, denied }

    /// What the iPhone app shows about the forecast it last sent.
    struct Summary: Equatable {
        let place: String?
        let temperature: Double
        let code: Int
        let isDay: Bool
        let high: Double?
        let low: Double?
        let sunrise: Date?
        let sunset: Date?
        let fetchedAt: Date
    }

    @Published private(set) var access: Access = .notAsked
    @Published private(set) var latest: Summary?
    @Published private(set) var updating = false
    @Published private(set) var lastError: String?
    /// Turned off in Settings: nothing is fetched or sent.
    @Published var enabled: Bool {
        didSet {
            UserDefaults.standard.set(enabled, forKey: Self.enabledKey)
            if enabled { updateIfNeeded(force: true) }
        }
    }

    /// Sends a message to the watch (set by WatchLink); nil while the watch isn't connected.
    var send: (([String: Any]) -> Void)?

    static let refreshInterval: TimeInterval = 30 * 60
    private static let retryInterval: TimeInterval = 5 * 60
    private static let enabledKey = "weatherEnabled"
    private static let messageKey = "weatherLastMessage"
    private static let locationKey = "weatherLastLocation"
    private static let placesKey = "weatherPlaces"

    private let manager = CLLocationManager()
    private var locationWaiters: [(CLLocation?) -> Void] = []
    /// When the forecast on the watch was fetched, from its info (nil: unknown or none).
    private var watchForecastDate: Date?
    private var lastAttempt: Date?
    private var backgroundTask = UIBackgroundTaskIdentifier.invalid

    override init() {
        enabled = UserDefaults.standard.object(forKey: Self.enabledKey) as? Bool ?? true
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyKilometer
        access = Self.access(manager.authorizationStatus)
        if let saved = UserDefaults.standard.dictionary(forKey: Self.messageKey) {
            latest = Self.summary(from: saved)
        }
    }

    func requestAccess() {
        manager.requestWhenInUseAuthorization()
    }

    /// The watch said how old its forecast is (Unix seconds, 0 for none).
    func watchReported(weatherAt seconds: Int64?) {
        watchForecastDate = seconds.flatMap { $0 > 0 ? Date(timeIntervalSince1970: TimeInterval($0)) : nil }
        updateIfNeeded()
    }

    /// Fetch and send a forecast if the watch's is missing or old (or right away when [force]).
    func updateIfNeeded(force: Bool = false) {
        guard enabled, access == .allowed, send != nil, !updating else { return }
        if !force {
            if let watchForecastDate, -watchForecastDate.timeIntervalSinceNow < Self.refreshInterval { return }
            if let lastAttempt, -lastAttempt.timeIntervalSinceNow < Self.retryInterval { return }
        }
        lastAttempt = Date()
        updating = true
        lastError = nil

        // iOS may suspend the app again soon after a Bluetooth wake-up; ask for a little time
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "weather") { [weak self] in
            self?.endBackgroundTask()
        }
        currentLocation { [weak self] location in
            guard let self else { return }
            Task {
                let error = await self.fetchAndSend(location)
                await MainActor.run {
                    self.updating = false
                    self.lastError = error
                    self.endBackgroundTask()
                }
            }
        }
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    // MARK: - Location

    /// A fresh fix when the app is open, else the last known position.
    private func currentLocation(_ done: @escaping (CLLocation?) -> Void) {
        let known = manager.location ?? savedLocation()
        let recent = known.map { -$0.timestamp.timeIntervalSinceNow < 15 * 60 } ?? false
        if recent || UIApplication.shared.applicationState != .active {
            done(known)
            return
        }
        locationWaiters.append(done)
        if locationWaiters.count == 1 { manager.requestLocation() }
    }

    private func resolveLocation(_ location: CLLocation?) {
        let found = location ?? manager.location ?? savedLocation()
        if let found {
            UserDefaults.standard.set([found.coordinate.latitude, found.coordinate.longitude], forKey: Self.locationKey)
        }
        let waiters = locationWaiters
        locationWaiters.removeAll()
        waiters.forEach { $0(found) }
    }

    private func savedLocation() -> CLLocation? {
        guard let pair = UserDefaults.standard.array(forKey: Self.locationKey) as? [Double], pair.count == 2 else { return nil }
        return CLLocation(latitude: pair[0], longitude: pair[1])
    }

    // MARK: - Forecast

    /// Returns an error message, or nil when the watch got a new forecast.
    private func fetchAndSend(_ location: CLLocation?) async -> String? {
        guard let location else { return "No location yet. Open WristLink once with location on." }
        let fahrenheit = Locale.current.measurementSystem == .us
        let coordinate = location.coordinate

        var components = URLComponents(string: "https://api.open-meteo.com/v1/forecast")!
        components.queryItems = [
            .init(name: "latitude", value: String(format: "%.3f", coordinate.latitude)),
            .init(name: "longitude", value: String(format: "%.3f", coordinate.longitude)),
            .init(name: "current", value: "temperature_2m,apparent_temperature,weather_code,is_day"),
            .init(name: "hourly", value: "temperature_2m,weather_code,precipitation_probability"),
            .init(name: "daily", value: "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max"),
            .init(name: "timezone", value: "auto"),
            .init(name: "timeformat", value: "unixtime"),
            .init(name: "forecast_days", value: "3"),
            .init(name: "forecast_hours", value: "25"),
            .init(name: "temperature_unit", value: fahrenheit ? "fahrenheit" : "celsius"),
        ]

        let response: OpenMeteo
        do {
            let (data, _) = try await URLSession.shared.data(from: components.url!)
            response = try JSONDecoder().decode(OpenMeteo.self, from: data)
        } catch {
            return "Couldn't get the forecast: \(error.localizedDescription)"
        }
        let place = await placeName(for: location)

        let hours: [[Any]] = response.hourly.time.indices.compactMap { i in
            guard let temp = response.hourly.temperature_2m[i], let code = response.hourly.weather_code[i] else { return nil }
            return [response.hourly.time[i], Self.round1(temp), code, response.hourly.precipitation_probability.flatMap { $0[i] } ?? 0]
        }
        let days: [[Any]] = response.daily.time.indices.compactMap { i in
            guard let high = response.daily.temperature_2m_max[i], let low = response.daily.temperature_2m_min[i],
                  let code = response.daily.weather_code[i] else { return nil }
            return [response.daily.time[i], Self.round1(high), Self.round1(low), code,
                    response.daily.sunrise[i], response.daily.sunset[i],
                    response.daily.precipitation_probability_max.flatMap { $0[i] } ?? 0]
        }
        var now: [String: Any] = ["t": Self.round1(response.current.temperature_2m), "code": response.current.weather_code]
        if let feel = response.current.apparent_temperature { now["feel"] = Self.round1(feel) }

        var message: [String: Any] = [
            "op": "weather",
            "at": Int(Date().timeIntervalSince1970),
            "unit": fahrenheit ? "F" : "C",
            "now": now,
            "hours": hours,
            "days": days,
        ]
        if let place { message["place"] = place }

        return await MainActor.run {
            guard let send else { return "The watch disconnected" }
            send(message)
            watchForecastDate = Date()
            UserDefaults.standard.set(message, forKey: Self.messageKey)
            latest = Self.summary(from: message)
            return nil
        }
    }

    /// The town for the watch's tile, looked up once per area (Apple limits these lookups).
    private func placeName(for location: CLLocation) async -> String? {
        let key = String(format: "%.2f,%.2f", location.coordinate.latitude, location.coordinate.longitude)
        var places = UserDefaults.standard.dictionary(forKey: Self.placesKey) as? [String: String] ?? [:]
        if let cached = places[key] { return cached }
        guard let placemark = try? await CLGeocoder().reverseGeocodeLocation(location).first,
              let name = placemark.locality ?? placemark.subAdministrativeArea ?? placemark.name else { return nil }
        if places.count > 50 { places.removeAll() }
        places[key] = name
        UserDefaults.standard.set(places, forKey: Self.placesKey)
        return name
    }

    private static func round1(_ value: Double) -> Double { (value * 10).rounded() / 10 }

    /// Reads back the message this sent (it's also what the watch stores).
    private static func summary(from message: [String: Any]) -> Summary? {
        guard let now = message["now"] as? [String: Any],
              let temperature = (now["t"] as? NSNumber)?.doubleValue,
              let at = (message["at"] as? NSNumber)?.doubleValue else { return nil }
        let fetchedAt = Date(timeIntervalSince1970: at)
        let today = (message["days"] as? [[Any]])?.first { row in
            guard let start = (row.first as? NSNumber)?.doubleValue else { return false }
            return fetchedAt.timeIntervalSince1970 >= start && fetchedAt.timeIntervalSince1970 < start + 86_400
        }
        let number = { (index: Int) -> Double? in
            guard let today, today.count > index else { return nil }
            return (today[index] as? NSNumber)?.doubleValue
        }
        let sunrise = number(4).map { Date(timeIntervalSince1970: $0) }
        let sunset = number(5).map { Date(timeIntervalSince1970: $0) }
        let isDay = sunrise.flatMap { rise in sunset.map { fetchedAt >= rise && fetchedAt < $0 } } ?? true
        return Summary(
            place: message["place"] as? String,
            temperature: temperature,
            code: (now["code"] as? NSNumber)?.intValue ?? 0,
            isDay: isDay,
            high: number(1),
            low: number(2),
            sunrise: sunrise,
            sunset: sunset,
            fetchedAt: fetchedAt
        )
    }

    private static func access(_ status: CLAuthorizationStatus) -> Access {
        switch status {
        case .authorizedWhenInUse, .authorizedAlways: return .allowed
        case .notDetermined: return .notAsked
        default: return .denied
        }
    }
}

extension WeatherFeed: CLLocationManagerDelegate {

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let newAccess = Self.access(manager.authorizationStatus)
        let changed = newAccess != access
        access = newAccess
        if changed && newAccess == .allowed { updateIfNeeded(force: true) }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        resolveLocation(locations.last)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        resolveLocation(nil)
    }
}

/// The parts of an Open-Meteo forecast response this uses (timeformat=unixtime).
private struct OpenMeteo: Decodable {
    struct Current: Decodable {
        let temperature_2m: Double
        let apparent_temperature: Double?
        let weather_code: Int
    }

    struct Hourly: Decodable {
        let time: [Int]
        let temperature_2m: [Double?]
        let weather_code: [Int?]
        let precipitation_probability: [Int?]?
    }

    struct Daily: Decodable {
        let time: [Int]
        let weather_code: [Int?]
        let temperature_2m_max: [Double?]
        let temperature_2m_min: [Double?]
        let sunrise: [Int]
        let sunset: [Int]
        let precipitation_probability_max: [Int?]?
    }

    let current: Current
    let hourly: Hourly
    let daily: Daily
}

/// WMO weather codes as words and SF Symbols, matching what the watch shows.
enum Sky {
    static func label(_ code: Int, isDay: Bool) -> String {
        switch code {
        case 0: return isDay ? "Sunny" : "Clear"
        case 1: return "Mostly clear"
        case 2: return "Partly cloudy"
        case 3: return "Cloudy"
        case 45, 48: return "Fog"
        case 51, 53, 55: return "Drizzle"
        case 56, 57: return "Freezing drizzle"
        case 61, 63: return "Rain"
        case 65: return "Heavy rain"
        case 66, 67: return "Freezing rain"
        case 71, 73, 77: return "Snow"
        case 75: return "Heavy snow"
        case 80, 81, 82: return "Showers"
        case 85, 86: return "Snow showers"
        case 95: return "Thunderstorm"
        case 96, 99: return "Storm, hail"
        default: return "–"
        }
    }

    static func symbol(_ code: Int, isDay: Bool) -> String {
        switch code {
        case 0: return isDay ? "sun.max.fill" : "moon.stars.fill"
        case 1, 2: return isDay ? "cloud.sun.fill" : "cloud.moon.fill"
        case 3: return "cloud.fill"
        case 45, 48: return "cloud.fog.fill"
        case 51...57: return "cloud.drizzle.fill"
        case 65: return "cloud.heavyrain.fill"
        case 61...67, 80...82: return "cloud.rain.fill"
        case 71...77, 85, 86: return "cloud.snow.fill"
        case 95...99: return "cloud.bolt.rain.fill"
        default: return "cloud.fill"
        }
    }
}
