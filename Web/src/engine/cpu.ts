import { BOARD_SIZE, separation } from "./board.js";
import type { EvaluationWeights } from "./evaluation.js";
import { defaultWeights, evaluate, WIN_SCORE } from "./evaluation.js";
import { SeededGenerator } from "./random.js";
import { apply, compareActions, legalActions, positionKey } from "./rules.js";
import type { GameAction, GameState } from "./state.js";
import { pieceAt, pieceWithID } from "./state.js";
import { baseValue, deliveryRow, deliveryValue, isFruit, wrapsRows } from "./types.js";

export type CPUDifficulty = "easy" | "medium" | "hard";

export const difficultyName = (d: CPUDifficulty): string =>
  d === "easy" ? "Easy" : d === "medium" ? "Medium" : "Hard";

export const difficultyBlurb = (d: CPUDifficulty): string =>
  d === "easy" ? "Moves without a plan. A good first game."
    : d === "medium" ? "Takes the point in front of it."
      : "Looks several moves ahead, and will trade.";

/** Stops deepening past the soft budget; the hard cap is absolute.
 *
 *  Lower than the app's on purpose. These run on the main thread of a browser tab, where
 *  a two-second search is two seconds of frozen page — the app can hold its search on a
 *  background task and the page cannot. */
const SOFT_BUDGET: Record<CPUDifficulty, number> = { easy: 20, medium: 120, hard: 600 };
const HARD_CAP: Record<CPUDifficulty, number> = { easy: 150, medium: 300, hard: 900 };

const MAXIMUM_DEPTH = 8;
const TIME_CHECK_INTERVAL = 512;

export interface CPUOptions {
  difficulty: CPUDifficulty;
  /** Set for reproducible play; omit for the system generator. */
  seed?: bigint;
  weights?: EvaluationWeights;
}

/** Picks one legal action from an immutable snapshot. Never applies its own move — the
 *  caller pushes the result back through `apply`, so the CPU has no private path into
 *  the game state. */
export function selectAction(state: GameState, options: CPUOptions): GameAction | null {
  const moves = legalActions(state);
  if (moves.length === 0) return null;
  const weights = options.weights ?? defaultWeights;

  if (options.difficulty === "easy") return pickRandom(moves, state, options.seed);
  if (options.difficulty === "medium") return pickBestOnePly(moves, state, weights, options.seed);
  return pickBestSearch(moves, state, weights, options.difficulty);
}

/** Reproducible for a given game, but deliberately not a pure function of the position:
 *  shuffling a fruit back and forth returns to the same position, and a position-only
 *  seed would answer it with the same move for ever. */
function generatorFor(state: GameState, seed: bigint | undefined): SeededGenerator {
  return new SeededGenerator(
    BigInt.asUintN(64, (seed ?? 0n) ^ positionKey(state) ^ BigInt(state.turnNumber)),
  );
}

function pickRandom(moves: GameAction[], state: GameState, seed: bigint | undefined): GameAction {
  if (seed === undefined) return moves[Math.floor(Math.random() * moves.length)]!;
  const generator = generatorFor(state, seed);
  return moves[Number(generator.next() % BigInt(moves.length))]!;
}

function pickBestOnePly(
  moves: GameAction[], state: GameState, weights: EvaluationWeights, seed: bigint | undefined,
): GameAction {
  const player = state.activePlayer;
  const scored: { move: GameAction; value: number }[] = [];
  for (const move of moves) {
    try {
      const next = apply(state, move, { battles: "expected" });
      scored.push({ move, value: evaluate(next.state, player, weights) });
    } catch {
      // An action that cannot be applied is simply not a candidate.
    }
  }
  if (scored.length === 0) return moves[0]!;
  const best = Math.max(...scored.map((s) => s.value));
  const tied = scored.filter((s) => s.value === best).map((s) => s.move);
  if (tied.length === 1) return tied[0]!;
  if (seed === undefined) return tied[Math.floor(Math.random() * tied.length)]!;
  return tied[Number(generatorFor(state, seed).next() % BigInt(tied.length))]!;
}

function pickBestSearch(
  moves: GameAction[], state: GameState, weights: EvaluationWeights, difficulty: CPUDifficulty,
): GameAction {
  const started = performance.now();
  const search = new Search(state.activePlayer, weights, started + HARD_CAP[difficulty]);
  const ordered = orderMoves(moves, state);
  let best = ordered[0]!;

  for (let depth = 2; depth <= MAXIMUM_DEPTH; depth += 1) {
    if (performance.now() - started >= SOFT_BUDGET[difficulty]) break;
    const result = search.bestMove(state, ordered, depth);
    // Ran out of time mid-depth: the partial result is untrustworthy, so the last fully
    // completed depth stands.
    if (!result) break;
    best = result.move;
    // A forced win is found, not improved on.
    if (Math.abs(result.value) >= WIN_SCORE) break;
  }
  return best;
}

