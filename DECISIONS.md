# Decisions

Non-obvious calls made building the MVP, and what was deliberately left out.

## Rules update (version 2)

`Banana_Chess_Rules_Update_Brief.docx` replaced reset-only self-blending and instant
blender resets with signed blending and a three-turn knockout. Implemented as specified;
the notes below are what the brief did not settle.

**An exploded blender is stored as a piece with no position.** The brief asks that a
knocked-out blender not be represented as occupying its spawn. `Piece.position` became
optional and gained `respawnTurn`, rather than moving exploded blenders into a separate
collection — one source of truth for IDs, owners, and spawns, so Codable, undo, replay,
and the CPU snapshots all keep working without special cases.

**Respawn timing is stored per blender, not per collision.** The brief suggests
persisting each collision's two blender IDs with its due turn. Both participants always
share a due turn and each returns to its own spawn independently, so a `respawnTurn` on
each blender is equivalent and avoids a second structure to keep in sync.

**Respawns resolve at the tail of `apply`, after the turn number increments.** That is
the same instant as "the start of the next turn, before selection" — nothing can happen
in between — and it keeps one move, one transition, one event list.

**Self-blending needs a confirmation, because otherwise it is unreachable.** The PRD
says tapping one of your own pieces changes selection; the brief makes your own fruit a
legal blender destination. Those rules collide, and selection-wins would make the new
move impossible to play. Legal destinations now win the tap, and a self-blend — costly
and irreversible — asks first. Every other move still commits on the tap. This is the
one interaction decision not dictated by either document.

**Occupied squares now announce whether they are legal targets.** Previously they never
did, which was harmless when blenders could only land on empty squares or enemy fruit.
Now a VoiceOver player needs to hear "legal move, blends your own strawberry, minus 2".
The existing UI test passed for the wrong reason until this was fixed.

**Same-owner collisions are legal but geometrically unreachable.** Diagonal movement
preserves square colour, respawns return a blender to its own spawn, and each player's
two spawns sit on opposite colours — so a player's own two blenders can never occupy the
same square. RU-05 is implemented and tested against a constructed position, but it
cannot arise from the standard opening. This was equally true of the PRD's original
same-owner reset rule. Worth knowing before tuning around it. Pinned by
`sameOwnerCollisionIsUnreachableInPlay`.

**Old matches are refused rather than migrated.** Schema and rules versions must now
match exactly, in both directions — a version-1 payload decodes cleanly under the new
`Piece` shape, so accepting it would silently continue a match under rules its board was
never arranged for. Local resume snapshots carry the same guard.

## Wildcard rules (version 3)

`Banana_Chess_Wildcard_Rules.docx`, added as an opt-in rule set rather than a
replacement. Off by default; a checkbox on the start screen turns them on, and the
choice is remembered.

**The rule set lives in `GameState`, not in a setting.** A match carries its own
`ruleSet`, so a saved game, an undo snapshot, a CPU search node, and a Game Center
payload all state which game they are. Nothing has to be told out of band, and an online
opponent inherits the creator's choice through the payload rather than through a
handshake. `WildcardProgress` is always present and simply untouched in a standard
match, so the shape of a stored match never depends on its rules.

**Golden Pineapple reads literally, now that the fruit mix is right.** The rule promotes
"that player's remaining pineapple", which was meaningless while each player had only
one — the earlier build reinterpreted it. With two pineapples each (see
`RULES_ADDENDUM.md`) no reinterpretation is needed, and promotion is per player, so both
sides can be carrying one. The mis-specified fruit mix was the actual bug; the rule was
fine all along.

**The opening order must stay asymmetric.** Two of each fruit invites a tidy
mirror-symmetric row, and that deadlocks: every fruit faces one of equal value, the two
lines advance into a wall, and neither side ever has a local reason to pay to break it.
Three of 14 self-play matches finished, against 14 of 14 for the asymmetric order the
addendum specifies. Measured, not guessed, and recorded in the addendum so nobody
"tidies" it later.

**Blenderama's negation is measured on the opponent's score, not on a list of actions.**
*Charles, 16 Aug 2026 — `rulesVersion 11`.* The instruction was "only succeeds if the other
player has not completed an action that netted positive points: fruit across or blended
enemy fruit". Those two are exactly the actions that raise a score in this game, so the
engine compares the score before and after each turn rather than enumerating cases. It
stays true to the wording, and it cannot drift out of step with the scoring table later.

