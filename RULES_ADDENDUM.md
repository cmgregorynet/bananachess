# Banana Chess — Rules Addendum

Corrections and additions on top of the original handoffs. This supersedes every `.docx`
in this directory where they disagree, and records the questions they left open.
**Rules version 13.** Payload schema 13.

The `.docx` files are left as the original handoffs; nothing in them has been edited.
This file is the correction of record.

## Latest round: blender combat, banana recycling, Blenderama

The mutual-explosion mechanic is gone, replaced by combat with a survivor. The full
current rules for the three areas that changed:

### Blender combat (standard rules — applies to every match)

Combat losses are the *permanent* kind.

- A blender that moves onto another **destroys it** and takes the square. Nobody scores
  for it. This works on your own blenders as well as theirs.
- Destroying a blender grants **nothing further**. No extra move, no second step: the
  attacker gains the square and the tempo, and that is all. (The Mega Blender that
  earlier drafts gave the attacker has been removed entirely — see below.)
- Destroying a **friendly** blender is legal and equally unrewarded.
- **A blender destroyed in combat never respawns on its own.** A player may spend a whole
  turn and **three points** to restore one to **either of their two spawn squares**,
  whichever is free — not only the one it started on. There is no affordability check:
  the score simply goes down, below zero if that is where it lands.

  This has a consequence worth knowing. A blender only ever travels on squares of one
  colour, because diagonal movement preserves colour and each player's two spawns sit on
  *opposite* colours. Buying a blender back onto the other spawn is now the only way a
  blender ever changes diagonal — which means a player's own two blenders, previously
  unable ever to meet, **can now be brought onto the same colour and destroy each
  other**. It also gives a player whose spawn is being camped somewhere else to rebuild,
  which takes some of the sting out of the camping exploit noted below.

### Banana delivery (wildcard rules only — see the note below)

- A delivered banana scores as normal and **returns to its own starting square**.
- If that square is occupied it waits just off the board and returns automatically the
  moment the square clears — the detail flagged in the handoff, so a banana is never
  lost to an accident of timing.
- Deliveries are counted **cumulatively**, and the first player to **three** wins
  immediately. With two bananas each that takes at least one round trip.
- This replaces the previous Banana Split condition entirely, including the
  "and none of theirs" clause from the round before. That clause is now withdrawn.

### The Mega Blender, withdrawn

An earlier draft rewarded a successful attack with a two-step turn. It is gone. Combat
already hands the attacker a free kill at no cost to itself; adding a second move on top
made first blood close to decisive. Removing it dropped the measured first-kill win rate
from **88% to 60%** at Medium — see §6.

### Tropical Smoothie (wildcard) — a bonus, not a weapon

> Blend at least one of every fruit type and collect **three points**. Whose fruit went in
> does not matter: your own count exactly the same as theirs. Once each per match.

This replaces both of the previous versions of the rule. The smoothie no longer blows the
opponent's blenders off the board, and the "at least one of theirs" restriction added in
rules 10 is gone with it — a recipe made entirely of your own fruit is a smoothie like any
other. Blending three of your own still costs six points on the way, so an all-self
smoothie remains a net loss; it is simply no longer *forbidden*.

**The blowout logic is retained, not deleted.** `RulesEngine.pourSmoothie`, the
`.smoothie` event, `smoothieBlackoutTurns`, and the respawn-scheduling path that returns
victims for free are all still there and still correct — kept deliberately so the effect
can be switched back on. `pourSmoothie` is `internal` rather than `private` for one
reason: the tests drive it directly, so the retained path cannot quietly rot while it is
out of service. Four tests cover it, including that a blender standing on a spawn is safe
from *capture* but not from a blowout.

To turn it back on, call `pourSmoothie` instead of `awardSmoothie` in
`recordBlendForWildcards`. Nothing else needs restoring.

### Board setups — Standard, Blendy, Random

Three opening arrangements, chosen on the start screen and orthogonal to the rule set:
any board can be played under either set of rules. The choice is carried in the state, so
a saved match and an online payload both say which board they were dealt.

- **Standard.** Blenders on the back row, fruit in front. The original arrangement.
- **Blendy.** The two rows swapped: fruit on the back row, blenders in front of it.
- **Random.** Every piece on a random square, subject to one rule — **no fruit starts on
  its own delivery row**. A fruit that begins there has a free delivery on turn one for
  the price of a sideways step, which is not a random opening, it is a random winner.
  Nothing else is constrained: a blender may start next to enemy fruit and the two sides
  may be lopsided. That is the mode. A random board is dealt once, by whoever creates the
  match, and travels inside the state — an online opponent receives the board rather than
  a seed to re-roll.

