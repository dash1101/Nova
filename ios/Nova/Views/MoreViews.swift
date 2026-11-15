import SwiftUI

/// Everything else, in one list.
struct MoreView: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        List {
            if app.offline != nil { Section { OfflineBanner().listRowInsets(EdgeInsets()).listRowBackground(Color.clear) } }
            Section("Server") {
                if app.isAdmin {
                    NavigationLink { PowerView() } label: { Label("Power", systemImage: "power") }
                    NavigationLink { ProcessesView() } label: { Label("Task manager", systemImage: "list.bullet.rectangle") }
                }
                NavigationLink { DrivesView() } label: { Label("Drives", systemImage: "internaldrive") }
            }
            Section("Access") {
                NavigationLink { DevicesView() } label: { Label("Users & devices", systemImage: "person.2") }
            }
            Section {
                NavigationLink { SettingsView() } label: { Label("Settings", systemImage: "gearshape") }
            } footer: {
                Text("Storage setup, backups, the app store, lighting and the rest are in Nova web — open your server's address in Safari. They'll come to this app over time.")
            }
        }
        .navigationTitle("More")
    }
}

// MARK: power

struct PowerView: View {
    @EnvironmentObject var app: AppState
    @State private var awake = JSON(nil)
    @State private var schedules: [JSON] = []
    @State private var ask: String?                       // "reboot" or "poweroff", waiting for "are you sure"
    @State private var scheduling = false
    @State private var when = Date().addingTimeInterval(3600)
    @State private var what = "reboot"

    var body: some View {
        List {
            Section {
                Button { ask = "reboot" } label: { Label("Restart the server", systemImage: "arrow.clockwise") }
                Button(role: .destructive) { ask = "poweroff" } label: { Label("Shut down the server", systemImage: "power") }
            } footer: { Text("Both ask for your confirmation. After a shutdown someone has to switch the server on again.") }
            Section("Keep awake") {
                if awake["on"].bool {
                    LabeledContent("Awake", value: awake["forever"].bool ? "until you turn this off" : awake["until"].double.map { "until " + clock($0) } ?? "yes")
                    Button("Let it sleep again") { keepAwake(["off": true], "It can sleep again") }
                } else {
                    Button("For 2 hours") { keepAwake(["hours": 2], "Kept awake for 2 hours") }
                    Button("For 8 hours") { keepAwake(["hours": 8], "Kept awake for 8 hours") }
                    Button("Until I turn it off") { keepAwake(["forever": true], "Kept awake") }
                }
            }
            Section("Scheduled") {
                if schedules.isEmpty { Text("Nothing scheduled").foregroundStyle(.secondary) }
                ForEach(Array(schedules.enumerated()), id: \.offset) { _, s in
                    LabeledContent(s["action"].str == "reboot" ? "Restart" : "Shut down", value: clock(s["at"].double ?? 0, day: true))
                        .swipeActions {
                            Button("Cancel", role: .destructive) {
                                app.act("Cancelled") { try await app.risky("Cancel the scheduled \(s["action"].str == "reboot" ? "restart" : "shutdown")", "DELETE", "/api/v1/power/schedule/\(s["id"].str)"); await load() }
                            }
                        }
                }
                Button("Schedule a restart or shutdown…") { when = Date().addingTimeInterval(3600); scheduling = true }
            }
        }
        .navigationTitle("Power")
        .task { await load() }
        .refreshable { await load() }
        .confirmationDialog(ask == "reboot" ? "Restart \(app.serverName)?" : "Shut down \(app.serverName)?",
                            isPresented: Binding(get: { ask != nil }, set: { if !$0 { ask = nil } }), titleVisibility: .visible) {
            Button(ask == "reboot" ? "Restart" : "Shut down", role: .destructive) {
                let a = ask ?? "reboot"
                app.act(a == "reboot" ? "Restarting — back in a minute or two" : "Shutting down") {
                    try await app.risky(a == "reboot" ? "Restart \(app.serverName)" : "Shut down \(app.serverName)", "POST", "/api/v1/power/\(a)")
                }
            }
        }
        .sheet(isPresented: $scheduling) {
            NavigationStack {
                Form {
                    Picker("Do", selection: $what) { Text("Restart").tag("reboot"); Text("Shut down").tag("poweroff") }.pickerStyle(.segmented)
                    DatePicker("When", selection: $when, in: Date().addingTimeInterval(90)...Date().addingTimeInterval(365 * 86400))
                }
                .navigationTitle("Schedule").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { scheduling = false } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Schedule") {
                            scheduling = false
                            let at = Int(when.timeIntervalSince1970), a = what
                            app.act("Scheduled") {
                                try await app.risky("Schedule a \(a == "reboot" ? "restart" : "shutdown")", "POST", "/api/v1/power/schedule", ["action": a, "at": at]); await load()
                            }
                        }
                    }
                }
            }.presentationDetents([.medium])
        }
    }

    private func keepAwake(_ body: [String: Any], _ done: String) {
        app.act(done) { if let r = try await app.api?.post("/api/v1/power/keep-awake", body) { awake = r } }
    }

    private func load() async {
        guard let api = app.api else { return }
        if let a = try? await api.get("/api/v1/power/keep-awake") { awake = a }
        if let s = try? await api.get("/api/v1/power/schedule") { schedules = s["schedules"].array }
    }

    private func clock(_ t: Double, day: Bool = false) -> String {
        let f = DateFormatter(); f.dateFormat = day ? "EEE d MMM, HH:mm" : "HH:mm"
        return f.string(from: Date(timeIntervalSince1970: t))
    }
}

