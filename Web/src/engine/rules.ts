import { BOARD_SIZE, DIAGONAL, ORTHOGONAL, step } from "./board.js";
import type { BattleReport, GameEvent } from "./events.js";
import { attackerWins } from "./events.js";
import { SeededGenerator } from "./random.js";
import { BANANA_DELIVERIES_TO_WIN, FRUIT_COUNT } from "./setup.js";
import type { GameAction, GameState, Outcome } from "./state.js";
import {
  activeBlenders, battleStrength, blendersOwnedBy, cloneState, destroyedBlenders, edgesFor,
  fruitOwnedBy, isGolden, onBoardFruit, pieceAt, pieceWithID, piecesOwnedBy, recordArrangement,
  recordSmoothie, removePiece, setPosition, setRespawnTurn, smoothieIsComplete, timesSeen,
} from "./state.js";
import type { Piece, PlayerID, Position } from "./types.js";
import {
  PLAYERS, baseValue, deliveryRow, deliveryValue, isBlender, isWaitingToReturn,
  needsBuyingBack, opponentOf, samePosition,
} from "./types.js";

/** Bumped whenever a rules change would make two clients disagree. Must equal the Swift
 *  engine's, or the conformance suite is comparing different games. */
export const RULES_VERSION = 18;
export const BLENDER_RESTORE_COST = 3;
export const SMOOTHIE_AWARD = 3;
export const SMOOTHIE_BLACKOUT_TURNS = 2;
export const BATTLE_ATTACKER_BONUS = 1;
export const REPETITION_LIMIT = 3;

export interface Transition {
  state: GameState;
  events: GameEvent[];
}

export class RulesError extends Error {
  constructor(public readonly code: string, message: string) {
    super(message);
    this.name = "RulesError";
  }
}

// MARK: - Ordering

const comparePositions = (a: Position, b: Position): number =>
  a.row !== b.row ? a.row - b.row : a.column - b.column;

const compareStrings = (a: string, b: string): number => (a < b ? -1 : a > b ? 1 : 0);

/** Canonical ordering, matching the Swift engine's `Comparable` conformances exactly: a
 *  move sorts by piece then destination, and every move sorts before every restore. */
export function compareActions(a: GameAction, b: GameAction): number {
  if (a.kind === "move" && b.kind === "move") {
    const byPiece = compareStrings(a.pieceID, b.pieceID);
    return byPiece !== 0 ? byPiece : comparePositions(a.destination, b.destination);
  }
  if (a.kind === "restoreBlender" && b.kind === "restoreBlender") {
    const byPiece = compareStrings(a.pieceID, b.pieceID);
    return byPiece !== 0 ? byPiece : comparePositions(a.at, b.at);
  }
  return a.kind === "move" ? -1 : 1;
}

export const describeAction = (action: GameAction): string =>
  action.kind === "move"
    ? `${action.pieceID} r${action.origin.row}c${action.origin.column}→r${action.destination.row}c${action.destination.column}`
    : `restore ${action.pieceID} at r${action.at.row}c${action.at.column}`;

// MARK: - Legal moves

/** Whether a blender is standing on one of its owner's spawns — either of them, since a
 *  destroyed blender may be bought back onto either. Both are home, and home is safe. */
export function isOnASpawn(state: GameState, blender: Piece): boolean {
  if (!isBlender(blender) || !blender.position) return false;
  const square = blender.position;
  return state.pieces.some(
    (p) => isBlender(p) && p.owner === blender.owner && samePosition(p.home, square),
  );
}

function canLand(state: GameState, piece: Piece, destination: Position): boolean {
  const occupant = pieceAt(state, destination);
  if (!occupant) return true;
  // Fruit never captures, including onto a blocked delivery square.
  if (!isBlender(piece)) return false;
  if (isBlender(occupant) && isOnASpawn(state, occupant)) return false;
  return true;
}

function geometricMoves(state: GameState, piece: Piece): GameAction[] {
  const origin = piece.position;
  if (!origin) return [];
  const steps = isBlender(piece) ? DIAGONAL : ORTHOGONAL;
  const wrap = edgesFor(state, piece);
  const moves: GameAction[] = [];
  for (const delta of steps) {
    const destination = step(origin, delta.row, delta.column, wrap);
    if (!destination) continue;
    if (!canLand(state, piece, destination)) continue;
    moves.push({ kind: "move", pieceID: piece.id, origin, destination });
  }
  return moves;
}

