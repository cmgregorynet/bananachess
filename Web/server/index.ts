import { MatchRoom, type ClientMessage, type ServerMessage } from "./room.js";

/** One Worker serves the whole thing: the built site from the assets binding, and a
 *  WebSocket per match from a Durable Object. One deploy, one origin, no CORS.
 *
 *  A Durable Object is the right shape here because a match is exactly what it models —
 *  a single addressable thing with its own state and its own sockets, which Cloudflare
 *  guarantees exists in only one place at a time. Two players cannot reach two copies of
 *  the same room. */
export interface Env {
  ASSETS: { fetch: (request: Request) => Promise<Response> };
  MATCH_ROOM: DurableObjectNamespace;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname.startsWith("/api/room/")) {
      const name = url.pathname.slice("/api/room/".length);
      if (!/^[A-Za-z0-9_-]{4,64}$/.test(name)) {
        return new Response("Bad room name", { status: 400 });
      }
      const id = env.MATCH_ROOM.idFromName(name);
      return env.MATCH_ROOM.get(id).fetch(request);
    }

    return env.ASSETS.fetch(request);
  },
};

export class MatchRoomObject {
  private readonly room = new MatchRoom();
  private nextConnectionID = 1;

  constructor(
    private readonly ctx: DurableObjectState,
    _env: Env,
  ) {}

  async fetch(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Expected a WebSocket", { status: 426 });
    }

    // Who is connecting, as opposed to which socket this is. A reconnect brings a new
    // socket and the same token, which is what lets a player get their own seat back.
    const player = new URL(request.url).searchParams.get("player");
    if (!player || !/^[A-Za-z0-9_-]{8,64}$/.test(player)) {
      return new Response("Missing player token", { status: 400 });
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    const id = `c${this.nextConnectionID++}`;

    // Hibernation: Cloudflare may evict the object between moves, which is the common
    // case in a turn-based game where both players are thinking. The socket survives it.
    this.ctx.acceptWebSocket(server, [id, player]);
    this.room.join({
      id,
      player,
      send: (message: ServerMessage) => {
        try {
          server.send(JSON.stringify(message));
        } catch {
          // A socket that has gone away is not an error worth propagating.
        }
      },
    });

    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(socket: WebSocket, data: string | ArrayBuffer): Promise<void> {
    const id = this.ctx.getTags(socket)[0];
    if (!id || typeof data !== "string") return;
    let message: ClientMessage;
    try {
      message = JSON.parse(data) as ClientMessage;
    } catch {
      return;
    }
    this.room.handle(id, message);
  }

  async webSocketClose(socket: WebSocket): Promise<void> {
    const id = this.ctx.getTags(socket)[0];
    if (id) this.room.leave(id);
  }

  async webSocketError(socket: WebSocket): Promise<void> {
    await this.webSocketClose(socket);
  }
}
