import CryptoKit
import Foundation
import UIKit

struct ApiError: LocalizedError {
    var code: Int                         // the HTTP status; 0 = the server couldn't be reached
    var message: String
    var reason = ""                       // "clock" or "replay": refused only for its timing
    var serverTime: Double?
    var body = JSON(nil)

    var offline: Bool { code == 0 }
    var errorDescription: String? { message }
}

/// One HTTPS conversation with a Nova server. On the home network and over Tailscale the server uses its own
/// certificate, so the connection is accepted only when that certificate's SHA-256 is the one pinned at pairing
/// (no certificate authority involved); the Cloudflare address has an ordinary certificate, checked the ordinary way.
final class Transport: NSObject, URLSessionDelegate {
    private let lock = NSLock()
    private var pins: [String: String] = [:]          // host → the certificate fingerprint it must show
    private var seen: [String: String] = [:]          // host → the fingerprint it last showed (for "does it match?")
    private var captureOnly = false                   // pairing by address: look at the certificate, accept nothing yet

    private lazy var session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.waitsForConnectivity = false
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpCookieStorage = nil
        c.timeoutIntervalForResource = 600
        return URLSession(configuration: c, delegate: self, delegateQueue: nil)
    }()

    func pin(_ url: String, to fingerprint: String) {
        guard let host = URL(string: url)?.host, !fingerprint.isEmpty else { return }
        lock.withLock { pins[host] = fingerprint.lowercased() }
    }

    func lastFingerprint(_ url: String) -> String? {
        guard let host = URL(string: url)?.host else { return nil }
        return lock.withLock { seen[host] }
    }

    /// The fingerprint of whatever answers at this address — nothing is sent to it and nothing it says is believed.
    func fingerprint(of url: String) async -> String? {
        lock.withLock { captureOnly = true }
        defer { lock.withLock { captureOnly = false } }
        guard let u = URL(string: url + "/api/v1/ping") else { return nil }
        var req = URLRequest(url: u); req.timeoutInterval = 6
        _ = try? await session.data(for: req)
        return lastFingerprint(url)
    }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        let space = challenge.protectionSpace
        guard space.authenticationMethod == NSURLAuthenticationMethodServerTrust, let trust = space.serverTrust else {
            return completionHandler(.performDefaultHandling, nil)
        }
        let (want, capture) = lock.withLock { (pins[space.host], captureOnly) }
        var shown = ""
        if let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate], let leaf = chain.first {
            shown = SHA256.hash(data: SecCertificateCopyData(leaf) as Data).map { String(format: "%02x", $0) }.joined()
            lock.withLock { seen[space.host] = shown }
        }
        if capture { return completionHandler(.cancelAuthenticationChallenge, nil) }
        guard let want = want else { return completionHandler(.performDefaultHandling, nil) }     // not pinned: the usual checks
        if !shown.isEmpty && shown == want { completionHandler(.useCredential, URLCredential(trust: trust)) }
        else { completionHandler(.cancelAuthenticationChallenge, nil) }
    }

    /// Sends one request. Throws ApiError: code 0 when nothing (or something that isn't Nova) answered.
    func send(_ method: String, _ base: String, _ path: String, body: Data, headers: [String: String], timeout: TimeInterval) async throws -> JSON {
        guard base.hasPrefix("https://"), let url = URL(string: base + path) else { throw ApiError(code: 0, message: "That address isn't https") }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = timeout
        if method != "GET" { req.httpBody = body; req.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        let data: Data, resp: URLResponse
        do { (data, resp) = try await session.data(for: req) }
        catch {
            let ns = error as NSError
            let why = ns.code == NSURLErrorCancelled || ns.code == NSURLErrorServerCertificateUntrusted ? "its certificate isn't the one this iPhone paired with"
                    : ns.code == NSURLErrorTimedOut ? "no answer" : ns.code == NSURLErrorNotConnectedToInternet ? "this iPhone is offline" : "can't connect"
            throw ApiError(code: 0, message: why)
        }
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let j = JSON.parse(data)
        if (200..<300).contains(status) { return j }
        guard let err = j["error"].string else { throw ApiError(code: 0, message: "something else answered there (HTTP \(status))") }
        throw ApiError(code: status, message: j["message"].string ?? err, reason: j["reason"].str, serverTime: j["server_time"].double, body: j)
    }
}

/// The signed API: every request carries this iPhone's id, the time, a one-time number and a signature over all of it
/// by the device key. The server is tried at home first, then over Tailscale, then through Cloudflare.
final class NovaAPI {
    struct Route { let name: String; let base: String }

