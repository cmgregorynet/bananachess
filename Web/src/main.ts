import type { CPUDifficulty } from "./engine/cpu.js";
import { difficultyBlurb, difficultyName, selectAction } from "./engine/cpu.js";
import { apply, legalActions, legalMovesForPiece, RulesError } from "./engine/rules.js";
import { newGame } from "./engine/setup.js";
import type { GameAction, Outcome } from "./engine/state.js";
import { pieceAt, pieceWithID } from "./engine/state.js";
import type { BoardSetup, BoardWrap, PieceKind, PlayerID, Position, RuleSet } from "./engine/types.js";
import { baseValue, samePosition } from "./engine/types.js";
import { renderBoard } from "./ui/board.js";
import { describeTrackers, renderTrackers } from "./ui/trackers.js";
import type { OnlineStatus } from "./ui/online.js";
import { OnlineMatch, linkForRoom, newRoomID, roomFromLocation } from "./ui/online.js";

interface Settings {
  ruleSet: RuleSet;
  boardSetup: BoardSetup;
  boardWrap: BoardWrap;
  blenderBattles: boolean;
  opponent: "human" | CPUDifficulty;
}

const SETTINGS_KEY = "bananachess.settings";

const defaultSettings: Settings = {
  ruleSet: "standard",
  boardSetup: "standard",
  boardWrap: "flat",
  blenderBattles: false,
  opponent: "human",
};

/** The human always plays player one against the computer. */
const HUMAN: PlayerID = "one";

function loadSettings(): Settings {
  try {
    const stored = localStorage.getItem(SETTINGS_KEY);
    return stored ? { ...defaultSettings, ...(JSON.parse(stored) as Partial<Settings>) } : defaultSettings;
  } catch {
    return defaultSettings;
  }
}

const BOARD_BLURBS: Record<BoardSetup, string> = {
  standard: "Blenders behind, fruit in front.",
  blendy: "Swapped: fruit behind, blenders in front.",
  random: "Everything scattered. A different board every time.",
};

const EDGE_BLURBS: Record<BoardWrap, string> = {
  flat: "Edges are walls. Everything stops at them.",
  sides: "Fruit can step off the left or right edge and arrive at the other.",
  all: "Fruit can cross any edge, top and bottom too. Blenders never can.",
};

let settings = loadSettings();
let state = newGame(settings);
let selected: string | null = null;
let pendingSelfBlend: GameAction | null = null;
let thinking = false;

/** Online play. The client never decides anything — it draws what the server sends. */
let online: OnlineMatch | null = null;
let onlineSeat: PlayerID | null = null;
let onlineStatus: OnlineStatus = { kind: "connecting" };
let onlineMoveNumber = 0;
let notice: string | null = null;

const isOnline = (): boolean => online !== null;

function startOnline(roomID: string, create: boolean): void {
  online?.close();
  onlineSeat = null;
  onlineMoveNumber = 0;
  notice = null;
  online = new OnlineMatch(roomID, {
    onState: (next, moveNumber) => {
      state = next;
      onlineMoveNumber = moveNumber;
      selected = null;
      pendingSelfBlend = null;
      render();
    },
    onSeat: (seat) => {
      onlineSeat = seat;
      render();
    },
    onStatus: (status) => {
      onlineStatus = status;
      render();
    },
    onError: (message) => {
      notice = message;
      render();
    },
  });
  online.connect();
  if (create) {
    const { opponent: _opponent, ...rules } = settings;
    online.create(rules);
  }
  history.replaceState(null, "", linkForRoom(roomID));
  render();
}

function leaveOnline(): void {
  online?.close();
  online = null;
  onlineSeat = null;
  notice = null;
  history.replaceState(null, "", location.pathname);
  restartMatch();
}

const computerPlays = (): boolean => settings.opponent !== "human";
const isComputersTurn = (): boolean =>
  computerPlays() && state.status.kind !== "ended" && state.activePlayer !== HUMAN;

/** The search runs on the page's own thread, so the board is painted first and the move
 *  taken after — otherwise the tap that triggered it would never be drawn. A worker would
 *  be better and is the obvious next step if the budgets ever rise. */
