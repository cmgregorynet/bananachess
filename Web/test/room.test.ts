import { beforeEach, describe, expect, it } from "vitest";
import { MatchRoom, type ServerMessage } from "../server/room.js";
import { describeAction, legalActions } from "../src/engine/rules.js";
import { decodeState } from "../src/engine/wire.js";

/** The server runs the rules, so these are the tests that stop a client talking it into
 *  something. */
describe("a match room", () => {
  let room: MatchRoom;
  let inboxes: Record<string, ServerMessage[]>;

  /** A socket and the player behind it. They are separate on purpose: a real WebSocket
   *  gets a fresh id every time it opens, so anything keyed on the connection cannot
   *  survive a reconnect. */
  const connect = (id: string, player = id): void => {
    inboxes[id] ??= [];
    room.join({ id, player, send: (message) => inboxes[id]!.push(message) });
  };
  const last = (id: string): ServerMessage => inboxes[id]!.at(-1)!;
  const seatOf = (id: string): string | null => {
    const message = inboxes[id]!.find((m) => m.type === "seat");
    return message && message.type === "seat" ? message.you : null;
  };
  const stateOf = (id: string) => {
    const message = [...inboxes[id]!].reverse().find((m) => m.type === "state");
    if (!message || message.type !== "state") throw new Error("no state");
    return decodeState(message.state);
  };

  beforeEach(() => {
    room = new MatchRoom();
    inboxes = {};
  });

  it("seats the first two arrivals and leaves the rest watching", () => {
    connect("a");
    connect("b");
    connect("c");

    expect(seatOf("a")).toBe("one");
    expect(seatOf("b")).toBe("two");
    expect(seatOf("c")).toBeNull();
  });

  /// This passed while the code was broken, because it reconnected with the same
  /// *connection* id — something a real socket never does. A live deployment put a
  /// player back as a spectator at their own match. The reconnect here now looks like a
  /// real one: a new socket carrying the same player token.
  it("gives a returning player their own side back, not a seat at the back", () => {
    connect("sock-a", "player-a");
    connect("sock-b", "player-b");
    room.leave("sock-a");
    connect("sock-a2", "player-a");

    expect(room.seatOf("sock-a2")).toBe("one");
    expect(room.seatOf("sock-b")).toBe("two");
  });

  it("does not hand a stranger the seat of someone who stepped away", () => {
    connect("sock-a", "player-a");
    connect("sock-b", "player-b");
    room.leave("sock-a");
    connect("sock-c", "player-c");

    expect(room.seatOf("sock-c")).toBeNull();
    connect("sock-a2", "player-a");
    expect(room.seatOf("sock-a2")).toBe("one");
  });

  it("sends the board to everyone when the match starts, and to late arrivals", () => {
    connect("a");
    room.handle("a", { type: "create", options: { ruleSet: "wildcard" } });
    expect(stateOf("a").ruleSet).toBe("wildcard");

    connect("late");
    expect(stateOf("late").ruleSet).toBe("wildcard");
  });

  it("plays a legal move and tells both sides", () => {
    connect("a");
    connect("b");
    room.handle("a", { type: "create" });

    const action = legalActions(stateOf("a"))[0]!;
    room.handle("a", { type: "action", action, expectedMoveNumber: 0 });

    expect(stateOf("a").history).toHaveLength(1);
    expect(stateOf("b").history).toHaveLength(1);
    expect(stateOf("b").activePlayer).toBe("two");
  });

  it("refuses a move from the player whose turn it is not", () => {
    connect("a");
    connect("b");
    room.handle("a", { type: "create" });

    const action = legalActions(stateOf("b"))[0]!;
    room.handle("b", { type: "action", action, expectedMoveNumber: 0 });

    expect(last("b")).toEqual({ type: "error", message: "It isn't your turn." });
    expect(stateOf("a").history).toHaveLength(0);
  });

  /** The whole reason the engine is on the server: a client cannot play an illegal move
   *  by simply asking for one. */
  it("refuses a move that is not legal, however politely it is asked for", () => {
    connect("a");
    connect("b");
    room.handle("a", { type: "create" });

    room.handle("a", {
      type: "action",
      action: {
        kind: "move", pieceID: "one.fruit.0",
        origin: { row: 4, column: 0 }, destination: { row: 0, column: 5 },
      },
      expectedMoveNumber: 0,
    });

    const errors = inboxes["a"]!.filter((m) => m.type === "error");
    expect(errors.at(-1)).toEqual({ type: "error", message: "That move isn't legal." });
    expect(stateOf("a").history).toHaveLength(0);
  });

  it("refuses a move built on a board that has already moved on", () => {
    connect("a");
    connect("b");
    room.handle("a", { type: "create" });
    const first = legalActions(stateOf("a"))[0]!;
    room.handle("a", { type: "action", action: first, expectedMoveNumber: 0 });

    const stale = legalActions(stateOf("b"))[0]!;
    room.handle("b", { type: "action", action: stale, expectedMoveNumber: 0 });

    const errors = inboxes["b"]!.filter((m) => m.type === "error");
    expect(errors.at(-1)!.type).toBe("error");
    // And it catches them up rather than leaving them stuck.
    expect(last("b").type).toBe("state");
  });

  it("does not let a spectator play", () => {
    connect("a");
    connect("b");
    connect("c");
    room.handle("a", { type: "create" });

    const action = legalActions(stateOf("c"))[0]!;
    room.handle("c", { type: "action", action, expectedMoveNumber: 0 });

    expect(last("c")).toEqual({
      type: "error", message: "You're watching this match, not playing it.",
    });
  });

  it("ends the match when somebody resigns, and says who won", () => {
    connect("a");
    connect("b");
    room.handle("a", { type: "create" });
    room.handle("a", { type: "resign" });

    const state = stateOf("b");
    expect(state.status.kind).toBe("ended");
    expect(state.status.kind === "ended" && state.status.outcome.kind === "win"
      && state.status.outcome.player).toBe("two");
  });

  it("carries the arrangement history across the wire, bigints and all", () => {
    connect("a");
    room.handle("a", { type: "create" });
    const action = legalActions(stateOf("a"))[0]!;
    room.handle("a", { type: "action", action, expectedMoveNumber: 0 });

    const state = stateOf("a");
    expect(state.boardHistory).toHaveLength(2);
    expect(typeof state.boardHistory[0]).toBe("bigint");
    // And the repetition rule still works on the far side of the wire.
    expect(legalActions(state).map(describeAction).length).toBeGreaterThan(0);
  });
});