The explicit blend-based counter in `recordBlendForWildcards` is kept even though the
score check now subsumes it: the canonical counter should not quietly depend on a fruit
being worth more than zero.

Consequence, measured and reported rather than assumed: this makes Blenderama a perfect
game. Both players' runs are dead by a median of turn 43 in Medium self-play, none
survived 24 matches, and the tracker had to stop announcing "countered" because it became
the normal state of every match.

**Two of the three CPU levels were set too low; the third was broken.** *Charles, 7
October 2026 — "the CPU players are all way too easy".*

Easy moved entirely at random, which is a board that shuffles rather than an opponent,
and Medium looked exactly one move ahead. Both are now measurably stronger: **16–0**
against the versions they replace, on an identical clock, and the ladder is strict —
Medium 16–0 over Easy, Hard 12–0 over Medium.

Hard was a different problem, and worth separating from "make it harder":

- **Its transposition table had been misfiling every entry since it was written.** Bounds
  were classified against the narrowed window rather than the one the node was given, and
  since alpha has by then been raised to `best`, almost everything was stored as `exact`
  when it was a bound. That is a wrong answer handed back with confidence, and it is what
  made Hard call a losing line a forced win and stop deepening on it — the symptom traced
  weeks earlier and never explained. Entries from timed-out nodes are no longer stored
  either.
- **Every search node had been paying for enforced legality since rules 18.** An enforced
  legality query plays out each candidate move to test for repetition, so asking for it at
  every node costs an extra ply of work per ply of depth. The search now runs unenforced
  internally; the move played is still taken from a fully enforced list at the root and
  applied through the real path, so nothing illegal escapes.

**Hard's clock is deliberately unchanged at 1.5s/2s.** Raising it to 2.5s/4s measured at
3,669 ms mean and 4,042 ms worst per move, which on a phone reads as a hang rather than as
a stronger opponent. Every bit of its improvement had to come from searching better in the
time it already had. The depth ceiling went from 8 to 12 for the same reason: with the
per-node cost down, the ceiling was at risk of being what stopped the search instead of
the clock.

**Levels are data now.** `SearchPlan` holds depth, how carelessly a level picks among what
it found, its budgets, and its tempo margin — with `.previous` kept beside `.standard` so
"twice as hard" can be *measured* as a duel rather than asserted.

**The tempo margin is per level and off for Hard.** Deep search has a horizon: where a
delivery cannot be interfered with, taking the points this move and next move are worth
the same, and all that distinguishes them is positional noise — measured at one position
as 543 against 563. The search is not wrong, but a player watching a computer shuffle on
top of six free points sees a bug, so a near-tie breaks toward scoring. It is an override
of the search's judgement, and Hard has the best judgement to override: with it applied
globally, Hard against Medium fell from 5–0–3 to 4–4. Isolating it afterwards came out
5–5 in ten games, which is no detectable effect at that sample size, so the honest
statement is that the shipped configuration measures 12–0 and the 4–4 may have been noise.

**Benchmarks have to use the real clock.** The first duel of new Hard against old Hard
returned exactly 8–8, which was the giveaway: with its budget unchanged the two plans were
identical, so it duelled Hard against itself and measured who moved first. Cutting budgets
to make duels fast is also what stops Hard being Hard.

**Repetition is keyed on the arrangement, not the position.** *Charles, 29 Aug 2026 —
`rulesVersion 18`, schema 16.* `boardKey` covers where the pieces stand and whose turn it
is; `positionKey` keeps the scores for the transposition table. Keying repetition on the
full position would have been useless: every loop this rule exists to stop was paying
points every lap, so the "same" position never looked the same twice.

**The `enforcingRepetition` flag is load-bearing, not a convenience.** Legality now depends
on the position a move *produces*, so answering it means simulating the move — and the
simulation's own turn-handover asks whether the next player has a legal move, which is the
same question. Without an unenforced path threaded through `apply`, `finishTurn` and
`legalActions`, it recurses until the stack goes. It did, twice, before the flag reached
all three.

**The board history is stored, not derived.** Replaying it from `history` would need the
opening position, which a `.random` board does not carry — the arrangement travels in the
state, not a seed. So the keys are stored, at roughly 20 bytes a turn.

