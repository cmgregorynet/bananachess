import { BOARD_SIZE } from "../engine/board.js";
import { isGolden, type GameState } from "../engine/state.js";
import type { Piece, PieceKind, PlayerID, Position } from "../engine/types.js";
import { backRow, deliveryRow, isBlender, samePosition, wrapsColumns, wrapsRows } from "../engine/types.js";
import { Palette } from "./palette.js";
import {
  BANANA, BLENDER_BASE, BLENDER_JAR, BLENDER_LID, PINEAPPLE, PINEAPPLE_CROWN,
  STRAWBERRY, STRAWBERRY_CALYX, washFor,
} from "./shapes.js";

const SVG_NS = "http://www.w3.org/2000/svg";
const CELL = 100;

export interface BoardRenderOptions {
  state: GameState;
  selected: string | null;
  legalDestinations: Position[];
  /** Which player sits at the bottom; the board turns a half turn for the other. */
  bottomPlayer: PlayerID;
  onSquare: (position: Position) => void;
}

const element = <K extends keyof SVGElementTagNameMap>(
  name: K, attributes: Record<string, string | number>,
): SVGElementTagNameMap[K] => {
  const node = document.createElementNS(SVG_NS, name);
  for (const [key, value] of Object.entries(attributes)) {
    node.setAttribute(key, String(value));
  }
  return node;
};

/** Ownership is carried by the tray under the piece, never by the fruit's own colour —
 *  the wash says what a piece is, the tray says whose it is. */
const trayColours = (owner: PlayerID): [string, string] =>
  owner === "one" ? [Palette.clayLight, Palette.clayDeep] : [Palette.indigoLight, Palette.indigoDeep];

function drawPiece(piece: Piece, golden: boolean): SVGGElement {
  const group = element("g", {});
  const [trayFill, trayEdge] = trayColours(piece.owner);

  group.append(element("ellipse", {
    cx: 50, cy: 78, rx: 32, ry: 11, fill: trayFill, stroke: trayEdge, "stroke-width": 2.5,
  }));
  // Player two's tray carries a second inner ring, so the two sides stay apart in
  // greyscale as well as in hue.
  if (piece.owner === "two") {
    group.append(element("ellipse", {
      cx: 50, cy: 78, rx: 25, ry: 7, fill: "none", stroke: Palette.cream, "stroke-width": 1.6,
    }));
  }

  const body = element("g", { transform: "translate(50 44) scale(0.74) translate(-50 -50)" });
  const wash = golden ? Palette.amber : washFor(piece.kind);
  const outline = { fill: wash, stroke: Palette.ink, "stroke-width": 3.4, "stroke-linejoin": "round" };

  if (piece.kind === "banana") {
    body.append(element("path", { d: BANANA, ...outline }));
  } else if (piece.kind === "strawberry") {
    body.append(element("path", { d: STRAWBERRY, ...outline }));
    body.append(element("path", { d: STRAWBERRY_CALYX, fill: "#6E7A4E", stroke: Palette.ink, "stroke-width": 2 }));
  } else if (piece.kind === "pineapple") {
    body.append(element("path", { d: PINEAPPLE, ...outline }));
    body.append(element("path", { d: PINEAPPLE_CROWN, fill: "#6E7A4E", stroke: Palette.ink, "stroke-width": 2 }));
  } else {
    body.append(element("path", { d: BLENDER_BASE, fill: Palette.ink, stroke: Palette.ink, "stroke-width": 2.5 }));
    body.append(element("path", { d: BLENDER_JAR, fill: Palette.blenderGlass, stroke: Palette.ink, "stroke-width": 3.4, "stroke-linejoin": "round" }));
    body.append(element("path", { d: BLENDER_LID, fill: Palette.ink, stroke: Palette.ink, "stroke-width": 2 }));
  }
  group.append(body);

  if (golden) {
    group.append(element("circle", {
      cx: 50, cy: 44, r: 40, fill: "none", stroke: Palette.amber, "stroke-width": 3, opacity: 0.8,
    }));
  }
  return group;
}

