import Foundation
import UIKit

/// Finds images the watch asks for, so the watch needs no internet of its own: app icons for
/// notifications (by bundle identifier) and album art for the music player (by artist, album and
/// title). Both come from Apple's iTunes Search API, which needs no account or key.
///
/// The watch sends `{"op":"fetch","kind":"icon"|"art","key":…}` (PhoneImages on the watch); this
/// answers `{"op":"image","kind":…,"key":…,"data":"<base64 JPEG>"}` or `"missing":true`.
/// Requests arrive as Bluetooth notifications, which also wake the app in the background.
final class ImageFetcher {

    /// Sends a message to the watch (set by WatchLink).
    var send: (([String: Any]) -> Void)?

    private static let iconSize = 128
    private static let artSize = 200
    /// Answers already sent, kept for a while in case the watch asks again (say it restarted).
    private let cache = NSCache<NSString, NSData>()
    private var inFlight = Set<String>()

    /// A fetch request from the watch.
    func handle(_ request: [String: Any]) {
        guard let kind = request["kind"] as? String, let key = request["key"] as? String else { return }
        let id = "\(kind):\(key)"
        if let cached = cache.object(forKey: id as NSString) {
            reply(kind: kind, key: key, data: cached as Data)
            return
        }
        guard !inFlight.contains(id) else { return }
        inFlight.insert(id)

        let task = BackgroundTask()
        Task {
            let data: Data?
            switch kind {
            case "icon": data = await Self.appIcon(bundleId: key)
            case "art": data = await Self.albumArt(
                artist: request["artist"] as? String ?? "",
                album: request["album"] as? String ?? "",
                title: request["title"] as? String ?? "")
            default: data = nil
            }
            await MainActor.run {
                self.inFlight.remove(id)
                if let data { self.cache.setObject(data as NSData, forKey: id as NSString) }
                self.reply(kind: kind, key: key, data: data)
                task.end()
            }
        }
    }

    private func reply(kind: String, key: String, data: Data?) {
        var message: [String: Any] = ["op": "image", "kind": kind, "key": key]
        if let data { message["data"] = data.base64EncodedString() } else { message["missing"] = true }
        send?(message)
    }

    // MARK: - App icons

    private static func appIcon(bundleId: String) async -> Data? {
        for country in storefronts() {
            var components = URLComponents(string: "https://itunes.apple.com/lookup")!
            components.queryItems = [.init(name: "bundleId", value: bundleId), .init(name: "country", value: country)]
            guard let result = await search(components.url!).first,
                  let artwork = result.artworkUrl100 ?? result.artworkUrl60 else { continue }
            if let data = await download(resized(artwork, to: iconSize), side: iconSize) { return data }
        }
        return nil
    }

    // MARK: - Album art

    private static func albumArt(artist: String, album: String, title: String) async -> Data? {
        let country = storefronts().first ?? "us"
        var candidates: [SearchResult] = []
        if !album.isEmpty {
            candidates = await search(term: "\(artist) \(album)", entity: "album", country: country)
                .filter { matches($0.artistName, artist) && matches($0.collectionName, album) }
        }
        if candidates.isEmpty && !title.isEmpty {
            candidates = await search(term: "\(artist) \(title)", entity: "song", country: country)
                .filter { matches($0.artistName, artist) && matches($0.trackName, title) }
        }
        guard let artwork = candidates.first?.artworkUrl100 else { return nil }
        return await download(resized(artwork, to: 300), side: artSize)
    }

    /// Loose match: case, punctuation and "(Deluxe Edition)" or "- Single" don't matter, and
    /// either name may contain the other ("Artist feat. Someone").
    private static func matches(_ found: String?, _ wanted: String) -> Bool {
        guard let found else { return false }
        let a = normalized(found), b = normalized(wanted)
        return !a.isEmpty && !b.isEmpty && (a == b || a.contains(b) || b.contains(a))
    }

    private static func normalized(_ text: String) -> String {
        var s = text.lowercased()
        s = s.replacingOccurrences(of: #"\s*[\(\[].*?[\)\]]"#, with: "", options: .regularExpression)
        s = s.replacingOccurrences(of: #"\s+-\s+(single|ep)$"#, with: "", options: .regularExpression)
        return s.folding(options: [.diacriticInsensitive], locale: nil)
            .components(separatedBy: CharacterSet.alphanumerics.inverted).joined()
    }

    // MARK: - iTunes Search

    private struct SearchResult: Decodable {
        let artistName: String?
        let collectionName: String?
        let trackName: String?
        let artworkUrl60: String?
        let artworkUrl100: String?
    }

    private struct SearchResponse: Decodable { let results: [SearchResult] }

    private static func search(term: String, entity: String, country: String) async -> [SearchResult] {
        var components = URLComponents(string: "https://itunes.apple.com/search")!
        components.queryItems = [
            .init(name: "term", value: term),
            .init(name: "media", value: "music"),
            .init(name: "entity", value: entity),
            .init(name: "limit", value: "10"),
            .init(name: "country", value: country),
        ]
        return await search(components.url!)
    }

    private static func search(_ url: URL) async -> [SearchResult] {
        guard let response = try? await URLSession.shared.data(from: url),
              let decoded = try? JSONDecoder().decode(SearchResponse.self, from: response.0) else { return [] }
        return decoded.results
    }

    /// The App Store's region first (regional apps are often only listed there), then the US.
    private static func storefronts() -> [String] {
        var list = [String]()
        if let region = Locale.current.region?.identifier.lowercased(), region.count == 2 { list.append(region) }
        if !list.contains("us") { list.append("us") }
        return list
    }

    /// "…/100x100bb.jpg" → the same artwork at another size, as JPEG.
    private static func resized(_ url: String, to side: Int) -> String {
        url.replacingOccurrences(of: #"/\d+x\d+bb\.(jpg|png|webp)$"#, with: "/\(side)x\(side)bb.jpg", options: .regularExpression)
    }

    /// Downloads an image and re-encodes it as a small JPEG for the Bluetooth trip.
    private static func download(_ address: String, side: Int) async -> Data? {
        guard let url = URL(string: address),
              let response = try? await URLSession.shared.data(from: url),
              let image = UIImage(data: response.0) else { return nil }
        let size = CGSize(width: side, height: side)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let scaled = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        return scaled.jpegData(compressionQuality: 0.72)
    }
}

/// A little time from iOS to finish work a Bluetooth wake-up started, given back with [end].
final class BackgroundTask {
    private var id = UIBackgroundTaskIdentifier.invalid

    init() {
        id = UIApplication.shared.beginBackgroundTask(withName: "WristLink") { [weak self] in self?.end() }
    }

    func end() {
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id)
        id = .invalid
    }
}
