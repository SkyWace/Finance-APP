import { describe, expect, it } from 'vitest';
import { exportCsv, isDuplicate, parseCsv } from './csv';
import { emptyData } from './defaults';
import { saveAccount, saveTransaction } from './operations';

describe('import CSV', () => {
  it('lit un releve avec en-tete, montants francais et lignes illisibles', () => {
    const r = parseCsv('﻿Date;Libellé;Montant\n31/10/2026;CB CARREFOUR;-45,50\n01/11/2026;"VIR ""SALAIRE""";1 800,00\n'
      + 'demain;X;1\n02/11/2026;Rien;0\n');
    expect(r.rows).toEqual([
      { date: '2026-10-31', label: 'CB CARREFOUR', amount: -4550 },
      { date: '2026-11-01', label: 'VIR "SALAIRE"', amount: 180000 },
    ]);
    expect(r.rejected.map((x) => x.line)).toEqual([4, 5]);
  });
  it('lit les colonnes Debit / Credit et le separateur virgule', () => {
    const r = parseCsv('date,description,debit,credit\n2026-10-02,Loyer,650.00,\n2026-10-03,Remboursement,,25.00\n');
    expect(r.rows.map((x) => x.amount)).toEqual([-65000, 2500]);
  });
  it('reconnait les operations deja presentes', () => {
    const data = emptyData();
    const a = saveAccount(data, { name: 'C', type: 'CHECKING', initialBalance: 0, openingDate: '2026-01-01',
      includeInAvailable: true });
    saveTransaction(data, { accountId: a.id, date: '2026-10-31', label: 'CB Carrefour', amount: 4550, type: 'EXPENSE',
      status: 'COMPLETED', categoryId: null, tags: [] });
    expect(isDuplicate(data, a.id, { date: '2026-10-31', label: 'CB CARREFOUR', amount: -4550 })).toBe(true);
    expect(isDuplicate(data, a.id, { date: '2026-10-31', label: 'CB CARREFOUR', amount: -4551 })).toBe(false);
  });
});

describe('export CSV', () => {
  it('neutralise les formules de tableur et garde les montants negatifs', () => {
    const data = emptyData();
    const a = saveAccount(data, { name: 'Courant', type: 'CHECKING', initialBalance: 0, openingDate: '2026-01-01',
      includeInAvailable: true });
    const t = saveTransaction(data, { accountId: a.id, date: '2026-10-31', label: '=HYPERLINK("http://x")', amount: 1234,
      type: 'EXPENSE', status: 'COMPLETED', categoryId: null, tags: ['a', 'b'] });
    const csv = exportCsv(data, [t]);
    expect(csv.startsWith('﻿Date;Compte;Libellé')).toBe(true);
    expect(csv).toContain(`"'=HYPERLINK(""http://x"")"`);
    expect(csv).toContain(';-12,34;');
  });
});