    private let transport = Transport()
    private let lock = NSLock()
    private var _pairing: Pairing
    private var _via = ""
    private var preferred = ""
    private var clockOffsetMs: Double = 0
    private var alive: [String: Date] = [:]           // route → when it last answered

    init(_ pairing: Pairing) {
        _pairing = pairing
        applyPins()
    }

    var pairing: Pairing {
        get { lock.withLock { _pairing } }
        set { lock.withLock { _pairing = newValue }; applyPins() }
    }

    /// The way the last request went: "home", "tailnet" or "remote".
    var via: String { lock.withLock { _via } }

    private func applyPins() {
        let p = pairing
        transport.pin(p.lanURL, to: p.pin)
        transport.pin(p.tailnetURL, to: p.pin)
    }

    var routes: [Route] {
        let p = pairing
        var r: [Route] = []
        if !p.lanURL.isEmpty { r.append(Route(name: "home", base: p.lanURL)) }
        if !p.tailnetURL.isEmpty && p.tailnetURL != p.lanURL { r.append(Route(name: "tailnet", base: p.tailnetURL)) }
        if !p.remoteURL.isEmpty { r.append(Route(name: "remote", base: p.remoteURL)) }
        return r.filter { $0.base.hasPrefix("https://") }
    }

    /// After the app comes back, or the network changes: start from the home network again.
    func forgetRoute() { lock.withLock { preferred = ""; alive = [:] } }

    func get(_ path: String) async throws -> JSON { try await call("GET", path) }
    func post(_ path: String, _ body: [String: Any] = [:]) async throws -> JSON { try await call("POST", path, body) }
    func delete(_ path: String) async throws -> JSON { try await call("DELETE", path) }

    /// `confirm`: a risky action — iOS asks for Face ID / Touch ID / the passcode with this text, and the request gets
    /// a second signature by the confirmation key.
    func call(_ method: String, _ path: String, _ body: [String: Any]? = nil, confirm: String? = nil) async throws -> JSON {
        let data: Data = method == "GET" ? Data() : ((try? JSONSerialization.data(withJSONObject: body ?? [:])) ?? Data("{}".utf8))
        // a confirmed request is signed once (one prompt) and that exact message is what gets sent
        let fixed = confirm == nil ? nil : try sign(method, path, data, confirm: confirm)
        do { return try await overRoutes(method, path, data, fixed) }
        catch let e as ApiError where e.reason == "clock" && fixed == nil {
            // refused only because this iPhone's clock is off: use the server's time and sign it again
            if let t = e.serverTime { lock.withLock { clockOffsetMs = t - Date().timeIntervalSince1970 * 1000 } }
            do { return try await overRoutes(method, path, data, nil) }
            catch let e2 as ApiError where e2.reason == "clock" {
                throw ApiError(code: 0, message: "This iPhone's clock is off from the server's. Turn on Set Automatically in Settings → General → Date & Time.", reason: "clock")
            }
        }
    }

    private func sign(_ method: String, _ path: String, _ body: Data, confirm: String?) throws -> [String: String] {
        let (off, id) = lock.withLock { (clockOffsetMs, _pairing.deviceId) }
        let ts = String(Int64(Date().timeIntervalSince1970 * 1000 + off))
        var rnd = [UInt8](repeating: 0, count: 18)
        _ = SecRandomCopyBytes(kSecRandomDefault, rnd.count, &rnd)
        let nonce = Data(rnd).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
        let digest = SHA256.hash(data: body).map { String(format: "%02x", $0) }.joined()
        let message = Data("\(method)\n\(path)\n\(ts)\n\(nonce)\n\(digest)".utf8)
        var h = ["X-Nova-Device": id, "X-Nova-Time": ts, "X-Nova-Nonce": nonce, "X-Nova-Client": "ios 2",
                 "X-Nova-Signature": try Keys.sign(.device, message)]
        if let reason = confirm { h["X-Nova-StepUp"] = try Keys.sign(.confirm, message, reason: reason) }
        return h
    }

