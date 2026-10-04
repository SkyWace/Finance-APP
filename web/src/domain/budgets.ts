import { endOfMonth, parts, startOfMonth, type IsoDate } from './dates';
import { balances, upcoming } from './ledger';
import { divideHalfEven, type Cents } from './money';
import { categoryPath } from './operations';
import { countsInBalance, type Budget, type FinanceData, type PlannedItem, type SavingsGoal } from './types';

/**
 * Budgets mensuels et objectifs d'epargne : memes calculs que l'application desktop
 * (BudgetEngine, SavingsGoalCalculator), au centime pres, arrondis compris.
 */

/**
 * Division d'un montant comme Money.divide du desktop : quotient arrondi a 4 decimales
 * de centime (HALF_EVEN), puis au centime (HALF_EVEN).
 */
export function divideMoney(cents: Cents, divisor: number): Cents {
  return divideHalfEven(divideHalfEven(cents * 10_000, divisor), 10_000);
}

function prorate(amount: Cents, covered: number, outOf: number): Cents {
  return covered >= outOf ? amount : divideMoney(amount * covered, outOf);
}

/** "2026-10" */
type Month = string;

function monthOf(date: IsoDate): Month {
  return date.slice(0, 7);
}

function monthIndex(date: IsoDate): number {
  const [y, m] = parts(date);
  return y * 12 + (m - 1);
}

function monthStart(index: number): IsoDate {
  const y = Math.floor(index / 12);
  const m = (index % 12) + 1;
  return `${String(y).padStart(4, '0')}-${String(m).padStart(2, '0')}-01`;
}

/** Mois entiers entre deux dates (ChronoUnit.MONTHS entre deux YearMonth). */
export function monthsBetweenMonths(from: IsoDate, to: IsoDate): number {
  return monthIndex(to) - monthIndex(from);
}

function daysInclusive(from: IsoDate, to: IsoDate): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000) + 1;
}

/** La categorie et ses sous-categories (un budget "Alimentation" couvre "Courses"). */
export function selfAndChildren(data: FinanceData, categoryId: number): Set<number> {
  const ids = new Set([categoryId]);
  data.categories.filter((c) => c.parentId === categoryId).forEach((c) => ids.add(c.id));
  return ids;
}

/** Depenses effectuees ou en attente de la categorie (et sous-categories) sur le mois (positif). */
function spent(data: FinanceData, scope: Set<number>, month: IsoDate): Cents {
  const from = startOfMonth(month);
  const to = endOfMonth(month);
  let total = 0;
  for (const t of data.transactions) {
    if (countsInBalance(t.status) && t.type === 'EXPENSE' && t.date >= from && t.date <= to
      && t.categoryId !== null && scope.has(t.categoryId)) {
      total -= t.amount;
    }
  }
  return total;
}

/** Depenses prevues de la categorie jusqu'a until, par mois ; les retards comptent pour le mois en cours. */
function plannedByMonth(items: PlannedItem[], scope: Set<number>, today: IsoDate, until: IsoDate): Map<Month, Cents> {
  const result = new Map<Month, Cents>();
  for (const i of items) {
    if (i.type !== 'EXPENSE' || i.date > until || i.categoryId === null || !scope.has(i.categoryId) || i.amount === 0) {
      continue;
    }
    const m = monthOf(i.date < today ? today : i.date);
    result.set(m, (result.get(m) ?? 0) - i.amount);
  }
  return result;
}

// ------------------------------------------------------------------ budgets

export type BudgetStatus = 'OK' | 'WARNING' | 'REACHED' | 'EXCEEDED';

/** Libelle et symbole : l'etat ne repose jamais sur la seule couleur. */
export const BUDGET_STATUS: Record<BudgetStatus, { label: string; symbol: string }> = {
  OK: { label: 'Dans le budget', symbol: '✓' },
  WARNING: { label: 'Proche de la limite', symbol: '!' },
  REACHED: { label: 'Budget atteint', symbol: '■' },
  EXCEEDED: { label: 'Budget dépassé', symbol: '✕' },
};

