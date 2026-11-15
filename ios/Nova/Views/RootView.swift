import SwiftUI

@main
struct NovaApp: App {
    @StateObject private var app = AppState()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(app)
                .onChange(of: phase) { p in
                    if p == .active { app.start() } else if p == .background { app.stop() }
                }
        }
    }
}

/// Pairing first; then the four tabs. Messages, a held action and "this iPhone was removed" sit on top of either.
struct RootView: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        Group {
            if app.pairing == nil { PairView() } else { MainTabs() }
        }
        .overlay(alignment: .bottom) { ToastView() }
        .sheet(item: $app.waiting) { w in WaitingSheet(waiting: w).presentationDetents([.medium]) }
        .alert("This iPhone was removed from the server", isPresented: $app.unpaired) {
            Button("Pair again") { app.unpair(tellServer: false) }
        } message: {
            Text("Someone removed it under Users & devices, or the server was set up again. Pair it again to carry on.")
        }
    }
}

struct MainTabs: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        TabView {
            NavigationStack { HomeView() }
                .tabItem { Label("Home", systemImage: "house.fill") }
            NavigationStack { InboxView() }
                .tabItem { Label("Inbox", systemImage: "tray.fill") }
                .badge(app.overview["status"]["active"].array.count + app.pendingApprovals.count)
            if app.has("docker") {
                NavigationStack { ContainersView() }
                    .tabItem { Label("Containers", systemImage: "shippingbox.fill") }
            }
            NavigationStack { MoreView() }
                .tabItem { Label("More", systemImage: "ellipsis.circle.fill") }
        }
    }
}

/// "Can't reach the server" — shown at the top of a page while that's so, with what each way in said.
struct OfflineBanner: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        if let why = app.offline {
            VStack(alignment: .leading, spacing: 6) {
                Label("Can't reach \(app.serverName)", systemImage: "wifi.slash").font(.headline)
                Text(why).font(.footnote).foregroundStyle(.secondary)
                Button("Try again") { Task { await app.refresh() } }.font(.footnote.weight(.semibold))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(Color.orange.opacity(0.15), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .accessibilityIdentifier("offline")
        }
    }
}

/// A short message that goes away by itself.
struct ToastView: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        if let t = app.toast {
            Text(t)
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16).padding(.vertical, 11)
                .background(.thinMaterial, in: Capsule())
                .shadow(color: .black.opacity(0.15), radius: 8, y: 3)
                .padding(.horizontal, 24).padding(.bottom, 70)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .accessibilityIdentifier("toast")
                .onTapGesture { app.toast = nil }
                .task(id: t) {
                    try? await Task.sleep(nanoseconds: UInt64(min(7, max(3, Double(t.count) / 18)) * 1_000_000_000))
                    if app.toast == t { withAnimation { app.toast = nil } }
                }
        }
    }
}

/// An action the server is holding: what it is, the code to approve it with, and where.
struct WaitingSheet: View {
    @EnvironmentObject var app: AppState
    let waiting: Waiting

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "hourglass").font(.largeTitle).foregroundStyle(.tint)
            Text("Waiting for approval").font(.title3.weight(.semibold))
            Text(waiting.what).multilineTextAlignment(.center).foregroundStyle(.secondary)
            if !waiting.code.isEmpty {
                Text(waiting.code).font(.system(size: 34, weight: .bold, design: .monospaced)).tracking(4)
                    .accessibilityIdentifier("approvalCode")
                Text("Approve it on another admin phone, or on the server:\nsudo nova approve \(waiting.code)")
                    .font(.footnote).multilineTextAlignment(.center).foregroundStyle(.secondary)
            }
            ProgressView().padding(.top, 4)
            Button("Cancel", role: .cancel) { app.stopWaiting() }.padding(.top, 4)
        }
        .padding(24)
        .interactiveDismissDisabled()
    }
}

// MARK: small pieces used on several pages

/// A rounded card.
struct Card<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// The last couple of minutes of a number, as a line.
struct Sparkline: Shape {
    var values: [Double]
    var top: Double = 100

    func path(in r: CGRect) -> Path {
        var p = Path()
        guard values.count > 1, top > 0 else { return p }
        let step = r.width / CGFloat(values.count - 1)
        for (i, v) in values.enumerated() {
            let pt = CGPoint(x: r.minX + CGFloat(i) * step, y: r.maxY - CGFloat(min(max(v / top, 0), 1)) * r.height)
            if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
        }
        return p
    }
}

/// "Nothing here" for an empty list.
struct EmptyNote: View {
    let icon: String
    let title: String
    var note: String = ""

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon).font(.system(size: 34)).foregroundStyle(.secondary)
            Text(title).font(.headline)
            if !note.isEmpty { Text(note).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center) }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 36)
        .listRowBackground(Color.clear)
    }
}

/// A colored dot for a state (running, stopped, warning…).
struct Dot: View {
    let color: Color
    var body: some View { Circle().fill(color).frame(width: 9, height: 9) }
}