export function legalMovesForPlayer(
  state: GameState,
  player: PlayerID,
  enforcingRepetition = true,
): GameAction[] {
  if (state.status.kind === "ended") return [];
  return piecesOwnedBy(state, player)
    .flatMap((piece) => geometricMoves(state, piece))
    .filter((move) => !enforcingRepetition || !wouldRepeat(state, move))
    .sort(compareActions);
}

export function legalMovesForPiece(state: GameState, id: string): GameAction[] {
  if (state.status.kind === "ended") return [];
  const piece = pieceWithID(state, id);
  if (!piece) return [];
  return geometricMoves(state, piece)
    .filter((move) => !wouldRepeat(state, move))
    .sort(compareActions);
}

export const spawnSquares = (state: GameState, player: PlayerID): Position[] =>
  blendersOwnedBy(state, player)
    .flatMap((b) => (b.home ? [b.home] : []))
    .sort(comparePositions);

export function legalActions(state: GameState, enforcingRepetition = true): GameAction[] {
  if (state.status.kind === "ended") return [];
  const actions: GameAction[] = legalMovesForPlayer(
    state, state.activePlayer, enforcingRepetition,
  );
  const spawns = spawnSquares(state, state.activePlayer);
  for (const blender of destroyedBlenders(state, state.activePlayer)) {
    if (!needsBuyingBack(blender)) continue;
    for (const spawn of spawns) {
      if (pieceAt(state, spawn)) continue;
      const restore: GameAction = { kind: "restoreBlender", pieceID: blender.id, at: spawn };
      if (enforcingRepetition && wouldRepeat(state, restore)) continue;
      actions.push(restore);
    }
  }
  return actions.sort(compareActions);
}

function legalActionsFor(
  state: GameState,
  player: PlayerID,
  enforcingRepetition = true,
): GameAction[] {
  const probe = cloneState(state);
  probe.activePlayer = player;
  return legalActions(probe, enforcingRepetition);
}

/** Whether an action would stand the board in an arrangement seen twice already.
 *
 *  Answered by playing it out, because a turn does more than move one piece. The
 *  simulation runs with the rule off, which is what stops this asking itself the same
 *  question for ever. */
export function wouldRepeat(state: GameState, action: GameAction): boolean {
  let next: Transition;
  try {
    next = apply(state, action, { enforcingRepetition: false });
  } catch {
    return false;
  }
  const key = next.state.boardHistory[next.state.boardHistory.length - 1]!;
  return timesSeen(state, key) >= REPETITION_LIMIT - 1;
}

// MARK: - Validation

export function validate(state: GameState, action: GameAction): void {
  if (state.status.kind === "ended") throw new RulesError("gameOver", "The match has already finished.");

  if (action.kind === "restoreBlender") {
    const blender = pieceWithID(state, action.pieceID);
    if (!blender) throw new RulesError("unknownPiece", "That piece is no longer on the board.");
    if (blender.owner !== state.activePlayer) {
      throw new RulesError("notActivePlayersPiece", "That piece belongs to the other player.");
    }
    if (blender.position !== null) {
      throw new RulesError("blenderNotDestroyed", "That blender is already on the board.");
    }
    if (!needsBuyingBack(blender)) {
      throw new RulesError("blenderReturningOnItsOwn", "That blender is coming back on its own.");
    }
    if (!spawnSquares(state, blender.owner).some((s) => samePosition(s, action.at))) {
      throw new RulesError("notASpawnSquare", "A blender can only come back on one of your spawns.");
    }
    if (pieceAt(state, action.at)) {
      throw new RulesError("spawnOccupied", "Something is standing on that blender's spawn.");
    }
    return;
  }

  const piece = pieceWithID(state, action.pieceID);
  if (!piece) throw new RulesError("unknownPiece", "That piece is no longer on the board.");
  if (piece.owner !== state.activePlayer) {
    throw new RulesError("notActivePlayersPiece", "That piece belongs to the other player.");
  }
  if (!piece.position) throw new RulesError("pieceIsOffBoard", "That blender is still waiting to come back.");
  if (!samePosition(piece.position, action.origin)) {
    throw new RulesError("originMismatch", "That piece has moved since the move was created.");
  }
  const legal = geometricMoves(state, piece);
  if (!legal.some((m) => m.kind === "move" && samePosition(m.destination, action.destination))) {
    throw new RulesError("illegalDestination", "That square is not a legal destination.");
  }
}

// MARK: - Applying

export interface ApplyOptions {
  /** The real game rolls dice; the search replaces both with the same number. */
  battles?: "dice" | "expected";
  enforcingRepetition?: boolean;
}

