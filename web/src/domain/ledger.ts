import { endOfMonth, endOfWeek, plusDays, type IsoDate } from './dates';
import type { Cents } from './money';
import { nextOccurrence, occurrences, signedAmount } from './recurrence';
import {
  ACCOUNT_TYPES, countsInBalance, type Account, type AccountGroup, type FinanceData, type HorizonType,
  type PlannedItem,
} from './types';

/**
 * Les occurrences recurrentes non validees restent "a venir" (en retard) pendant
 * ce nombre de jours, puis sont considerees comme obsoletes.
 */
export const RECURRING_OVERDUE_DAYS = 14;

export function groupOf(account: Account): AccountGroup {
  return ACCOUNT_TYPES[account.type].group;
}

/** Solde de chaque compte : solde initial + operations effectuees et en attente. */
export function balances(data: FinanceData): Map<number, Cents> {
  const result = new Map<number, Cents>();
  for (const a of data.accounts) {
    result.set(a.id, a.initialBalance);
  }
  for (const t of data.transactions) {
    if (countsInBalance(t.status) && result.has(t.accountId)) {
      result.set(t.accountId, result.get(t.accountId)! + t.amount);
    }
  }
  return result;
}

/** Comptes du disponible reel : actifs et marques "inclus dans le disponible". */
export function availableScope(data: FinanceData): Account[] {
  return data.accounts.filter((a) => !a.archived && a.includeInAvailable);
}

/** Occurrences de recurrences non encore traitees dans [from, to]. */
export function pendingOccurrences(data: FinanceData, from: IsoDate, to: IsoDate): PlannedItem[] {
  const done = new Set<string>();
  for (const t of data.transactions) {
    if (t.recurringId !== undefined && t.occurrenceDate) {
      done.add(`${t.recurringId}|${t.occurrenceDate}`);
    }
  }
  const items: PlannedItem[] = [];
  for (const rule of data.rules) {
    const ruleFrom = rule.trackedFrom > from ? rule.trackedFrom : from;
    for (const date of occurrences(rule, ruleFrom, to)) {
      if (done.has(`${rule.id}|${date}`)) {
        continue;
      }
      items.push({
        date, accountId: rule.accountId, label: rule.label, amount: signedAmount(rule), type: rule.type,
        categoryId: rule.categoryId, source: 'RECURRING', ruleId: rule.id, transferAccountId: rule.toAccountId,
        certain: rule.type !== 'INCOME' || rule.certain,
      });
      if (rule.type === 'TRANSFER' && rule.toAccountId !== undefined) {
        items.push({
          date, accountId: rule.toAccountId, label: rule.label, amount: rule.amount, type: 'TRANSFER',
          categoryId: null, source: 'RECURRING', ruleId: rule.id, transferAccountId: rule.accountId, certain: true,
        });
      }
    }
  }
  return items.sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0));
}

/** Operations a venir jusqu'a until (inclus), retards compris, triees par date. */
export function upcoming(data: FinanceData, today: IsoDate, until: IsoDate): PlannedItem[] {
  const items: PlannedItem[] = data.transactions
    .filter((t) => t.status === 'PLANNED' && t.date <= until)
    .map((t) => ({
      date: t.date, accountId: t.accountId, label: t.label, amount: t.amount, type: t.type,
      categoryId: t.categoryId, source: 'PLANNED_TRANSACTION' as const, transactionId: t.id,
      transferAccountId: t.transferAccountId, certain: true,
    }));
  items.push(...pendingOccurrences(data, plusDays(today, -RECURRING_OVERDUE_DAYS), until));
  return items.sort((a, b) => (a.date !== b.date ? (a.date < b.date ? -1 : 1) : a.amount - b.amount));
}

/** Une seule ligne par virement (la jambe debitrice), pour les listes affichees. */
export function upcomingForDisplay(data: FinanceData, today: IsoDate, until: IsoDate): PlannedItem[] {
  return upcoming(data, today, until).filter((i) => i.type !== 'TRANSFER' || i.amount < 0);
}

/** Prochaine paie : prochaine occurrence (apres aujourd'hui) du plus gros revenu recurrent du perimetre. */
export function nextPayday(data: FinanceData, today: IsoDate, accountIds: Set<number>): IsoDate | undefined {
  const salary = data.rules
    .filter((r) => r.type === 'INCOME' && r.active && accountIds.has(r.accountId))
    .sort((a, b) => b.amount - a.amount)[0];
  return salary ? nextOccurrence(salary, plusDays(today, 1)) : undefined;
}

export interface Horizon {
  type: HorizonType;
  end: IsoDate;
  payday?: IsoDate;
}

export function resolveHorizon(data: FinanceData, today: IsoDate, type: HorizonType, custom?: IsoDate): Horizon {
  switch (type) {
    case 'END_OF_WEEK':
      return { type, end: endOfWeek(today) };
    case 'END_OF_MONTH':
      return { type, end: endOfMonth(today) };
    case 'CUSTOM_DATE':
      return { type, end: !custom || custom < today ? today : custom };
    case 'NEXT_PAYDAY': {
      const ids = new Set(availableScope(data).map((a) => a.id));
      const payday = nextPayday(data, today, ids);
      // La paie elle-meme n'est pas comptee : ce qui doit tenir jusqu'a la veille.
      return payday ? { type, end: plusDays(payday, -1), payday } : { type, end: endOfMonth(today) };
    }
  }
}