    private func overRoutes(_ method: String, _ path: String, _ body: Data, _ fixed: [String: String]?) async throws -> JSON {
        var order = routes
        let first = lock.withLock { preferred }
        if let i = order.firstIndex(where: { $0.name == first }) { order.insert(order.remove(at: i), at: 0) }
        if order.isEmpty { throw ApiError(code: 0, message: "This iPhone isn't paired with a server") }
        var notes: [String] = []
        for r in order {
            do {
                // a way in that hasn't answered lately gets a quick knock first, so a dead one costs seconds, not a long wait
                let fresh = lock.withLock { (alive[r.name].map { Date().timeIntervalSince($0) < 20 }) ?? false }
                if !fresh { _ = try await transport.send("GET", r.base, "/api/v1/ping", body: Data(), headers: extra(r), timeout: r.name == "remote" ? 8 : 3.5) }
                var h = try fixed ?? sign(method, path, body, confirm: nil)
                for (k, v) in extra(r) { h[k] = v }
                let j = try await transport.send(method, r.base, path, body: body, headers: h, timeout: 90)
                lock.withLock { _via = r.name; preferred = r.name == "home" ? "" : r.name; alive[r.name] = Date() }
                return j
            } catch let e as ApiError {
                if !e.offline { lock.withLock { alive[r.name] = Date() }; throw e }
                lock.withLock { alive[r.name] = nil }
                notes.append("\(label(r.name)): \(e.message)")
            }
        }
        throw ApiError(code: 0, message: "Can't reach \(pairing.serverName.isEmpty ? "the server" : pairing.serverName) — " + notes.joined(separator: "; "))
    }

    private func extra(_ r: Route) -> [String: String] {
        let p = pairing
        guard r.name == "remote", !p.cfId.isEmpty else { return [:] }
        return ["CF-Access-Client-Id": p.cfId, "CF-Access-Client-Secret": p.cfSecret]
    }

    private func label(_ name: String) -> String { name == "home" ? "home network" : name == "tailnet" ? "Tailscale" : "Cloudflare" }

    // MARK: pairing

    /// Pairs with the code's server: this iPhone's new public key goes to the first of its addresses that answers with
    /// the right certificate. Returns the pairing to keep.
    static func pair(_ info: PairInfo, name: String) async throws -> Pairing {
        let t = Transport()
        t.pin(info.lan, to: info.pin); t.pin(info.tailnet, to: info.pin)
        let pem = try Keys.ensure(.device)
        let form = await MainActor.run { UIDevice.current.userInterfaceIdiom == .pad } ? "tablet" : "phone"
        let body = try JSONSerialization.data(withJSONObject: ["code": info.code, "name": name, "public_key": pem, "form": form])
        var tries: [(String, String, [String: String])] = []
        if !info.lan.isEmpty { tries.append(("home network", info.lan, [:])) }
        if !info.tailnet.isEmpty && info.tailnet != info.lan { tries.append(("Tailscale", info.tailnet, [:])) }
        if !info.remote.isEmpty && !info.cfId.isEmpty { tries.append(("Cloudflare", info.remote, ["CF-Access-Client-Id": info.cfId, "CF-Access-Client-Secret": info.cfSecret])) }
        var notes: [String] = []
        for (label, base, headers) in tries {
            do {
                let j = try await t.send("POST", base, "/api/v1/pair", body: body, headers: headers, timeout: 12)
                guard let id = j["device_id"].string else { throw ApiError(code: 0, message: "the server's answer made no sense") }
                var p = Pairing(deviceId: id, serverName: j["name"].str, role: j["role"].string ?? "admin",
                                lanURL: normURL(j["lan_url"].string ?? info.lan), pin: j["lan_pin"].string ?? info.pin)
                if !p.lanURL.hasPrefix("https://") { p.lanURL = info.lan }
                p.tailnetURL = info.tailnet
                p.remoteURL = normURL(j["remote_url"].string ?? info.remote)
                p.cfId = j["cf_client_id"].string ?? info.cfId
                p.cfSecret = j["cf_client_secret"].string ?? info.cfSecret
                if p.cfId.isEmpty { p.cfId = info.cfId; p.cfSecret = info.cfSecret }
                return p
            } catch let e as ApiError {
                if !e.offline { throw e }
                notes.append("\(label): \(e.message)")
            }
        }
        throw ApiError(code: 0, message: notes.isEmpty ? "The code has no address to pair with" : "Couldn't reach the server — " + notes.joined(separator: "; "))
    }

    /// For pairing by typing the address: the first 12 characters of the certificate fingerprint at that address,
    /// as the server shows them ("9F2C 41AB 07DE"), to compare by eye — and the full fingerprint to pin.
    static func certificate(at url: String) async -> (full: String, short: String)? {
        guard let f = await Transport().fingerprint(of: url), f.count >= 12 else { return nil }
        let s = Array(f.prefix(12).uppercased())
        return (f, "\(String(s[0..<4])) \(String(s[4..<8])) \(String(s[8..<12]))")
    }
}
