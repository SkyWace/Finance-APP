import { computeAvailable, type AvailableResult } from './available';
import { reservations } from './budgets';
import { endOfMonth, plusDays, startOfMonth, type IsoDate } from './dates';
import { computeForecast, type Forecast } from './forecast';
import { availableScope, balances, groupOf, resolveHorizon, upcoming, upcomingForDisplay, type Horizon } from './ledger';
import type { Cents } from './money';
import { countsInBalance, type FinanceData, type HorizonType, type PlannedItem, type Transaction } from './types';

export function availableFor(data: FinanceData, today: IsoDate, type: HorizonType, custom?: IsoDate):
  { horizon: Horizon; result: AvailableResult } {
  const horizon = resolveHorizon(data, today, type, custom);
  const b = balances(data);
  const result = computeAvailable({
    today,
    horizonEnd: horizon.end,
    balances: availableScope(data).map((a) => ({ accountId: a.id, name: a.name, balance: b.get(a.id) ?? 0 })),
    planned: upcoming(data, today, horizon.end),
    reservations: reservations(data, today, horizon.end),
    includeCertainIncome: data.settings.includeCertainIncome,
  });
  return { horizon, result };
}

/** Prevision du solde du perimetre "disponible" (comptes courants par defaut). */
export function forecastFor(data: FinanceData, today: IsoDate, historyDays: number, days: number): Forecast {
  const scopeAccounts = availableScope(data);
  const scope = new Set(scopeAccounts.map((a) => a.id));
  const b = balances(data);
  const historyFrom = plusDays(today, -Math.max(0, historyDays));
  const until = plusDays(today, Math.max(0, days));
  return computeForecast({
    scope,
    currentBalance: scopeAccounts.reduce((s, a) => s + (b.get(a.id) ?? 0), 0),
    today,
    historyFrom,
    realized: data.transactions.filter((t) => countsInBalance(t.status) && t.date > historyFrom),
    until,
    planned: upcoming(data, today, until),
  });
}

export interface DashboardSummary {
  netWorth: Cents;
  current: Cents;
  savings: Cents;
  available: AvailableResult;
  horizon: Horizon;
  incomeThisMonth: Cents;
  expensesThisMonth: Cents;
  upcomingOutThisMonth: Cents;
  next: PlannedItem[];
  recent: Transaction[];
}

export function dashboard(data: FinanceData, today: IsoDate): DashboardSummary {
  const b = balances(data);
  const active = data.accounts.filter((a) => !a.archived);
  const total = (filter: (g: string) => boolean) =>
    active.filter((a) => filter(groupOf(a))).reduce((s, a) => s + (b.get(a.id) ?? 0), 0);
  const monthStart = startOfMonth(today);
  const monthEnd = endOfMonth(today);
  const activeIds = new Set(active.map((a) => a.id));
  let income = 0;
  let expenses = 0;
  for (const t of data.transactions) {
    if (!countsInBalance(t.status) || !activeIds.has(t.accountId) || t.date < monthStart || t.date > monthEnd) {
      continue;
    }
    if (t.type === 'INCOME') {
      income += t.amount;
    } else if (t.type === 'EXPENSE') {
      expenses += t.amount;
    }
  }
  const upcomingMonth = upcomingForDisplay(data, today, monthEnd).filter((i) => activeIds.has(i.accountId));
  const { horizon, result } = availableFor(data, today, data.settings.defaultHorizon);
  return {
    netWorth: total(() => true),
    current: total((g) => g === 'CURRENT'),
    savings: total((g) => g === 'SAVINGS'),
    available: result,
    horizon,
    incomeThisMonth: income,
    expensesThisMonth: expenses,
    upcomingOutThisMonth: upcomingMonth.filter((i) => i.type === 'EXPENSE').reduce((s, i) => s + i.amount, 0),
    next: upcomingForDisplay(data, today, plusDays(today, 30)).filter((i) => activeIds.has(i.accountId)).slice(0, 8),
    recent: [...data.transactions]
      .filter((t) => countsInBalance(t.status) && (t.type !== 'TRANSFER' || t.amount < 0))
      .sort((x, y) => (x.date !== y.date ? (x.date < y.date ? 1 : -1) : y.id - x.id))
      .slice(0, 8),
  };
}