export function renderBoard(host: HTMLElement, options: BoardRenderOptions): void {
  const { state, selected, legalDestinations, bottomPlayer, onSquare } = options;
  const flipped = bottomPlayer === "two";
  const side = BOARD_SIZE * CELL;

  host.replaceChildren();
  const svg = element("svg", {
    viewBox: `0 0 ${side} ${side}`,
    class: "board",
    role: "grid",
    "aria-label": "Banana Chess board",
  });

  const screenOf = (p: Position): { x: number; y: number } => ({
    x: (flipped ? BOARD_SIZE - 1 - p.column : p.column) * CELL,
    y: (flipped ? BOARD_SIZE - 1 - p.row : p.row) * CELL,
  });

  const spawns = state.pieces.filter(isBlender).flatMap((b) => (b.home ? [b.home] : []));

  for (let row = 0; row < BOARD_SIZE; row += 1) {
    for (let column = 0; column < BOARD_SIZE; column += 1) {
      const position = { row, column };
      const { x, y } = screenOf(position);
      const dark = (row + column) % 2 === 0;

      const cell = element("g", { transform: `translate(${x} ${y})` });
      cell.append(element("rect", {
        x: 0, y: 0, width: CELL, height: CELL,
        fill: dark ? Palette.squareDark : Palette.squareLight,
      }));

      // The two scoring edges, as a soft band rather than a label.
      for (const player of ["one", "two"] as const) {
        if (row !== deliveryRow(player, BOARD_SIZE)) continue;
        cell.append(element("rect", {
          x: 0, y: 0, width: CELL, height: CELL,
          fill: player === "one" ? Palette.clayDeep : Palette.indigoDeep, opacity: 0.16,
        }));
      }

      if (spawns.some((s) => samePosition(s, position))) {
        cell.append(element("circle", {
          cx: CELL / 2, cy: CELL / 2, r: 30, fill: "none",
          stroke: Palette.inkFaint, "stroke-width": 2, "stroke-dasharray": "6 6",
        }));
      }

      const piece = state.pieces.find((p) => samePosition(p.position, position));
      if (piece) {
        const art = drawPiece(piece, isGolden(state, piece.id));
        if (piece.id === selected) {
          art.setAttribute("transform", "translate(50 50) scale(1.08) translate(-50 -50)");
        }
        cell.append(art);
      }

      if (legalDestinations.some((d) => samePosition(d, position))) {
        cell.append(element("circle", {
          cx: CELL / 2, cy: CELL / 2, r: piece ? 44 : 13,
          fill: piece ? "none" : Palette.amber,
          stroke: Palette.amber, "stroke-width": 4, opacity: 0.9,
        }));
      }

      const hit = element("rect", {
        x: 0, y: 0, width: CELL, height: CELL, fill: "transparent",
        class: "square", tabindex: 0, role: "gridcell",
        "aria-label": describeSquare(state, position, piece),
      });
      hit.addEventListener("click", () => onSquare(position));
      hit.addEventListener("keydown", (event) => {
        const key = (event as KeyboardEvent).key;
        if (key === "Enter" || key === " ") {
          event.preventDefault();
          onSquare(position);
        }
      });
      cell.append(hit);
      svg.append(cell);
    }
  }

  // Joined edges, drawn as a dashed amber seam: a legal move on the far side of the
  // board needs something explaining it.
  if (wrapsColumns(state.boardWrap)) {
    for (const x of [1.5, side - 1.5]) {
      svg.append(element("line", {
        x1: x, y1: 0, x2: x, y2: side,
        stroke: Palette.amber, "stroke-width": 3, "stroke-dasharray": "10 10", opacity: 0.55,
      }));
    }
  }
  if (wrapsRows(state.boardWrap)) {
    for (const y of [1.5, side - 1.5]) {
      svg.append(element("line", {
        x1: 0, y1: y, x2: side, y2: y,
        stroke: Palette.amber, "stroke-width": 3, "stroke-dasharray": "10 10", opacity: 0.55,
      }));
    }
  }

  host.append(svg);
}

function describeSquare(state: GameState, position: Position, piece: Piece | undefined): string {
  const place = `row ${position.row + 1}, column ${position.column + 1}`;
  if (!piece) return `Empty, ${place}`;
  const owner = piece.owner === "one" ? "Player One" : "Player Two";
  const golden = isGolden(state, piece.id) ? "golden " : "";
  return `${owner} ${golden}${piece.kind}, ${place}`;
}

export const backRowOf = (player: PlayerID): number => backRow(player, BOARD_SIZE);
export type { PieceKind };
