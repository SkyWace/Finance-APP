import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { sectionTotal } from './available';
import { budgetProgress, goalProgress } from './budgets';
import type { FinanceData, HorizonType } from './types';
import { availableFor } from './views';

/**
 * Donnees exportees par l'application desktop et resultats calcules par elle
 * (WebParityTest) : le site doit trouver exactement les memes montants.
 */
interface Expected {
  today: string;
  horizons: { type: HorizonType; custom?: string; horizonEnd: string; available: number;
    availableBeforeReservations: number; reservations: { label: string; amount: number }[] }[];
  budgets: { month: string; category: string; spent: number; planned: number; remaining: number; permille: number;
    status: string }[];
  goals: { name: string; saved: number; remaining: number; permille: number; monthsLeft?: number;
    monthlyNeeded?: number; reached: boolean; overdue: boolean }[];
}

const fixture = JSON.parse(readFileSync(new URL('./fixtures/desktop-parity.json', import.meta.url), 'utf8')) as
  { data: FinanceData; expected: Expected };
const { data, expected } = fixture;

describe('memes resultats que le desktop', () => {
  it.each(expected.horizons.map((h) => [`${h.type} ${h.custom ?? ''}`, h] as const))(
    'disponible reel : %s', (_name, h) => {
      const { horizon, result } = availableFor(data, expected.today, h.type, h.custom);
      expect(horizon.end).toBe(h.horizonEnd);
      const reservations = result.sections.find((s) => s.kind === 'RESERVATIONS')?.lines ?? [];
      expect(reservations.map((l) => ({ label: l.label, amount: l.amount }))).toEqual(h.reservations);
      expect(result.availableBeforeReservations).toBe(h.availableBeforeReservations);
      expect(result.available).toBe(h.available);
      expect(sectionTotal(result, 'RESERVATIONS')).toBe(h.available - h.availableBeforeReservations);
    });

  it('budgets du mois (passe, en cours, a venir)', () => {
    const months = [...new Set(expected.budgets.map((b) => b.month))];
    const actual = months.flatMap((month) => budgetProgress(data, month, expected.today).map((p) => ({
      month, category: p.categoryName, spent: p.spent, planned: p.planned, remaining: p.remaining,
      permille: p.permille, status: p.status,
    })));
    expect(actual).toEqual(expected.budgets);
  });

  it('objectifs d\'epargne', () => {
    const actual = goalProgress(data, expected.today).map((p) => {
      const o: Expected['goals'][number] = { name: p.goal.name, saved: p.saved, remaining: p.remaining,
        permille: p.permille, reached: p.reached, overdue: p.overdue };
      if (p.monthsLeft !== undefined) {
        o.monthsLeft = p.monthsLeft;
      }
      if (p.monthlyNeeded !== undefined) {
        o.monthlyNeeded = p.monthlyNeeded;
      }
      return o;
    });
    expect(actual).toEqual(expected.goals);
  });
});
