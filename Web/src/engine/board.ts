import type { BoardWrap, Position } from "./types.js";
import { wrapsColumns, wrapsRows } from "./types.js";

export const BOARD_SIZE = 6;

export const allPositions: readonly Position[] = Array.from({ length: BOARD_SIZE }, (_, row) =>
  Array.from({ length: BOARD_SIZE }, (_, column) => ({ row, column })),
).flat();

export const contains = (p: Position): boolean =>
  p.row >= 0 && p.row < BOARD_SIZE && p.column >= 0 && p.column < BOARD_SIZE;

const wrapValue = (value: number): number => ((value % BOARD_SIZE) + BOARD_SIZE) % BOARD_SIZE;

/** One step, with the board's edges applied. Null when the step leaves the board and that
 *  edge is a wall. Every legal-move calculation goes through here. */
export function step(
  origin: Position,
  rowDelta: number,
  columnDelta: number,
  wrap: BoardWrap,
): Position | null {
  let row = origin.row + rowDelta;
  let column = origin.column + columnDelta;
  if (wrapsRows(wrap)) row = wrapValue(row);
  if (wrapsColumns(wrap)) column = wrapValue(column);
  const destination = { row, column };
  return contains(destination) ? destination : null;
}

/** Distance along one axis, the short way round when that axis joins up. */
export const separation = (a: number, b: number, joined: boolean): number => {
  const direct = Math.abs(a - b);
  return joined ? Math.min(direct, BOARD_SIZE - direct) : direct;
};

export const ORTHOGONAL: readonly { row: number; column: number }[] = [
  { row: -1, column: 0 },
  { row: 1, column: 0 },
  { row: 0, column: -1 },
  { row: 0, column: 1 },
];

export const DIAGONAL: readonly { row: number; column: number }[] = [
  { row: -1, column: -1 },
  { row: -1, column: 1 },
  { row: 1, column: -1 },
  { row: 1, column: 1 },
];

export const isDarkSquare = (p: Position): boolean => (p.row + p.column) % 2 === 0;