/** Seuil, en dixiemes de %, a partir duquel un budget est "proche de la limite" (80 %). */
const WARNING_PERMILLE = 800;

export interface BudgetProgress {
  budget: Budget;
  categoryName: string;
  /** Depenses effectuees ou en attente du mois (positif). */
  spent: Cents;
  /** Depenses encore prevues dans le mois (positif). */
  planned: Cents;
  /** Limite - depense (negatif en cas de depassement). */
  remaining: Cents;
  /** Part consommee en dixiemes de % (peut depasser 1000). */
  permille: number;
  status: BudgetStatus;
}

function progressOf(budget: Budget, categoryName: string, spentCents: Cents, planned: Cents): BudgetProgress {
  const permille = divideHalfEven(spentCents * 1000, budget.limit);
  const status: BudgetStatus = spentCents > budget.limit ? 'EXCEEDED' : spentCents === budget.limit ? 'REACHED'
    : permille >= WARNING_PERMILLE ? 'WARNING' : 'OK';
  return { budget, categoryName, spent: spentCents, planned, remaining: budget.limit - spentCents, permille, status };
}

/** Budgets dans l'ordre du desktop : actifs d'abord, puis par anciennete. */
export function sortedBudgets(data: FinanceData): Budget[] {
  return [...data.budgets].sort((a, b) => (a.active === b.active ? a.id - b.id : a.active ? -1 : 1));
}

/** Situation des budgets actifs pour le mois de month, les plus consommes en premier. */
export function budgetProgress(data: FinanceData, month: IsoDate, today: IsoDate): BudgetProgress[] {
  const end = endOfMonth(month);
  const current = monthOf(month) === monthOf(today);
  const items = current ? upcoming(data, today, end) : [];
  const result = sortedBudgets(data).filter((b) => b.active).map((b) => {
    const scope = selfAndChildren(data, b.categoryId);
    const planned = current ? plannedByMonth(items, scope, today, end).get(monthOf(month)) ?? 0 : 0;
    return progressOf(b, categoryPath(data, b.categoryId) || '?', spent(data, scope, month), planned);
  });
  return result.sort((a, b) => b.permille - a.permille);
}

/**
 * Somme a reserver pour un budget entre aujourd'hui et l'echeance (incluse) :
 * mois en cours = reste du budget diminue des depenses deja prevues, au prorata des
 * jours couverts ; mois suivants = limite au prorata, diminuee des depenses prevues.
 */
export function budgetReservation(budget: Budget, spentThisMonth: Cents, planned: Map<Month, Cents>, today: IsoDate,
  horizonEnd: IsoDate): Cents {
  if (horizonEnd < today) {
    return 0;
  }
  let total = 0;
  const first = monthIndex(today);
  const last = monthIndex(horizonEnd);
  for (let index = first; index <= last; index++) {
    const start = monthStart(index);
    const from = index === first ? today : start;
    const to = index === last ? horizonEnd : endOfMonth(start);
    const covered = daysInclusive(from, to);
    const plannedHere = planned.get(monthOf(start)) ?? 0;
    let share: Cents;
    if (index === first) {
      const daysLeft = daysInclusive(today, endOfMonth(today));
      const left = budget.limit - spentThisMonth - plannedHere;
      share = left > 0 ? prorate(left, covered, daysLeft) : 0;
    } else {
      const part = prorate(budget.limit, covered, daysInclusive(start, endOfMonth(start))) - plannedHere;
      share = part > 0 ? part : 0;
    }
    total += share;
  }
  return total;
}

// ------------------------------------------------------------------ objectifs

export interface GoalProgress {
  goal: SavingsGoal;
  saved: Cents;
  remaining: Cents;
  /** Part atteinte en dixiemes de % (0 a 1000). */
  permille: number;
  /** Mois restants jusqu'au mois de l'echeance (absent sans echeance). */
  monthsLeft?: number;
  /** Epargne mensuelle necessaire (absente sans echeance ou si atteint). */
  monthlyNeeded?: Cents;
  reached: boolean;
  overdue: boolean;
}