function takeComputerTurnIfNeeded(): void {
  if (!isComputersTurn() || thinking) return;
  thinking = true;
  const joining = roomFromLocation();
if (joining) {
  // Arriving on somebody's link: join their match rather than starting a fresh one.
  startOnline(joining, false);
} else {
  render();
  takeComputerTurnIfNeeded();
}
  setTimeout(() => {
    const difficulty = settings.opponent as CPUDifficulty;
    const action = selectAction(state, { difficulty });
    thinking = false;
    if (!action) {
      render();
      return;
    }
    try {
      state = apply(state, action).state;
    } catch {
      // A move the engine refuses is not a move; leave the turn where it is.
    }
    selected = null;
    render();
    takeComputerTurnIfNeeded();
  }, 60);
}

const app = document.querySelector<HTMLElement>("#app")!;

const nameOf = (player: PlayerID): string => {
  if (computerPlays()) return player === HUMAN ? "You" : "Computer";
  return player === "one" ? "Player One" : "Player Two";
};

const describeOutcome = (outcome: Outcome): string => {
  if (outcome.kind === "draw") return "A draw — level on points.";
  const how =
    outcome.reason === "bananaSplit" ? "wins by Banana Split"
      : outcome.reason === "blenderama" ? "wins by Blenderama"
        : "wins";
  return `${nameOf(outcome.player)} ${how}.`;
};

/** Legal destinations win the tap over re-selection, because a blender may land on its
 *  owner's own fruit — treating those as selection would make self-blending unreachable. */
function onSquare(position: Position): void {
  if (state.status.kind === "ended" || thinking) return;
  if (isOnline() && state.activePlayer !== onlineSeat) return;
  if (!isOnline() && computerPlays() && state.activePlayer !== HUMAN) return;

  if (selected) {
    const move = legalMovesForPiece(state, selected).find(
      (m) => m.kind === "move" && samePosition(m.destination, position),
    );
    if (move) {
      const target = pieceAt(state, position);
      if (target && target.owner === state.activePlayer && target.kind !== "blender") {
        pendingSelfBlend = move;
        render();
        return;
      }
      commit(move);
      return;
    }
  }

  const piece = pieceAt(state, position);
  selected = piece && piece.owner === state.activePlayer ? piece.id : null;
  render();
}

function commit(action: GameAction): void {
  if (online) {
    // Sent, not applied: the board moves when the server says it has.
    online.play(action, onlineMoveNumber);
    selected = null;
    pendingSelfBlend = null;
    render();
    return;
  }
  try {
    state = apply(state, action).state;
    selected = null;
    pendingSelfBlend = null;
  } catch (error) {
    if (error instanceof RulesError) {
      selected = null;
      pendingSelfBlend = null;
    } else throw error;
  }
  render();
  takeComputerTurnIfNeeded();
}

function restartMatch(): void {
  state = newGame(settings);
  selected = null;
  pendingSelfBlend = null;
  thinking = false;
  render();
  takeComputerTurnIfNeeded();
}

function choice<T extends string>(
  label: string,
  options: readonly T[],
  current: T,
  names: Record<T, string>,
  blurb: string | null,
  onPick: (value: T) => void,
): HTMLElement {
  const fieldset = document.createElement("fieldset");
  const legend = document.createElement("legend");
  legend.textContent = label;
  fieldset.append(legend);

  const row = document.createElement("div");
  row.className = "options";
  for (const option of options) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = names[option];
    button.setAttribute("aria-pressed", String(option === current));
    button.addEventListener("click", () => onPick(option));
    row.append(button);
  }
  fieldset.append(row);

  if (blurb) {
    const text = document.createElement("p");
    text.className = "blurb";
    text.textContent = blurb;
    fieldset.append(text);
  }
  return fieldset;
}

function updateSettings(change: Partial<Settings>): void {
  settings = { ...settings, ...change };
  localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
  restartMatch();
}