type Bound = "exact" | "lower" | "upper";

class Search {
  private transpositions = new Map<bigint, { depth: number; value: number; bound: Bound }>();
  private nodes = 0;
  private timedOut = false;

  constructor(
    private readonly root: "one" | "two",
    private readonly weights: EvaluationWeights,
    private readonly deadline: number,
  ) {}

  bestMove(
    state: GameState, moves: GameAction[], depth: number,
  ): { move: GameAction; value: number } | null {
    let alpha = -WIN_SCORE * 2;
    const beta = WIN_SCORE * 2;
    let best: { move: GameAction; value: number } | null = null;

    for (const move of moves) {
      let next;
      try {
        next = apply(state, move, { battles: "expected" });
      } catch {
        continue;
      }
      const value = this.search(next.state, depth - 1, alpha, beta);
      if (this.timedOut) return null;
      if (!best || value > best.value) {
        best = { move, value };
        alpha = Math.max(alpha, value);
      }
    }
    return best;
  }

  /** Minimax rather than true negamax: a player with no legal move is skipped, so the
   *  side to move does not simply alternate and the sign cannot be flipped blindly. */
  private search(state: GameState, depth: number, alpha: number, beta: number): number {
    this.nodes += 1;
    if (this.nodes % TIME_CHECK_INTERVAL === 0 && performance.now() >= this.deadline) {
      this.timedOut = true;
    }
    if (this.timedOut) return 0;

    if (depth <= 0 || state.status.kind === "ended") {
      return evaluate(state, this.root, this.weights);
    }

    const key = positionKey(state);
    const entry = this.transpositions.get(key);
    if (entry && entry.depth >= depth) {
      if (entry.bound === "exact") return entry.value;
      if (entry.bound === "lower" && entry.value >= beta) return entry.value;
      if (entry.bound === "upper" && entry.value <= alpha) return entry.value;
    }

    const moves = legalActions(state);
    if (moves.length === 0) return evaluate(state, this.root, this.weights);

    const maximizing = state.activePlayer === this.root;
    let localAlpha = alpha;
    let localBeta = beta;
    let best = maximizing ? Number.NEGATIVE_INFINITY : Number.POSITIVE_INFINITY;

    for (const move of orderMoves(moves, state)) {
      let next;
      try {
        next = apply(state, move, { battles: "expected" });
      } catch {
        continue;
      }
      const value = this.search(next.state, depth - 1, localAlpha, localBeta);
      if (this.timedOut) return Number.isFinite(best) ? best : 0;

      if (maximizing) {
        best = Math.max(best, value);
        localAlpha = Math.max(localAlpha, best);
      } else {
        best = Math.min(best, value);
        localBeta = Math.min(localBeta, best);
      }
      if (localAlpha >= localBeta) break;
    }

    const bound: Bound = best <= localAlpha ? "upper" : best >= localBeta ? "lower" : "exact";
    this.transpositions.set(key, { depth, value: best, bound });
    return best;
  }
}

const orderMoves = (moves: GameAction[], state: GameState): GameAction[] =>
  [...moves].sort((a, b) => {
    const rank = staticRank(b, state) - staticRank(a, state);
    return rank !== 0 ? rank : compareActions(b, a);
  });

/** Cheap ordering heuristic — no move is applied. Immediate points first, then fruit
 *  getting closer to home, then everything else. Self-blends sort last: legal and
 *  occasionally right, but they should be the moves the search reaches for only once the
 *  obvious ones have been refuted. */
function staticRank(action: GameAction, state: GameState): number {
  if (action.kind === "restoreBlender") return -60;

  const piece = pieceWithID(state, action.pieceID);
  if (!piece) return 0;

  if (isFruit(piece)) {
    if (action.destination.row === deliveryRow(piece.owner, BOARD_SIZE)) {
      return 1_000 + deliveryValue(piece.kind) * 10;
    }
    const joined = wrapsRows(state.boardWrap);
    const goal = deliveryRow(piece.owner, BOARD_SIZE);
    const before = separation(action.origin.row, goal, joined);
    const after = separation(action.destination.row, goal, joined);
    return (before - after) * baseValue(piece.kind) * 4;
  }

  const occupant = pieceAt(state, action.destination);
  if (!occupant) return 1;
  if (isFruit(occupant)) {
    return occupant.owner === piece.owner
      ? -800 - baseValue(occupant.kind) * 10
      : 800 + baseValue(occupant.kind) * 10;
  }
  // Destroying their blender is the strongest thing a blender can do; wrecking your own
  // is the weakest.
  return occupant.owner === piece.owner ? -900 : 900;
}
