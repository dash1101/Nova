import Foundation
import SwiftUI
import UIKit

/// An action the server is holding until someone approves it (another admin phone, or `sudo nova approve CODE` there).
struct Waiting: Identifiable, Equatable {
    let id: String
    let code: String
    let what: String
}

/// Everything the screens show, and the one place that talks to the server for them.
@MainActor
final class AppState: ObservableObject {
    @Published var pairing: Pairing?
    @Published var overview = JSON(nil)
    @Published var now = JSON(nil)                    // the live numbers (CPU, memory, network…)
    @Published var cpuTrail: [Double] = []            // the last two minutes, for the small graphs
    @Published var memTrail: [Double] = []
    @Published var events: [JSON] = []
    @Published var approvals: [JSON] = []
    @Published var offline: String?                   // why the server can't be reached right now
    @Published var unpaired = false                   // the server doesn't know this iPhone anymore
    @Published var loaded = false
    @Published var toast: String?
    @Published var waiting: Waiting?
    @Published var via = ""

    private(set) var api: NovaAPI?
    private var loop: Task<Void, Never>?
    private var liveViews = 0
    private var lastStat: Double = 0
    private var cancelWaiting = false

    init() {
        #if DEBUG
        if ProcessInfo.processInfo.environment["NOVA_UITEST_RESET"] == "1" { Pairing.forget() }      // UI tests start unpaired
        #endif
        if let p = Pairing.load(), Keys.exists(.device) { adopt(p) }
    }

    var isAdmin: Bool { pairing?.role != "viewer" }
    var serverName: String {
        let n = overview["server"]["display_name"].str
        return !n.isEmpty ? n : (overview["server"]["name"].string ?? pairing?.serverName ?? "Server")
    }
    func has(_ feature: String) -> Bool { overview["features"][feature].notFalse }
    var pendingApprovals: [JSON] { approvals.filter { $0["state"].str == "pending" } }

    // MARK: pairing

    func adopt(_ p: Pairing) {
        p.save()
        pairing = p
        api = NovaAPI(p)
        unpaired = false; offline = nil; loaded = false
        start()
    }

    func unpair(tellServer: Bool = true) {
        let old = api, id = pairing?.deviceId
        stop()
        if tellServer, let old = old, let id = id { Task.detached { _ = try? await old.delete("/api/v1/devices/\(id)") } }
        Pairing.forget()
        api = nil; pairing = nil
        overview = JSON(nil); now = JSON(nil); events = []; approvals = []; cpuTrail = []; memTrail = []; waiting = nil
    }

    // MARK: keeping the screens fresh (only while the app is in front)