function onlineStatusText(): string {
  if (onlineStatus.kind === "lost") return onlineStatus.message;
  if (onlineStatus.kind === "connecting") return "Connecting…";
  if (onlineStatus.kind === "watching") return "Watching this match";
  if (state.status.kind === "ended") return describeOutcome(state.status.outcome);
  if (onlineStatus.kind === "waiting") return "Waiting for the other player to join…";
  return state.activePlayer === onlineSeat ? "Your move" : "Their move";
}

function onlineControls(): HTMLElement {
  const panel = document.createElement("div");
  panel.className = "controls";

  const share = document.createElement("button");
  share.type = "button";
  share.textContent = "Copy invite link";
  share.addEventListener("click", () => {
    void navigator.clipboard.writeText(location.href).then(
      () => {
        notice = "Link copied. Send it to whoever you want to play.";
        render();
      },
      () => {
        notice = location.href;
        render();
      },
    );
  });

  const leave = document.createElement("button");
  leave.type = "button";
  leave.textContent = "Leave match";
  leave.addEventListener("click", leaveOnline);

  panel.append(share, leave);

  if (onlineSeat && state.status.kind !== "ended") {
    const resign = document.createElement("button");
    resign.type = "button";
    resign.textContent = "Resign";
    resign.addEventListener("click", () => online?.resign());
    panel.append(resign);
  }
  return panel;
}

function render(): void {
  app.replaceChildren();

  const title = document.createElement("h1");
  title.textContent = "Banana Chess";
  const subtitle = document.createElement("p");
  subtitle.className = "subtitle";
  subtitle.textContent = "Race the fruit. Mind the blenders.";
  app.append(title, subtitle);

  const scores = document.createElement("div");
  scores.className = "scores";
  for (const player of ["two", "one"] as const) {
    const card = document.createElement("div");
    card.className = `score${state.activePlayer === player && state.status.kind !== "ended" ? " active" : ""}`;
    const name = document.createElement("span");
    name.className = "name";
    name.textContent = nameOf(player);
    const value = document.createElement("span");
    value.className = "value";
    value.textContent = String(state.scores[player]);
    const heading = document.createElement("div");
    heading.className = "score-heading";
    heading.append(name, value);
    card.append(heading);

    if (state.ruleSet === "wildcard") {
      const trackers = document.createElement("div");
      trackers.className = "trackers";
      renderTrackers(trackers, state, player);
      trackers.setAttribute("aria-label", `${nameOf(player)}: ${describeTrackers(state, player)}`);
      card.append(trackers);
    }
    scores.append(card);
  }
  app.append(scores);

  const status = document.createElement("p");
  status.className = "status";
  status.setAttribute("role", "status");
  status.textContent = isOnline()
    ? onlineStatusText()
    : state.status.kind === "ended"
      ? describeOutcome(state.status.outcome)
      : thinking
        ? `${difficultyName(settings.opponent as CPUDifficulty)} computer is thinking…`
        : `${nameOf(state.activePlayer)} to move`;
  app.append(status);

  if (notice) {
    const warning = document.createElement("p");
    warning.className = "status";
    warning.setAttribute("role", "alert");
    warning.textContent = notice;
    app.append(warning);
  }

  const boardHost = document.createElement("div");
  renderBoard(boardHost, {
    state,
    selected,
    legalDestinations: selected
      ? legalMovesForPiece(state, selected).flatMap((m) => (m.kind === "move" ? [m.destination] : []))
      : [],
    // Pass-and-play on one screen, so the board does not turn: both players share a view.
    bottomPlayer: "one",
    onSquare,
  });
  app.append(boardHost);

  const controls = document.createElement("div");
  controls.className = "controls";
  const restart = document.createElement("button");
  restart.type = "button";
  restart.textContent = "New match";
  restart.addEventListener("click", restartMatch);
  controls.append(restart);

  // Buying a blender back costs a turn and three points, and is the only action that is
  // not a move, so it gets its own control rather than hiding on the empty spawn.
  for (const action of legalActions(state)) {
    if (action.kind !== "restoreBlender") continue;
    const blender = pieceWithID(state, action.pieceID);
    if (!blender) continue;
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = `Buy blender back (−3) at row ${action.at.row + 1}, column ${action.at.column + 1}`;
    button.addEventListener("click", () => commit(action));
    controls.append(button);
  }
  app.append(controls);

  if (isOnline()) {
    app.append(onlineControls());
    if (pendingSelfBlend && pendingSelfBlend.kind === "move") {
      app.append(selfBlendDialog(pendingSelfBlend));
    }
    return;
  }

  app.append(
    choice("Opponent", ["human", "easy", "medium", "hard"] as const, settings.opponent,
      { human: "Two player", easy: "Easy", medium: "Medium", hard: "Hard" },
      settings.opponent === "human"
        ? "Pass the device back and forth."
        : difficultyBlurb(settings.opponent),
      (opponent) => updateSettings({ opponent })),
    choice("Board", ["standard", "blendy", "random"] as const, settings.boardSetup,
      { standard: "Standard", blendy: "Blendy", random: "Random" },
      BOARD_BLURBS[settings.boardSetup], (boardSetup) => updateSettings({ boardSetup })),
    choice("Edges", ["flat", "sides", "all"] as const, settings.boardWrap,
      { flat: "Walls", sides: "Sides", all: "All" },
      EDGE_BLURBS[settings.boardWrap], (boardWrap) => updateSettings({ boardWrap })),
    choice("Rules", ["standard", "wildcard"] as const, settings.ruleSet,
      { standard: "Standard", wildcard: "Wildcard" },
      settings.ruleSet === "wildcard"
        ? "Golden Pineapple, Banana Split, Tropical Smoothie, Blenderama."
        : "Score the most points.",
      (ruleSet) => updateSettings({ ruleSet })),
    choice("Blender battles", ["off", "on"] as const, settings.blenderBattles ? "on" : "off",
      { off: "Off", on: "On" },
      "Blenders fight for a square instead of just taking it. Half your standing fruit, plus one for attacking, plus a die each.",
      (value) => updateSettings({ blenderBattles: value === "on" })),
  );

  // Hosting a match keeps whatever is chosen above: the creator settles the rules, and
  // they travel to whoever joins, because the server deals the board.
  const invite = document.createElement("div");
  invite.className = "controls";
  const host = document.createElement("button");
  host.type = "button";
  host.textContent = "Play online — create a match";
  host.addEventListener("click", () => startOnline(newRoomID(), true));
  invite.append(host);
  app.append(invite);

  if (pendingSelfBlend && pendingSelfBlend.kind === "move") {
    app.append(selfBlendDialog(pendingSelfBlend));
  }
}