**Blendy broke the CPU, and that was worth finding.** As first built, sixteen of sixteen
Blendy self-play matches never ended: after 600 turns, zero fruit blended, zero delivered,
all twelve fruit still on the board, and both players past **−290 points** — the entire
score spent on 4,218 blender buy-backs between them.

The cause is structural to the board. Blendy puts the fruit *behind* the blenders, so at
the opening a blender has no fruit within reach in any direction, and both delivery rows
are walled off by the enemy's fruit line, which cannot be captured because fruit never
captures. The only immediately profitable move on the board is attacking the other
blender. Standard play hid this behind a fruit line sitting directly in front of each
blender.

What made it unbounded was the CPU's price on a destroyed blender: at −450 against a
three-point (−300) restore, every rebuy scored +150, so two blenders with nothing better
to do traded forever and both paid for it. Swept −450 → −150; **−250** is where every
board finishes, buy-backs remain a real part of play (28 across sixteen standard matches,
against 0 at −150), and the difficulty ladder holds. Blendy now measures as healthily as
Standard: 0 stalls in 30, median 74 turns.

**Measured, 30 Medium self-play matches per combination, after the fix:**

| Board | Rules | Outcome | Stalls | Median turns | First mover wins |
|---|---|---|---|---|---|
| Standard | standard | 29 score, 1 draw | 0 | 78 | 72% |
| Standard | wildcard | 18 score, 7 Banana Split | 5 | 86 | 80% |
| Blendy | standard | 29 score, 1 draw | 0 | 74 | 79% |
| Blendy | wildcard | 16 score, 7 Split, 1 draw | 6 | 118 | 65% |
| Random | standard | 27 score, 2 draws | 1 | 39 | 59% |
| Random | wildcard | 8 score, 21 Banana Split | 1 | 22 | 55% |

Two things to note in that table, neither of them fixed:

1. **Random plus wildcard is a Banana Split sprint** — 21 of 29 decided matches, median 22
   turns. The placement rule keeps a fruit off its delivery row but not one row short of
   it, and a banana that starts one step away can deliver, recycle to that same home
   square, and do it again. Three deliveries wins. Widening the rule to "no fruit within
   one row of its delivery row" would settle it, but that is a change to the mode as
   specified rather than a bug in it.
2. **Random is the fairest board of the three.** First mover wins 55–59% of decided
   matches on a scattered board against 72–80% on the ordered ones. A lopsided random
   deal turns out to matter less than moving first does.

### Board edges — Walls, Sides, All

A second board dimension, chosen alongside the arrangement and independent of it. Unlike
`BoardSetup`, this one is read by the rules on every turn: it decides which moves exist.

- **Walls.** Edges stop a piece. The original board.
- **Sides.** Left and right join. Step off one side and arrive on the other, same row.
- **All.** Every edge joins, rows as well as columns. The board is a loop both ways.

Every legal-move calculation in the engine and the CPU goes through one function,
`Board.step(from:row:column:wrap:)`, so the edges cannot be right in one place and wrong
in another. The CPU's distance terms use the short way round on any axis that joins.

**The square-colour invariant survives, and that is not luck.** A blender never changes
square colour, which is what lets a player's two blenders between them cover the board and
what the whole buy-back rule leans on. A wrap shifts a coordinate by six, which is even,
so the parity holds. On an odd-sided board it would not. Pinned by a test over every
square and every wrap.

**With all edges joined, the back row is one step from the delivery row.** A fruit can
score by walking *backwards* off its own edge. That is the mechanic working as asked
rather than a hole in it, but it is the single biggest consequence: median match length
drops from 78 turns to 27.

**Measured, 30 Medium self-play matches per combination, standard arrangement:**

| Edges | Rules | Outcome | Stalls | Median turns | First mover wins |
|---|---|---|---|---|---|
| Walls | standard | 29 score, 1 draw | 0 | 78 | 72% |
| Walls | wildcard | 18 score, 7 Split | 5 | 86 | 80% |
| Sides | standard | 30 score | 0 | 61 | 56% |
| Sides | wildcard | **30 draws** | 0 | 45 | — |
| All | standard | 30 score | 0 | 27 | 30% |
| All | wildcard | 21 score, 9 Split | 0 | 33 | 40% |