**The battle bonus and the tie rule are one dial, so they were tuned as one.**
*Charles, 29 Aug 2026 — `rulesVersion 17`.* Bonus `B` with ties to the attacker is the
same inequality as `B + 1` with ties to the defender, which means the two "open questions"
from the first cut were never independent. Settled at bonus 1 with ties to the defender —
58% for the attacker on level fruit.

Separately, fruit counts at **half** value in a battle. Twelve points of opening fruit
against a die spanning five meant the fruit overruled the dice: a third of measured
battles were decided before anyone rolled. Halving puts the two on the same scale, and
every battle now turns on the roll. `GameState.battleStrength(for:)` is the halved value
and `fruitStandingValue(for:)` the raw one — both named, so neither has to be inferred.

**Blender battle dice come from the position, and the CPU searches without them.**
*Charles, 23 Aug 2026 — `rulesVersion 16`, schema 15.* Two constraints pull against each
other here. `RulesEngine.apply` must be a pure function of (action, state) or two devices
desynchronise the first time a blender attacks — so the dice are derived from
`positionKey`. But a deterministic roll is a *readable* roll, and a searching CPU would
have picked only battles it had already won.

`BattleResolution` splits the two: `.dice` for the real game, `.expected` for the CPU's
search, which replaces both dice with the same constant so they cancel and the CPU weighs
the battle on standing fruit plus the attacker's bonus. Pinned by a test asserting the
search never sees two different dice.

**Match creation moved onto a `MatchOptions` value.** Four settings — rule set, board
arrangement, edges, battles — were being threaded one at a time through the service
protocol, both implementations, the lobby view model and the home screen. That is four
places to drop one, and the compiler only catches it at the ends. One struct now carries
them.

**The Golden Pineapple is counted, not remembered.** *Charles, 23 Aug 2026 —
`rulesVersion 15`, schema 14.* "Whenever there is one pineapple left it is always a golden
pineapple" is a statement about the position, so it is implemented as one:
`GameState.isGolden(_:)` counts that owner's pineapples. The two stored fields —
`goldenPineapple` and `firstPineappleBlended` — were deleted rather than kept in sync,
because a derived rule with a cached copy is a rule with two answers.

The `.pineappleTurnedGolden` event stayed, now inert on replay: the UI needs a beat to
play the promotion on, and nothing else needs it. That is the same shape as
`.smoothiePoured` — an event that announces rather than mutates.

**A Tropical Smoothie pays three points and takes any fruit.** *Charles, 17 Aug 2026 —
`rulesVersion 13`.* One of every fruit type pays `RulesEngine.smoothieAward`, once each
per match, with no restriction on whose fruit went in.

This supersedes two earlier decisions recorded here: that self-blends counted toward a
smoothie on their own (the original reading of the brief), and that rules 10 restriction
requiring a stolen ingredient. `SmoothieProgress.includesOpposingFruit` is gone with the
restriction, which is why the payload schema moved to 11.

**The blender blowout is kept as retained code, on request.** `pourSmoothie` and the
`.smoothie` event stay whole and unemitted. Retained code that nothing exercises stops
working without anyone noticing, so `pourSmoothie` was made `internal` and four tests
drive it directly — it is out of service, not unverified. One line in
`recordBlendForWildcards` switches it back on.

**The smoothie bonus forced the alternate-win weight down.** Measured, not guessed. Three
points back on a self-blend makes a Blenderama run cost nine rather than twelve, which was
enough for `alternateWinThreat` at 40,000 to take over Medium play entirely: both CPUs
raced for a Blenderama, neither ever scored, so neither run was negated, and ten of
twenty-four matches ended on turn 31 with the winner on −16. Legitimate under the rules —
the loser's peak score was 0 in every one — but degenerate, and exploitable, since scoring
once leaves the CPU having binned its whole board.

At 4,000 the alternate routes stay live without crowding out ordinary play: five Banana
Splits, sixteen on score, one draw. At 0 no alternate win ever happens at Medium, which
wastes the wildcard rules. The difficulty ladder holds at 12/12 in all three. Two of
twenty-four matches still stall at 4,000, which is the known terminal-condition gap in
wildcard rather than anything this weight controls.

