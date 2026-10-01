# Banana Chess — web

A TypeScript port of the rules engine, held to the Swift one by generated fixtures.

## Why a port and not WebAssembly

Compiling the Swift engine to WASM would keep one implementation, which is the obvious
argument for it. Against: the toolchain is a separate Swift distribution, Foundation
support is partial, the download is megabytes before a pixel is drawn, and the UI would
still have to be written. For a board game that has to start instantly on a phone browser,
a 1,200-line TypeScript engine with no dependencies wins.

## How the duplication is kept honest

Two implementations of the same rules is the real risk here, and not a theoretical one:
these rules have changed **eighteen times** in six weeks. Prose would drift within days.

So the Swift engine is the specification and it writes itself down:

```sh
npm run fixtures     # Engine/Sources/ConformanceFixtures → fixtures/conformance.json
npm test             # replays every one of them through this port
```

`fixtures/conformance.json` is 36 games — every rule set crossed with every board
arrangement, every edge setting and battles on and off — played move by move by "always
take the first legal action in canonical order". That is deterministic without porting any
search, and because the ordering is canonical it walks into blends, deliveries, respawns,
wildcard progress, battles and repetition bans by itself. Nearly 5,000 moves, each checked
for the resulting position, whose turn it is, both scores and the turn number, plus the
full legal-move list for the opening moves of every game.

**Change a rule in Swift, regenerate, and the port fails until it agrees.** That is the
whole design.

### What that caught on the first run

Two things worth recording, because neither would have been found by reading:

- **Swift's `next(upperBound:)` is Lemire's "nearly divisionless" method**, not
  modulo-with-rejection. Both are unbiased, so both deal a *fair* random board — but they
  consume the generator differently and therefore deal a *different* one. A port that
  cannot reproduce a deal cannot be handed a match from an iPhone.
- `JSONEncoder` **omits nil optionals**, so an unfinished game has no `outcome` key at
  all. That was a fault in the harness rather than the engine, and it masked 25 games that
  were replaying perfectly.

## What is here

- `src/engine/` — the rules: board geometry and edges, setup including the random deal,
  legal moves, the full turn pipeline, wildcards, blender battles, and the repetition rule.
- `src/ui/` — the board, drawn as SVG from the app's own piece curves and palette, so the
  web version reads as the same game rather than a second artist's impression of it.
- `src/main.ts` — pass-and-play on one screen, with the board, edges, rules and battles
  pickers, and the self-blend confirmation.
- `test/` — the conformance suite above, plus jsdom tests over the board.

```sh
npm run dev      # http://localhost:5173
npm run build    # 23 kB of JavaScript, 8.5 kB gzipped
```

## What is not here yet

- **The CPU.** `Evaluation` and `CPUPlayer` are not ported, so the only mode is
  pass-and-play. The fixtures do not need it — they are driven by canonical ordering — so
  the engine is proven without it.
- **Online play.** `MatchLink`, `MatchPayload` and the services are iOS-side.
- **The animation.** Moves land instantly; there is no equivalent of the app's event
  replay, blend bursts or splatter yet.
- **Wildcard trackers.** The rules run, but there is nothing on screen showing smoothie
  progress or a Banana Split count.
