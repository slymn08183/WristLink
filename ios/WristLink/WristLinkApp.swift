import SwiftUI

@main
struct WristLinkApp: App {
    // One link for the app's lifetime: it also gets relaunched by iOS for Bluetooth events
    @StateObject private var link = WatchLink()
    @StateObject private var health = HealthAccess()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(link)
                .environmentObject(health)
        }
    }
}
