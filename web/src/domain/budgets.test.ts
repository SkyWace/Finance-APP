import { describe, expect, it } from 'vitest';
import { budgetReservation, divideMoney, goalProgressOf, reservations } from './budgets';
import { emptyData } from './defaults';
import { addContribution, deleteAccount, deleteCategory, saveAccount, saveBudget, saveGoal } from './operations';
import type { SavingsGoal } from './types';

const code = (data: ReturnType<typeof emptyData>, c: string) => data.categories.find((x) => x.code === c)!.id;

describe('budgets et objectifs', () => {
  it('arrondit comme Money.divide du desktop (double arrondi HALF_EVEN)', () => {
    expect(divideMoney(175000, 15)).toBe(11667);
    expect(divideMoney(1, 2)).toBe(0); // 0,005 -> 0,00 (pair)
    expect(divideMoney(3, 2)).toBe(2); // 0,015 -> 0,02 (pair)
    expect(divideMoney(-175000, 15)).toBe(-11667);
  });

  it("exemple du cahier des charges : 1 750 EUR sur 15 mois = 116,67 EUR par mois", () => {
    const goal: SavingsGoal = { id: 1, name: 'Voyage', target: 500000, targetDate: '2027-12-15', manualSaved: 325000,
      reserveInAvailable: true, archived: false };
    const p = goalProgressOf(goal, 325000, '2026-09-10');
    expect(p.monthsLeft).toBe(15);
    expect(p.monthlyNeeded).toBe(11667);
    expect(p.permille).toBe(650);
  });

  it("un budget depasse ne reserve rien ; le reste est proratise", () => {
    const budget = { id: 1, categoryId: 1, limit: 31000, reserveInAvailable: true, active: true };
    expect(budgetReservation(budget, 40000, new Map(), '2026-10-01', '2026-10-31')).toBe(0);
    // 310 EUR, rien depense, du 1er au 10 octobre (10 jours sur 31) : 100 EUR
    expect(budgetReservation(budget, 0, new Map(), '2026-10-01', '2026-10-10')).toBe(10000);
    expect(budgetReservation(budget, 0, new Map(), '2026-10-01', '2026-09-30')).toBe(0);
  });

  it('refuse les saisies incoherentes', () => {
    const data = emptyData();
    expect(() => saveBudget(data, { categoryId: code(data, 'INCOME.SALARY'), limit: 100, reserveInAvailable: true }))
      .toThrow('catégorie de dépenses');
    expect(() => saveBudget(data, { categoryId: code(data, 'FOOD'), limit: 0, reserveInAvailable: true }))
      .toThrow('strictement positif');
    saveBudget(data, { categoryId: code(data, 'FOOD'), limit: 40000, reserveInAvailable: true });
    expect(() => saveBudget(data, { categoryId: code(data, 'FOOD'), limit: 100, reserveInAvailable: false }))
      .toThrow('déjà un budget');
    expect(() => deleteCategory(data, code(data, 'FOOD'))).toThrow();

    const livret = saveAccount(data, { name: 'Livret', type: 'LIVRET_A', initialBalance: 100000,
      openingDate: '2026-01-01', includeInAvailable: false });
    const linked = saveGoal(data, { name: 'Urgence', target: 300000, linkedAccountId: livret.id, manualSaved: 0,
      reserveInAvailable: false });
    expect(() => addContribution(data, linked.id, 1000)).toThrow('virement');
    expect(() => deleteAccount(data, livret.id)).toThrow('objectif');
    const manual = saveGoal(data, { name: 'Vacances', target: 100000, manualSaved: 2000, reserveInAvailable: false });
    expect(() => addContribution(data, manual.id, -5000)).toThrow('négatif');
    addContribution(data, manual.id, 3000);
    expect(data.goals.find((g) => g.id === manual.id)!.manualSaved).toBe(5000);
  });

  it("le disponible n'inclut que les budgets et objectifs marques a reserver", () => {
    const data = emptyData();
    saveBudget(data, { categoryId: code(data, 'FOOD'), limit: 31000, reserveInAvailable: false });
    saveGoal(data, { name: 'Libre', target: 100000, targetDate: '2027-10-31', manualSaved: 0, reserveInAvailable: false });
    expect(reservations(data, '2026-10-01', '2026-10-31')).toEqual([]);
    saveBudget(data, { categoryId: code(data, 'TRANSPORT'), limit: 31000, reserveInAvailable: true });
    expect(reservations(data, '2026-10-01', '2026-10-31')).toEqual([{ label: 'Budget Transport (reste)', amount: 31000 }]);
  });
});