**A blender already knocked out is not re-timed by a smoothie.** It is not on the board
to blow up, and resetting its clock would quietly extend a penalty it is already serving.

**Byte-stable containers, not dictionaries.** `PlayerPair` and `SmoothieProgress` are
plain structs rather than `[PlayerID: …]` and `Set<PieceKind>`. Swift encodes both of
those with arbitrary ordering, which would break the byte-stable payload encoding that
the online retry path depends on.

## Board setups (schema 12)

*Charles, 17 Aug 2026.* Three opening arrangements: Standard, Blendy, Random.

**Orthogonal to the rule set, and stored beside it.** A board is not a rule, so
`BoardSetup` sits alongside `RuleSet` in `GameState` rather than folding into it — any of
the three boards can be played under either rule set, which is six combinations, all
measured. Nothing in the rules reads `boardSetup` after setup: the arrangement is already
in `pieces` by then. It is stored so a match can *say* what it is, to the player and in a
payload, exactly as `ruleSet` is.

**A random board is dealt once, not seeded.** `newGame` takes an optional seed for
reproducibility in tests, but the resulting arrangement travels inside the state. An
online opponent receives the board itself rather than a seed to re-roll, so there is no
way for two devices to disagree about where the pieces are — and no need to version the
shuffling algorithm.

**One placement rule, and only one.** No fruit starts on its own delivery row; everything
else is left to chance, including lopsided sides and blenders that start next to enemy
fruit. The rule exists because a fruit dealt onto its delivery row can deliver on turn one
for a sideways step. Measured across 200 boards.

**`GameSetup.allBlenderHomes` had to go.** It was a static derived from the back row, and
the board's spawn markers were drawn from it. With three arrangements the spawns move, so
`GameState.allSpawnSquares` reads them off the pieces instead.

**Blendy found a CPU bug that had been there all along.** Sixteen of sixteen Blendy
matches never terminated — 4,218 buy-backs, no fruit ever touched. The board's blenders
start with no fruit in reach, so blender combat is the only profitable move, and at
`blenderDestroyed = -450` against a 300-unit restore cost every rebuy scored +150, making
the trade infinitely worth repeating. Repriced to -250 by sweep: every board finishes,
buy-backs survive as a real option, ladder holds. Full numbers in `RULES_ADDENDUM.md`.

This is the third time a weight priced far from its true cost has produced an unbounded
loop — see also spawn camping and the Blenderama beeline. The pattern is worth naming: if
an action's reward exceeds its cost by a wide margin *and* the position it produces can
recur, the CPU will find the cycle.

## Board edges (schema 13)

*Charles, 18 Aug 2026.* Left/right wrapping and all-direction wrapping.

**A third dimension, not two more board setups.** Wrapping is a movement rule, not an
arrangement, so `BoardWrap` sits beside `BoardSetup` rather than extending it. That keeps
all nine combinations available — Random on a torus is a different game from Standard on
one — and it keeps the enum honest: `BoardSetup` is still never read after setup, while
`BoardWrap` is read on every turn.

**Three states, not two toggles.** The brief asked for two toggles, but "all directions"
contains "left/right", so as checkboxes they would have had an unreachable combination and
an ambiguous one. A three-way choice says the same thing without a contradictory state.

**One function owns the geometry.** `Board.step(from:row:column:wrap:)` is the only place
an edge is interpreted, and the rules engine, the CPU's mobility term, its threat map and
its distance heuristics all call it. Before this there were four separate
`offset` + `Board.contains` pairs, which is four places to get an edge wrong.

**The square-colour invariant survives on a 6x6 board.** A blender never changing colour
is what the buy-back and board-coverage reasoning depends on. Wrapping shifts a coordinate
by six, which is even, so parity holds — on an odd-sided board it would break. Pinned by a
test across every square and every wrap rather than left as a comment.

**Blenders are exempt from the edges, and the exemption lives in exactly one place.**
*Charles, 18 Aug 2026 — `rulesVersion 14`.* `GameState.edges(for:)` hands a blender
`.flat` and a fruit the match's setting; nothing else in the engine or the CPU asks the
question. `Board.blenderSteps` dropped its wrap parameter entirely rather than being
passed `.flat` at its one call site — a rule stated in the type cannot be got wrong by a
future caller.