// MARK: task manager

struct ProcessesView: View {
    @EnvironmentObject var app: AppState
    @State private var procs: [JSON] = []
    @State private var byMemory = false
    @State private var picked: JSON?
    @State private var loaded = false

    var body: some View {
        let sorted = procs.sorted { byMemory ? ($0["mem"].double ?? 0) > ($1["mem"].double ?? 0) : ($0["cpu"].double ?? 0) > ($1["cpu"].double ?? 0) }
        List {
            Picker("Sort by", selection: $byMemory) { Text("Processor").tag(false); Text("Memory").tag(true) }
                .pickerStyle(.segmented).listRowBackground(Color.clear).listRowInsets(EdgeInsets())
            ForEach(Array(sorted.prefix(60).enumerated()), id: \.offset) { _, p in
                Button { if !p["kernel"].bool { picked = p } } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(p["name"].str).font(.subheadline.weight(.medium)).lineLimit(1)
                            Text(p["user"].str).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(String(format: "%.1f%%", p["cpu"].double ?? 0)).font(.subheadline).monospacedDigit()
                            Text(Fmt.bytes(p["mem"].double ?? 0)).font(.caption).foregroundStyle(.secondary).monospacedDigit()
                        }
                    }
                }.foregroundStyle(.primary)
            }
        }
        .overlay { if !loaded { ProgressView() } }
        .navigationTitle("Task manager")
        .refreshable { await load() }
        .task {
            while !Task.isCancelled { await load(); try? await Task.sleep(nanoseconds: 4_000_000_000) }
        }
        .confirmationDialog(picked.map { "\($0["name"].str) (\($0["pid"].int ?? 0))" } ?? "", isPresented: Binding(get: { picked != nil }, set: { if !$0 { picked = nil } }), titleVisibility: .visible) {
            Button("End it") { signal("term") }
            Button("Force stop", role: .destructive) { signal("kill") }
        } message: { Text("End asks it to close; Force stop doesn't ask. Unsaved work in it is lost.") }
    }

    private func signal(_ sig: String) {
        guard let p = picked else { return }
        app.act(sig == "kill" ? "Stopped" : "Asked to close") {
            try await app.risky("\(sig == "kill" ? "Force stop" : "End") \(p["name"].str)", "POST", "/api/v1/processes/\(p["pid"].int ?? 0)/signal", ["sig": sig]); await load()
        }
    }

    private func load() async {
        if let r = try? await app.api?.get("/api/v1/processes") { procs = r["processes"].array; loaded = true }
        else if app.offline != nil { loaded = true }
    }
}

// MARK: drives

struct DrivesView: View {
    @EnvironmentObject var app: AppState
    @State private var drives: [JSON] = []
    @State private var loaded = false

    var body: some View {
        List {
            ForEach(Array(drives.enumerated()), id: \.offset) { _, d in
                Section {
                    HStack(spacing: 10) {
                        Image(systemName: d["bus"].str == "usb" ? "externaldrive" : "internaldrive").foregroundStyle(.tint)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(d["model"].str.isEmpty ? d["name"].str : d["model"].str).font(.subheadline.weight(.medium))
                            Text([Fmt.bytes(d["size"].double ?? 0), d["ssd"].bool ? "SSD" : "hard drive", d["role"].str].filter { !$0.isEmpty }.joined(separator: " · "))
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    if d["smart_passed"].exists {
                        Label(d["smart_passed"].bool ? "Healthy" : "Failing — back it up and replace it", systemImage: d["smart_passed"].bool ? "checkmark.seal" : "exclamationmark.triangle.fill")
                            .foregroundStyle(d["smart_passed"].bool ? Color.green : Color.red).font(.subheadline)
                    }
                    if let t = d["temp"].double { LabeledContent("Temperature", value: String(format: "%.0f°C", t)) }
                    ForEach(Array(d["usage"].array.enumerated()), id: \.offset) { _, u in
                        let total = u["total"].double ?? 0, used = u["used"].double ?? 0
                        VStack(alignment: .leading, spacing: 4) {
                            HStack { Text(u["mount"].str).font(.footnote); Spacer(); Text("\(Fmt.bytes(u["free"].double ?? 0)) free of \(Fmt.bytes(total))").font(.footnote).foregroundStyle(.secondary) }
                            ProgressView(value: total > 0 ? min(used / total, 1) : 0)
                        }
                    }
                }
            }
            if loaded && drives.isEmpty { EmptyNote(icon: "internaldrive", title: "No drives found") }
        }
        .overlay { if !loaded { ProgressView() } }
        .navigationTitle("Drives")
        .refreshable { await load() }
        .task { await load() }
    }

    private func load() async {
        if let r = try? await app.api?.get("/api/v1/hardware") { drives = r["drives"].array }
        loaded = true
    }
}

// MARK: users & devices

struct DevicesView: View {
    @EnvironmentObject var app: AppState
    @State private var devices: [JSON] = []
    @State private var removing: JSON?

