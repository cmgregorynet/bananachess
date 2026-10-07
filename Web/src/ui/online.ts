import type { ClientMessage, ServerMessage } from "../../server/room.js";
import type { GameAction, GameState } from "../engine/state.js";
import type { PlayerID } from "../engine/types.js";
import { decodeState } from "../engine/wire.js";
import { RULES_VERSION } from "../engine/rules.js";
import type { NewGameOptions } from "../engine/setup.js";

export type OnlineStatus =
  | { kind: "connecting" }
  | { kind: "waiting" }
  | { kind: "playing" }
  | { kind: "watching" }
  | { kind: "lost"; message: string };

export interface OnlineHandlers {
  onState: (state: GameState, moveNumber: number) => void;
  onSeat: (seat: PlayerID | null) => void;
  onStatus: (status: OnlineStatus) => void;
  onError: (message: string) => void;
}

/** A live match over one WebSocket.
 *
 *  The client never decides anything: it sends what the player wants and draws whatever
 *  the server sends back. That is why the engine is on the server — two clients cannot
 *  drift apart if neither of them is the authority.
 *
 *  Reconnects on its own with a backoff, because a phone that locks for thirty seconds
 *  drops the socket and a player should not have to know that. */
export class OnlineMatch {
  private socket: WebSocket | null = null;
  private attempts = 0;
  private closed = false;
  private reconnectTimer: number | null = null;
  private pendingCreate: ClientMessage | null = null;

  constructor(
    readonly roomID: string,
    private readonly handlers: OnlineHandlers,
  ) {}

  connect(): void {
    this.closed = false;
    this.handlers.onStatus({ kind: "connecting" });
    const protocol = location.protocol === "https:" ? "wss:" : "ws:";
    const socket = new WebSocket(
      `${protocol}//${location.host}/api/room/${this.roomID}?player=${playerToken(this.roomID)}`,
    );
    this.socket = socket;

    socket.addEventListener("open", () => {
      this.attempts = 0;
      if (this.pendingCreate) {
        socket.send(JSON.stringify(this.pendingCreate));
        this.pendingCreate = null;
      }
    });

    socket.addEventListener("message", (event) => {
      let message: ServerMessage;
      try {
        message = JSON.parse(String(event.data)) as ServerMessage;
      } catch {
        return;
      }
      this.receive(message);
    });

    socket.addEventListener("close", () => this.scheduleReconnect());
    socket.addEventListener("error", () => socket.close());
  }

  private receive(message: ServerMessage): void {
    if (message.type === "seat") {
      this.handlers.onSeat(message.you);
      this.handlers.onStatus(message.you ? { kind: "waiting" } : { kind: "watching" });
      return;
    }
    if (message.type === "error") {
      this.handlers.onError(message.message);
      return;
    }
    // A server on different rules would quietly play a different game, so say so rather
    // than letting the boards disagree.
    if (message.rulesVersion !== RULES_VERSION) {
      this.closed = true;
      this.socket?.close();
      this.handlers.onStatus({
        kind: "lost",
        message: "This page is out of date with the server. Reload to pick up the new rules.",
      });
      return;
    }
    this.handlers.onStatus({ kind: "playing" });
    this.handlers.onState(decodeState(message.state), message.moveNumber);
  }

  private scheduleReconnect(): void {
    if (this.closed) return;
    this.attempts += 1;
    if (this.attempts > 6) {
      this.handlers.onStatus({ kind: "lost", message: "Lost the connection. Reload to rejoin." });
      return;
    }
    this.handlers.onStatus({ kind: "connecting" });
    const delay = Math.min(1_000 * 2 ** (this.attempts - 1), 10_000);
    this.reconnectTimer = window.setTimeout(() => this.connect(), delay);
  }

  create(options: Omit<NewGameOptions, "seed">): void {
    this.send({ type: "create", options });
  }

  play(action: GameAction, expectedMoveNumber: number): void {
    this.send({ type: "action", action, expectedMoveNumber });
  }

  resign(): void {
    this.send({ type: "resign" });
  }

  close(): void {
    this.closed = true;
    if (this.reconnectTimer !== null) window.clearTimeout(this.reconnectTimer);
    this.socket?.close();
  }

  private send(message: ClientMessage): void {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(message));
    } else if (message.type === "create") {
      // Sent the moment the socket opens: creating is the one message that cannot simply
      // be retried by the player, because they have already pressed the button.
      this.pendingCreate = message;
    }
  }
}

/** Who this browser is, for this match, across reconnections.
 *
 *  Kept because seats cannot be keyed on the connection: a WebSocket gets a fresh id
 *  every time it opens, so a player whose phone locked for thirty seconds would come back
 *  to find themselves a spectator at their own game. Stored per room, so joining a
 *  different match is a clean slate.
 *
 *  One consequence worth knowing: two tabs in the same browser share the token and
 *  therefore share a seat. Playing both sides needs two browsers, or a private window. */
function playerToken(roomID: string): string {
  const key = `bananachess.player.${roomID}`;
  const existing = localStorage.getItem(key);
  if (existing) return existing;
  const bytes = new Uint8Array(12);
  crypto.getRandomValues(bytes);
  const token = [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
  localStorage.setItem(key, token);
  return token;
}

/** Room ids are typed into URLs and read aloud, so no vowels and no look-alikes. */
export function newRoomID(): string {
  const alphabet = "bcdfghjkmnpqrstvwxz23456789";
  const bytes = new Uint8Array(8);
  crypto.getRandomValues(bytes);
  return [...bytes].map((b) => alphabet[b % alphabet.length]).join("");
}

export const roomFromLocation = (): string | null => {
  const room = new URLSearchParams(location.search).get("room");
  return room && /^[A-Za-z0-9_-]{4,64}$/.test(room) ? room : null;
};

export const linkForRoom = (roomID: string): string =>
  `${location.origin}${location.pathname}?room=${roomID}`;