export function apply(
  state: GameState,
  action: GameAction,
  options: ApplyOptions = {},
): Transition {
  const { battles = "dice", enforcingRepetition = true } = options;
  validate(state, action);
  if (enforcingRepetition && wouldRepeat(state, action)) {
    throw new RulesError("wouldRepeatPosition", "That would put the board back where it has already been twice.");
  }

  const next = cloneState(state);
  const events: GameEvent[] = [];

  if (action.kind === "restoreBlender") {
    restore(next, action.pieceID, action.at, events);
  } else {
    const piece = pieceWithID(state, action.pieceID)!;
    const occupant = pieceAt(state, action.destination);
    events.push({
      kind: "moved", pieceID: piece.id, from: action.origin, to: action.destination,
    });

    if (isBlender(piece)) {
      if (occupant && isBlender(occupant)) {
        resolveCollision(next, piece, occupant, action.destination, battles, events);
      } else {
        if (occupant) blend(next, occupant, action.destination, piece.owner, events);
        setPosition(next, piece.id, action.destination);
      }
    } else {
      setPosition(next, piece.id, action.destination);
      if (action.destination.row === deliveryRow(piece.owner, BOARD_SIZE)) {
        deliver(next, piece, action.destination, events);
      }
    }
  }

  next.history.push(action);
  announceGoldenPineapples(state, next, events);
  recordScoringTurn(next, state.activePlayer, state.scores);
  finishTurn(next, events, enforcingRepetition);
  // Recorded last, once the turn has changed hands: the arrangement includes whose move
  // it is, so counting it earlier files it under the wrong player.
  recordArrangement(next);
  return { state: next, events };
}

function restore(state: GameState, id: string, at: Position, events: GameEvent[]): void {
  const blender = pieceWithID(state, id)!;
  setPosition(state, id, at);
  setRespawnTurn(state, id, null);
  state.scores[blender.owner] -= BLENDER_RESTORE_COST;
  events.push({
    kind: "blenderRestored", pieceID: id, owner: blender.owner, at, cost: BLENDER_RESTORE_COST,
  });
  events.push({ kind: "scoreChanged", player: blender.owner, newScore: state.scores[blender.owner] });
}

/** A battle if the match is playing them, otherwise the plain kill. Running into one of
 *  your own blenders is never a battle: there is nobody to roll against. */
function resolveCollision(
  state: GameState,
  attacker: Piece,
  defender: Piece,
  square: Position,
  battles: "dice" | "expected",
  events: GameEvent[],
): void {
  if (!state.blenderBattles || defender.owner === attacker.owner) {
    destroy(state, defender, attacker, square, events);
    setPosition(state, attacker.id, square);
    return;
  }

  const report = battleReport(state, attacker, defender, square, battles);
  events.push({ kind: "blenderBattle", report, at: square });

  if (attackerWins(report)) {
    destroy(state, defender, attacker, square, events);
    setPosition(state, attacker.id, square);
  } else {
    // The attack failed: the attacker leaves, the defender holds its square.
    destroy(state, attacker, defender, square, events);
  }
}

function battleReport(
  state: GameState,
  attacker: Piece,
  defender: Piece,
  square: Position,
  battles: "dice" | "expected",
): BattleReport {
  let attackerRoll: number;
  let defenderRoll: number;
  if (battles === "dice") {
    const base = BigInt.asUintN(
      64, positionKey(state) + BigInt(square.row * BOARD_SIZE + square.column),
    );
    attackerRoll = die(BigInt.asUintN(64, base + 0x9e3779b9n));
    defenderRoll = die(BigInt.asUintN(64, base + 0x85ebca6bn));
  } else {
    attackerRoll = 4;
    defenderRoll = 4;
  }
  return {
    attacker: attacker.owner,
    attackerFruit: battleStrength(state, attacker.owner),
    attackerBonus: BATTLE_ATTACKER_BONUS,
    attackerRoll,
    defenderFruit: battleStrength(state, defender.owner),
    defenderRoll,
  };
}

const die = (seed: bigint): number => Number(new SeededGenerator(seed).next() % 6n) + 1;

/** The full position digest, scores and piece identity included — distinct from
 *  `boardKey`, and what the battle dice are drawn from. */
