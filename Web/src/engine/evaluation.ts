import { BOARD_SIZE, DIAGONAL, separation, step } from "./board.js";
import { BANANA_DELIVERIES_TO_WIN, FRUIT_COUNT } from "./setup.js";
import type { GameState } from "./state.js";
import {
  activeBlenders, blendersOwnedBy, fruitOwnedBy, goldenPineapple, pieceAt,
} from "./state.js";
import type { PlayerID, Position } from "./types.js";
import {
  baseValue, deliveryRow, isBlender, isFruit, needsBuyingBack, opponentOf, samePosition,
  wrapsRows,
} from "./types.js";

/** Every number the CPU cares about, ported from `EvaluationWeights`. Playtest dials, not
 *  rules: changing them must never change which moves are legal. */
export interface EvaluationWeights {
  score: number;
  fruitOnBoard: number;
  advancement: number;
  threatenedFruit: number;
  blenderThreat: number;
  blenderOnCoveredSpawn: number;
  blenderMobility: number;
  fruitOnOwnBlenderHome: number;
  blenderDestroyed: number;
  clockPressure: number;
  alternateWinThreat: number;
  smoothieProgress: number;
  goldenPineapple: number;
}

export const defaultWeights: EvaluationWeights = {
  score: 100,
  fruitOnBoard: 18,
  advancement: 6,
  threatenedFruit: -14,
  blenderThreat: 12,
  /** Entombed rather than doomed: a blender bought back onto a camped spawn is three
   *  points for a piece that can never move. Removing this put the CPU straight back into
   *  the old rebuy loop. */
  blenderOnCoveredSpawn: -320,
  blenderMobility: 3,
  fruitOnOwnBlenderHome: -8,
  /** Only *just* above its three-point replacement cost. At -450 two idle blenders traded
   *  for ever, 4,218 buy-backs across sixteen Blendy matches that never ended. */
  blenderDestroyed: -250,
  clockPressure: 7,
  /** Held down by measurement: at 40,000 both CPUs simply raced for a Blenderama and
   *  neither ever scored. */
  alternateWinThreat: 4_000,
  smoothieProgress: 22,
  goldenPineapple: 30,
};

export const WIN_SCORE = 1_000_000;

const marginFor = (state: GameState, player: PlayerID): number =>
  state.scores[player] - state.scores[opponentOf(player)];

/** Positive is good for `player`. Symmetric by construction, which is what makes minimax
 *  sound here. */
export function evaluate(
  state: GameState, player: PlayerID, weights: EvaluationWeights = defaultWeights,
): number {
  if (state.status.kind === "ended") {
    const outcome = state.status.outcome;
    if (outcome.kind === "draw") return 0;
    const margin = Math.abs(marginFor(state, player));
    return outcome.player === player ? WIN_SCORE + margin : -WIN_SCORE - margin;
  }

  let total = marginFor(state, player) * weights.score;
  total += positional(state, player, weights);
  total -= positional(state, opponentOf(player), weights);

  // How many moves of match are left, roughly: whichever side is nearer to empty.
  const clock = Math.min(
    fruitOwnedBy(state, player).length,
    fruitOwnedBy(state, opponentOf(player)).length,
  );
  const margin = marginFor(state, player);
  if (margin !== 0) total += (margin > 0 ? -clock : clock) * weights.clockPressure;

  if (state.ruleSet === "wildcard") {
    total += wildcards(state, player, weights);
    total -= wildcards(state, opponentOf(player), weights);
  }
  return total;
}

/** Progress toward an outright win, priced by how close it is rather than how much of it
 *  is done: both alternate victories are all-or-nothing, so the last move is worth vastly
 *  more than the first. */
function wildcards(state: GameState, player: PlayerID, weights: EvaluationWeights): number {
  let total = 0;

  const split = movesToBananaSplit(state, player);
  if (split !== null) total += threatValue(split, weights);
  const rama = movesToBlenderama(state, player);
  if (rama !== null) total += threatValue(rama, weights);

  total += counterReadiness(state, player, weights);
  const smoothie = state.wildcards.smoothie[player];
  const collected = [smoothie.banana, smoothie.strawberry, smoothie.pineapple].filter(Boolean).length;
  total += collected * weights.smoothieProgress;
  if (goldenPineapple(state, player) !== null) total += weights.goldenPineapple;
  return total;
}

/** Credit for being *able* to answer the opponent's alternate win. A threat alone is
 *  worth the same after every move that does not remove it, so it cancels out of the
 *  comparison and says nothing about what to do. */
function counterReadiness(
  state: GameState, player: PlayerID, weights: EvaluationWeights,
): number {
  const attacker = opponentOf(player);
  let total = 0;

  const rama = movesToBlenderama(state, attacker);
  if (rama !== null) {
    total += readiness(
      threatValue(rama, weights),
      fruitOwnedBy(state, attacker).flatMap((f) => (f.position ? [f.position] : [])),
      player, state,
    );
  }

  const split = movesToBananaSplit(state, attacker);
  if (split !== null) {
    total += readiness(
      threatValue(split, weights),
      fruitOwnedBy(state, attacker)
        .filter((f) => f.kind === "banana")
        .flatMap((f) => (f.position ? [f.position] : [])),
      player, state,
    );
  }
  return total;
}

