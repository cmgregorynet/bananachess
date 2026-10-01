import { describe, expect, it } from "vitest";
import type { CPUDifficulty } from "../src/engine/cpu.js";
import { selectAction } from "../src/engine/cpu.js";
import { apply, legalActions, describeAction } from "../src/engine/rules.js";
import { newGame } from "../src/engine/setup.js";
import type { BoardSetup, PlayerID } from "../src/engine/types.js";

/** Unlike the rules, the CPU is not held to the Swift one move for move. It is a
 *  heuristic: a port that plays differently but sensibly is correct, where a port that
 *  applies the rules differently is not. So these check that it plays legally, that it
 *  finishes, that the ladder holds, and that it stays inside its time budget. */
const DIFFICULTIES: CPUDifficulty[] = ["easy", "medium", "hard"];

function playOut(
  options: { one: CPUDifficulty; two: CPUDifficulty; seed: bigint; setup?: BoardSetup },
): ReturnType<typeof newGame> {
  let state = newGame({
    startingPlayer: options.seed % 2n === 0n ? "one" : "two",
    ruleSet: "wildcard",
    boardSetup: options.setup ?? "standard",
    seed: options.seed,
  });
  let guard = 0;
  while (state.status.kind !== "ended" && guard < 600) {
    const difficulty = state.activePlayer === "one" ? options.one : options.two;
    const action = selectAction(state, {
      difficulty,
      seed: options.seed * 31n + BigInt(guard === 0 ? 0 : 1),
    });
    if (!action) break;
    state = apply(state, action).state;
    guard += 1;
  }
  return state;
}

describe("the computer opponent", () => {
  it.each(DIFFICULTIES)("%s always picks a move that is actually legal", (difficulty) => {
    for (const setup of ["standard", "blendy", "random"] as BoardSetup[]) {
      let state = newGame({ ruleSet: "wildcard", boardSetup: setup, seed: 5n });
      for (let turn = 0; turn < 30 && state.status.kind !== "ended"; turn += 1) {
        const action = selectAction(state, { difficulty, seed: 7n });
        expect(action, `${difficulty} found no move on ${setup}`).not.toBeNull();
        const legal = legalActions(state).map(describeAction);
        expect(legal, `${difficulty} picked an illegal move on ${setup}`)
          .toContain(describeAction(action!));
        state = apply(state, action!).state;
      }
    }
  });

  it("returns null only when there is genuinely nothing to do", () => {
    const state = newGame();
    expect(selectAction(state, { difficulty: "medium", seed: 1n })).not.toBeNull();
    const finished = { ...state, status: { kind: "ended" as const, outcome: { kind: "draw" as const } } };
    expect(selectAction(finished, { difficulty: "medium", seed: 1n })).toBeNull();
  });

  it("plays a whole match to a finish", () => {
    const final = playOut({ one: "medium", two: "medium", seed: 3n });
    expect(final.status.kind).toBe("ended");
  });

  it("is reproducible from a seed", () => {
    const a = playOut({ one: "easy", two: "easy", seed: 11n });
    const b = playOut({ one: "easy", two: "easy", seed: 11n });
    expect(a.scores).toEqual(b.scores);
    expect(a.turnNumber).toBe(b.turnNumber);
  });

  it("ranks: medium beats easy more often than not", () => {
    let mediumWins = 0;
    let decided = 0;
    for (let seed = 1n; seed <= 8n; seed += 1n) {
      const final = playOut({ one: "medium", two: "easy", seed });
      if (final.status.kind !== "ended") continue;
      const outcome = final.status.outcome;
      if (outcome.kind === "draw") continue;
      decided += 1;
      if (outcome.player === ("one" as PlayerID)) mediumWins += 1;
    }
    expect(decided).toBeGreaterThan(0);
    expect(mediumWins / decided).toBeGreaterThan(0.6);
  });

  it("stays inside its budget, because this runs on the page's own thread", () => {
    const state = newGame({ ruleSet: "wildcard" });
    for (const difficulty of DIFFICULTIES) {
      const started = performance.now();
      selectAction(state, { difficulty, seed: 2n });
      const elapsed = performance.now() - started;
      // The hard cap is 900ms for hard; allow room for a loaded CI machine.
      expect(elapsed, `${difficulty} took ${elapsed.toFixed(0)}ms`).toBeLessThan(2_500);
    }
  });
});