export function positionKey(state: GameState): bigint {
  const MASK = (1n << 64n) - 1n;
  const PRIME = 0x00000100000001b3n;
  let hash = 0xcbf29ce484222325n;
  const mixByte = (byte: number): void => {
    hash = (hash ^ BigInt(byte)) & MASK;
    hash = (hash * PRIME) & MASK;
  };
  const mixInt = (value: number): void => {
    let v = BigInt.asUintN(64, BigInt(value));
    for (let i = 0; i < 8; i += 1) {
      mixByte(Number(v & 0xffn));
      v >>= 8n;
    }
  };
  const encoder = new TextEncoder();
  for (const piece of state.pieces) {
    for (const byte of encoder.encode(piece.id)) mixByte(byte);
    mixInt(piece.position?.row ?? -1);
    mixInt(piece.position?.column ?? -1);
    mixInt(piece.respawnTurn ?? 0);
  }
  mixInt(state.activePlayer === "one" ? 1 : 2);
  mixInt(state.scores.one);
  mixInt(state.scores.two);
  return hash;
}

function destroy(
  state: GameState, victim: Piece, attacker: Piece, square: Position, events: GameEvent[],
): void {
  setPosition(state, victim.id, null);
  events.push({
    kind: "blenderDestroyed", pieceID: victim.id, owner: victim.owner, by: attacker.id, at: square,
  });
}

function blend(
  state: GameState, fruit: Piece, square: Position, player: PlayerID, events: GameEvent[],
): void {
  const magnitude = isGolden(state, fruit.id) ? baseValue(fruit.kind) * 2 : baseValue(fruit.kind);
  const points = fruit.owner === player ? -magnitude : magnitude;

  removePiece(state, fruit.id);
  state.scores[player] += points;
  events.push({
    kind: "blended", pieceID: fruit.id, fruitKind: fruit.kind, fruitOwner: fruit.owner,
    at: square, awardedTo: player, points,
  });
  events.push({ kind: "scoreChanged", player, newScore: state.scores[player] });

  if (state.ruleSet !== "wildcard") return;
  recordBlendForWildcards(state, fruit, player, events);
}

function deliver(state: GameState, fruit: Piece, square: Position, events: GameEvent[]): void {
  const points = isGolden(state, fruit.id) ? baseValue(fruit.kind) * 3 : deliveryValue(fruit.kind);
  const recycles = state.ruleSet === "wildcard" && fruit.kind === "banana";
  if (recycles) state.wildcards.bananasDelivered[fruit.owner] += 1;

  if (recycles) setPosition(state, fruit.id, null);
  else removePiece(state, fruit.id);

  state.scores[fruit.owner] += points;
  events.push({
    kind: "delivered", pieceID: fruit.id, fruitKind: fruit.kind, owner: fruit.owner,
    at: square, points,
  });
  events.push({ kind: "scoreChanged", player: fruit.owner, newScore: state.scores[fruit.owner] });
}

// MARK: - Wildcards

function recordBlendForWildcards(
  state: GameState, fruit: Piece, player: PlayerID, events: GameEvent[],
): void {
  if (fruit.owner === player) {
    state.wildcards.ownFruitBlended[player] += 1;
  } else {
    // Stealing one of their self-blends is the hard counter.
    state.wildcards.blenderamaCountered[fruit.owner] = true;
  }

  recordSmoothie(state.wildcards.smoothie[player], fruit.kind);
  if (!smoothieIsComplete(state.wildcards.smoothie[player])) return;
  if (state.wildcards.smoothieFired[player]) return;
  state.wildcards.smoothieFired[player] = true;
  state.scores[player] += SMOOTHIE_AWARD;
  events.push({ kind: "smoothiePoured", player, points: SMOOTHIE_AWARD });
  events.push({ kind: "scoreChanged", player, newScore: state.scores[player] });
}

/** Emitted whenever a player drops to their last pineapple, however it went. Changes no
 *  state — golden is read off the board — so it is purely a cue. */
function announceGoldenPineapples(before: GameState, after: GameState, events: GameEvent[]): void {
  if (after.ruleSet !== "wildcard") return;
  for (const player of PLAYERS) {
    const had = before.pieces.filter((p) => p.owner === player && p.kind === "pineapple").length;
    if (had <= 1) continue;
    const left = after.pieces.filter((p) => p.owner === player && p.kind === "pineapple");
    if (left.length !== 1) continue;
    events.push({ kind: "pineappleTurnedGolden", pieceID: left[0]!.id, owner: player });
  }
}

/** Blenderama is a shutout: any turn that nets its player points takes the *other*
 *  player's run off for good. Measured on the score, because "netted positive points" is
 *  the rule — which is why a run never negates itself and a buy-back never negates
 *  theirs. */