/**
 * Exemple : objectif 5 000 EUR, 3 250 EUR epargnes, echeance decembre 2027, calcul en
 * septembre 2026 → reste 1 750 EUR sur 15 mois → 116,67 EUR par mois.
 */
export function goalProgressOf(goal: SavingsGoal, saved: Cents, today: IsoDate): GoalProgress {
  let remaining = goal.target - saved;
  const reached = remaining <= 0;
  if (reached) {
    remaining = 0;
  }
  const permille = Math.min(1000, Math.max(0, divideHalfEven(saved * 1000, goal.target)));
  const progress: GoalProgress = { goal, saved, remaining, permille, reached, overdue: false };
  if (goal.targetDate) {
    const months = monthsBetweenMonths(today, goal.targetDate);
    progress.monthsLeft = Math.max(0, months);
    progress.overdue = !reached && goal.targetDate < today;
    if (!reached) {
      progress.monthlyNeeded = divideMoney(remaining, Math.max(1, months));
    }
  }
  return progress;
}

/** Epargne d'un objectif : solde du compte lie (jamais negatif) ou montant tenu a la main. */
export function savedOf(goal: SavingsGoal, accountBalances: Map<number, Cents>): Cents {
  if (goal.linkedAccountId === undefined) {
    return goal.manualSaved;
  }
  return Math.max(0, accountBalances.get(goal.linkedAccountId) ?? 0);
}

/** Ordre du desktop : actifs d'abord, echeance la plus proche, sans echeance a la fin, puis par nom. */
export function sortedGoals(data: FinanceData): SavingsGoal[] {
  return [...data.goals].sort((a, b) => {
    if (a.archived !== b.archived) {
      return a.archived ? 1 : -1;
    }
    if (!a.targetDate !== !b.targetDate) {
      return a.targetDate ? -1 : 1;
    }
    if (a.targetDate && b.targetDate && a.targetDate !== b.targetDate) {
      return a.targetDate < b.targetDate ? -1 : 1;
    }
    const x = a.name.toLowerCase();
    const y = b.name.toLowerCase();
    return x < y ? -1 : x > y ? 1 : 0;
  });
}

export function goalProgress(data: FinanceData, today: IsoDate): GoalProgress[] {
  const b = balances(data);
  return sortedGoals(data).filter((g) => !g.archived).map((g) => goalProgressOf(g, savedOf(g, b), today));
}

// ------------------------------------------------------------------ disponible reel

export interface Reservation {
  label: string;
  amount: Cents;
}

/**
 * Sommes a mettre de cote dans le disponible reel : reste des budgets marques "a
 * reserver", puis effort mensuel des objectifs marques "a reserver" pour chaque mois
 * entame d'ici l'echeance. Les montants nuls sont omis.
 */
export function reservations(data: FinanceData, today: IsoDate, horizonEnd: IsoDate): Reservation[] {
  const result: Reservation[] = [];
  if (horizonEnd < today) {
    return result;
  }
  const reserved = sortedBudgets(data).filter((b) => b.active && b.reserveInAvailable);
  if (reserved.length > 0) {
    const items = upcoming(data, today, horizonEnd);
    for (const b of reserved) {
      const scope = selfAndChildren(data, b.categoryId);
      const amount = budgetReservation(b, spent(data, scope, today), plannedByMonth(items, scope, today, horizonEnd),
        today, horizonEnd);
      result.push({ label: `Budget ${categoryPath(data, b.categoryId) || '?'} (reste)`, amount });
    }
  }
  const months = monthsBetweenMonths(today, horizonEnd) + 1;
  const goals = sortedGoals(data).filter((g) => !g.archived && g.reserveInAvailable);
  if (goals.length > 0) {
    const b = balances(data);
    for (const g of goals) {
      const p = goalProgressOf(g, savedOf(g, b), today);
      if (p.monthlyNeeded !== undefined && p.monthlyNeeded > 0) {
        result.push({ label: `Objectif ${g.name}`, amount: p.monthlyNeeded * months });
      }
    }
  }
  return result.filter((r) => r.amount !== 0);
}
