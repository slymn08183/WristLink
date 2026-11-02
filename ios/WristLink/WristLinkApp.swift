import SwiftUI

@main
struct WristLinkApp: App {
    // One link for the app's lifetime: it also gets relaunched by iOS for Bluetooth events
    @StateObject private var link = WatchLink()
    @StateObject private var health = HealthAccess()
    @StateObject private var stats = HealthStats()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(link)
                .environmentObject(health)
                .environmentObject(stats)
                .tint(Color(red: 0.31, green: 0.62, blue: 0.97))
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { link.appBecameActive() }
                }
        }
    }
}