function recordScoringTurn(
  state: GameState, player: PlayerID, previous: Record<PlayerID, number>,
): void {
  if (state.ruleSet !== "wildcard") return;
  if (state.scores[player] <= previous[player]) return;
  state.wildcards.blenderamaCountered[opponentOf(player)] = true;
}

function wildcardWin(state: GameState): Outcome | null {
  if (state.ruleSet !== "wildcard") return null;
  for (const player of PLAYERS) {
    if (state.wildcards.bananasDelivered[player] >= BANANA_DELIVERIES_TO_WIN) {
      return { kind: "win", player, reason: "bananaSplit" };
    }
    if (
      state.wildcards.ownFruitBlended[player] >= FRUIT_COUNT &&
      !state.wildcards.blenderamaCountered[player]
    ) {
      return { kind: "win", player, reason: "blenderama" };
    }
  }
  return null;
}

// MARK: - Ending the turn

export const isFruitDepleted = (state: GameState): boolean =>
  PLAYERS.some((player) => fruitOwnedBy(state, player).length === 0);

function end(state: GameState, events: GameEvent[], outcome?: Outcome): void {
  const decided = outcome ?? byScore(state);
  state.status = { kind: "ended", outcome: decided };
  events.push({ kind: "gameEnded", outcome: decided });
}

function byScore(state: GameState): Outcome {
  if (state.scores.one === state.scores.two) return { kind: "draw" };
  return {
    kind: "win",
    player: state.scores.one > state.scores.two ? "one" : "two",
    reason: "score",
  };
}

function finishTurn(state: GameState, events: GameEvent[], enforcingRepetition: boolean): void {
  // Alternate victories win outright, so they are checked before anything else happens.
  const immediate = wildcardWin(state);
  if (immediate) return end(state, events, immediate);

  state.turnNumber += 1;
  returnWaitingBananas(state, events);
  returnScheduledBlenders(state, events);

  const afterReturns = wildcardWin(state);
  if (afterReturns) return end(state, events, afterReturns);

  if (isFruitDepleted(state)) return end(state, events);

  const opponent = opponentOf(state.activePlayer);
  if (legalActionsFor(state, opponent, enforcingRepetition).length > 0) {
    state.activePlayer = opponent;
    events.push({ kind: "turnChanged", active: opponent });
  } else if (legalActionsFor(state, state.activePlayer, enforcingRepetition).length > 0) {
    // A player with nothing legal to do is skipped rather than deadlocking the match.
    events.push({ kind: "turnPassed", skipped: opponent });
    events.push({ kind: "turnChanged", active: state.activePlayer });
  } else {
    end(state, events);
  }
}

/** Wildcard only: a delivered banana reappears on its spawn as soon as it is clear. */
function returnWaitingBananas(state: GameState, events: GameEvent[]): void {
  if (state.ruleSet !== "wildcard") return;
  for (const banana of state.pieces.filter(isWaitingToReturn).sort((a, b) => (a.id < b.id ? -1 : 1))) {
    if (!banana.home || pieceAt(state, banana.home)) continue;
    setPosition(state, banana.id, banana.home);
    events.push({ kind: "bananaReturned", pieceID: banana.id, to: banana.home });
  }
}

function returnScheduledBlenders(state: GameState, events: GameEvent[]): void {
  const waiting = state.pieces
    .filter((p) => isBlender(p) && p.position === null && p.respawnTurn !== null)
    .sort((a, b) => (a.id < b.id ? -1 : 1));
  for (const blender of waiting) {
    if (blender.respawnTurn === null || blender.respawnTurn > state.turnNumber) continue;
    if (!blender.home || pieceAt(state, blender.home)) continue;
    setPosition(state, blender.id, blender.home);
    setRespawnTurn(state, blender.id, null);
    events.push({
      kind: "blenderRespawned", pieceID: blender.id, owner: blender.owner, at: blender.home,
    });
  }
}

/** Retained, never called: completing a smoothie pays points now. Kept whole against the
 *  day the blowout comes back, and exported so a test can prove it still works. */
export function pourSmoothie(state: GameState, player: PlayerID, events: GameEvent[]): void {
  const victims = activeBlenders(state, opponentOf(player))
    .map((b) => b.id)
    .sort(compareStrings);
  if (victims.length === 0) return;
  const due = state.turnNumber + SMOOTHIE_BLACKOUT_TURNS;
  for (const id of victims) {
    setPosition(state, id, null);
    setRespawnTurn(state, id, due);
  }
  events.push({ kind: "smoothie", player, pieceIDs: victims, respawnTurn: due });
}

export const remainingFruitFor = onBoardFruit;
export const fruitLeft = fruitOwnedBy;
