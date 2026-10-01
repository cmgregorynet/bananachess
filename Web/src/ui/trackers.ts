import { BANANA_DELIVERIES_TO_WIN, FRUIT_COUNT } from "../engine/setup.js";
import type { GameState } from "../engine/state.js";
import { fruitOwnedBy } from "../engine/state.js";
import type { PieceKind, PlayerID } from "../engine/types.js";
import { Palette } from "./palette.js";
import { BANANA, PINEAPPLE, STRAWBERRY, washFor } from "./shapes.js";

/** The wildcard threats, kept where both players can read them.
 *
 *  These rules only work if an alternate win is something you can see coming and go and
 *  stop, so nothing here is hidden from either side — including when a route is already
 *  dead, which is as useful to know as when it is live. */
const PATHS: Record<Exclude<PieceKind, "blender">, string> = {
  banana: BANANA,
  strawberry: STRAWBERRY,
  pineapple: PINEAPPLE,
};

function pip(kind: Exclude<PieceKind, "blender">, lit: boolean): SVGSVGElement {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 100 100");
  svg.setAttribute("width", "15");
  svg.setAttribute("height", "15");
  svg.style.opacity = lit ? "1" : "0.22";
  if (!lit) svg.style.filter = "grayscale(1)";
  const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
  path.setAttribute("d", PATHS[kind]);
  path.setAttribute("fill", washFor(kind));
  path.setAttribute("stroke", Palette.ink);
  path.setAttribute("stroke-width", "4");
  path.setAttribute("stroke-linejoin", "round");
  svg.append(path);
  return svg;
}

function track(label: string, complete: boolean, blocked: boolean, contents: Node[]): HTMLElement {
  const wrapper = document.createElement("span");
  wrapper.className = `track${complete ? " complete" : ""}${blocked ? " blocked" : ""}`;
  const name = document.createElement("span");
  name.className = "track-label";
  name.textContent = label;
  wrapper.append(name, ...contents);
  return wrapper;
}

export function renderTrackers(host: HTMLElement, state: GameState, player: PlayerID): void {
  host.replaceChildren();
  if (state.ruleSet !== "wildcard") return;

  const smoothie = state.wildcards.smoothie[player];
  const delivered = state.wildcards.bananasDelivered[player];
  const blenderama = state.wildcards.ownFruitBlended[player];
  const countered = state.wildcards.blenderamaCountered[player];
  const fruitLeft = fruitOwnedBy(state, player);

  host.append(
    track("Smoothie", state.wildcards.smoothieFired[player], false, [
      pip("banana", smoothie.banana),
      pip("strawberry", smoothie.strawberry),
      pip("pineapple", smoothie.pineapple),
    ]),
  );

  // A Split only dies when there is no banana left to run across at all — bananas
  // recycle, so being short of one is not the same as being out.
  const splitBlocked =
    delivered < BANANA_DELIVERIES_TO_WIN && !fruitLeft.some((f) => f.kind === "banana");
  host.append(
    track("Split", delivered >= BANANA_DELIVERIES_TO_WIN, splitBlocked,
      Array.from({ length: BANANA_DELIVERIES_TO_WIN }, (_, index) =>
        pip("banana", index < delivered)),
    ),
  );

  // Blenderama appears once a run has started: a 0 of 6 counter on every match would be
  // noise, and under the shutout rule being countered is the normal state within about
  // forty turns.
  if (blenderama > 0) {
    const count = document.createElement("span");
    count.className = "track-count";
    count.textContent = `${blenderama}/${FRUIT_COUNT}`;
    const dead = countered || blenderama + fruitLeft.length < FRUIT_COUNT;
    host.append(track(countered ? "Countered" : "Blenderama", false, dead, [count]));
  }
}

export function describeTrackers(state: GameState, player: PlayerID): string {
  if (state.ruleSet !== "wildcard") return "";
  const smoothie = state.wildcards.smoothie[player];
  const collected = [smoothie.banana, smoothie.strawberry, smoothie.pineapple].filter(Boolean).length;
  const delivered = state.wildcards.bananasDelivered[player];
  const blenderama = state.wildcards.ownFruitBlended[player];

  const parts = [
    `Smoothie ${collected} of 3${state.wildcards.smoothieFired[player] ? ", already poured" : ""}`,
    `Banana Split ${delivered} of ${BANANA_DELIVERIES_TO_WIN}`,
  ];
  if (blenderama > 0) {
    parts.push(
      `Blenderama ${blenderama} of ${FRUIT_COUNT}` +
        (state.wildcards.blenderamaCountered[player] ? ", countered" : ""),
    );
  }
  return parts.join(", ");
}
