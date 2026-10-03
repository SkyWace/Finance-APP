import { daysBetween, monthsBetween, plusDays, plusMonths, type IsoDate } from './dates';
import type { Frequency, RecurringRule } from './types';

type Unit = 'DAYS' | 'WEEKS' | 'MONTHS';

const SPEC: Record<Frequency, { unit: Unit; step: number; custom: boolean }> = {
  WEEKLY: { unit: 'WEEKS', step: 1, custom: false },
  BIWEEKLY: { unit: 'WEEKS', step: 2, custom: false },
  MONTHLY: { unit: 'MONTHS', step: 1, custom: false },
  QUARTERLY: { unit: 'MONTHS', step: 3, custom: false },
  YEARLY: { unit: 'MONTHS', step: 12, custom: false },
  EVERY_N_DAYS: { unit: 'DAYS', step: 1, custom: true },
  EVERY_N_WEEKS: { unit: 'WEEKS', step: 1, custom: true },
  EVERY_N_MONTHS: { unit: 'MONTHS', step: 1, custom: true },
};

/** Garde-fou contre une periode demesuree. */
const MAX_OCCURRENCES = 10_000;

export function isCustom(f: Frequency): boolean {
  return SPEC[f].custom;
}

function step(f: Frequency, interval: number): number {
  return SPEC[f].step * (SPEC[f].custom ? Math.max(1, interval) : 1);
}

/**
 * n-ieme occurrence (n = 0 : date de depart), toujours calculee depuis la date de
 * depart : 31 janv. -> 28 fevr. -> 31 mars (et non 28 mars).
 */
export function nth(f: Frequency, start: IsoDate, n: number, interval: number): IsoDate {
  const s = step(f, interval) * n;
  switch (SPEC[f].unit) {
    case 'DAYS':
      return plusDays(start, s);
    case 'WEEKS':
      return plusDays(start, s * 7);
    case 'MONTHS':
      return plusMonths(start, s);
  }
}

/** Occurrences comprises dans [from, to] (bornes incluses) ; aucune si la regle est inactive. */
export function occurrences(rule: RecurringRule, from: IsoDate, to: IsoDate): IsoDate[] {
  const result: IsoDate[] = [];
  if (!rule.active || to < from) {
    return result;
  }
  const effectiveTo = rule.endDate && rule.endDate < to ? rule.endDate : to;
  if (effectiveTo < rule.startDate) {
    return result;
  }
  let n = firstIndexNotBefore(rule, from);
  while (result.length < MAX_OCCURRENCES) {
    const date = nth(rule.frequency, rule.startDate, n, rule.interval);
    if (date > effectiveTo) {
      break;
    }
    if (date >= from) {
      result.push(date);
    }
    n++;
  }
  return result;
}

/** Prochaine occurrence a partir de from (inclus), sur 5 ans au plus. */
export function nextOccurrence(rule: RecurringRule, from: IsoDate): IsoDate | undefined {
  return occurrences(rule, from, plusMonths(from, 60))[0];
}

/** Indice de depart estime par le bas, pour ne pas tout reparcourir depuis la date de depart. */
function firstIndexNotBefore(rule: RecurringRule, from: IsoDate): number {
  if (from <= rule.startDate) {
    return 0;
  }
  const unit = SPEC[rule.frequency].unit;
  const elapsed = unit === 'MONTHS' ? monthsBetween(rule.startDate, from)
    : unit === 'WEEKS' ? Math.floor(daysBetween(rule.startDate, from) / 7)
      : daysBetween(rule.startDate, from);
  const estimate = Math.floor(elapsed / step(rule.frequency, rule.interval)) - (unit === 'MONTHS' ? 1 : 0);
  return Math.max(0, estimate);
}

/** Nombre moyen d'occurrences par an (annee de 365,25 jours ; exact pour les mois). */
export function occurrencesPerYear(f: Frequency, interval: number): number {
  const s = step(f, interval);
  switch (SPEC[f].unit) {
    case 'DAYS':
      return 365.25 / s;
    case 'WEEKS':
      return 365.25 / (s * 7);
    case 'MONTHS':
      return 12 / s;
  }
}

/** Montant signe vu du compte source. */
export function signedAmount(rule: RecurringRule): number {
  return rule.type === 'INCOME' ? rule.amount : -rule.amount;
}

/** Equivalent mensuel moyen, arrondi au centime (affichage uniquement). */
export function monthlyEquivalent(rule: RecurringRule): number {
  return Math.round((signedAmount(rule) * occurrencesPerYear(rule.frequency, rule.interval)) / 12);
}
