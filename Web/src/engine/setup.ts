import { BOARD_SIZE, allPositions } from "./board.js";
import { SeededGenerator, shuffled } from "./random.js";
import type { GameState } from "./state.js";
import { emptyWildcards, recordArrangement, sortPieces } from "./state.js";
import type { BoardSetup, BoardWrap, Piece, PieceKind, PlayerID, Position, RuleSet } from "./types.js";
import { PLAYERS, backRow, columnFromOwnLeft, deliveryRow, fruitRow } from "./types.js";

/** Banana, Strawberry, Banana, Pineapple, Strawberry, Pineapple — read from each player's
 *  own left. The asymmetry is deliberate and measured: a row that reads the same forwards
 *  and backwards deadlocks self-play. Do not tidy it. */
export const FRUIT_ORDER: readonly PieceKind[] = [
  "banana", "strawberry", "banana", "pineapple", "strawberry", "pineapple",
];

export const BLENDER_HOME_COLUMNS: readonly number[] = [1, 4];
export const FRUIT_COUNT = FRUIT_ORDER.length;
export const BANANA_DELIVERIES_TO_WIN = 3;

export const pieceID = (player: PlayerID, kind: string, index: number): string =>
  `${player}.${kind}.${index}`;

export interface NewGameOptions {
  startingPlayer?: PlayerID;
  ruleSet?: RuleSet;
  boardSetup?: BoardSetup;
  boardWrap?: BoardWrap;
  blenderBattles?: boolean;
  seed?: bigint;
}

export function newGame(options: NewGameOptions = {}): GameState {
  const {
    startingPlayer = "one",
    ruleSet = "standard",
    boardSetup = "standard",
    boardWrap = "flat",
    blenderBattles = false,
    seed,
  } = options;

  const pieces =
    boardSetup === "random"
      ? randomPieces(seed ?? randomSeed())
      : boardSetup === "blendy"
        ? rowPieces(backRow, fruitRow)
        : rowPieces(fruitRow, backRow);

  const state: GameState = {
    pieces: sortPieces(pieces),
    activePlayer: startingPlayer,
    scores: { one: 0, two: 0 },
    status: { kind: "inProgress" },
    turnNumber: 1,
    history: [],
    ruleSet,
    boardSetup,
    boardWrap,
    blenderBattles,
    boardHistory: [],
    wildcards: emptyWildcards(),
  };
  // The history always contains the position it describes.
  recordArrangement(state);
  return state;
}

const randomSeed = (): bigint => {
  const bytes = new Uint8Array(8);
  crypto.getRandomValues(bytes);
  return bytes.reduce((value, byte) => (value << 8n) | BigInt(byte), 0n);
};

type RowChooser = (player: PlayerID, size: number) => number;

/** Standard and Blendy differ by nothing but which row each kind of piece stands on. */
function rowPieces(fruitRowFor: RowChooser, blenderRowFor: RowChooser): Piece[] {
  const pieces: Piece[] = [];
  for (const player of PLAYERS) {
    FRUIT_ORDER.forEach((kind, index) => {
      const square: Position = {
        row: fruitRowFor(player, BOARD_SIZE),
        column: columnFromOwnLeft(player, index, BOARD_SIZE),
      };
      pieces.push({
        id: pieceID(player, "fruit", index),
        owner: player,
        kind,
        position: square,
        home: square,
        respawnTurn: null,
      });
    });
    BLENDER_HOME_COLUMNS.forEach((column, index) => {
      const home: Position = { row: blenderRowFor(player, BOARD_SIZE), column };
      pieces.push({
        id: pieceID(player, "blender", index),
        owner: player,
        kind: "blender",
        position: home,
        home,
        respawnTurn: null,
      });
    });
  }
  return pieces;
}

/** Every piece on a square of its own, with one placement rule: no fruit starts on its
 *  own delivery row, which would be a free delivery on turn one for a sideways step. */
function randomPieces(seed: bigint): Piece[] {
  const generator = new SeededGenerator(seed);
  const pool = shuffled(allPositions, generator);

  const take = (isAllowed: (p: Position) => boolean): Position => {
    const index = pool.findIndex(isAllowed);
    const [square] = pool.splice(index, 1);
    return square!;
  };

  const pieces: Piece[] = [];
  for (const player of PLAYERS) {
    FRUIT_ORDER.forEach((kind, index) => {
      const square = take((p) => p.row !== deliveryRow(player, BOARD_SIZE));
      pieces.push({
        id: pieceID(player, "fruit", index),
        owner: player,
        kind,
        position: square,
        home: square,
        respawnTurn: null,
      });
    });
    BLENDER_HOME_COLUMNS.forEach((_, index) => {
      const home = take(() => true);
      pieces.push({
        id: pieceID(player, "blender", index),
        owner: player,
        kind: "blender",
        position: home,
        home,
        respawnTurn: null,
      });
    });
  }
  return pieces;
}
