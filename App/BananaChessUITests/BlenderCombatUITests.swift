import XCTest

@MainActor
final class BlenderCombatUITests: XCTestCase {
    func testBuyingBackADestroyedBlender() {
        let app = XCUIApplication()
        app.launchArguments += ["-hasSeenTeachingOverlay", "YES", "-wildcardRulesEnabled", "NO"]
        app.launch()
        app.buttons["Local Two Player. Share one phone and take turns."].tap()
        _ = app.buttons["square_4_0"].waitForExistence(timeout: 5)

        func move(_ f: (Int, Int), _ t: (Int, Int)) {
            app.buttons["square_\(f.0)_\(f.1)"].tap()
            app.buttons["square_\(t.0)_\(t.1)"].tap()
            Thread.sleep(forTimeInterval: 0.9)
        }
        move((4, 2), (3, 2)); move((1, 3), (2, 3))
        move((5, 1), (4, 2)); move((0, 4), (1, 3))
        move((4, 2), (3, 3)); move((1, 3), (2, 4))
        move((3, 3), (2, 4))            // player one destroys player two's blender
        Thread.sleep(forTimeInterval: 1.5)

        // It is now player two's turn and their spawn at row 0, column 4 is empty.
        let spawn = app.buttons["square_0_4"]
        XCTAssertTrue(spawn.label.contains("destroyed"), "spawn should report the loss: \(spawn.label)")
        XCTAssertTrue(spawn.label.contains("buy it back"), "and offer the purchase: \(spawn.label)")

        spawn.tap()
        Thread.sleep(forTimeInterval: 1.5)

        XCTAssertFalse(
            spawn.label.contains("Empty square"),
            "the blender should be standing on its spawn again: \(spawn.label)"
        )
        XCTAssertFalse(spawn.label.contains("destroyed"), "and no longer reported as lost")
        XCTAssertTrue(
            app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'score -3'")).firstMatch.exists,
            "and three points paid for it"
        )
    }
}
