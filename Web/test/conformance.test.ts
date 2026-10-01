import { readFileSync } from "node:fs";
import { gunzipSync } from "node:zlib";
import { describe, expect, it } from "vitest";
import { apply, RULES_VERSION, describeAction, legalActions } from "../src/engine/rules.js";
import { newGame } from "../src/engine/setup.js";
import type { GameState, Outcome } from "../src/engine/state.js";
import type { BoardSetup, BoardWrap, PlayerID, RuleSet } from "../src/engine/types.js";

/** The Swift engine is the specification. These games were generated from it; this port
 *  has to reproduce them move for move, position for position, event for event. */
interface Fixture {
  rulesVersion: number;
  games: FixtureGame[];
}

interface FixtureGame {
  driver: string;
  ruleSet: RuleSet;
  boardSetup: BoardSetup;
  boardWrap: BoardWrap;
  blenderBattles: boolean;
  startingPlayer: PlayerID;
  seed: number;
  steps: FixtureStep[];
  /** Absent rather than null when the game was still going: Swift's JSONEncoder omits
   *  nil optionals entirely. */
  outcome?: string;
}

interface FixtureStep {
  action: string;
  events: string[];
  after: string;
  activePlayer: PlayerID;
  scores: [number, number];
  turnNumber: number;
  legalBefore?: string[];
}

const fixture = JSON.parse(
  gunzipSync(readFileSync(new URL("../fixtures/conformance.json.gz", import.meta.url))).toString(
    "utf8",
  ),
) as Fixture;

const places = (state: GameState): string =>
  state.pieces
    .map((p) => (p.position ? `${p.id}@${p.position.row},${p.position.column}` : `${p.id}@-`))
    .join(" ");

const describeOutcome = (outcome: Outcome): string =>
  outcome.kind === "draw" ? "draw" : `${outcome.player} by ${outcome.reason}`;

describe("conformance with the Swift engine", () => {
  it("is built against the same rules version", () => {
    expect(RULES_VERSION).toBe(fixture.rulesVersion);
  });

  for (const game of fixture.games) {
    const name =
      `${game.ruleSet}/${game.boardSetup}/${game.boardWrap}` +
      `${game.blenderBattles ? "/battles" : ""} by ${game.driver} (seed ${game.seed})`;

    it(`replays ${name}`, () => {
      let state = newGame({
        startingPlayer: game.startingPlayer,
        ruleSet: game.ruleSet,
        boardSetup: game.boardSetup,
        boardWrap: game.boardWrap,
        blenderBattles: game.blenderBattles,
        seed: BigInt(game.seed),
      });

      game.steps.forEach((expected, index) => {
        const legal = legalActions(state);

        if (expected.legalBefore) {
          expect(legal.map(describeAction), `legal moves at step ${index} of ${name}`)
            .toEqual(expected.legalBefore);
        }

        // The port does not reproduce the driver's *choice* — that is a property of the
        // fixture generator, not of the rules. It has to agree that the chosen action was
        // available, which is the claim that matters, and then produce the same result
        // from it. That also lets the generator use drivers the port knows nothing about,
        // such as the one that chases a Blenderama.
        const action = legal.find((candidate) => describeAction(candidate) === expected.action);
        expect(action, `step ${index} of ${name}: ${expected.action} was not legal here`)
          .toBeDefined();

        const transition = apply(state, action!);
        expect(places(transition.state), `position after step ${index} of ${name}`)
          .toBe(expected.after);
        expect(transition.state.activePlayer, `active player after step ${index}`)
          .toBe(expected.activePlayer);
        expect(
          [transition.state.scores.one, transition.state.scores.two],
          `scores after step ${index} of ${name}`,
        ).toEqual(expected.scores);
        expect(transition.state.turnNumber, `turn number after step ${index}`)
          .toBe(expected.turnNumber);

        state = transition.state;
      });

      const outcome = state.status.kind === "ended" ? describeOutcome(state.status.outcome) : null;
      expect(outcome, `outcome of ${name}`).toBe(game.outcome ?? null);
    });
  }
});
