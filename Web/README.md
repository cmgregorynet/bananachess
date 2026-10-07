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
- `src/evaluation.ts`, `src/engine/cpu.ts` — the computer opponent: a one-ply scorer for
  Easy and Medium, alpha-beta with iterative deepening and a transposition table for Hard.
- `src/ui/` — also the wildcard trackers, so an alternate win is something you can see
  coming and go and stop.
- `src/main.ts` — pass-and-play or against the computer, with the opponent, board, edges,
  rules and battles pickers, and the self-blend confirmation.
- `test/` — the conformance suite above, plus jsdom tests over the board and a CPU suite.

### The CPU is held to a different standard from the rules

The rules must match Swift exactly: a port that applies them differently is wrong, and
two devices would disagree about the same match. The CPU is a heuristic, so a port that
plays *differently but sensibly* is correct. Its tests check that it only ever picks legal
moves, finishes matches, is reproducible from a seed, keeps the difficulty ladder, and
stays inside its time budget — not that it chooses what Swift would choose.

Its budgets are lower than the app's on purpose: this search runs on the page's own
thread, where a two-second search is two seconds of frozen tab. The board is painted
before the move is taken for the same reason. A worker is the obvious next step if those
budgets ever rise.

```sh
npm run dev      # http://localhost:5173
npm run build    # 23 kB of JavaScript, 8.5 kB gzipped
```

## Live play, and hosting

One Cloudflare Worker serves both halves: the built site from an assets binding, and a
WebSocket per match from a Durable Object. One deploy, one origin, no CORS to arrange.

**The server runs the rules.** It is not a relay — it holds the authoritative state and
puts every submitted action through the same engine the clients do, which is only
possible because the port is TypeScript and the Worker imports the identical module. A
client cannot play an illegal move by asking nicely, and two clients cannot drift apart,
because neither of them is deciding anything. Verified against a locally running Worker:
an out-of-turn move is refused, and a legal one lands on the other client.

A Durable Object is the right shape because it is what a match already is — one
addressable thing with its own state and its own sockets, which Cloudflare guarantees
exists in a single place. Two players cannot reach two copies of the same room.

```sh
npm run serve    # wrangler dev, the whole thing locally on one port
npm run deploy   # build, then wrangler deploy
```

Deploying needs `npx wrangler login` once. There is no database and no scheduled work, so
the free tier covers it comfortably.

`wrangler deploy --temporary` will publish to a throwaway account without logging in,
which is useful for proving a change works against the real edge. It is not a home for
the game: the one used during development started returning 403 for its static assets
within the hour, while the Worker itself kept answering.

### Seats survive a reconnect, and that needed a real deployment to get right

A seat is held by a **player token** the browser keeps, not by the connection. A WebSocket
gets a fresh id every time it opens, so anything keyed on the connection cannot survive a
phone locking for thirty seconds — the player comes back and finds themselves a spectator
at their own match. The unit test did not catch this, because it reconnected using the
same connection id, which is something no real socket ever does. A live two-client test
caught it immediately.

Two tabs in one browser share the token and therefore share a seat. Playing both sides
needs two browsers, or a private window.

### Why not GitHub Pages

This repository is private, and Pages from a private repository needs a paid plan. That
is worth knowing before reaching for it — but it would not have been enough anyway, since
Pages serves static files only and live play needs something that can hold a socket.

## What is not here yet

- **The CPU.** `Evaluation` and `CPUPlayer` are not ported, so the only mode is
  pass-and-play. The fixtures do not need it — they are driven by canonical ordering — so
  the engine is proven without it.
- **The animation.** Moves land instantly; there is no equivalent of the app's event
  replay, blend bursts or splatter yet.
- **Rematch.** Leaving a finished online match means creating a new one and sending a
  fresh link.
- **The CPU here is the old one.** The iOS levels were sharpened in October; this port
  still has the versions that moved at random and looked one move ahead.
- **Spectators** can watch but the seats are never released, so a player who abandons a
  match holds their side until the room is gone.