function readiness(
  threat: number, targets: Position[], player: PlayerID, state: GameState,
): number {
  if (targets.length === 0) return 0;
  const distance = shortestBlenderApproach(state, player, targets) ?? BOARD_SIZE;
  const closeness = BOARD_SIZE - Math.min(distance, BOARD_SIZE);
  return Math.trunc((threat * closeness) / BOARD_SIZE);
}

const threatValue = (stepsAway: number, weights: EvaluationWeights): number => {
  const steps = Math.max(1, stepsAway);
  return Math.trunc(weights.alternateWinThreat / (steps * steps));
};

function movesToBlenderama(state: GameState, player: PlayerID): number | null {
  if (state.wildcards.blenderamaCountered[player]) return null;
  const blended = state.wildcards.ownFruitBlended[player];
  const remaining = fruitOwnedBy(state, player);
  if (!(blended > 0 || remaining.length === FRUIT_COUNT)) return null;
  if (blended + remaining.length < FRUIT_COUNT) return null;
  if (remaining.length === 0) return null;

  const approach = shortestBlenderApproach(
    state, player, remaining.flatMap((f) => (f.position ? [f.position] : [])),
  );
  return remaining.length + (approach ?? BOARD_SIZE);
}

function movesToBananaSplit(state: GameState, player: PlayerID): number | null {
  const delivered = state.wildcards.bananasDelivered[player];
  const remaining = BANANA_DELIVERIES_TO_WIN - delivered;
  if (remaining <= 0) return null;
  const bananas = fruitOwnedBy(state, player).filter((f) => f.kind === "banana");
  if (bananas.length === 0) return null;

  const joined = wrapsRows(state.boardWrap);
  const nearest = Math.min(
    ...bananas.map((banana) =>
      banana.position
        ? separation(banana.position.row, deliveryRow(player, BOARD_SIZE), joined)
        : BOARD_SIZE - 1,
    ),
  );
  return (remaining - 1) * (BOARD_SIZE - 1) + nearest;
}

/** Blenders never cross an edge, so their distances are always the walled ones. */
function shortestBlenderApproach(
  state: GameState, player: PlayerID, squares: Position[],
): number | null {
  const blenders = activeBlenders(state, player).flatMap((b) => (b.position ? [b.position] : []));
  if (blenders.length === 0 || squares.length === 0) return null;
  let best = Number.MAX_SAFE_INTEGER;
  for (const from of blenders) {
    for (const to of squares) {
      best = Math.min(
        best,
        Math.max(separation(from.row, to.row, false), separation(from.column, to.column, false)),
      );
    }
  }
  return best;
}

function positional(state: GameState, player: PlayerID, weights: EvaluationWeights): number {
  let total = 0;
  const enemyReach = blenderReach(state, opponentOf(player));
  const own = blendersOwnedBy(state, player);
  const ownHomes = own.flatMap((b) => (b.home ? [b.home] : []));
  const destroyedSpawns = own
    .filter((b) => b.position === null)
    .flatMap((b) => (b.home ? [b.home] : []));

  for (const fruit of fruitOwnedBy(state, player)) {
    total += baseValue(fruit.kind) * weights.fruitOnBoard;
    const square = fruit.position;
    if (!square) continue;

    const distance = separation(
      square.row, deliveryRow(player, BOARD_SIZE), wrapsRows(state.boardWrap),
    );
    total += (BOARD_SIZE - 1 - distance) * baseValue(fruit.kind) * weights.advancement;

    if (enemyReach.some((p) => samePosition(p, square))) {
      total += baseValue(fruit.kind) * weights.threatenedFruit;
    }
    if (
      ownHomes.some((p) => samePosition(p, square)) &&
      !destroyedSpawns.some((p) => samePosition(p, square))
    ) {
      total += weights.fruitOnOwnBlenderHome;
    }
  }

  for (const blender of own) {
    const position = blender.position;
    if (!position) {
      // A smoothie victim comes back for free; a combat loss only returns if its owner
      // spends a turn and three points on it.
      total += needsBuyingBack(blender)
        ? weights.blenderDestroyed
        : Math.trunc(weights.blenderDestroyed / 4);
      continue;
    }
    const onSpawn = state.pieces.some(
      (p) => isBlender(p) && p.owner === player && samePosition(p.home, position),
    );
    if (onSpawn && enemyReach.some((p) => samePosition(p, position))) {
      total += weights.blenderOnCoveredSpawn;
    }

    let mobility = 0;
    for (const delta of DIAGONAL) {
      const square = step(position, delta.row, delta.column, "flat");
      if (!square) continue;
      const occupant = pieceAt(state, square);
      if (!occupant) {
        mobility += 1;
      } else if (isFruit(occupant) && occupant.owner !== player) {
        total += baseValue(occupant.kind) * weights.blenderThreat;
      } else if (isBlender(occupant)) {
        mobility += 1;
      }
      // Landing on your own fruit is legal but costs points, so it is not somewhere this
      // blender usefully wants to go.
    }
    total += mobility * weights.blenderMobility;
  }
  return total;
}

/** Squares the player's blenders could land on next move — raw geometry, which is why it
 *  still flags a camped spawn even though that square can no longer be captured. */
function blenderReach(state: GameState, player: PlayerID): Position[] {
  const reach: Position[] = [];
  for (const blender of activeBlenders(state, player)) {
    if (!blender.position) continue;
    for (const delta of DIAGONAL) {
      const square = step(blender.position, delta.row, delta.column, "flat");
      if (square) reach.push(square);
    }
  }
  return reach;
}
