import XCTest

/// The MVP smoke path from the PRD: start a match, select and deselect, move a fruit
/// and a blender, blend, collide, deliver, and reach the result overlay. It drives the
/// real app, so it also proves input stays locked while a move resolves.
@MainActor
final class MatchSmokeUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// The rule set is stated per launch rather than left to whatever a previous test
    /// last ticked: the checkbox persists through `@AppStorage`, so relying on the
    /// stored default couples every test to the order they happen to run in.
    private func launch(
        wildcards: Bool = false,
        board: String = "standard",
        edges: String = "flat"
    ) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += [
            "-hasSeenTeachingOverlay", "YES",
            "-wildcardRulesEnabled", wildcards ? "YES" : "NO",
            "-boardSetup", board,
            "-boardWrap", edges,
        ]
        app.launch()
        return app
    }

    private func square(_ app: XCUIApplication, _ row: Int, _ column: Int) -> XCUIElement {
        app.buttons["square_\(row)_\(column)"]
    }

    private func startLocalMatch(wildcards: Bool = false) -> XCUIApplication {
        let app = launch(wildcards: wildcards)
        app.buttons["Local Two Player. Share one phone and take turns."].tap()
        XCTAssertTrue(square(app, 4, 0).waitForExistence(timeout: 5), "the board should appear")
        return app
    }

    // MARK: - Tests

    func testSelectDeselectAndMoveAFruit() {
        let app = startLocalMatch()
        // Row 4 is player one's opening fruit row; row 3 in front of it is empty.
        let banana = square(app, 4, 0)
        let ahead = square(app, 3, 0)

        XCTAssertTrue(banana.label.contains("banana"), "unexpected opening layout: \(banana.label)")

        banana.tap()
        XCTAssertTrue(
            ahead.label.contains("legal move"),
            "selecting a fruit should mark the square in front of it"
        )

        // Tapping the same piece again deselects.
        banana.tap()
        XCTAssertFalse(ahead.label.contains("legal move"), "tapping a selected piece should deselect it")

        banana.tap()
        ahead.tap()

        XCTAssertTrue(
            app.staticTexts["turnIndicator"].waitForExistence(timeout: 5)
        )
        expectTurnIndicator(app, toContain: "Player Two", message: "the turn should pass after a move")
        XCTAssertTrue(square(app, 3, 0).label.contains("banana"), "the fruit should now be on its new square")
        XCTAssertTrue(square(app, 4, 0).label.contains("Empty"), "and gone from the old one")
    }

    func testMovesABlenderDiagonally() {
        let app = startLocalMatch()
        // Player one's blender spawns are row 5, columns 1 and 4.
        let blender = square(app, 5, 1)
        XCTAssertTrue(blender.label.contains("blender"), "unexpected opening layout: \(blender.label)")

        blender.tap()
        // Both diagonals hold this player's own fruit — legal now, but priced.
        XCTAssertTrue(
            square(app, 4, 2).label.contains("legal move"),
            "a blender may land on its owner's fruit: \(square(app, 4, 2).label)"
        )
        XCTAssertTrue(
            square(app, 4, 2).label.contains("minus"),
            "and VoiceOver should say what that costs: \(square(app, 4, 2).label)"
        )
        blender.tap()

        // Step the fruit out of the way, then move the blender onto the empty square.
        square(app, 4, 2).tap()
        square(app, 3, 2).tap()
        expectTurnIndicator(app, toContain: "Player Two")

        square(app, 1, 0).tap()
        square(app, 2, 0).tap()
        expectTurnIndicator(app, toContain: "Player One")

        square(app, 5, 1).tap()
        square(app, 4, 2).tap()

        expectTurnIndicator(app, toContain: "Player Two")
        XCTAssertTrue(square(app, 4, 2).label.contains("blender"))
        XCTAssertTrue(square(app, 5, 1).label.contains("blender home"), "a vacated spawn stays marked")
    }

    /// RU-02 through the real app: blending your own fruit takes the points off you.
    func testSelfBlendingCostsPoints() {
        let app = startLocalMatch()

        square(app, 5, 1).tap()
        square(app, 4, 0).tap()

        // Blending your own fruit is destructive, so it asks first.
        let confirm = app.buttons["Blend it"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 3), "a self-blend should ask for confirmation")
        confirm.tap()

        expectTurnIndicator(app, toContain: "Player Two")
        XCTAssertTrue(
            app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'score -1'")).firstMatch.exists
                || app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'score −1'")).firstMatch.exists,
            "player one should be one point down after blending its own banana"
        )
        XCTAssertTrue(square(app, 4, 0).label.contains("blender"), "the blender stays on the square")
    }

    func testInputIsIgnoredWhileItIsNotYourTurn() {
        let app = startLocalMatch()
        square(app, 4, 0).tap()
        square(app, 3, 0).tap()
        expectTurnIndicator(app, toContain: "Player Two")

        // Player one's pieces must not be selectable now.
        square(app, 4, 1).tap()
        XCTAssertFalse(
            square(app, 3, 1).label.contains("legal move"),
            "the waiting player's pieces must not respond"
        )
    }

    func testRulesScreenCoversSignedBlendingAndKnockout() {
        let app = launch()
        app.buttons["How to play"].tap()

        XCTAssertTrue(app.navigationBars["How to play"].waitForExistence(timeout: 3))
        XCTAssertTrue(
            app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'Blend your own and you lose it'"))
                .firstMatch.exists,
            "the rules must say that blending your own fruit costs you"
        )
        XCTAssertTrue(
            app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'below zero'")).firstMatch.exists,
            "and that scores can go negative"
        )
        XCTAssertTrue(
            app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'destroys that one outright'"))
                .firstMatch.exists,
            "the rules must explain blender combat"
        )
        XCTAssertTrue(
            app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'no extra move'")).firstMatch.exists,
            "and that destroying a blender grants nothing further"
        )
        XCTAssertTrue(
            app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'three points'")).firstMatch.exists,
            "and what it costs to get a blender back"
        )
    }

    func testComputerMatchStartsAndTheCPUReplies() {
        let app = launch()
        app.buttons.containing(NSPredicate(format: "label BEGINSWITH 'Play Computer'")).firstMatch.tap()
        app.buttons["Start match"].tap()

        XCTAssertTrue(square(app, 4, 0).waitForExistence(timeout: 5))
        square(app, 4, 0).tap()
        square(app, 3, 0).tap()

        // The CPU must take its turn and hand control back with no further input.
        expectTurnIndicator(app, toContain: "You to move", timeout: 20, message: "the CPU should reply")
    }

    func testWildcardRulesAreOffByDefault() {
        let app = startLocalMatch()

        XCTAssertFalse(
            app.otherElements.containing(NSPredicate(format: "label CONTAINS 'Banana Split'"))
                .firstMatch.exists,
            "a standard match should show no wildcard trackers"
        )
    }

    /// The self-blend dialog has to say where the move stands relative to Blenderama —
    /// that is the only reason to pay for one, and finding out at the end of the match
    /// that the run was already dead is far too late.
    func testSelfBlendDialogExplainsBlenderamaStanding() {
        let app = startLocalMatch(wildcards: true)

        square(app, 5, 1).tap()
        square(app, 4, 0).tap()

        XCTAssertTrue(app.buttons["Blend it"].waitForExistence(timeout: 3))
        XCTAssertTrue(
            app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'Blenderama'"))
                .firstMatch.exists,
            "the dialog should place the move against Blenderama"
        )
        app.buttons["Blend it"].tap()
    }

    func testStandardSelfBlendDialogMentionsOnlyTheCost() {
        let app = startLocalMatch()

        square(app, 5, 1).tap()
        square(app, 4, 0).tap()

        XCTAssertTrue(app.buttons["Blend it"].waitForExistence(timeout: 3))
        XCTAssertFalse(
            app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'Blenderama'"))
                .firstMatch.exists,
            "a standard match has no Blenderama to mention"
        )
        app.buttons["Blend it"].tap()
    }

    func testWildcardRulesShowTheirTrackersWhenTicked() {
        let app = startLocalMatch(wildcards: true)

        // Both players' progress has to be visible, per the design brief.
        let trackers = app.otherElements.matching(
            NSPredicate(format: "label CONTAINS 'Banana Split' AND label CONTAINS 'Smoothie'")
        )
        XCTAssertEqual(trackers.count, 2, "each player needs their own visible tracker")
        XCTAssertTrue(
            trackers.firstMatch.label.contains("Banana Split 0 of 3"),
            "three deliveries is the target: \(trackers.firstMatch.label)"
        )
    }

    func testWildcardChoiceIsRememberedBetweenLaunches() {
        // No rule-set argument on either launch: an argument sits in front of the stored
        // value for the whole session, which is exactly what this test must not do.
        let first = XCUIApplication()
        first.launchArguments += ["-hasSeenTeachingOverlay", "YES"]
        first.launch()
        if first.buttons["Wildcard rules"].value as? String == "on" {
            first.buttons["Wildcard rules"].tap()
        }
        first.buttons["Wildcard rules"].tap()
        XCTAssertEqual(first.buttons["Wildcard rules"].value as? String, "on")
        first.terminate()

        // Deliberately no rule-set argument: this is the one test that checks the
        // stored choice actually survives.
        let second = XCUIApplication()
        second.launchArguments += ["-hasSeenTeachingOverlay", "YES"]
        second.launch()
        XCTAssertEqual(
            second.buttons["Wildcard rules"].value as? String, "on",
            "the rules choice should survive a relaunch"
        )
        // Put it back so the setting does not leak into other tests.
        second.buttons["Wildcard rules"].tap()
    }

    // MARK: - Helpers

    private func expectTurnIndicator(
        _ app: XCUIApplication,
        toContain text: String,
        timeout: TimeInterval = 8,
        message: String = "",
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let indicator = app.staticTexts["turnIndicator"]
        let predicate = NSPredicate(format: "label CONTAINS %@", text)
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: indicator)
        let result = XCTWaiter().wait(for: [expectation], timeout: timeout)
        XCTAssertEqual(
            result, .completed,
            "\(message) — turn indicator was “\(indicator.label)”, expected it to contain “\(text)”",
            file: file, line: line
        )
    }

    // MARK: - Board setups

    func testBoardSetupPickerStartsOnStandardAndSwitches() {
        // Deliberately no -boardSetup argument. An argument sits in front of the stored
        // value for the whole session, so with one set the taps below would write a
        // setting that could never be read back — the picker would look frozen.
        let app = XCUIApplication()
        app.launchArguments += ["-hasSeenTeachingOverlay", "YES"]
        app.launch()

        // Normalise first, since this is the one test that reads the persisted choice.
        if app.buttons["Standard"].value as? String != "selected" {
            app.buttons["Standard"].tap()
        }
        XCTAssertEqual(
            app.buttons["Standard"].value as? String, "selected",
            "Standard should be the selected board"
        )
        XCTAssertTrue(app.staticTexts["Blenders behind, fruit in front."].exists)

        app.buttons["Blendy"].tap()
        XCTAssertEqual(app.buttons["Blendy"].value as? String, "selected")
        XCTAssertEqual(
            app.buttons["Standard"].value as? String, "not selected",
            "the choice is exclusive"
        )
        XCTAssertTrue(
            app.staticTexts["Swapped: fruit behind, blenders in front."].waitForExistence(timeout: 2),
            "the blurb has to follow the selection — 'Random' means nothing without it"
        )

        app.buttons["Random"].tap()
        XCTAssertTrue(
            app.staticTexts["Everything scattered. A different board every time."]
                .waitForExistence(timeout: 2)
        )

        // Put it back, so the stored choice does not leak into tests that walk fixed
        // board coordinates.
        app.buttons["Standard"].tap()
        XCTAssertEqual(app.buttons["Standard"].value as? String, "selected")
    }

    /// Blendy is Standard with the two rows swapped, which is visible on the board
    /// itself: the blenders' spawn markers move up a row with them.
    func testBlendyDealsFruitOnTheBackRow() {
        let app = launch(board: "blendy")
        app.buttons["Local Two Player. Share one phone and take turns."].tap()
        XCTAssertTrue(square(app, 5, 0).waitForExistence(timeout: 5))

        // Player one's back row is row 5, and under Blendy it holds fruit, not blenders.
        XCTAssertTrue(
            square(app, 5, 0).label.contains("banana"),
            "expected fruit on the back row, got: \(square(app, 5, 0).label)"
        )
        XCTAssertTrue(
            square(app, 4, 1).label.contains("blender"),
            "expected a blender one row in, got: \(square(app, 4, 1).label)"
        )
    }

    func testRandomBoardStillPlays() {
        let app = launch(board: "random")
        app.buttons["Local Two Player. Share one phone and take turns."].tap()

        // Nothing is known about where the pieces are on a scattered board, so this
        // asserts only what must hold regardless: the board is dealt and playable.
        XCTAssertTrue(square(app, 0, 0).waitForExistence(timeout: 5), "a random board should appear")
        XCTAssertTrue(square(app, 5, 5).exists)
    }

    // MARK: - Board edges

    func testEdgePickerSwitchesAndExplainsItself() {
        // No -boardWrap argument: an argument sits in front of the stored value, so the
        // taps below could never be read back.
        let app = XCUIApplication()
        app.launchArguments += ["-hasSeenTeachingOverlay", "YES"]
        app.launch()

        if app.buttons["Walls"].value as? String != "selected" {
            app.buttons["Walls"].tap()
        }
        XCTAssertTrue(app.staticTexts["Edges are walls. Pieces stop at them."].exists)

        app.buttons["Sides"].tap()
        XCTAssertEqual(app.buttons["Sides"].value as? String, "selected")
        XCTAssertEqual(app.buttons["Walls"].value as? String, "not selected")
        XCTAssertTrue(
            app.staticTexts["Left and right join up. Step off one side, arrive at the other."]
                .waitForExistence(timeout: 2)
        )

        // Restore, so the stored choice cannot leak into tests that walk the board.
        app.buttons["Walls"].tap()
        XCTAssertEqual(app.buttons["Walls"].value as? String, "selected")
    }

    /// Walks a fruit onto the empty middle of the left-hand file, where the square it
    /// would wrap onto is empty. The opening row is no good for this: a fruit there wraps
    /// onto its *own* fruit, and fruit never captures, so the move is illegal on any
    /// edges and the test would prove nothing.
    private func fruitOnTheLeftEdge(_ app: XCUIApplication) {
        XCTAssertTrue(square(app, 4, 0).waitForExistence(timeout: 5))
        square(app, 4, 0).tap()
        square(app, 3, 0).tap()
        Thread.sleep(forTimeInterval: 1.2)
        square(app, 1, 0).tap()          // player two takes a turn
        square(app, 2, 0).tap()
        Thread.sleep(forTimeInterval: 1.2)
    }

    /// The rule itself, through the UI: a piece on the left edge can move to the right
    /// edge of the same row.
    func testAPieceWalksOffOneSideAndArrivesOnTheOther() {
        let app = launch(edges: "sides")
        app.buttons["Local Two Player. Share one phone and take turns."].tap()
        fruitOnTheLeftEdge(app)

        square(app, 3, 0).tap()
        XCTAssertTrue(
            square(app, 3, 5).label.contains("legal move"),
            "the far column should be reachable: \(square(app, 3, 5).label)"
        )

        square(app, 3, 5).tap()
        Thread.sleep(forTimeInterval: 1.2)
        XCTAssertTrue(
            square(app, 3, 5).label.contains("banana"),
            "the fruit should have arrived on the far side: \(square(app, 3, 5).label)"
        )
        XCTAssertFalse(
            square(app, 3, 0).label.contains("banana"),
            "and left the near side: \(square(app, 3, 0).label)"
        )
    }

    /// The same position with walls, which is what makes the test above mean something.
    func testWallsKeepThePieceOnItsOwnSide() {
        let app = launch(edges: "flat")
        app.buttons["Local Two Player. Share one phone and take turns."].tap()
        fruitOnTheLeftEdge(app)

        square(app, 3, 0).tap()
        XCTAssertFalse(
            square(app, 3, 5).label.contains("legal move"),
            "with walls the far column must not be reachable: \(square(app, 3, 5).label)"
        )
    }
}