**The CPU's distances had to learn the short way round.** Four separate terms measured
distance with `abs()`: advancement, Banana Split proximity, blender approach and the
move-ordering heuristic. On joined edges all four overstate distance, which would have had
the CPU marching fruit the long way round its own board.

## Fruit depletion (version 5)

`Banana_Chess_Updated_Rules_Addendum.docx` §5 changed how every match ends: the moment
either player has no fruit on the board, not when the board is empty.

**It applies to standard play too, not only wildcard.** The section is headed "Standard
Game End", and §6 makes it the general fallback once alternate wins are ruled out. This
is the largest change to standard play since version 2, hence the version bump.

**It fixed the draw problem.** Two of each fruit left both sides with identical material,
and standard medium self-play drew six of sixteen matches. With depletion, none of sixteen
drew, and matches shortened by about a quarter.

**The CPU needed a clock term.** Whichever side is nearer to empty is how much match is
left, so `clockPressure` rewards a player who is ahead for shortening it and a player
behind for stretching it. Without it the search only noticed depletion on the ply it
happened, and evaluation's "fruit on board is good" instinct pointed the wrong way for
whoever was winning.

**Half the fixtures had to grow fruit.** Many tests used boards where only one player had
any fruit at all, which now ends the match instantly. That was the rule working, not a
test problem, but it is worth knowing why those fixtures look padded.

**Banana Split's extra condition was kept, on a judgement call.** The updated document's
§2 restates Banana Split without the "and none of theirs" clause agreed verbally in the
previous round — but its §2 is word-for-word the original wildcard brief, which reads as
carry-over rather than a reversal. Kept, and flagged in `RULES_ADDENDUM.md`; one line in
`wildcardWin` if it should go.

## Blender combat (versions 6 and 7)

**Two ways to lose a blender, and they are deliberately different.** Combat destroys a
blender outright — it comes back only when its owner spends a turn and three points.
A Tropical Smoothie blows blenders off the board for exactly one turn and then returns
them for nothing. `Piece.respawnTurn` is set only in the smoothie case, and
`needsBuyingBack` versus `isAwaitingRespawn` is what the rest of the code branches on. A
smoothie victim is not purchasable: there is nothing to buy.

**A returning blender waits for its spawn rather than being lost.** Same reasoning as the
delivered banana: a one-turn penalty must not become permanent because a square happened
to be busy.

**The purchase has no affordability check, on purpose.** The score goes down by three
wherever it lands, including well below zero.

**One tap handler per square.** The buy-back marker originally carried its own
`onTapGesture`, layered over the square's. The purchase never fired: two gestures were
competing for the same point, and the square won. The marker is now decorative
(`allowsHitTesting(false)`) and `MatchViewModel.tap(_:)` handles the purchase, so every
tap on the board goes through exactly one path — the one the UI tests and VoiceOver
already exercise. Pinned by `BlenderCombatUITests`.

## The Mega Blender, and what removing it taught the CPU (version 8)

The two-step reward for a successful attack is gone. Combat already gives the attacker a
free kill at no cost to itself; a second move on top made first blood close to decisive
(88% first-kill win rate, down to 60% without it).

**A symmetric threat term is worth nothing.** Trying to stop the CPU feeding a spawn
camper, the first attempt penalised any blender standing in an enemy blender's reach.
Both blenders threaten each other across the same diagonal, so the penalties cancelled
exactly and the loop continued. The second attempt scoped it to the side not about to
move, which fixed the loop but made the CPU refuse to attack at all — the approach costs
a turn before the reward lands, and a one-ply search never sees that far. What works is
narrow: a blender on *its own spawn* while an enemy covers that square, which is
precisely what a just-rebought blender looks like under a camper.

**Spawn camping is a real rules-level exploit, not just a CPU bug.** The loop is legal
and a human can run it on another human. Recorded in `RULES_ADDENDUM.md` with three
options for closing it — *closed in version 12 by the first of them, making spawn squares
safe from capture.*

## Spawn squares are safe from capture (version 12)

*Charles, 16 Aug 2026.* "A blender cannot capture another blender on its spawn block."

**Read as either spawn, not only the one it started on.** A blender bought back onto its
sibling's spawn is standing on one of its owner's spawns, and treating that as unprotected
would reopen camping on the other square — which the version 9 change exists to prevent.
So the check is "is this square home to any of that owner's blenders", not "is this the
piece's own home".

