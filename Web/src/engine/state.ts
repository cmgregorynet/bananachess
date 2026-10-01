import { BOARD_SIZE } from "./board.js";
import type {
  BoardSetup, BoardWrap, PlayerID, Piece, PieceKind, Position, RuleSet,
} from "./types.js";
import {
  PLAYERS, baseValue, isBlender, isDestroyed, isFruit, isOnBoard, isWaitingToReturn,
  opponentOf, samePosition, wrapsRows,
} from "./types.js";

export interface SmoothieProgress {
  banana: boolean;
  strawberry: boolean;
  pineapple: boolean;
}

export interface WildcardProgress {
  smoothie: Record<PlayerID, SmoothieProgress>;
  smoothieFired: Record<PlayerID, boolean>;
  bananasDelivered: Record<PlayerID, number>;
  ownFruitBlended: Record<PlayerID, number>;
  blenderamaCountered: Record<PlayerID, boolean>;
}

export type WinReason = "score" | "bananaSplit" | "blenderama";
export type Outcome = { kind: "draw" } | { kind: "win"; player: PlayerID; reason: WinReason };
export type GameStatus = { kind: "inProgress" } | { kind: "ended"; outcome: Outcome };

export type GameAction =
  | { kind: "move"; pieceID: string; origin: Position; destination: Position }
  | { kind: "restoreBlender"; pieceID: string; at: Position };

export interface GameState {
  /** Always sorted by piece id, so encoding and comparison are canonical. */
  pieces: Piece[];
  activePlayer: PlayerID;
  scores: Record<PlayerID, number>;
  status: GameStatus;
  turnNumber: number;
  history: GameAction[];
  ruleSet: RuleSet;
  boardSetup: BoardSetup;
  boardWrap: BoardWrap;
  blenderBattles: boolean;
  boardHistory: bigint[];
  wildcards: WildcardProgress;
}

export const emptyWildcards = (): WildcardProgress => ({
  smoothie: {
    one: { banana: false, strawberry: false, pineapple: false },
    two: { banana: false, strawberry: false, pineapple: false },
  },
  smoothieFired: { one: false, two: false },
  bananasDelivered: { one: 0, two: 0 },
  ownFruitBlended: { one: 0, two: 0 },
  blenderamaCountered: { one: false, two: false },
});

export const smoothieIsComplete = (p: SmoothieProgress): boolean =>
  p.banana && p.strawberry && p.pineapple;

export const recordSmoothie = (p: SmoothieProgress, kind: PieceKind): void => {
  if (kind === "banana") p.banana = true;
  else if (kind === "strawberry") p.strawberry = true;
  else if (kind === "pineapple") p.pineapple = true;
};

// MARK: - Lookup

export const pieceWithID = (state: GameState, id: string): Piece | undefined =>
  state.pieces.find((p) => p.id === id);

export const pieceAt = (state: GameState, position: Position): Piece | undefined =>
  state.pieces.find((p) => samePosition(p.position, position));

export const piecesOwnedBy = (state: GameState, player: PlayerID): Piece[] =>
  state.pieces.filter((p) => p.owner === player);

export const fruitOwnedBy = (state: GameState, player: PlayerID): Piece[] =>
  state.pieces.filter((p) => p.owner === player && isFruit(p));

export const onBoardFruit = (state: GameState, player: PlayerID): Piece[] =>
  fruitOwnedBy(state, player).filter(isOnBoard);

export const blendersOwnedBy = (state: GameState, player: PlayerID): Piece[] =>
  state.pieces.filter((p) => p.owner === player && isBlender(p));

export const activeBlenders = (state: GameState, player: PlayerID): Piece[] =>
  blendersOwnedBy(state, player).filter(isOnBoard);

export const destroyedBlenders = (state: GameState, player: PlayerID): Piece[] =>
  blendersOwnedBy(state, player).filter(isDestroyed).sort((a, b) => (a.id < b.id ? -1 : 1));

export const waitingBananas = (state: GameState, player: PlayerID): Piece[] =>
  state.pieces
    .filter((p) => p.owner === player && isWaitingToReturn(p))
    .sort((a, b) => (a.id < b.id ? -1 : 1));

export const pineapplesOwnedBy = (state: GameState, player: PlayerID): Piece[] =>
  state.pieces.filter((p) => p.owner === player && p.kind === "pineapple");

/** Derived, not recorded: whenever one pineapple is left, it is golden — however the
 *  other one went. */