And across every arrangement, standard rules, 12 matches each — nothing stalls anywhere:

| Board | Walls | Sides | All |
|---|---|---|---|
| Standard | 0 stalls, median 78 | 0, 61 | 0, 34 |
| Blendy | 0 stalls, median 66 | 0, 64 | 0, 38 |
| Random | 0 stalls, median 34 | 0, 35 | 0, 27 |

Joining any edge **removes the wildcard stalls entirely** — the extra mobility means the
positions that used to shuffle forever now resolve.

**Why Sides plus wildcard draws every time, and why it is not the mode's fault.** The
opening fruit order is `banana, strawberry, banana, pineapple, strawberry, pineapple`,
read from each player's own left. That makes player two's row the reverse of player one's:

```
player one: ba st ba pi st pi
player two: pi st pi ba st ba
```

Those are the same sequence rotated by three — exactly half the board's width. On a
walled board that is only a mirror. **Once the columns join, it becomes a true symmetry of
the board**: the two openings are the same position, so two equally strong deterministic
players mirror each other into a 16-16 draw on turn 45, identically at every seed.

It takes perfect symmetry on both sides to do it. Introduce any asymmetry and it vanishes:
Easy against Easy, whose tie-breaks are random, decided 20 of 20; Medium against Easy
decided 20 of 20. A human breaks it on the first move. So this is a CPU-versus-CPU
artefact, not a broken mode — but it is worth knowing before anyone watches two Mediums
play on joined sides.

Closing it properly would mean an opening order that is **not its own half-board
rotation**, and that would change the Standard opening for every mode, so it has not been
done. It is the same lesson as the mirror-symmetric opening recorded in section 0: keep the
asymmetry, and now also keep it asymmetric *under rotation*.

### Blenderama (wildcard) — now a shutout

> Blend every one of your own fruit **yourself** and you win outright — but only if the
> opponent has never scored. Any turn that nets them points, a fruit across or one of
> yours through their blenders, takes your Blenderama off for the rest of the match.

The negation is measured on **their score going up**, because "netted positive points" is
the rule. Two things fall out of that and both are intended: a Blenderama run never
negates itself, since self-blends cost points rather than earn them; and buying a blender
back never negates theirs, since that costs three points too. Your *own* scoring is your
own business — only the opponent's points count against you.

Blending all six of your own fruit costs you **twelve points**. So if the run is not valid
when it finishes, you have not drawn — you have almost certainly lost, and the match ends
immediately because your last fruit just left the board. Four ways a run that *feels*
complete is not:

1. **They scored, at any point.** The new one, and by far the most common.
2. **They took one of your fruit.** A subset of the above — blending your fruit scores
   them points — but recorded explicitly rather than inferred from the scoring table, so
   the counter does not depend on what a fruit happens to be worth.
3. **One of your fruit left the board any other way** — delivered, or taken during a
   respawn landing. Only a self-blend counts.
4. **You are in a standard match.** Blenderama is a wildcard rule; with the box unticked
   it does not exist, and blending your own fruit is purely a cost.

**Measured cost of the shutout rule.** In 24 Medium self-play matches, both players'
Blenderamas were dead by a median of **turn 43**, and none survived to the end of a single
game. At Easy — where a CPU can go a long time without scoring — one Blenderama win still
landed in 24. Against anyone who scores at all, this is now a perfect-game objective
rather than a race. That is what the rule says; flagging the size of it, not arguing with
it.

The tracker used to show a Blenderama row the moment it was countered, back when being
countered was rare and worth announcing. Under the shutout rule that is the normal state
of every match within about forty turns, so the row would be permanent noise; it now
appears only once a player has actually started a run. The warning lives at the point of
decision instead — the self-blend confirmation says outright when Blenderama is already
off, before you pay for the blend.

Blending a friendly *blender* has never counted toward it — blenders are not fruit — and
now that friendly blender destruction is legal, that distinction is covered by a test.

### Why banana recycling is a wildcard rule and not a standard one

The handoff does not scope it. It has to be wildcard-only, because the three-delivery
win is what bounds it: without that cap, a recycling banana is an unlimited scoring
engine and, under the fruit-depletion ending, a player with one could keep a standard
match alive indefinitely. Tied to the win condition it is self-limiting — at most three
deliveries and the match is over. Standard bananas are still consumed on delivery.