**Symmetric.** It covers friendly blenders too. Home is simply safe; there is no case for
a square that repels one side and not the other.

**Not immunity.** The square is safe, the piece is not: step off and it can be taken.
Camping degrades from a toll into a siege, which is a fair tactic rather than an exploit.
Smoothies are unaffected — the rule stops captures.

**The CPU's `blenderOnCoveredSpawn` penalty survives this, contrary to first instinct.**
It looks obsolete — the position it flags can no longer be captured — but the conclusion
it draws is still right for a new reason: a blender bought back onto a camped spawn is
entombed rather than doomed, and three points for a piece that can never move is still a
bad trade. Removing it drove a CPU to −424 in the old rebuy loop displaced one square, and
the self-play test caught it immediately. Left in, with the reasoning corrected in place.

## Buying a blender back on either spawn (version 9)

The restore action names the square it lands on, and both of the owner's spawns are
offered. The board shows a price on every spawn that can take one, rather than only on
the square the blender originally came from.

**It breaks a documented invariant, deliberately.** A blender never changed square colour
before, so a player's own two blenders — which spawn on opposite colours — could never
meet. Buying one back onto the other spawn switches its diagonal, so same-owner
destruction is now reachable in real play. The parity test was narrowed to cover games
without a purchase, and the new behaviour has a test of its own.

**It also softens spawn camping.** A player whose spawn is being camped can rebuild on
the other one instead of feeding the camper. That was not the reason for the change, but
it is a welcome side effect.

## Rules the PRD did not fully specify

**A fruit move must always end on an empty square.** The PRD says fruit "must end on an
empty square unless reaching an open scoring square on the far edge." Read strictly,
*open* means empty either way, so the exception adds nothing — a fruit cannot deliver
onto a delivery square that a piece is standing on. That is how it is implemented and
tested (`blockedDeliverySquare`). If the intent was that fruit may displace or ignore a
piece sitting on the scoring row, this is the one line to change.

**A player with no legal move is skipped.** The PRD has no answer for this and the
engine needs one. The turn passes to the other player and a `turnPassed` event is
emitted; if neither player can move, the match ends on the current scores. Under
version 2 this got rarer still: a blender may now land on anything, so it always has a
move, and only a player whose blenders are *both* knocked out can be stuck at all.

**Respawn chains terminate by construction.** A blender that lands back on an occupied
spawn explodes again rather than being placed, and its new window is three turns further
out, so a turn's respawn resolution can never loop. (This replaces the old reset-chain
guard, which no longer has anything to guard.)

## Architecture

**The engine is a local Swift package, not a folder in the app target.** It makes the
"no SwiftUI dependency" rule structural instead of a convention, and it means the whole
rules and CPU suite runs in about two seconds with `swift test`, no simulator involved.

**One transition path, and the UI replays events rather than recomputing.**
`GameState.applying(_:)` projects a single event onto a state for presentation only.
Without it the view model would have had to decide when a piece disappears, which is
exactly the rules duplication the PRD warns against. Two tests pin it: one on a fixture
that fires a collision and a blend together, and one across a whole match.

**`MatchAuthority` splits preparing a turn from committing it.** Preparing is pure, so
the payload for a retry is byte-identical and local authority only moves once Game
Center confirms. This is what the "no duplicated move, no score drift" requirement
actually needs, and it is testable without a network.

**The CPU had to learn the new prices.** Own fruit parked on your own spawn flipped from
a small bonus to a penalty — under the old rules it set up a free reset-blend, under the
new ones the blender comes back and eats it at a loss. Move ordering sorts self-blends
and collisions last so the search reaches for them only after the obvious moves are
refuted, and evaluation charges for every turn a blender spends off the board.

**Minimax rather than negamax in the CPU search.** Because a player with no legal move
is skipped, the side to move does not simply alternate, so the sign cannot be flipped
blindly at each ply. Explicit maximising and minimising nodes handle the skip correctly.

**Blend effects roll their dice in the view, never in the engine.** A blend throws pulp
at the square, and one time in three it clears the board and splatters the screen. The
roll happens on the device that is watching, so `GameState`, the event stream, and the
online payload are all untouched — two devices replaying the same match may splatter
differently and neither is wrong. `BlendEffectPolicy` is injected rather than reached
for, so a preview or a screenshot run can pin it on or off instead of rolling.

