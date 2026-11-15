import XCTest

/// Pairs the app with a real Nova server (the one CI installs on the Mac running the Simulator) and walks through it,
/// taking a picture of every page. NOVA_PAIR carries the pairing code: `sudo nova add --json` → "payload".
final class NovaUITests: XCTestCase {
    private let app = XCUIApplication()

    override func setUp() { continueAfterFailure = true }

    private func shot(_ name: String) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        a.name = name; a.lifetime = .keepAlways
        add(a)
    }

    private func anything(_ id: String) -> XCUIElement { app.descendants(matching: .any).matching(identifier: id).firstMatch }

    /// What the app last said in its message bubble ("" when there's none).
    private func said() -> String { anything("toast").exists ? anything("toast").label : "no message shown" }

    func testFirstScreen() {
        app.launchEnvironment["NOVA_UITEST_RESET"] = "1"
        app.launch()
        XCTAssertTrue(app.buttons["scan"].waitForExistence(timeout: 20), "the pairing screen offers to scan the code")
        XCTAssertTrue(app.textFields["address"].exists && app.textFields["code"].exists, "…or to type the address and code")
        shot("00-pair")
    }

    func testPairAndLookAround() throws {
        guard let pair = ProcessInfo.processInfo.environment["NOVA_PAIR"], !pair.isEmpty else { throw XCTSkip("no server to pair with (NOVA_PAIR isn't set)") }
        app.launchEnvironment["NOVA_UITEST_RESET"] = "1"
        app.launchEnvironment["NOVA_UITEST_PAIR"] = pair
        app.launch()

        XCTAssertTrue(anything("status").waitForExistence(timeout: 60), "paired, and Home shows the server's status — \(anything("pairError").exists ? anything("pairError").label : "no error shown")")
        XCTAssertFalse(anything("offline").exists, "the server is reachable")
        sleep(5)                                              // a few live numbers for the graphs
        shot("01-home")
        app.swipeUp(); shot("02-home-more"); app.swipeDown()

        app.tabBars.buttons["Inbox"].tap()
        XCTAssertTrue(app.navigationBars["Inbox"].waitForExistence(timeout: 10))
        shot("03-inbox")

        app.tabBars.buttons["More"].tap()
        XCTAssertTrue(app.staticTexts["Task manager"].waitForExistence(timeout: 10), "an admin sees the task manager")
        shot("04-more")

        app.staticTexts["Drives"].tap()
        XCTAssertTrue(app.staticTexts["Healthy"].waitForExistence(timeout: 30) || app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'free of'")).firstMatch.waitForExistence(timeout: 5), "drives are listed with their space")
        shot("05-drives")
        app.navigationBars.buttons.firstMatch.tap()

        app.staticTexts["Task manager"].tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label ENDSWITH '%'")).firstMatch.waitForExistence(timeout: 60), "processes are listed with their share of the processor")
        shot("06-task-manager")
        app.navigationBars.buttons.firstMatch.tap()

        app.staticTexts["Users & devices"].tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS '(this iPhone)'")).firstMatch.waitForExistence(timeout: 20), "this iPhone is in the device list")
        shot("07-devices")
        app.navigationBars.buttons.firstMatch.tap()

        app.staticTexts["Power"].tap()
        XCTAssertTrue(app.buttons["For 2 hours"].waitForExistence(timeout: 20))
        app.buttons["For 2 hours"].tap()
        XCTAssertTrue(app.buttons["Let it sleep again"].waitForExistence(timeout: 20), "keep awake turned on")
        shot("08-power")
        app.buttons["Let it sleep again"].tap()
        XCTAssertTrue(app.buttons["For 2 hours"].waitForExistence(timeout: 20), "…and off again")
        // a risky action: scheduled, confirmed (the Simulator has no Face ID, so the key signs straight away), listed, cancelled
        app.buttons["Schedule a restart or shutdown…"].tap()
        XCTAssertTrue(app.buttons["Schedule"].waitForExistence(timeout: 10))
        shot("09-schedule")
        app.buttons["Schedule"].tap()
        // The Simulator has no passcode, so this iPhone has no confirmation key: the server holds the action and shows a
        // code — CI approves it the way a person would on the server (sudo nova approve CODE), and the app carries on.
        if anything("approvalCode").waitForExistence(timeout: 15) { shot("10-waiting-for-approval") }
        XCTAssertTrue(app.staticTexts["Restart"].waitForExistence(timeout: 90), "the scheduled restart is listed — \(said())")
        shot("11-scheduled")
        app.staticTexts["Restart"].swipeLeft()
        app.buttons["Cancel"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Nothing scheduled"].waitForExistence(timeout: 90), "…and cancelled — \(said())")
        app.navigationBars.buttons.firstMatch.tap()

        app.staticTexts["Settings"].tap()
        XCTAssertTrue(app.staticTexts["Paired with"].waitForExistence(timeout: 10))
        shot("12-settings")
    }
}