    func start() {
        guard loop == nil, api != nil else { return }
        api?.forgetRoute()
        loop = Task { [weak self] in
            var tick = 0
            while !Task.isCancelled {
                guard let self = self else { return }
                if tick % 8 == 0 { await self.refresh() }               // every 16 s
                else if self.liveViews > 0 { await self.refreshStats() } // every 2 s, only while a live page is showing
                if tick % 3 == 0, self.isAdmin, self.loaded { await self.refreshApprovals() }
                tick += 1
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
    }

    func stop() { loop?.cancel(); loop = nil }

    /// A page with live numbers appeared (true) or went away (false).
    func live(_ on: Bool) { liveViews = max(0, liveViews + (on ? 1 : -1)) }

    func refresh() async {
        guard let api = api else { return }
        do {
            let o = try await api.get("/api/v1/overview")
            overview = o; offline = nil; loaded = true; via = api.via
            learnAddresses(o)
            await refreshStats()
            if let e = try? await api.get("/api/v1/events") { events = e["events"].array }
            await registerConfirmKeyIfHome()
        } catch let e as ApiError {
            if e.code == 401 { unpaired = true }
            else if e.offline { offline = e.message }
            else { offline = nil; if !loaded { toast = e.message } }
        } catch { offline = error.localizedDescription }
    }

    func refreshStats() async {
        guard let api = api, offline == nil || !loaded else { return }
        guard let s = try? await api.get("/api/v1/stats?since=\(lastStat)") else { return }
        if s["now"].exists { now = s["now"] }
        let pts = s["recent"].array.isEmpty ? s["history"].array : s["recent"].array
        for p in pts {
            guard let t = p["t"].double, t > lastStat else { continue }
            lastStat = t
            cpuTrail.append(p["cpu"].double ?? 0); memTrail.append(p["mem"].double ?? 0)
        }
        if cpuTrail.count > 120 { cpuTrail.removeFirst(cpuTrail.count - 120) }
        if memTrail.count > 120 { memTrail.removeFirst(memTrail.count - 120) }
    }

    func refreshApprovals() async {
        guard let api = api, let a = try? await api.get("/api/v1/approvals") else { return }
        approvals = a["approvals"].array
    }

    /// The server says where it is now (after it moved, or Tailscale was added later): keep this iPhone's addresses current.
    private func learnAddresses(_ o: JSON) {
        guard var p = pairing else { return }
        let before = p
        let s = o["server"]
        if let lan = s["lan_url"].string, lan.hasPrefix("https://"), api?.via != "home" { p.lanURL = lan }
        if let th = s["tailnet_host"].string, !th.isEmpty, let port = URL(string: p.lanURL)?.port { p.tailnetURL = "https://\(th):\(port)" }
        if let r = s["remote_url"].string, !r.isEmpty { p.remoteURL = normURL(r) }
        if !o["server"]["name"].str.isEmpty { p.serverName = serverName }
        if p != before { p.save(); pairing = p; api?.pairing = p }
    }

    /// Face ID confirmation is set up by itself on the home network or over Tailscale (your own networks); from anywhere
    /// else it's a button in Settings, and the server asks for an approval first.
    private func registerConfirmKeyIfHome() async {
        guard let api = api, var p = pairing, !p.confirmRegistered, isAdmin, api.via == "home" || api.via == "tailnet" else { return }
        guard let pem = try? Keys.ensure(.confirm) else { return }
        do { _ = try await api.post("/api/v1/device/stepup-key", ["public_key": pem]) }
        catch let e as ApiError where e.code == 409 { }                 // the server already has it
        catch { return }
        p.confirmRegistered = true; p.save(); pairing = p; api.pairing = p
    }

    /// Settings → Confirm with Face ID → Set it up now (when it couldn't happen by itself).
    func registerConfirmKey() async throws {
        guard let api = api, var p = pairing else { return }
        let pem = try Keys.ensure(.confirm)
        do {
            let r = try await api.post("/api/v1/device/stepup-key", ["public_key": pem])
            _ = try await waitApproval(r, "Turn on Face ID confirmation")
        } catch let e as ApiError where e.code == 409 { }
        p.confirmRegistered = true; p.save(); pairing = p; api.pairing = p
    }

    // MARK: doing things

    /// Runs something the person asked for; a failure is shown instead of thrown, and `done` is shown when it worked.
    func act(_ done: String? = nil, _ work: @escaping () async throws -> Void) {
        Task {
            do { try await work(); if let d = done { toast = d } }
            catch let e as ApiError { if e.code == 401 { unpaired = true } else { toast = e.message } }
            catch { toast = error.localizedDescription }
        }
    }

    /// A risky action (restart, remove a device, approve…): confirmed with Face ID where this iPhone has its confirmation
    /// key at the server; otherwise the server holds it for an approval, and this waits for that.
    @discardableResult
    func risky(_ title: String, _ method: String, _ path: String, _ body: [String: Any]? = nil) async throws -> JSON {
        guard let api = api else { throw ApiError(code: 0, message: "Not paired") }
        let confirmed = (pairing?.confirmRegistered ?? false) && Keys.exists(.confirm)
        let r: JSON
        do { r = try await api.call(method, path, body, confirm: confirmed ? title : nil) }
        catch let e as ApiError where e.body["error"].str == "stepup_required" {
            throw ApiError(code: 403, message: "This needs your confirmation. Turn it on in Settings → This iPhone → Confirm with Face ID.")
        }
        return try await waitApproval(r, title)
    }

    private func waitApproval(_ r: JSON, _ title: String) async throws -> JSON {
        guard let id = r["approval"].string, let api = api else { return r }
        waiting = Waiting(id: id, code: r["code"].str, what: title); cancelWaiting = false
        defer { waiting = nil }
        let until = Date().addingTimeInterval(600)
        while Date() < until {
            if cancelWaiting { throw ApiError(code: 0, message: "Cancelled") }
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard let st = try? await api.get("/api/v1/approvals/\(id)") else { continue }
            switch st["state"].str {
            case "done": return st["result"]
            case "failed": throw ApiError(code: st["status"].int ?? 400, message: st["result"]["error"].string ?? "It didn't work")
            case "denied": throw ApiError(code: 403, message: "That was declined.")
            case "expired": throw ApiError(code: 410, message: "Nobody approved it in time.")
            default: break
            }
        }
        throw ApiError(code: 410, message: "Nobody approved it in time.")
    }

    func stopWaiting() { cancelWaiting = true }
}

/// Colors with a meaning, the same everywhere.
enum Level {
    static func color(_ level: String) -> Color {
        switch level {
        case "critical": return .red
        case "warning": return .orange
        case "ok", "resolved": return .green
        default: return .accentColor
        }
    }
    static func icon(_ level: String) -> String {
        switch level {
        case "critical": return "exclamationmark.octagon.fill"
        case "warning": return "exclamationmark.triangle.fill"
        case "ok", "resolved": return "checkmark.circle.fill"
        default: return "info.circle.fill"
        }
    }
}
