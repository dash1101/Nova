import CryptoKit
import XCTest
@testable import Nova

/// The parts that must agree with the server byte for byte: reading a pairing code, and how a request is signed.
final class NovaTests: XCTestCase {
    func testPairingCodeFromTheQR() {
        let raw = #"{"nova":1,"lan":"https://192.168.1.20:8495","pin":"AB12cd","remote":"nova.example.com","code":"ABCDEFGHJK"}"#
        let p = PairInfo.parse(raw)
        XCTAssertEqual(p?.lan, "https://192.168.1.20:8495")
        XCTAssertEqual(p?.pin, "ab12cd")
        XCTAssertEqual(p?.remote, "https://nova.example.com", "an address typed without https:// is read as https")
        XCTAssertEqual(p?.code, "ABCDEFGHJK")
        XCTAssertEqual(p?.tailnet, "")
    }

    func testSetupCodeForPairingAwayFromHome() {
        let json = #"{"nova":1,"lan":"https://192.168.1.20:8495","pin":"ab","remote":"","code":"ABCDEFGHJK","tailnet":"https://100.101.1.2:8495","cf":{"id":"x.access","secret":"s3"}}"#
        let b64 = Data(json.utf8).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        let p = PairInfo.parse("nova1:" + b64)
        XCTAssertEqual(p?.tailnet, "https://100.101.1.2:8495")
        XCTAssertEqual(p?.cfId, "x.access")
        XCTAssertEqual(p?.cfSecret, "s3")
    }

    func testWhatIsNotAPairingCode() {
        XCTAssertNil(PairInfo.parse("https://example.com"))
        XCTAssertNil(PairInfo.parse(#"{"nova":1,"lan":"https://192.168.1.20:8495"}"#), "no code")
        XCTAssertNil(PairInfo.parse(#"{"nova":2,"code":"X"}"#), "a version this app doesn't know")
        XCTAssertNil(PairInfo.parse("nova1:not-base64!"))
    }

    func testAddresses() {
        XCTAssertEqual(normURL("nova.example.com/"), "https://nova.example.com")
        XCTAssertEqual(normURL(" https://192.168.1.20:8495 "), "https://192.168.1.20:8495")
        XCTAssertEqual(normURL(""), "")
        XCTAssertTrue(isTailscale("https://100.101.1.2:8495"))
        XCTAssertFalse(isTailscale("https://100.1.1.2:8495"))
        XCTAssertFalse(isTailscale("https://192.168.1.20:8495"))
    }

    func testTheDeviceKeySignsWhatTheServerChecks() throws {
        let key = Keys.Kind(slot: "key.unit-test")              // a key of its own: the app hosting these tests keeps its real one
        Keys.remove(key)
        let pem = try Keys.ensure(key)
        XCTAssertTrue(pem.hasPrefix("-----BEGIN PUBLIC KEY-----"), "the server reads the key as PEM")
        XCTAssertEqual(try Keys.ensure(key), pem, "asking again gives the same key")
        let message = Data("GET\n/api/v1/overview\n1794616313839\nabcdefghijklmnopqrstuvwx\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".utf8)
        let sig = try Keys.sign(key, message)
        let der = try XCTUnwrap(Data(base64Encoded: sig))
        let pub = try P256.Signing.PublicKey(pemRepresentation: pem)
        XCTAssertTrue(pub.isValidSignature(try P256.Signing.ECDSASignature(derRepresentation: der), for: message), "ECDSA P-256 over SHA-256, DER")
        Keys.remove(key)
        XCTAssertFalse(Keys.exists(key))
    }

    func testReadingAnswers() {
        let j = JSON.parse(Data(#"{"a":{"b":[1,2.5,"x",true,null]},"n":3,"f":false}"#.utf8))
        XCTAssertEqual(j["a"]["b"].array.count, 5)
        XCTAssertEqual(j["a"]["b"].array[1].double, 2.5)
        XCTAssertEqual(j["a"]["b"].array[2].str, "x")
        XCTAssertTrue(j["a"]["b"].array[3].bool)
        XCTAssertFalse(j["a"]["b"].array[4].exists)
        XCTAssertEqual(j["n"].int, 3)
        XCTAssertNil(j["missing"]["deeper"].string)
        XCTAssertTrue(j["missing"].notFalse, "a feature the server doesn't mention is there")
        XCTAssertFalse(j["f"].notFalse)
    }

    func testNumbersAsPeopleReadThem() {
        XCTAssertEqual(Fmt.bytes(512), "512 B")
        XCTAssertEqual(Fmt.bytes(1536), "1.5 KB")
        XCTAssertEqual(Fmt.bytes(250 * 1024 * 1024 * 1024), "250 GB")
        XCTAssertEqual(Fmt.duration(50), "50s")
        XCTAssertEqual(Fmt.duration(3700), "1h 1m")
        XCTAssertEqual(Fmt.duration(93784), "1d 2h")
    }
}
