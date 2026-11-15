import SwiftUI

/// Home: is the server all right, and what is it doing right now.
struct HomeView: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                OfflineBanner()
                if !app.loaded && app.offline == nil {
                    ProgressView("Connecting to \(app.serverName)…").padding(.top, 60)
                } else {
                    StatusCard()
                    HStack(spacing: 12) {
                        Tile(title: "CPU", value: percent(app.now["cpu"].double), icon: "cpu", trail: app.cpuTrail,
                             note: app.now["load"].double.map { String(format: "load %.2f", $0) })
                        Tile(title: "Memory", value: percent(app.now["mem"].double), icon: "memorychip", trail: app.memTrail,
                             note: app.now["mem_used_gb"].double.map { String(format: "%.1f of %.0f GB", $0, app.now["mem_total_gb"].double ?? 0) })
                    }
                    HStack(spacing: 12) {
                        if let t = app.now["temp"].double {
                            Tile(title: "Temperature", value: String(format: "%.0f°C", t), icon: "thermometer.medium", trail: nil, note: "processor")
                        }
                        Tile(title: "Network", value: "↓ " + Fmt.bytes(app.now["rx"].double ?? 0) + "/s", icon: "arrow.up.arrow.down", trail: nil,
                             note: "↑ " + Fmt.bytes(app.now["tx"].double ?? 0) + "/s")
                    }
                    StorageCard()
                    if app.has("docker") { ContainersSummary() }
                    AboutServerCard()
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 24)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(app.serverName)
        .refreshable { await app.refresh() }
        .onAppear { app.live(true) }
        .onDisappear { app.live(false) }
    }

    private func percent(_ v: Double?) -> String { v.map { String(format: "%.0f%%", $0) } ?? "—" }
}

/// The headline ("All systems normal") and anything that needs attention.
private struct StatusCard: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        let st = app.overview["status"]
        let level = st["level"].string ?? "ok"
        let active = st["active"].array.filter { ["info", "warning", "critical"].contains($0["level"].str) }
        Card {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 10) {
                    Image(systemName: Level.icon(level)).font(.title2).foregroundStyle(Level.color(level))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(st["headline"].string ?? "Connected").font(.headline)
                        Text(reach).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                if active.count == 1, !active[0]["detail"].str.isEmpty {      // (the headline already names a single one)
                    Text(active[0]["detail"].str).font(.footnote).foregroundStyle(.secondary)
                } else if active.count > 1 {
                    ForEach(Array(active.prefix(3).enumerated()), id: \.offset) { _, a in
                        Divider()
                        HStack(alignment: .top, spacing: 8) {
                            Dot(color: Level.color(a["level"].str)).padding(.top, 5)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(Fmt.plain(a["title"].str)).font(.subheadline.weight(.medium))
                                if !a["detail"].str.isEmpty { Text(a["detail"].str).font(.footnote).foregroundStyle(.secondary).lineLimit(2) }
                            }
                        }
                    }
                }
                if active.count > 3 { Text("and \(active.count - 3) more in the Inbox").font(.footnote).foregroundStyle(.secondary) }
            }
        }
        .accessibilityIdentifier("status")
    }

    private var reach: String {
        let up = app.now["uptime_s"].double ?? app.overview["server"]["uptime_s"].double
        let how = app.via == "home" ? "on your home network" : app.via == "tailnet" ? "over Tailscale" : app.via == "remote" ? "through Cloudflare" : ""
        return [how, up.map { "up " + Fmt.duration($0) }].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

/// One live number with a small graph of the last two minutes.
private struct Tile: View {
    let title: String
    let value: String
    let icon: String
    let trail: [Double]?                  // nil: this number has no graph
    var note: String?

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 6) {
                Label(title, systemImage: icon).font(.footnote.weight(.medium)).foregroundStyle(.secondary)
                Text(value).font(.title2.weight(.semibold)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.6)
                if let t = trail {         // (the space is kept while the first points arrive, so the tiles don't jump)
                    Sparkline(values: t).stroke(Color.accentColor, style: StrokeStyle(lineWidth: 2, lineJoin: .round)).frame(height: 28)
                }
                if let n = note { Text(n).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
            }
        }
    }
}

/// How full each drive is.
private struct StorageCard: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        let storage = app.overview["status"]["metrics"]["storage"].array
        if !storage.isEmpty {
            Card {
                VStack(alignment: .leading, spacing: 12) {
                    Label("Storage", systemImage: "internaldrive").font(.footnote.weight(.medium)).foregroundStyle(.secondary)
                    ForEach(Array(storage.enumerated()), id: \.offset) { _, s in
                        let pct = s["pct"].double ?? 0
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(s["name"].string ?? s["mount"].str).font(.subheadline.weight(.medium)).lineLimit(1)
                                Spacer()
                                Text(Fmt.bytes(s["free"].double ?? 0) + " free").font(.footnote).foregroundStyle(.secondary).monospacedDigit()
                            }
                            ProgressView(value: min(max(pct, 0), 100), total: 100).tint(pct >= 92 ? .red : pct >= 85 ? .orange : .accentColor)
                        }
                    }
                }
            }
        }
    }
}

private struct ContainersSummary: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        let c = app.overview["containers"]
        let running = c["running"].int ?? 0, total = c["total"].int ?? 0
        if total > 0 {
            Card {
                HStack {
                    Label("Containers", systemImage: "shippingbox").font(.subheadline.weight(.medium))
                    Spacer()
                    Text("\(running) of \(total) running").font(.subheadline).foregroundStyle(running == total ? Color.secondary : Color.orange).monospacedDigit()
                }
            }
        }
    }
}

private struct AboutServerCard: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        let s = app.overview["server"]
        Card {
            VStack(alignment: .leading, spacing: 8) {
                Label("This server", systemImage: "info.circle").font(.footnote.weight(.medium)).foregroundStyle(.secondary)
                row("System", s["os"].str == "macos" ? "macOS" : s["os"].str == "windows" ? "Windows" : "Linux")
                if !s["cpu"].str.isEmpty { row("Processor", s["cpu"].str) }
                if let ram = s["ram_gb"].int, ram > 0 { row("Memory", "\(ram) GB") }
                if !s["board"].str.isEmpty { row("Model", s["board"].str) }
            }
        }
    }

    private func row(_ k: String, _ v: String) -> some View {
        HStack(alignment: .top) {
            Text(k).foregroundStyle(.secondary)
            Spacer()
            Text(v).multilineTextAlignment(.trailing)
        }.font(.subheadline)
    }
}
