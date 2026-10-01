import type { Outcome } from "./state.js";
import type { PieceKind, PlayerID, Position } from "./types.js";

export interface BattleReport {
  attacker: PlayerID;
  attackerFruit: number;
  attackerBonus: number;
  attackerRoll: number;
  defenderFruit: number;
  defenderRoll: number;
}

export const attackerTotal = (r: BattleReport): number =>
  r.attackerFruit + r.attackerBonus + r.attackerRoll;
export const defenderTotal = (r: BattleReport): number => r.defenderFruit + r.defenderRoll;
/** A tie goes to the defender: the attacker has to actually beat the total. */
export const attackerWins = (r: BattleReport): boolean => attackerTotal(r) > defenderTotal(r);

export type GameEvent =
  | { kind: "moved"; pieceID: string; from: Position; to: Position }
  | { kind: "blenderBattle"; report: BattleReport; at: Position }
  | { kind: "blenderDestroyed"; pieceID: string; owner: PlayerID; by: string; at: Position }
  | { kind: "blenderRestored"; pieceID: string; owner: PlayerID; at: Position; cost: number }
  | { kind: "blenderRespawned"; pieceID: string; owner: PlayerID; at: Position }
  | { kind: "bananaReturned"; pieceID: string; to: Position }
  | {
      kind: "blended"; pieceID: string; fruitKind: PieceKind; fruitOwner: PlayerID;
      at: Position; awardedTo: PlayerID; points: number;
    }
  | {
      kind: "delivered"; pieceID: string; fruitKind: PieceKind; owner: PlayerID;
      at: Position; points: number;
    }
  | { kind: "scoreChanged"; player: PlayerID; newScore: number }
  | { kind: "smoothiePoured"; player: PlayerID; points: number }
  | { kind: "smoothie"; player: PlayerID; pieceIDs: string[]; respawnTurn: number }
  | { kind: "pineappleTurnedGolden"; pieceID: string; owner: PlayerID }
  | { kind: "turnPassed"; skipped: PlayerID }
  | { kind: "turnChanged"; active: PlayerID }
  | { kind: "gameEnded"; outcome: Outcome };
