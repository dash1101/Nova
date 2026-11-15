import SwiftUI

/// The Inbox: what needs attention now, requests waiting for your approval, and what happened lately.
struct InboxView: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        let active = app.overview["status"]["active"].array.filter { ["info", "warning", "critical"].contains($0["level"].str) }
        List {
            if app.offline != nil { Section { OfflineBanner().listRowInsets(EdgeInsets()).listRowBackground(Color.clear) } }
            if !app.pendingApprovals.isEmpty {
                Section("Waiting for your approval") {
                    ForEach(Array(app.pendingApprovals.enumerated()), id: \.offset) { _, a in ApprovalRow(a: a) }
                }
            }
            if !active.isEmpty {
                Section("Needs attention") {
                    ForEach(Array(active.enumerated()), id: \.offset) { _, a in
                        EventRow(level: a["level"].str, title: a["title"].str, detail: a["detail"].str, when: a["since"].double)
                            .swipeActions(edge: .trailing) {
                                if app.isAdmin, let key = a["key"].string {
                                    Button("Ignore") {
                                        app.act("Ignored — it alerts again if it comes back") {
                                            _ = try await app.api?.post("/api/v1/alerts/dismiss", ["key": key]); await app.refresh()
                                        }
                                    }.tint(.gray)
                                }
                            }
                    }
                }
            }
            Section(active.isEmpty && app.pendingApprovals.isEmpty ? "" : "Earlier") {
                if app.events.isEmpty {
                    EmptyNote(icon: "tray", title: "Nothing yet", note: "Alerts from your server show up here: a full drive, a container that stopped, a new device.")
                }
                ForEach(Array(app.events.prefix(150).enumerated()), id: \.offset) { _, e in
                    EventRow(level: e["level"].str, title: e["title"].str, detail: e["detail"].str, when: e["t"].double)
                        .swipeActions(edge: .trailing) {
                            if app.isAdmin, let t = e["t"].double {
                                Button("Archive") {
                                    app.act { _ = try await app.api?.post("/api/v1/events/delete", ["t": t]); await app.refresh() }
                                }.tint(.indigo)
                            }
                        }
                }
            }
        }
        .navigationTitle("Inbox")
        .refreshable { await app.refresh(); await app.refreshApprovals() }
    }
}

struct EventRow: View {
    let level: String
    let title: String
    let detail: String
    let when: Double?

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: Level.icon(level)).foregroundStyle(Level.color(level)).padding(.top, 2)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.subheadline.weight(.medium))
                if !detail.isEmpty { Text(detail).font(.footnote).foregroundStyle(.secondary) }
                if let t = when, t > 0 { Text(Fmt.ago(t)).font(.caption).foregroundStyle(.tertiary) }
            }
        }
        .padding(.vertical, 2)
    }
}

/// A browser (or a phone without Face ID confirmation) asked to do something risky: approve or decline it here.
struct ApprovalRow: View {
    @EnvironmentObject var app: AppState
    let a: JSON

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(a["what"].string ?? "\(a["method"].str) \(a["path"].str)").font(.subheadline.weight(.medium))
            Text("Asked by \(a["device_name"].string ?? "a device") · code \(a["code"].str)").font(.footnote).foregroundStyle(.secondary)
            HStack(spacing: 12) {
                Button("Approve") {
                    app.act("Approved") {
                        try await app.risky("Approve: \(a["what"].str)", "POST", "/api/v1/approvals/\(a["id"].str)/approve"); await app.refreshApprovals()
                    }
                }.buttonStyle(.borderedProminent)
                Button("Decline", role: .destructive) {
                    app.act("Declined") { _ = try await app.api?.post("/api/v1/approvals/\(a["id"].str)/deny"); await app.refreshApprovals() }
                }.buttonStyle(.bordered)
            }
        }
        .padding(.vertical, 4)
    }
}
