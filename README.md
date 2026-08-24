# Banana Chess

An iPhone board game: race valuable fruit across a 6×6 board for double points while
roaming blenders turn anything in their path into points — including, at a price, your
own. Built to `Banana_Chess_iOS_PRD.docx`, amended by
`Banana_Chess_Rules_Update_Brief.docx`, `Banana_Chess_Wildcard_Rules.docx`,
`Banana_Chess_Updated_Rules_Addendum.docx`, and `RULES_ADDENDUM.md`, each superseding the
last where they disagree. Start with `RULES_ADDENDUM.md` — it is the correction of
record.

Rules version 9. Each player starts with two of each fruit; **the match ends the moment
either player runs out of fruit**; and **a blender that runs into another destroys it**,
earning a two-step Mega turn, with no automatic respawn — a lost blender costs a turn and
three points to replace. Standard play: blending is signed (theirs pays, yours costs, scores may
go negative) and colliding blenders are knocked off the board for three turns rather than
resetting. Optional **wildcard rules** — Golden Pineapple, Banana Split, Tropical
Smoothie, Blenderama — add two ways to win outright and are ticked on at the start
screen. See `Banana_Chess_Wildcard_Rules.docx`.

## Layout

```
Engine/                 Pure Swift package. No SwiftUI, no GameKit, no UIKit.
  Sources/BananaChessEngine/
    Board, Piece, GameState, GameAction, Event the domain model
    RulesEngine.swift                          the single deterministic transition path
    GameSetup.swift                            the locked opening and its tuning constants
    Wildcards.swift                            the optional rule set and its progress
    CPU/                                       difficulty levels, evaluation, alpha-beta
    Online/                                    payload, authority, service protocol, fake
  Tests/BananaChessEngineTests/                106 tests
App/
  BananaChess.xcodeproj
  BananaChess/
    Art/                 the Cherry Coast look: palette, paper, piece silhouettes
    Home/                mode selection
    Match/               the board screen, its view model, haptics, resume snapshots
    Online/              GameKit service and the lobby
    Rules/               rules screen and the first-launch teaching overlay
  BananaChessUITests/    the PRD's smoke path
```

## Running it

Xcode is not on the default `xcode-select` path on this machine, so command-line builds
need `DEVELOPER_DIR`:

```sh
# Engine tests — fast, no simulator
cd Engine && DEVELOPER_DIR=/Applications/Xcode-26.5.0.app/Contents/Developer swift test

# App build and UI tests
cd App && DEVELOPER_DIR=/Applications/Xcode-26.5.0.app/Contents/Developer \
  xcodebuild -project BananaChess.xcodeproj -scheme BananaChess \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17' test
```

Opening `App/BananaChess.xcodeproj` in Xcode works normally; the engine is referenced as
a local package, so editing engine sources rebuilds the app.

## The shape of it

Everything that can change the game goes through one function:

```swift
RulesEngine.apply(move, to: state) -> (state: GameState, events: [GameEvent])
```

Human taps, CPU searches, and payloads arriving from Game Center all call it. Nothing
else computes a score or moves a piece. The UI animates the returned events in order
through `GameState.applying(_:)`, so the board performs what the engine decided rather
than deciding anything itself — and a test proves that replaying a transition's events
reproduces exactly the state the engine returned.

Online play adds `MatchAuthority`, which separates *building* a turn from *committing*
it. Building is pure; committing only happens once Game Center confirms the write. That
is what makes a retry after a failed submission safe: the same bytes go out again and
the local score cannot advance twice.

## Art

The look is the Cherry Coast art bible — itself downstream of
`~/CharlesOS/.../japanese alligator/ART_DIRECTION.md`: warm-paper watercolour washes,
soft pencil outlines, visible paper grain, washi panels, one amber accent per screen
(`#E8A24A`, reserved here for selection, legal moves, and the wildcard Golden Pineapple,
which glows rather than taking a ring so the two never read as the same thing). No pure
black; the darkest value is the warm ink `#61524D`.

