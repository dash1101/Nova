import SwiftUI

/// Docker containers, grouped by the stack (Compose project) they belong to.
struct ContainersView: View {
    @EnvironmentObject var app: AppState
    @State private var containers: [JSON] = []
    @State private var loaded = false
    @State private var search = ""

    var body: some View {
        let shown = containers.filter { search.isEmpty || $0["name"].str.localizedCaseInsensitiveContains(search) || $0["image"].str.localizedCaseInsensitiveContains(search) }
        let stacks = Dictionary(grouping: shown) { $0["stack"].str }.sorted { $0.key.lowercased() < $1.key.lowercased() }
        List {
            if app.offline != nil { Section { OfflineBanner().listRowInsets(EdgeInsets()).listRowBackground(Color.clear) } }
            if loaded && containers.isEmpty {
                EmptyNote(icon: "shippingbox", title: "No containers", note: "Containers you run with Docker on the server show up here.")
            }
            ForEach(stacks, id: \.key) { stack, items in
                Section(stack.isEmpty ? "On their own" : stack) {
                    ForEach(Array(items.enumerated()), id: \.offset) { _, c in
                        NavigationLink { ContainerDetail(name: c["name"].str, reload: load) } label: { ContainerRow(c: c) }
                    }
                }
            }
        }
        .overlay { if !loaded && app.offline == nil { ProgressView() } }
        .navigationTitle("Containers")
        .searchable(text: $search, prompt: "Search")
        .refreshable { await load() }
        .task { await load() }
    }

    private func load() async {
        guard let j = try? await app.api?.get("/api/v1/containers") else { loaded = app.offline == nil ? loaded : true; return }
        containers = j["containers"].array; loaded = true
    }
}

func containerColor(_ c: JSON) -> Color {
    switch c["state"].str {
    case "running": return c["health"].str == "unhealthy" ? .orange : .green
    case "restarting", "paused": return .orange
    default: return c["stopped"].bool ? .gray : .red                   // stopped on purpose is quiet; stopped by itself isn't
    }
}

func containerStatus(_ c: JSON) -> String {
    switch c["state"].str {
    case "running": return "Running" + (c["up_s"].double.map { " for " + Fmt.duration($0) } ?? "") + (c["health"].str == "unhealthy" ? " · unhealthy" : "")
    case "exited", "dead":
        let ago = c["exited_s"].double.map { " " + Fmt.duration($0) + " ago" } ?? ""
        return (c["stopped"].bool ? "Stopped" : "Stopped by itself (code \(c["exit_code"].int ?? 0))") + ago
    default: return c["state"].str.capitalized
    }
}

private struct ContainerRow: View {
    let c: JSON
    var body: some View {
        HStack(spacing: 10) {
            Dot(color: containerColor(c))
            VStack(alignment: .leading, spacing: 2) {
                Text(c["name"].str).font(.body.weight(.medium)).lineLimit(1)
                Text(containerStatus(c)).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
            }
        }
    }
}

/// One container: what it is, start / stop / restart, and its log.
struct ContainerDetail: View {
    @EnvironmentObject var app: AppState
    let name: String
    let reload: () async -> Void
    @State private var c = JSON(nil)
    @State private var lines: [String] = []
    @State private var busy = false

    var body: some View {
        List {
            Section {
                HStack(spacing: 10) { Dot(color: containerColor(c)); Text(c.exists ? containerStatus(c) : "Loading…") }
                if !c["image"].str.isEmpty { LabeledContent("Image", value: c["image"].str) }
                let ports = c["ports"].array.compactMap { p -> String? in p["published"].int.map { "\($0)" } ?? p.string }
                if !ports.isEmpty { LabeledContent("Ports", value: ports.joined(separator: ", ")) }
                if !c["stack"].str.isEmpty { LabeledContent("Stack", value: c["stack"].str) }
            }
            if app.isAdmin {
                Section {
                    if c["state"].str == "running" {
                        action("Restart", "arrow.clockwise", "restart", risky: true)
                        action("Stop", "stop.fill", "stop", risky: true, destructive: true)
                    } else {
                        action("Start", "play.fill", "start", risky: false)
                    }
                }
            }
            Section("Log") {
                if lines.isEmpty { Text("No log lines").foregroundStyle(.secondary) }
                ForEach(Array(lines.suffix(200).enumerated()), id: \.offset) { _, l in
                    Text(l).font(.system(.caption2, design: .monospaced)).textSelection(.enabled).listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets(top: 1, leading: 16, bottom: 1, trailing: 12))
                }
            }
        }
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await load() }
        .task { await load() }
        .disabled(busy)
    }

    private func action(_ title: String, _ icon: String, _ verb: String, risky: Bool, destructive: Bool = false) -> some View {
        Button(role: destructive ? .destructive : nil) {
            busy = true
            app.act("\(title): done") {
                defer { busy = false }
                let path = "/api/v1/containers/\(name)/\(verb)"
                if risky { try await app.risky("\(title) \(name)", "POST", path) } else { _ = try await app.api?.post(path) }
                await load(); await reload()
            }
        } label: { Label(title, systemImage: icon) }
    }

    private func load() async {
        guard let api = app.api else { return }
        if let all = try? await api.get("/api/v1/containers"), let mine = all["containers"].array.first(where: { $0["name"].str == name }) { c = mine }
        if let log = try? await api.get("/api/v1/containers/\(name)/logs?lines=200") { lines = log["lines"].array.map { $0.str } }
    }
}
