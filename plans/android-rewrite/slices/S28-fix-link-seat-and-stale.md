---
id: S28
title: Fix the final-position seat and the same-link refusal in link play
layer: ui
points: 1
depends_on: []
touches: [Android/app/src/main/kotlin/com/charlesbarton/bananachess/link/LinkPlay.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/link/LinkOpeningTest.kt]
status: done
branch: slice/android-rewrite/S28
started: 2026-11-24T21:12
completed: 2026-11-24T21:14
actual_points: 1
review_fixes: none
blocked_reason:
---

# S28 — Fix the final-position seat and the same-link refusal in link play

## Goal
Opening a finished match's link seats the receiver correctly, and opening the same link again opens the match instead of calling it stale.

## Context
- From FINAL_REVIEW.md findings 1 and 2, approved by Charles for fixing before merge.
- `LinkPlay.open` seats the local player as `payload.state.activePlayer`. `RulesEngine` never hands the turn over when a match ends (`end()` only sets the status), so on a finished payload `activePlayer` is the sender.
- `holdsAtLeast` (`>=`) is used by both `open` and `start`. `start` should keep preferring a saved match at the same move or later; `open` should refuse only a link strictly behind the saved match. iOS refuses an equal link too; Android deliberately differs because the message "That link is older…" is false for an equal link.

## Scope
- `open`: a finished payload seats `lastMove.playedBy.opponent`, else `activePlayer.opponent`.
- `open`: refuse as Stale only when the saved same-match history is longer than the link's move number.
- Tests for both.

## Out of Scope
- CPU, iOS and minor findings (follow-ups).

## Acceptance Criteria
- [ ] A final-position link opens seated as the receiver, with and without `lastMove`.
- [ ] The same link opened twice opens the match; an older link is still refused.
- [ ] Build, lint and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug
```

## Estimate Rationale
1: two small logic changes with focused tests.

## Actuals
- **Estimate vs actual:** 1 → 1. Done by the orchestrator after Charles approved fixing final-review findings 1 and 2.
- **What shipped:** `LinkPlay.open` seats a finished payload's receiver as `lastMove.playedBy.opponent`, else `activePlayer.opponent` (the engine never hands the turn over on `end()`). `open` refuses as Stale only when the saved same-match history is longer than the link's move number; an equal link opens the saved match (deliberate difference from iOS, whose message would be false). `start` still prefers a saved match at the same move or later.
- **Tests:** final position seated correctly with and without `lastMove`; same link opens the saved match; older still refused; newer accepted.
- **Verification:** no cache — app tests, lint, ktlint, assembleDebug; then on the merged tree.
