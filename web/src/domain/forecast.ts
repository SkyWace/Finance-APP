import { plusDays, type IsoDate } from './dates';
import type { Cents } from './money';
import type { PlannedItem, Transaction } from './types';

export interface ForecastPoint {
  date: IsoDate;
  balance: Cents;
  projected: boolean;
}

export interface Forecast {
  history: ForecastPoint[];
  projection: ForecastPoint[];
  lowest: ForecastPoint;
  firstNegative?: ForecastPoint;
}

export interface ForecastInput {
  scope: Set<number>;
  currentBalance: Cents;
  today: IsoDate;
  historyFrom: IsoDate;
  /** Operations comptees dans le solde, posterieures a historyFrom. */
  realized: Transaction[];
  until: IsoDate;
  planned: PlannedItem[];
}

/**
 * Historique reconstruit a rebours depuis le solde actuel ; projection = solde
 * actuel + operations a venir cumulees jour par jour (les retards appliques
 * aujourd'hui). Les virements internes au perimetre sont neutres.
 */
export function computeForecast(input: ForecastInput): Forecast {
  const internal = (type: string, other?: number) =>
    type === 'TRANSFER' && other !== undefined && input.scope.has(other);

  const realizedByDay = new Map<IsoDate, Cents>();
  for (const t of input.realized) {
    if (!input.scope.has(t.accountId) || internal(t.type, t.transferAccountId) || t.date < input.historyFrom) {
      continue;
    }
    realizedByDay.set(t.date, (realizedByDay.get(t.date) ?? 0) + t.amount);
  }
  let balance = input.currentBalance;
  // Operations reelles datees dans le futur : deja dans le solde actuel.
  for (const [day, amount] of realizedByDay) {
    if (day > input.today) {
      balance -= amount;
    }
  }
  const history: ForecastPoint[] = [];
  for (let d = input.today; d >= input.historyFrom; d = plusDays(d, -1)) {
    history.push({ date: d, balance, projected: false });
    balance -= realizedByDay.get(d) ?? 0;
  }
  history.reverse();

  const plannedByDay = new Map<IsoDate, Cents>();
  for (const p of input.planned) {
    if (!input.scope.has(p.accountId) || internal(p.type, p.transferAccountId) || p.date > input.until) {
      continue;
    }
    const day = p.date < input.today ? input.today : p.date;
    plannedByDay.set(day, (plannedByDay.get(day) ?? 0) + p.amount);
  }
  const projection: ForecastPoint[] = [];
  balance = history[history.length - 1].balance;
  let lowest: ForecastPoint | undefined;
  let firstNegative: ForecastPoint | undefined;
  for (let d = input.today; d <= input.until; d = plusDays(d, 1)) {
    balance += plannedByDay.get(d) ?? 0;
    const point = { date: d, balance, projected: true };
    projection.push(point);
    if (!lowest || balance < lowest.balance) {
      lowest = point;
    }
    if (!firstNegative && balance < 0) {
      firstNegative = point;
    }
  }
  return { history, projection, lowest: lowest!, firstNegative };
}
