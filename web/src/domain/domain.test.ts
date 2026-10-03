import { describe, expect, it } from 'vitest';
import { computeAvailable, sectionTotal } from './available';
import { endOfWeek, monthsBetween, plusMonths } from './dates';
import { emptyData } from './defaults';
import { computeForecast } from './forecast';
import { balances, upcoming } from './ledger';
import { divideHalfEven, formatMoney, formatSigned, parseAmount, toEditable } from './money';
import {
  BusinessError, cleanTags, confirmOccurrence, createTransfer, deleteAccount, deleteTransaction, saveAccount,
  saveRule, saveTransaction, skipOccurrence,
} from './operations';
import { nth, occurrences } from './recurrence';
import type { PlannedItem, RecurringRule } from './types';
import { availableFor, dashboard } from './views';

const nbsp = (s: string) => s.replace(/[  ]/g, ' ');

describe('montants', () => {
  it('lit les saisies francaises au centime pres', () => {
    expect(parseAmount('1 234,56')).toBe(123456);
    expect(parseAmount('-12.5')).toBe(-1250);
    expect(parseAmount('12 €')).toBe(1200);
    expect(parseAmount('0,1')).toBe(10);
    expect(parseAmount('12,345')).toBeNull();
    expect(parseAmount('abc')).toBeNull();
  });
  it('affiche toujours le signe', () => {
    expect(nbsp(formatMoney(123456))).toBe('1 234,56 €');
    expect(nbsp(formatSigned(2500))).toBe('+25,00 €');
    expect(nbsp(formatSigned(-2500))).toBe('-25,00 €');
    expect(toEditable(-123456)).toBe('-1234,56');
  });
  it('arrondit au pair comme BigDecimal HALF_EVEN', () => {
    expect(divideHalfEven(5, 2)).toBe(2);
    expect(divideHalfEven(7, 2)).toBe(4);
    expect(divideHalfEven(-5, 2)).toBe(-2);
    expect(divideHalfEven(10, 3)).toBe(3);
  });
});

describe('dates', () => {
  it('ramene a la fin du mois', () => {
    expect(plusMonths('2026-01-31', 1)).toBe('2026-02-28');
    expect(plusMonths('2028-01-31', 1)).toBe('2028-02-29');
    expect(plusMonths('2026-12-15', 2)).toBe('2027-02-15');
    expect(monthsBetween('2026-01-31', '2026-02-28')).toBe(0);
    expect(endOfWeek('2026-10-01')).toBe('2026-10-04');
    expect(endOfWeek('2026-10-04')).toBe('2026-10-04');
  });
});

function rule(partial: Partial<RecurringRule>): RecurringRule {
  return {
    id: 1, accountId: 1, type: 'EXPENSE', label: 'Loyer', amount: 70000, categoryId: null, frequency: 'MONTHLY',
    interval: 1, startDate: '2026-01-31', trackedFrom: '2026-01-31', certain: true, active: true, tags: [],
    ...partial,
  };
}

describe('recurrences', () => {
  it('ne derive pas en fin de mois', () => {
    expect(occurrences(rule({}), '2026-01-01', '2026-05-31'))
      .toEqual(['2026-01-31', '2026-02-28', '2026-03-31', '2026-04-30', '2026-05-31']);
  });
  it('gere les intervalles et la date de fin', () => {
    const r = rule({ frequency: 'EVERY_N_WEEKS', interval: 2, startDate: '2026-10-01', endDate: '2026-11-01' });
    expect(occurrences(r, '2026-10-01', '2026-12-31')).toEqual(['2026-10-01', '2026-10-15', '2026-10-29']);
    expect(nth('YEARLY', '2024-02-29', 1, 1)).toBe('2025-02-28');
    expect(occurrences(rule({ active: false }), '2026-01-01', '2026-12-31')).toEqual([]);
  });
  it('saute directement pres de la periode demandee', () => {
    const r = rule({ startDate: '1990-01-31' });
    expect(occurrences(r, '2026-02-01', '2026-03-31')).toEqual(['2026-02-28', '2026-03-31']);
  });
});