## 0. What changed in the latest round

`Banana_Chess_Updated_Rules_Addendum.docx` restated the wildcard rules and added two
things. Sections 1–3 of that document are unchanged from the original wildcard brief and
were already implemented.

- **§4 Blenderama — revised.** The hard counter is now tracked explicitly: the moment an
  opponent blends one of your fruit, your Blenderama is off for the rest of the match,
  and the board says "Countered" rather than leaving you to work it out. Behaviour was
  already equivalent — a stolen fruit can never be self-blended — but the doc asks for
  it to be tracked and communicated, so it is now a recorded flag rather than an
  inference from a count.
- **§5 Standard game end — fruit depletion.** New, and the largest change in this round.
  See below.
- **§6 Victory priority.** Alternate wins are settled before the score is. Already the
  case; now covered by a test.

### Fruit depletion (§5)

> The match ends the moment **either** player has no fruit left on the board. Whoever is
> ahead at that instant wins. Fruit still standing has never scored and counts for
> nothing.

This applies to **both** rule sets, not only wildcard. It replaces the old condition
(the match ends when the board is empty of all fruit) and it changes standard play
substantially: fruit is now a clock, and running yours out is a way to stop it. A player
who is ahead can deliberately shorten the match — including by blending their own last
fruit at a price — while a player behind has to keep enough on the board to cash in.

It also fixed a real problem. Under the previous end condition, standard medium self-play
drew six matches in sixteen, because two of each fruit leaves both sides holding
identical material. With depletion, **none of sixteen drew**, and matches got shorter
(64 turns average, down from 84). See §5 below for the full table.

### Previously open, now resolved

The "and none of theirs" clause on Banana Split, kept provisionally in the previous
round, is superseded by the three-delivery rule and has been removed.

## 1. Correction: the opening fruit mix

The PRD's fruit mix — three bananas, two strawberries, one pineapple — was a slip. A
pineapple had been dropped and a banana added in its place.

**Each player starts with two of each fruit: two bananas, two strawberries, two
pineapples.**

| | Was | Is |
|---|---|---|
| Bananas | 3 | 2 |
| Strawberries | 2 | 2 |
| Pineapples | 1 | 2 |
| Fruit value on the board, per player | 10 | 12 |

This is not only arithmetic. The Golden Pineapple rule promotes "that player's
*remaining* pineapple" — which is meaningless if a player only ever has one. With two,
the rule reads exactly as written and no reinterpretation is needed. See §3.

### Opening order

The PRD's order was Banana, Strawberry, Banana, Pineapple, Strawberry, **Banana**, read
from each player's own left. The last banana becomes the second pineapple:

> **Banana, Strawberry, Banana, Pineapple, Strawberry, Pineapple**

The asymmetry matters and should not be tidied up. A row holding two of each fruit
arranged symmetrically about its own centre — Banana, Strawberry, Pineapple, Pineapple,
Strawberry, Banana, for instance — leaves every fruit facing one of exactly equal value
across the board. Self-play then deadlocks: the two fruit lines advance into a wall and
neither side ever has a local reason to pay for breaking it. Measured over fourteen
seeded medium-vs-medium matches:

| Opening order | Matches finishing |
|---|---|
| B S B P S P (this one) | 14 / 14 |
| B S P P S B (mirror-symmetric) | 3 / 14 |
| B P S S P B (mirror-symmetric) | 2 / 14 |
| P S B B S P (mirror-symmetric) | 1 / 14 |

## 2. Addendum: Banana Split

Both of your bananas across **and none of theirs**.

> A player wins immediately if they have delivered both of their own bananas while their
> opponent has delivered none of theirs.

So there are now two ways to shut a Banana Split down, and the second one is new:

- Blend one of their bananas. Blended fruit can never be delivered, so the attempt is
  dead for good.
- **Deliver one of your own bananas.** Their Split is off from that moment, permanently,
  because your delivered count never returns to zero.

The second is the more interesting of the two: getting a banana across is now a defensive
move as well as two points, and it costs you nothing to make.

Both trackers on the match screen strike through and fade once an objective can no longer
be reached, so neither player spends turns chasing something already dead.

## 3. Golden Pineapple, as originally written

With two pineapples per player the original wording works and is implemented literally:

> If a player's first pineapple is blended — by the opponent or by the player themself —
> that player's remaining pineapple becomes the Golden Pineapple.

