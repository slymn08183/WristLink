import SwiftUI

@main
struct WristLinkApp: App {
    // One link for the app's lifetime: it also gets relaunched by iOS for Bluetooth events
    @StateObject private var link = WatchLink()
    @StateObject private var health = HealthAccess()
    @StateObject private var stats = HealthStats()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(link)
                .environmentObject(health)
                .environmentObject(stats)
                .tint(Color(red: 0.31, green: 0.62, blue: 0.97))
        }
    }
}