const item = (account: number, day: number, label: string, amount: number, type: PlannedItem['type'],
  other?: number): PlannedItem => ({
  date: `2026-10-${String(day).padStart(2, '0')}`, accountId: account, label, amount, type, categoryId: null,
  source: 'RECURRING', ruleId: 1, transferAccountId: other, certain: true,
});

describe('disponible reel (scenarios du cahier des charges)', () => {
  const base = { today: '2026-10-01', horizonEnd: '2026-10-31' };
  it('1000 + 1800 - 700 - 200 - 300 = 1600', () => {
    const planned = [item(1, 5, 'Loyer + charges', -70000, 'EXPENSE'), item(1, 10, 'Crédit auto', -20000, 'EXPENSE'),
      item(1, 28, 'Salaire', 180000, 'INCOME')];
    const input = { ...base, balances: [{ accountId: 1, name: 'Compte courant', balance: 100000 }], planned,
      reservations: [{ label: 'Courses', amount: 30000 }] };
    const withIncome = computeAvailable({ ...input, includeCertainIncome: true });
    expect(withIncome.available).toBe(160000);
    expect(withIncome.availableBeforeReservations).toBe(190000);
    expect(sectionTotal(withIncome, 'PLANNED_EXPENSES')).toBe(-90000);
    const withoutIncome = computeAvailable({ ...input, includeCertainIncome: false });
    expect(withoutIncome.available).toBe(-20000);
    const excluded = withoutIncome.sections.find((s) => s.kind === 'EXCLUDED_INCOME')!;
    expect(excluded.counted).toBe(false);
    expect(excluded.total).toBe(180000);
  });
  it('1420 - 934 - 300 - 100 = 86 (virement vers l\'epargne)', () => {
    const planned = [item(1, 3, 'Loyer', -65000, 'EXPENSE'), item(1, 5, 'Assurance', -9000, 'EXPENSE'),
      item(1, 10, 'Crédit', -19400, 'EXPENSE'), item(1, 2, 'Virement', -10000, 'TRANSFER', 3)];
    const r = computeAvailable({ ...base, balances: [{ accountId: 1, name: 'Compte courant', balance: 142000 }],
      planned, reservations: [{ label: 'Carburant', amount: 20000 }, { label: 'Courses', amount: 10000 }],
      includeCertainIncome: true });
    expect(r.available).toBe(8600);
  });
  it('un virement entre deux comptes du perimetre est neutre', () => {
    const r = computeAvailable({ ...base, balances: [{ accountId: 1, name: 'A', balance: 1000 },
      { accountId: 2, name: 'B', balance: 1000 }],
    planned: [item(1, 5, 'V', -500, 'TRANSFER', 2), item(2, 5, 'V', 500, 'TRANSFER', 1)], reservations: [],
    includeCertainIncome: true });
    expect(r.available).toBe(2000);
  });
});

describe('prevision', () => {
  it('reconstruit l\'historique et projette les operations a venir', () => {
    const f = computeForecast({
      scope: new Set([1]), currentBalance: 100000, today: '2026-10-10', historyFrom: '2026-10-08',
      realized: [{ id: 1, accountId: 1, date: '2026-10-09', label: 'Courses', amount: -5000, type: 'EXPENSE',
        status: 'COMPLETED', categoryId: null, tags: [] }],
      until: '2026-10-12',
      planned: [item(1, 5, 'Retard', -1000, 'EXPENSE'), item(1, 12, 'Loyer', -200000, 'EXPENSE')],
    });
    expect(f.history.map((p) => p.balance)).toEqual([105000, 100000, 100000]);
    expect(f.projection.map((p) => p.balance)).toEqual([99000, 99000, -101000]);
    expect(f.firstNegative?.date).toBe('2026-10-12');
    expect(f.lowest.balance).toBe(-101000);
  });
});