Pieces are drawn as vectors behind an art chokepoint, the same pattern as Cherry Coast's
`PlaceholderArt.gd`. `ArtCatalog.sprite(for:seat:)` prefers a produced sprite named
`piece_<kind>_<p1|p2>` from the asset catalog and falls back to the vector artwork, so
watercolour sprites can be generated later and dropped in without touching code.

Ownership never rests on colour alone. Player one's pieces sit on warm clay, player
two's on dusty indigo — opposed in hue and separated in value, and indigo is the one
saturated colour the root art direction already sanctions (its "deep indigo bottle", and
the colour of Cherry Coast's chef otter). Player two's tray also carries a second, light
inner ring, and every square has a VoiceOver label naming its owner, so the two sides
stay apart in greyscale and for colour-blind players too.

## Boards

Three opening arrangements, picked on the start screen and independent of the rule set:

- **Standard** — blenders on the back row, fruit in front.
- **Blendy** — the two rows swapped.
- **Random** — every piece scattered, with one rule: no fruit starts on its own delivery
  row. Dealt once by whoever creates the match and carried in the state, so both devices
  in an online match see the same board.

## Edges

Independently of the arrangement, the board's edges can be walls or can join up:

- **Walls** — the original board.
- **Sides** — left and right join; step off one side, arrive on the other.
- **All** — every edge joins, so the board is a loop both ways. This changes the game the
  most: a fruit's own back row is then one step from its delivery row, and matches run
  about a third as long.

**Blenders never cross an edge**, whatever the board is set to — the shortcut belongs to
the fruit, not to the machines chasing it. Joined edges are drawn as a dashed amber seam,
so a legal-move dot on the far side of the board has something explaining it.

## Blender battles

Optional, off by default. With them on, a blender running into an enemy blender fights for
the square: each player totals the value of the fruit they still have standing, the
attacker adds two, and both roll a die. The higher total takes the other player's blender,
so a failed attack costs you your own.

The dice are derived from the position so both devices in an online match settle a battle
identically — and the CPU searches with the dice removed, so it cannot pick only the
battles it has already won.

## Building and releasing

Xcode 26.5, iOS 18 deployment target, iPhone only. `Engine/` is a local Swift package
referenced by the app target — open `App/BananaChess.xcodeproj` and it resolves itself.

```sh
export DEVELOPER_DIR=/Applications/Xcode-26.5.0.app/Contents/Developer

# Engine tests (120)
cd Engine && swift test

# UI tests
xcodebuild -project App/BananaChess.xcodeproj -scheme BananaChess \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' test

# Archive for distribution
xcodebuild -project App/BananaChess.xcodeproj -scheme BananaChess \
  -configuration Release -destination 'generic/platform=iOS' \
  -archivePath "$HOME/Library/Developer/Xcode/Archives/$(date +%F)/BananaChess.xcarchive" archive
```

### Before the first TestFlight upload

Two things are outstanding and neither can be done from the command line without
touching the Apple Developer account:

1. **An app icon.** `Assets.xcassets/AppIcon.appiconset` declares a 1024×1024 slot and
   has no image in it, so the build carries no `CFBundleIconName` and App Store Connect
   will reject the upload. Drop a 1024×1024 PNG in and it is fixed.
2. **A distribution certificate and an App Store provisioning profile.** Only an Apple
   Development identity is installed, so `xcodebuild -exportArchive` fails with "No
   profiles for 'com.charlesbarton.bananachess' were found". Archive, then use Xcode's
   Organizer → Distribute App, which creates both against the signed-in Apple ID.

Also worth knowing: the app record for `com.charlesbarton.bananachess` has to exist in
App Store Connect first, the App ID needs the **Game Center** capability enabled to match
`App/BananaChess.entitlements`, and every TestFlight upload needs a unique
`CURRENT_PROJECT_VERSION` — it is currently `1`, at marketing version `0.1`.
