import CryptoKit
import Foundation
import LocalAuthentication
import Security

/// A small wrapper over the Keychain: one named blob per item, this device only, never in backups or iCloud.
enum Keychain {
    private static let service = "app.novalabs.nova"

    static func get(_ name: String) -> Data? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: name, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var out: CFTypeRef?
        if SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data { return d }
        #if targetEnvironment(simulator)
        return UserDefaults.standard.data(forKey: "kc." + name)        // (a Simulator without a signed build has no Keychain)
        #else
        return nil
        #endif
    }

    @discardableResult
    static func set(_ name: String, _ data: Data) -> Bool {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: name]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let ok = SecItemAdd(add as CFDictionary, nil) == errSecSuccess
        #if targetEnvironment(simulator)
        if !ok { UserDefaults.standard.set(data, forKey: "kc." + name); return true }
        #endif
        return ok
    }

    static func delete(_ name: String) {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: name]
        SecItemDelete(base as CFDictionary)
        #if targetEnvironment(simulator)
        UserDefaults.standard.removeObject(forKey: "kc." + name)
        #endif
    }
}

/// This iPhone's two keys, both P-256 and both made in the Secure Enclave, where they can sign but never leave:
///  - the device key signs every request (the server knows its public half from pairing);
///  - the confirmation key signs risky actions a second time, and only after Face ID, Touch ID or the passcode.
/// The Simulator has no Secure Enclave, so there (and only there) the keys are ordinary ones kept in the Keychain.
enum Keys {
    /// Which key: where it's kept in the Keychain, and whether using it asks for Face ID / Touch ID / the passcode.
    struct Kind: Equatable {
        let slot: String
        var asks = false
        static let device = Kind(slot: "key.device")
        static let confirm = Kind(slot: "key.confirm", asks: true)
    }

    struct Failure: LocalizedError { let message: String; var errorDescription: String? { message } }

    static var hasEnclave: Bool { SecureEnclave.isAvailable }

    static func exists(_ kind: Kind) -> Bool { Keychain.get(kind.slot) != nil }

    static func remove(_ kind: Kind) { Keychain.delete(kind.slot) }

    /// Makes the key if there isn't one yet. Returns its public half as PEM (what the server stores).
    @discardableResult
    static func ensure(_ kind: Kind) throws -> String {
        if let pem = try? publicPEM(kind) { return pem }
        if hasEnclave {
            var flags: SecAccessControlCreateFlags = [.privateKeyUsage]
            if kind.asks { flags.insert(.userPresence) }
            let protection = kind.asks ? kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly : kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            var err: Unmanaged<CFError>?
            guard let access = SecAccessControlCreateWithFlags(nil, protection, flags, &err) else {
                throw Failure(message: kind.asks ? "Set a passcode on this iPhone first (Settings → Face ID & Passcode)." : "Couldn't make this iPhone's key.")
            }
            let key: SecureEnclave.P256.Signing.PrivateKey
            do { key = try SecureEnclave.P256.Signing.PrivateKey(accessControl: access) }
            catch { throw Failure(message: kind.asks ? "Set a passcode on this iPhone first (Settings → Face ID & Passcode) — the confirmation key is tied to it." : "Couldn't make this iPhone's key.") }
            guard Keychain.set(kind.slot, key.dataRepresentation) else { throw Failure(message: "Couldn't save this iPhone's key.") }
            return key.publicKey.pemRepresentation
        }
        let key = P256.Signing.PrivateKey()
        guard Keychain.set(kind.slot, key.rawRepresentation) else { throw Failure(message: "Couldn't save this iPhone's key.") }
        return key.publicKey.pemRepresentation
    }

    static func publicPEM(_ kind: Kind) throws -> String {
        guard let blob = Keychain.get(kind.slot) else { throw Failure(message: "This iPhone has no key yet.") }
        if hasEnclave { return try SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: blob).publicKey.pemRepresentation }
        return try P256.Signing.PrivateKey(rawRepresentation: blob).publicKey.pemRepresentation
    }

    /// Signs `message`; the answer is the signature the server expects (DER, base64).
    /// For the confirmation key, iOS shows Face ID / Touch ID / passcode with `reason` first — call it off the main thread.
    static func sign(_ kind: Kind, _ message: Data, reason: String = "") throws -> String {
        guard let blob = Keychain.get(kind.slot) else { throw Failure(message: "This iPhone has no key yet.") }
        if hasEnclave {
            let ctx = LAContext()
            if kind.asks { ctx.localizedReason = reason.isEmpty ? "Confirm it's you" : reason }
            do {
                let key = try SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: blob, authenticationContext: ctx)
                return try key.signature(for: message).derRepresentation.base64EncodedString()
            } catch {
                throw Failure(message: kind.asks ? "Not confirmed." : "This iPhone's key couldn't sign (\(error.localizedDescription)).")
            }
        }
        if kind.asks {                                          // Simulator: ask the same way where it can, then sign
            let ctx = LAContext()
            var e: NSError?
            if ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &e) {
                let sem = DispatchSemaphore(value: 0)
                var ok = false
                ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason.isEmpty ? "Confirm it's you" : reason) { good, _ in ok = good; sem.signal() }
                sem.wait()
                if !ok { throw Failure(message: "Not confirmed.") }
            }
        }
        return try P256.Signing.PrivateKey(rawRepresentation: blob).signature(for: message).derRepresentation.base64EncodedString()
    }
}