    var body: some View {
        List {
            ForEach(Array(devices.enumerated()), id: \.offset) { _, d in
                HStack(spacing: 12) {
                    Image(systemName: icon(d)).font(.title3).foregroundStyle(.tint).frame(width: 28)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(d["name"].str + (d["current"].bool ? " (this iPhone)" : "")).font(.subheadline.weight(.medium))
                        Text([d["role"].str == "viewer" ? "View only" : "Admin", d["user"].str, d["last_seen"].string.map { "seen " + $0 }].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .swipeActions {
                    if app.isAdmin && !d["current"].bool { Button("Remove", role: .destructive) { removing = d } }
                }
            }
        }
        .navigationTitle("Users & devices")
        .refreshable { await load() }
        .task { await load() }
        .confirmationDialog("Remove \(removing?["name"].str ?? "")?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                guard let d = removing else { return }
                app.act("Removed") { try await app.risky("Remove \(d["name"].str)", "DELETE", "/api/v1/devices/\(d["id"].str)"); await load() }
            }
        } message: { Text("It can't use Nova anymore until it's paired again.") }
    }

    private func icon(_ d: JSON) -> String {
        if d["type"].str == "browser" { return "macwindow" }
        if d["type"].str == "watch" { return "applewatch" }
        if d["type"].str == "head" { return "server.rack" }
        return d["form"].str == "tablet" ? "ipad" : d["form"].str == "desktop" ? "desktopcomputer" : "iphone"
    }

    private func load() async {
        if let r = try? await app.api?.get("/api/v1/devices") { devices = r["devices"].array }
    }
}

// MARK: settings

struct SettingsView: View {
    @EnvironmentObject var app: AppState
    @State private var confirmUnpair = false

    var body: some View {
        List {
            Section("This iPhone") {
                LabeledContent("Paired with", value: app.serverName)
                LabeledContent("Access", value: app.isAdmin ? "Admin" : "View only")
                LabeledContent("Connected", value: app.via == "home" ? "on your home network" : app.via == "tailnet" ? "over Tailscale" : app.via == "remote" ? "through Cloudflare" : "—")
            }
            if app.isAdmin {
                Section {
                    if app.pairing?.confirmRegistered == true {
                        Label("On — risky actions ask for Face ID, Touch ID or your passcode", systemImage: "faceid").font(.subheadline)
                    } else {
                        Text("Not set up yet. Until then, risky actions wait for an approval on the server or another admin phone.").font(.subheadline)
                        Button("Set it up now") { app.act("Confirmation is on") { try await app.registerConfirmKey() } }
                    }
                } header: { Text("Confirm with Face ID") } footer: {
                    Text("Restarting, stopping a container, removing a device and the like are signed a second time by a key in this iPhone's Secure Enclave, which only answers after you confirm it's you.")
                }
            }
            Section("Ways to reach the server") {
                if let p = app.pairing {
                    route("Home network", p.lanURL)
                    if !p.tailnetURL.isEmpty && p.tailnetURL != p.lanURL { route("Tailscale", p.tailnetURL) }
                    if !p.remoteURL.isEmpty { route("Cloudflare", p.remoteURL) }
                }
            }
            Section {
                Button("Unpair this iPhone", role: .destructive) { confirmUnpair = true }
            } footer: {
                Text("Nova \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") for iPhone — a preview. The server and the Android app are at github.com/dash1101/Nova.")
            }
        }
        .navigationTitle("Settings")
        .confirmationDialog("Unpair from \(app.serverName)?", isPresented: $confirmUnpair, titleVisibility: .visible) {
            Button("Unpair", role: .destructive) { app.unpair() }
        } message: { Text("This iPhone is removed from the server and its keys are deleted. You can pair again any time.") }
    }

    private func route(_ name: String, _ url: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(name).font(.subheadline)
            Text(url).font(.system(.footnote, design: .monospaced)).foregroundStyle(.secondary).textSelection(.enabled)
        }
    }
}
