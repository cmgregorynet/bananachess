/** Ported from `BananaChessEngine`. The Swift engine is the specification; this is held
 *  to it by `test/conformance.test.ts`, which replays games generated from it. */

export type PlayerID = "one" | "two";
export const PLAYERS: readonly PlayerID[] = ["one", "two"];
export const opponentOf = (player: PlayerID): PlayerID => (player === "one" ? "two" : "one");

export type PieceKind = "banana" | "strawberry" | "pineapple" | "blender";

export const baseValue = (kind: PieceKind): number =>
  kind === "banana" ? 1 : kind === "strawberry" ? 2 : kind === "pineapple" ? 3 : 0;
export const deliveryValue = (kind: PieceKind): number => baseValue(kind) * 2;

export type RuleSet = "standard" | "wildcard";
export type BoardSetup = "standard" | "blendy" | "random";
export type BoardWrap = "flat" | "sides" | "all";

export const wrapsColumns = (wrap: BoardWrap): boolean => wrap !== "flat";
export const wrapsRows = (wrap: BoardWrap): boolean => wrap === "all";

export interface Position {
  readonly row: number;
  readonly column: number;
}

export const samePosition = (a: Position | null, b: Position | null): boolean =>
  a === null || b === null ? a === b : a.row === b.row && a.column === b.column;

export const describePosition = (p: Position): string => `r${p.row}c${p.column}`;

export interface Piece {
  readonly id: string;
  readonly owner: PlayerID;
  readonly kind: PieceKind;
  /** Null while off the board: destroyed, or a delivered banana waiting to come back. */
  position: Position | null;
  readonly home: Position | null;
  /** Set only for a smoothie blackout, which ends on its own. Null for a combat kill. */
  respawnTurn: number | null;
}

export const isBlender = (piece: Piece): boolean => piece.kind === "blender";
export const isFruit = (piece: Piece): boolean => piece.kind !== "blender";
export const isOnBoard = (piece: Piece): boolean => piece.position !== null;
export const isDestroyed = (piece: Piece): boolean => isBlender(piece) && piece.position === null;
export const isAwaitingRespawn = (piece: Piece): boolean =>
  isDestroyed(piece) && piece.respawnTurn !== null;
/** A blender gone for good until somebody pays for it. */
export const needsBuyingBack = (piece: Piece): boolean =>
  isDestroyed(piece) && piece.respawnTurn === null;
export const isWaitingToReturn = (piece: Piece): boolean =>
  isFruit(piece) && piece.position === null;

/** Row 0 is player two's back row; row 5 is player one's. */
export const backRow = (player: PlayerID, size: number): number => (player === "one" ? size - 1 : 0);
export const fruitRow = (player: PlayerID, size: number): number => (player === "one" ? size - 2 : 1);
export const deliveryRow = (player: PlayerID, size: number): number =>
  backRow(opponentOf(player), size);
export const columnFromOwnLeft = (player: PlayerID, index: number, size: number): number =>
  player === "one" ? index : size - 1 - index;
