import type { GameState } from "./state.js";

/** A match, as JSON.
 *
 *  `boardHistory` is a list of 64-bit arrangement keys, which `JSON.stringify` refuses
 *  outright — it throws on a BigInt rather than coercing it. So they travel as decimal
 *  strings. Everything else is already plain data.
 *
 *  The whole state travels, not a diff, for the same reason it does on iOS: a state is
 *  the complete truth of a match, so there is nothing to merge and only a newer version
 *  to take. */
export interface WireState extends Omit<GameState, "boardHistory"> {
  boardHistory: string[];
}

export const RULES_VERSION_KEY = "rulesVersion";

export const encodeState = (state: GameState): WireState => ({
  ...state,
  boardHistory: state.boardHistory.map((key) => key.toString()),
});

export const decodeState = (wire: WireState): GameState => ({
  ...wire,
  boardHistory: wire.boardHistory.map((key) => BigInt(key)),
});
