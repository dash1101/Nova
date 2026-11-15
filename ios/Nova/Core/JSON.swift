import Foundation

/// The server's answers, read the forgiving way: a key that isn't there is simply nil (as the Android app reads them).
struct JSON {
    let raw: Any?

    init(_ raw: Any?) { self.raw = (raw is NSNull) ? nil : raw }

    static func parse(_ data: Data) -> JSON {
        JSON(try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]))
    }

    subscript(key: String) -> JSON { JSON((raw as? [String: Any])?[key]) }

    var exists: Bool { raw != nil }
    var string: String? { raw as? String }
    var str: String { (raw as? String) ?? "" }
    var double: Double? { (raw as? NSNumber)?.doubleValue }
    var int: Int? { (raw as? NSNumber)?.intValue }
    var bool: Bool { (raw as? Bool) ?? false }
    /// true unless the server said false (what a missing feature flag means).
    var notFalse: Bool { (raw as? Bool) ?? true }
    var array: [JSON] { ((raw as? [Any]) ?? []).map { JSON($0) } }
    var dict: [String: JSON] { ((raw as? [String: Any]) ?? [:]).mapValues { JSON($0) } }
}

enum Fmt {
    /// The server puts a symbol in front of an alert's title for Discord ("⚠️ Drive is hot"); this app shows its own icon.
    static func plain(_ title: String) -> String {
        var s = Substring(title)
        while let c = s.first, !(c.isLetter || c.isNumber || c == "/" || c == "\"" || c == "(") { s = s.dropFirst() }
        return s.isEmpty ? title : String(s)
    }

    /// 1536 → "1.5 KB", for sizes and speeds.
    static func bytes(_ n: Double) -> String {
        let units = ["B", "KB", "MB", "GB", "TB", "PB"]
        var v = max(0, n), i = 0
        while v >= 1024 && i < units.count - 1 { v /= 1024; i += 1 }
        return i == 0 ? "\(Int(v)) B" : String(format: v >= 100 ? "%.0f %@" : "%.1f %@", v, units[i])
    }

    /// 93784 s → "1d 2h"; 3700 → "1h 1m"; 50 → "50s".
    static func duration(_ seconds: Double) -> String {
        let s = Int(max(0, seconds))
        if s >= 86400 { return "\(s / 86400)d \(s % 86400 / 3600)h" }
        if s >= 3600 { return "\(s / 3600)h \(s % 3600 / 60)m" }
        if s >= 60 { return "\(s / 60)m" }
        return "\(s)s"
    }

    /// A moment as people say it: "just now", "5 min ago", "3 h ago", "Tue 14:05", "12 Nov".
    static func ago(_ t: Double) -> String {
        let d = Date().timeIntervalSince1970 - t
        if d < 60 { return "just now" }
        if d < 3600 { return "\(Int(d / 60)) min ago" }
        if d < 86400 { return "\(Int(d / 3600)) h ago" }
        let f = DateFormatter()
        f.dateFormat = d < 6 * 86400 ? "EEE HH:mm" : "d MMM"
        return f.string(from: Date(timeIntervalSince1970: t))
    }
}