Promotion is per player, so **both players can be carrying a Golden Pineapple at once**.
Values are unchanged: 3× base delivered (9 points), 2× base to whoever blends it (6
points, or −6 if that is its own owner).

The earlier build promoted "whichever pineapple was still on the board, whoever owned
it", because with one pineapple each the literal rule could never fire. That
reinterpretation is now withdrawn.

## 4. What this changed in the build

- `GameSetup.fruitOrder` — two of each, in the order above.
- `WildcardProgress.goldenPineapple` and `.firstPineappleBlended` are per player.
- The Banana Split win condition checks the opponent's delivered count.
- Rules version 5, payload schema 5. Matches in progress under earlier versions are
  refused rather than migrated, as before.

## 6. What the numbers say now

Sixteen seeded self-play matches per row, measured from the engine. Every match finishes
without a turn limit.

| Rules | Difficulty | How they ended | Avg turns | Blenders destroyed | Bought back |
|---|---|---|---|---|---|
| Standard | Medium | 16 on score | 103 | 195 | 195 |
| Standard | Easy | 16 on score | 153 | 27 | 23 |
| Wildcard | Medium | 15 on score, 1 drawn | 73 | 100 | 99 |
| Wildcard | Easy | 12 on score, 2 Blenderama, 2 drawn | 142 | 27 | 23 |

### The momentum question, answered

The handoff's key playtest target was whether one successful blender attack produces too
much momentum. Measured as how often the player who lands the first kill goes on to win:

| Rules | With the Mega Blender | Without it |
|---|---|---|
| Standard, Medium | 14 / 16 (**88%**) | 9 / 15 (**60%**) |
| Wildcard, Medium | 5 / 16 (31%) | 9 / 15 (60%) |

Removing the Mega did the job it was meant to. 60% is still an edge — it should be, first
blood ought to be worth something — but it is no longer close to decisive.

### Two things worth watching

**Blenders are traded constantly.** 195 kills and 195 rebuys across 16 standard matches
is roughly twelve of each per match, and the near-perfect one-to-one ratio says the CPU
rebuys *every single time*. Three points is not currently deterring anything. If blender
churn feels excessive in human play, the restore cost is the first dial to turn.

**Spawn squares are safe from capture.** *Rules 12.* A blender standing on either of its
owner's spawn squares cannot be taken, by the opponent or by its own side. Either spawn
counts, not just the one that blender started on, which follows the either-spawn buy-back
rule: both squares are home.

This closes the original spawn-camping loop, where a blender parked on an enemy spawn
stepped off, let the opponent rebuild for three points, and stepped back on to destroy it
again, indefinitely. The CPU discovered that on its own and drove a player to −416 points.
Until rules 12 the only defence was the CPU's judgement, and a human could still do it to
another human.

Two things it deliberately does **not** do, both pinned by tests:

- **It is not immunity, only safety at home.** The blender is safe while it stands there
  and capturable the moment it steps off. Camping therefore still works as a *siege*: the
  camper can wait next door and take the blender when it finally has to move. Buying a
  blender back onto a camped spawn is still a bad deal — three points for a piece that is
  entombed — which is why the CPU's `blenderOnCoveredSpawn` penalty survives the rule
  change rather than being retired by it. Removing it put the CPU straight back into the
  old loop one square over, at −424 points, and the self-play test caught it.
- **It stops captures, not everything.** The retained smoothie blowout goes straight
  through it, which is pinned by a test so the distinction survives the blowout being
  switched back on.

Measured after the change: standard and wildcard, Easy and Medium, 24 matches each — all
terminate bar one, medians 88–134 turns, and blender combat is as frequent as before
(172–201 kills per 24 Medium matches). Making home safe did not make anyone turtle.

### CPU calibration notes

Two lessons that will matter to anyone tuning the weights:

- A destroyed blender must be priced **above** its three-point replacement cost, or the
  CPU never buys one back. It first sat at 0.7 points and restores never happened at all.
- A threat term applied to **both** sides cancels out. Both blenders threaten each other
  across a diagonal, so scoring that symmetrically nets to zero — which is exactly how
  the CPU convinced itself to keep feeding a spawn camper. The penalty is now narrow: a
  blender standing on *its own spawn* while an enemy blender covers it. A broader version
  made the CPU too timid to attack at all, because an approach costs a turn before the
  reward arrives and a one-ply search cannot see that far.