/** Blending your own fruit is costly and irreversible, so it asks first — the one
 *  interaction in the game that does. */
function selfBlendDialog(move: GameAction & { kind: "move" }): HTMLDialogElement {
  const target = pieceAt(state, move.destination);
  const kind = (target?.kind ?? "banana") as PieceKind;
  const cost = baseValue(kind);

  const dialog = document.createElement("dialog");
  const heading = document.createElement("h2");
  heading.textContent = `Blend your own ${kind}?`;
  const detail = document.createElement("p");
  detail.textContent = `It leaves the board and costs you ${cost} point${cost === 1 ? "" : "s"}.`;

  const row = document.createElement("div");
  row.className = "controls";
  const cancel = document.createElement("button");
  cancel.type = "button";
  cancel.textContent = "Cancel";
  cancel.addEventListener("click", () => {
    pendingSelfBlend = null;
    render();
  });
  const confirm = document.createElement("button");
  confirm.type = "button";
  confirm.textContent = "Blend it";
  confirm.addEventListener("click", () => commit(move));
  row.append(cancel, confirm);

  dialog.append(heading, detail, row);
  queueMicrotask(() => dialog.showModal());
  return dialog;
}

const joining = roomFromLocation();
if (joining) {
  // Arriving on somebody's link: join their match rather than starting a fresh one.
  startOnline(joining, false);
} else {
  render();
  takeComputerTurnIfNeeded();
}
