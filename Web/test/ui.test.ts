/** @vitest-environment jsdom */
import { beforeEach, describe, expect, it } from "vitest";
import { renderBoard } from "../src/ui/board.js";
import { newGame } from "../src/engine/setup.js";
import { legalMovesForPiece } from "../src/engine/rules.js";
import type { Position } from "../src/engine/types.js";

describe("the board renders and can be played", () => {
  let host: HTMLElement;

  beforeEach(() => {
    document.body.replaceChildren();
    host = document.createElement("div");
    document.body.append(host);
  });

  it("draws a square and a piece for every one on the board", () => {
    const state = newGame();
    renderBoard(host, {
      state, selected: null, legalDestinations: [], bottomPlayer: "one", onSquare: () => {},
    });

    expect(host.querySelectorAll(".square")).toHaveLength(36);
    // Sixteen pieces, each drawn with a tray under it.
    expect(host.querySelectorAll("ellipse").length).toBeGreaterThanOrEqual(16);
  });

  it("reports a square's contents to a screen reader", () => {
    const state = newGame();
    renderBoard(host, {
      state, selected: null, legalDestinations: [], bottomPlayer: "one", onSquare: () => {},
    });
    const labels = [...host.querySelectorAll(".square")].map((n) => n.getAttribute("aria-label"));

    expect(labels).toContain("Player One banana, row 5, column 1");
    expect(labels).toContain("Empty, row 3, column 1");
  });

  it("turns the board a half turn for the other seat", () => {
    const state = newGame();
    // Squares are appended in board order and placed by transform, so DOM order is the
    // same either way — it is where row 0, column 0 is *drawn* that turns over.
    const cornerTransform = (bottomPlayer: "one" | "two"): string => {
      const target = document.createElement("div");
      renderBoard(target, {
        state, selected: null, legalDestinations: [], bottomPlayer, onSquare: () => {},
      });
      const corner = [...target.querySelectorAll(".square")].find(
        (n) => n.getAttribute("aria-label")?.endsWith("row 1, column 1"),
      )!;
      return corner.parentElement!.getAttribute("transform")!;
    };

    expect(cornerTransform("one")).toBe("translate(0 0)");
    expect(cornerTransform("two")).toBe("translate(500 500)");
  });

  it("passes the tapped square back, so a move can be made", () => {
    const state = newGame();
    const tapped: Position[] = [];
    renderBoard(host, {
      state, selected: null, legalDestinations: [], bottomPlayer: "one",
      onSquare: (position) => tapped.push(position),
    });

    (host.querySelectorAll(".square")[0] as SVGElement).dispatchEvent(
      new window.MouseEvent("click", { bubbles: true }),
    );
    expect(tapped).toEqual([{ row: 0, column: 0 }]);
  });

  it("marks the legal destinations of a selected piece", () => {
    const state = newGame();
    const piece = state.pieces.find((p) => p.id === "one.fruit.0")!;
    const destinations = legalMovesForPiece(state, piece.id).flatMap((m) =>
      m.kind === "move" ? [m.destination] : [],
    );
    expect(destinations.length).toBeGreaterThan(0);

    renderBoard(host, {
      state, selected: piece.id, legalDestinations: destinations, bottomPlayer: "one",
      onSquare: () => {},
    });
    // One amber marker per destination.
    const amber = [...host.querySelectorAll("circle")].filter(
      (c) => c.getAttribute("stroke") === "#E8A24A",
    );
    expect(amber.length).toBeGreaterThanOrEqual(destinations.length);
  });

  it("draws the joined-edge seams only when the edges join", () => {
    const walls = newGame({ boardWrap: "flat" });
    const loop = newGame({ boardWrap: "all" });

    renderBoard(host, {
      state: walls, selected: null, legalDestinations: [], bottomPlayer: "one", onSquare: () => {},
    });
    expect(host.querySelectorAll("line")).toHaveLength(0);

    renderBoard(host, {
      state: loop, selected: null, legalDestinations: [], bottomPlayer: "one", onSquare: () => {},
    });
    expect(host.querySelectorAll("line")).toHaveLength(4);
  });
});