describe('operations', () => {
  it('soldes, virements neutres, suppression', () => {
    const data = emptyData();
    const checking = saveAccount(data, { name: 'Courant', type: 'CHECKING', initialBalance: 100000,
      openingDate: '2026-01-01', includeInAvailable: true });
    const livret = saveAccount(data, { name: 'Livret A', type: 'LIVRET_A', initialBalance: 0,
      openingDate: '2026-01-01', includeInAvailable: false });
    saveTransaction(data, { accountId: checking.id, date: '2026-10-02', label: 'Courses', amount: 4550,
      type: 'EXPENSE', status: 'COMPLETED', categoryId: null, tags: ['Maison', 'maison', ' vacances '] });
    saveTransaction(data, { accountId: checking.id, date: '2026-10-20', label: 'Prévu', amount: 9900,
      type: 'EXPENSE', status: 'PLANNED', categoryId: null, tags: [] });
    const [out] = createTransfer(data, { fromAccountId: checking.id, toAccountId: livret.id, date: '2026-10-03',
      label: 'Épargne', amount: 10000, status: 'COMPLETED' });
    let b = balances(data);
    expect(b.get(checking.id)).toBe(100000 - 4550 - 10000);
    expect(b.get(livret.id)).toBe(10000);
    expect(data.transactions[0].tags).toEqual(['Maison', 'vacances']);
    expect(() => deleteAccount(data, livret.id)).toThrow(BusinessError);
    deleteTransaction(data, out.id);
    b = balances(data);
    expect(b.get(livret.id)).toBe(0);
    expect(data.transactions).toHaveLength(2);
    expect(() => saveTransaction(data, { accountId: checking.id, date: '2026-13-01', label: 'X', amount: 1,
      type: 'EXPENSE', status: 'COMPLETED', categoryId: null, tags: [] })).toThrow('date');
    expect(() => cleanTags(['x'.repeat(31)])).toThrow(BusinessError);
  });

  it('recurrences : a venir, validation, refus du doublon, ignorer', () => {
    const data = emptyData();
    const a = saveAccount(data, { name: 'Courant', type: 'CHECKING', initialBalance: 100000,
      openingDate: '2026-01-01', includeInAvailable: true });
    const r = saveRule(data, { accountId: a.id, type: 'EXPENSE', label: 'Loyer', amount: 65000, categoryId: null,
      frequency: 'MONTHLY', interval: 1, startDate: '2026-10-05', certain: true, active: true, tags: ['maison'] },
    '2026-10-01');
    expect(upcoming(data, '2026-10-01', '2026-11-30').map((i) => i.date)).toEqual(['2026-10-05', '2026-11-05']);
    const [t] = confirmOccurrence(data, r.id, '2026-10-05', '2026-10-06', 66000);
    expect(t.amount).toBe(-66000);
    expect(t.tags).toEqual(['maison']);
    expect(() => confirmOccurrence(data, r.id, '2026-10-05', '2026-10-06', 66000)).toThrow('déjà');
    expect(() => confirmOccurrence(data, r.id, '2026-10-07', '2026-10-07', 1)).toThrow('occurrence');
    skipOccurrence(data, r.id, '2026-11-05');
    expect(upcoming(data, '2026-10-01', '2026-11-30')).toEqual([]);
    expect(balances(data).get(a.id)).toBe(100000 - 66000);
  });

  it('disponible reel de bout en bout, prochaine paie', () => {
    const data = emptyData();
    const a = saveAccount(data, { name: 'Courant', type: 'CHECKING', initialBalance: 100000,
      openingDate: '2026-01-01', includeInAvailable: true });
    saveRule(data, { accountId: a.id, type: 'INCOME', label: 'Salaire', amount: 180000, categoryId: null,
      frequency: 'MONTHLY', interval: 1, startDate: '2026-10-28', certain: true, active: true, tags: [] }, '2026-10-01');
    saveRule(data, { accountId: a.id, type: 'EXPENSE', label: 'Loyer', amount: 70000, categoryId: null,
      frequency: 'MONTHLY', interval: 1, startDate: '2026-10-05', certain: true, active: true, tags: [] }, '2026-10-01');
    expect(availableFor(data, '2026-10-01', 'END_OF_MONTH').result.available).toBe(100000 - 70000 + 180000);
    const payday = availableFor(data, '2026-10-01', 'NEXT_PAYDAY');
    expect(payday.horizon.payday).toBe('2026-10-28');
    expect(payday.horizon.end).toBe('2026-10-27');
    expect(payday.result.available).toBe(30000);
    const d = dashboard(data, '2026-10-01');
    expect(d.netWorth).toBe(100000);
    expect(d.next.map((i) => i.label)).toEqual(['Loyer', 'Salaire']);
  });
});