**Effects are driven by `KeyframeAnimator`, not `onAppear` plus `withAnimation`.** The
first version set its animation state in `onAppear`, which SwiftUI folds into the same
transaction that inserted the view: both effects arrived already finished, which is to
say invisible. Keyframes start on appear and drive per frame, which is what a one-shot
effect actually needs.

**Squares are laid out, not offset.** The first version positioned squares with
`.offset`, which renders correctly but leaves every square's accessibility frame at the
board origin — all thirty-six stacked on one cell for VoiceOver, and every UI-test tap
landing on square one. The UI tests caught it. Squares now use real layout; only pieces,
which have to travel between squares, use offsets.

## Playtest data already available

Self-play over twelve seeded matches per row, measured from the engine.

Under the original rules:

| Matchup | Finished | Average turns | Longest |
|---|---|---|---|
| Medium vs Medium | 12 / 12 | 82 | 110 |
| Easy vs Easy | 12 / 12 | 386 | 551 |

Under version 2:

| Matchup | Finished | Average turns | Longest | Collisions | Self-blends | Matches ending negative |
|---|---|---|---|---|---|---|
| Medium vs Medium | 12 / 12 | 77 | 123 | 37 | 2 | 0 |
| Easy vs Easy | 12 / 12 | 186 | 251 | 29 | 92 | 10 |

With wildcards on, over sixteen seeded matches per row:

| Matchup | How they ended | Average turns | Golden Pineapple appeared | Smoothies poured |
|---|---|---|---|---|
| Medium vs Medium | 16 on score | 98 | 6 / 16 | 12 |
| Easy vs Easy | 12 on score, 2 Blenderama, 2 drawn | 178 | 15 / 16 | 21 |

Matches still conclude without a turn limit, which was the PRD's open question, and the
knockout is being used rather than avoided — roughly three collisions per medium match.
Medium self-blends only twice in twelve matches, which is what a priced move should look
like: available, occasionally correct, rarely right.

On the wildcards' own design question — whether an alternate win becomes the dominant
strategy — the answer so far is no, arguably too much so. Every medium match still ended
on points, with the Golden Pineapple showing up in about a third of them and smoothies
going off regularly: threats, not shortcuts, which is what the brief asked for. But
**neither a Banana Split nor a Blenderama occurred in any of the sixteen medium matches**,
and the two Blenderamas at Easy were random play stumbling into it. Two readings, and
playtesting should separate them: the counters may simply be too cheap — one blend on one
banana ends a Banana Split permanently — or the CPU may just be too shallow to plan a
three-banana rush (Medium looks one ply ahead, so it cannot). Watch human games before
tuning either the rules or the evaluation weights.

Two things to watch in real playtesting. Easy vs Easy now ends nearly twice as fast
(186 turns, down from 386) because random play blends its own fruit off the board — and
ten of its twelve matches end with someone on a negative score. That is the random
policy punishing itself rather than a property of the ruleset, but a beginner's first
game against Easy will look strange, and it is the strongest argument for revisiting
what Easy does. A DEBUG-only turn counter sits next to the Undo button for this.

Unrestricted backward fruit movement did not stall any of the forty-eight matches.

## A fourth level, and why it is not simply deeper (Brutal)

Blenderama was still winnable against the CPU, and the cause was ten rule versions old.
`counterReadiness` priced the counter as *closing a blender on the attacker's fruit*,
which was the right answer before the shutout came in at rules 11. Since then Blenderama
dies the moment the defender scores at all, by any route — so the CPU was marching
blenders across the board while the one-move answer, running a fruit home, was worth
nothing to it. In the test fixture the delivery route evaluated at −354 against −282 for
the blender march: the evaluation actively preferred the wrong move. It now prices the
counter on the nearer of the two routes to the scoreboard.

The readiness credit is deliberately capped below the threat it answers
(`(Board.size - 1) / Board.size` of it, with the distance floored at one). Price it any
higher and the CPU hovers one move from scoring forever instead of scoring, because the
hovering bonus would exceed what it gains by removing the threat.

