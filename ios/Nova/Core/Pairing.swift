import Foundation

/// What this iPhone knows about its server after pairing: its id there, and the ways to reach it.
struct Pairing: Codable, Equatable {
    var deviceId: String
    var serverName: String
    var role: String                       // "admin" or "viewer"
    var lanURL: String                     // https://192.168.1.20:8495 — the home network, certificate pinned
    var pin: String                        // SHA-256 of the server's certificate (hex)
    var tailnetURL: String = ""            // the same server over Tailscale, same certificate
    var remoteURL: String = ""             // through a Cloudflare tunnel (a real certificate)
    var cfId: String = ""                  // the service token for that tunnel's door
    var cfSecret: String = ""
    var confirmRegistered: Bool = false    // the server knows this iPhone's confirmation key

    private static let slot = "pairing"

    static func load() -> Pairing? {
        guard let d = Keychain.get(slot) else { return nil }
        return try? JSONDecoder().decode(Pairing.self, from: d)
    }

    func save() {
        if let d = try? JSONEncoder().encode(self) { Keychain.set(Pairing.slot, d) }
    }

    static func forget() {
        Keychain.delete(slot)
        Keys.remove(.device)
        Keys.remove(.confirm)
    }
}

/// What a pairing code carries (the QR on the server, or the "nova1:…" text for pairing away from home).
struct PairInfo: Equatable {
    var lan: String
    var pin: String
    var remote: String
    var tailnet: String
    var cfId: String
    var cfSecret: String
    var code: String

    /// Reads a scanned QR or pasted setup text. nil when it isn't one of Nova's.
    static func parse(_ raw: String) -> PairInfo? {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.lowercased().hasPrefix("nova1:") {
            var b64 = String(text.dropFirst(6)).trimmingCharacters(in: .whitespacesAndNewlines)
                .replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
            while b64.count % 4 != 0 { b64 += "=" }
            guard let d = Data(base64Encoded: b64), let s = String(data: d, encoding: .utf8) else { return nil }
            text = s
        }
        guard let d = text.data(using: .utf8) else { return nil }
        let j = JSON.parse(d)
        guard j["nova"].int == 1, let code = j["code"].string, !code.isEmpty else { return nil }
        return PairInfo(lan: normURL(j["lan"].str), pin: j["pin"].str.lowercased(), remote: normURL(j["remote"].str), tailnet: normURL(j["tailnet"].str),
                        cfId: j["cf"]["id"].str, cfSecret: j["cf"]["secret"].str, code: code)
    }
}

/// "nova.example.com" → "https://nova.example.com"; trailing slashes off; "" stays "".
func normURL(_ u: String) -> String {
    var s = u.trimmingCharacters(in: .whitespacesAndNewlines)
    while s.hasSuffix("/") { s.removeLast() }
    if s.isEmpty { return "" }
    return s.contains("://") ? s : "https://" + s
}

/// Is this address one of Tailscale's (100.64.0.0/10)?
func isTailscale(_ url: String) -> Bool {
    guard let host = URL(string: normURL(url))?.host else { return false }
    let p = host.split(separator: ".").compactMap { Int($0) }
    return p.count == 4 && p[0] == 100 && (64...127).contains(p[1])
}
