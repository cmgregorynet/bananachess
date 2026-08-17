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