A first attempt gave defence its own, much larger weight. That cannot work:
`Evaluation.score` is antisymmetric — `wildcards(me) - wildcards(them)` — which is what
makes minimax sound here, so a defence-only weight comes back as an offence weight
through the opponent's term. It would have re-inflated chasing your own Blenderama to
24,000 and brought back the beeline that lowering `alternateWinThreat` to 4,000 fixed.
Offence and defence share one scale; only the target changed.

**The new level is not Hard with a longer clock, because that was measured and it was
worse.** Brutal first shipped as depth 16 with a four-second cap and lost to Hard 2–4.
Timing a real midgame position explains it: depth 4 costs 0.6s, depth 5 1.3s, depth 6
**9.8s**, depth 7 17.6s. A ply costs roughly seven times the one before it, so two
seconds and four seconds both complete exactly depth five — the extra two seconds went
entirely on a depth-six iteration that was always abandoned. `depth: 12` and `depth: 16`
were both fiction; the clock stops at five either way, and `maximumDepth` is back to 12
because raising it buys nothing.

Width is priced out by node cost, not by tree shape: the branching factor is only 13,
but `RulesEngine.apply` costs 49µs and `Evaluation.score` 51µs per node. Getting a
genuine extra ply would mean a compact state with make/unmake instead of copied value
types — an engine rewrite, not a difficulty setting. Storing the best move in the
transposition table and re-ranking the root from the previous iteration bought about 5%
at depth 6 and 25% at depth 7; both are kept, both are pure ordering changes that cannot
alter a completed search's value, and neither was nearly enough.

What does buy sight is **selective** depth. Brutal follows forcing moves — a fruit
reaching its delivery row, or a blender arriving on an occupied square — eight plies past
the nominal depth, so a line is never judged half way through an exchange. That took it
from 2–4 down to **9–3 up** over twelve games with alternating colours, no draws and none
unfinished. Mean 2.98s a move, worst 4.05s.

Two cautions on the measurement. Games must be given more than 300 turns: at that cap six
of the twelve were silently dropped and the result read 6–0, which flatters whoever was
ahead — five genuinely ended between turns 326 and 409. And a time-limited plan cannot be
duelled on a loaded machine, nor can two levels be compared by overriding `plan:` on both,
which makes them byte-identical players whose results then differ only by where the clock
happened to cut the deepening.

Quiescence is gated to Brutal alone via `SearchPlan.quiescenceDepth`, and with it off the
search returns the leaf value before the node is even counted, so Easy, Medium and Hard
are unchanged down to which nodes the clock is polled on.

## Deferred

- **No sprites yet.** The vector artwork ships as the real look, behind the art
  chokepoint, so generated watercolour sprites are a file drop later.
- **No app icon.** `AppIcon.appiconset` is empty and will warn on archive.
- **No push notifications.** The PRD calls them opportunistic; correctness comes from
  refreshing on activation, which is implemented.
- **Undo is DEBUG-only**, per the PRD, and disabled for online matches. So is the
  `-alwaysSplatter` launch argument, which pins the one-in-three splatter on.
- **No dark mode.** The board is a painted object on warm paper; the app pins
  `.light`. Inverting it would break the art bible rather than serve it.

## Needs verification I could not do here

**Game Center is written but unverified against the real service.** It builds, and the
online rules underneath it are covered by tests against a fake service — two simulated
clients play a full match and converge on identical state after every move, conflicts
and stale payloads are refused, and an injected transport failure followed by a retry
submits exactly one turn. What has not run is GameKit itself. That needs a paid
developer account, a real bundle identifier, the Game Center capability enabled on the
App ID, and two sandbox accounts on two devices.

Specific things to check first on device:

- Seats are assigned by participant order (`participants[0]` is player one). If Game
  Center ever orders that array differently on the two devices, the match desyncs.
  Everything else derives from the payload, so this is the single riskiest assumption.
- `inviteFriend()` completes via the turn-event listener, because current GameKit does
  not hand the match back through the matchmaker delegate. Worth confirming the listener
  fires for the inviting device.
- Turn timeout is set to 90 days rather than `GKTurnTimeoutNone`, which Swift 6 will not
  let concurrent code read because GameKit declares it as a mutable global.

**Bundle identifier is `com.charlesbarton.bananachess`** and `DEVELOPMENT_TEAM` is
unset — change both before any device build.