export const goldenPineapple = (state: GameState, player: PlayerID): Piece | null => {
  if (state.ruleSet !== "wildcard") return null;
  const remaining = pineapplesOwnedBy(state, player);
  return remaining.length === 1 ? remaining[0]! : null;
};

export const isGolden = (state: GameState, id: string): boolean => {
  if (state.ruleSet !== "wildcard") return false;
  const piece = pieceWithID(state, id);
  if (!piece || piece.kind !== "pineapple") return false;
  return pineapplesOwnedBy(state, piece.owner).length === 1;
};

export const fruitStandingValue = (state: GameState, player: PlayerID): number =>
  onBoardFruit(state, player).reduce((total, piece) => total + baseValue(piece.kind), 0);

/** Half, rounded down: full value let twelve points of fruit overrule a die spanning
 *  five. */
export const battleStrength = (state: GameState, player: PlayerID): number =>
  Math.floor(fruitStandingValue(state, player) / 2);

export const allSpawnSquares = (state: GameState): Position[] =>
  state.pieces.filter(isBlender).flatMap((p) => (p.home ? [p.home] : []));

/** Which edges a piece may cross. Blenders never cross one, whatever the board is set
 *  to: joined edges are a rule about the fruit's run. */
export const edgesFor = (state: GameState, piece: Piece): BoardWrap =>
  isBlender(piece) ? "flat" : state.boardWrap;

export const outcomeOf = (state: GameState): Outcome | null =>
  state.status.kind === "ended" ? state.status.outcome : null;

// MARK: - The arrangement key

const FNV_OFFSET = 0xcbf29ce484222325n;
const FNV_PRIME = 0x00000100000001b3n;
const MASK64 = (1n << 64n) - 1n;

/** Where every piece stands and whose turn it is — and deliberately not the scores.
 *  Every unbounded loop in this game bled points while the board went round, so a key
 *  that included them would see a new position every lap. */
export function boardKey(state: GameState): bigint {
  let hash = FNV_OFFSET;
  const mix = (value: number): void => {
    // Int64 little-endian, byte by byte, exactly as the Swift engine hashes it.
    let v = BigInt.asUintN(64, BigInt(value));
    for (let i = 0; i < 8; i += 1) {
      hash = (hash ^ (v & 0xffn)) & MASK64;
      hash = (hash * FNV_PRIME) & MASK64;
      v >>= 8n;
    }
  };
  for (const piece of state.pieces) {
    mix(piece.position?.row ?? -1);
    mix(piece.position?.column ?? -1);
    mix(piece.respawnTurn ?? 0);
  }
  mix(state.activePlayer === "one" ? 1 : 2);
  return hash;
}

export const timesSeen = (state: GameState, key: bigint): number =>
  state.boardHistory.reduce((count, seen) => count + (seen === key ? 1 : 0), 0);

export const recordArrangement = (state: GameState): void => {
  state.boardHistory.push(boardKey(state));
};

// MARK: - Mutation

export const setPosition = (state: GameState, id: string, position: Position | null): void => {
  const piece = pieceWithID(state, id);
  if (piece) piece.position = position;
};

export const setRespawnTurn = (state: GameState, id: string, turn: number | null): void => {
  const piece = pieceWithID(state, id);
  if (piece) piece.respawnTurn = turn;
};

export const removePiece = (state: GameState, id: string): void => {
  state.pieces = state.pieces.filter((p) => p.id !== id);
};

export const cloneState = (state: GameState): GameState => ({
  ...state,
  pieces: state.pieces.map((p) => ({ ...p })),
  scores: { ...state.scores },
  history: [...state.history],
  boardHistory: [...state.boardHistory],
  wildcards: {
    smoothie: { one: { ...state.wildcards.smoothie.one }, two: { ...state.wildcards.smoothie.two } },
    smoothieFired: { ...state.wildcards.smoothieFired },
    bananasDelivered: { ...state.wildcards.bananasDelivered },
    ownFruitBlended: { ...state.wildcards.ownFruitBlended },
    blenderamaCountered: { ...state.wildcards.blenderamaCountered },
  },
});

export const sortPieces = (pieces: Piece[]): Piece[] =>
  [...pieces].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));

/** Distance a fruit still has to run, the short way round when the rows join. */
export const rowsToGoal = (state: GameState, player: PlayerID, row: number): number => {
  const goal = player === "one" ? 0 : BOARD_SIZE - 1;
  const direct = Math.abs(row - goal);
  return wrapsRows(state.boardWrap) ? Math.min(direct, BOARD_SIZE - direct) : direct;
};

export const everyPlayer = PLAYERS;
export const otherPlayer = opponentOf;
