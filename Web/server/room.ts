import { apply, legalActions, describeAction, RULES_VERSION } from "../src/engine/rules.js";
import { newGame, type NewGameOptions } from "../src/engine/setup.js";
import type { GameAction, GameState } from "../src/engine/state.js";
import { encodeState, type WireState } from "../src/engine/wire.js";
import type { PlayerID } from "../src/engine/types.js";

/** One match, with the rules on the server's side of the wire.
 *
 *  The server is not a relay. It holds the authoritative state and runs every submitted
 *  action through the same engine the clients do, which is only possible because the port
 *  is TypeScript — the Worker imports the identical module. A client cannot play an
 *  illegal move by asking nicely, and two clients cannot drift apart, because neither of
 *  them is deciding anything.
 *
 *  Kept free of Cloudflare types so it can be tested as a plain object. */
export type ClientMessage =
  | { type: "create"; options?: Omit<NewGameOptions, "seed"> & { seed?: string } }
  | { type: "action"; action: GameAction; expectedMoveNumber: number }
  | { type: "resign" };

export type ServerMessage =
  | {
      type: "state";
      state: WireState;
      moveNumber: number;
      seats: { one: boolean; two: boolean };
      rulesVersion: number;
    }
  | { type: "seat"; you: PlayerID | null }
  | { type: "error"; message: string };

export interface Connection {
  id: string;
  send: (message: ServerMessage) => void;
}

export class MatchRoom {
  private state: GameState | null = null;
  private readonly seats = new Map<PlayerID, string>();
  private readonly connections = new Map<string, Connection>();

  get currentState(): GameState | null {
    return this.state;
  }

  /** Seats go first come, first served, held by connection id so a reconnect from the
   *  same client takes its own side back rather than becoming a spectator at its own
   *  match. */
  join(connection: Connection): void {
    this.connections.set(connection.id, connection);
    if (!this.seatOf(connection.id)) {
      for (const seat of ["one", "two"] as const) {
        if (!this.seats.has(seat)) {
          this.seats.set(seat, connection.id);
          break;
        }
      }
    }
    connection.send({ type: "seat", you: this.seatOf(connection.id) });
    if (this.state) connection.send(this.stateMessage());
  }

  leave(connectionID: string): void {
    this.connections.delete(connectionID);
    // The seat is kept: whoever held it may come back, and handing it to the next
    // arrival would give a stranger their half-finished match.
  }

  seatOf(connectionID: string): PlayerID | null {
    for (const [seat, holder] of this.seats) if (holder === connectionID) return seat;
    return null;
  }

  handle(connectionID: string, message: ClientMessage): void {
    const connection = this.connections.get(connectionID);
    if (!connection) return;

    if (message.type === "create") {
      if (this.state) {
        connection.send({ type: "error", message: "That match has already started." });
        return;
      }
      const { seed, ...options } = message.options ?? {};
      this.state = newGame({ ...options, ...(seed === undefined ? {} : { seed: BigInt(seed) }) });
      this.broadcast(this.stateMessage());
      return;
    }

    const seat = this.seatOf(connectionID);

    if (message.type === "resign") {
      if (!this.state || !seat) return;
      this.state = {
        ...this.state,
        status: {
          kind: "ended",
          outcome: { kind: "win", player: seat === "one" ? "two" : "one", reason: "score" },
        },
      };
      this.broadcast(this.stateMessage());
      return;
    }

    if (!this.state) {
      connection.send({ type: "error", message: "That match hasn't started yet." });
      return;
    }
    if (!seat) {
      connection.send({ type: "error", message: "You're watching this match, not playing it." });
      return;
    }
    if (this.state.activePlayer !== seat) {
      connection.send({ type: "error", message: "It isn't your turn." });
      return;
    }
    // Guards a double submission from a client that retried after a dropped socket: the
    // move number it built on must still be the one the server is on.
    if (message.expectedMoveNumber !== this.state.history.length) {
      connection.send({ type: "error", message: "The board has moved on. Catching you up." });
      connection.send(this.stateMessage());
      return;
    }

    const wanted = describeAction(message.action);
    const legal = legalActions(this.state).find((a) => describeAction(a) === wanted);
    if (!legal) {
      connection.send({ type: "error", message: "That move isn't legal." });
      connection.send(this.stateMessage());
      return;
    }

    try {
      this.state = apply(this.state, legal).state;
    } catch (error) {
      connection.send({
        type: "error",
        message: error instanceof Error ? error.message : "That move was refused.",
      });
      return;
    }
    this.broadcast(this.stateMessage());
  }

  private stateMessage(): ServerMessage {
    return {
      type: "state",
      state: encodeState(this.state!),
      moveNumber: this.state!.history.length,
      seats: { one: this.seats.has("one"), two: this.seats.has("two") },
      rulesVersion: RULES_VERSION,
    };
  }

  private broadcast(message: ServerMessage): void {
    for (const connection of this.connections.values()) connection.send(message);
  }
}
