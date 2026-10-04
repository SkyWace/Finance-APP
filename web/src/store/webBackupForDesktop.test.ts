import 'fake-indexeddb/auto';
import { readFileSync, writeFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { upgradeData } from '../domain/defaults';
import { balances } from '../domain/ledger';
import {
  confirmOccurrence, createTransfer, saveAccount, saveCategory, saveTransaction, skipOccurrence,
} from '../domain/operations';
import type { FinanceData } from '../domain/types';
import { availableFor } from '../domain/views';
import { createProfile, exportBackup, importBackup, unlock } from './vault';

/**
 * Sauvegarde du site destinee au test d'import de l'application desktop (WebImportTest) :
 * les donnees exportees par le desktop (WebParityTest), puis des saisies faites sur le site.
 * Regenerer : WRITE_FIXTURE=1 npx vitest run src/store/webBackupForDesktop.test.ts
 */
const FIXTURE = new URL('./fixtures/web-backup-for-desktop.json', import.meta.url);
const PASSWORD = 'Saisies du téléphone 2026';
const TODAY = '2026-10-13';

function scenario(): FinanceData {
  const parity = JSON.parse(readFileSync(new URL('../domain/fixtures/desktop-parity.json', import.meta.url), 'utf8'));
  const d = upgradeData(parity.data as FinanceData);
  const tx = (id: number) => d.transactions.find((t) => t.id === id)!;
  // Deux achats identiques le meme jour : tous deux nouveaux
  for (let i = 0; i < 2; i++) {
    saveTransaction(d, { accountId: 1, date: '2026-10-12', label: 'Boulangerie', amount: 480, type: 'EXPENSE',
      status: 'COMPLETED', categoryId: 33, tags: ['web'] });
  }
  const pets = saveCategory(d, 'Animaux test', null, 'EXPENSE');
  saveTransaction(d, { accountId: 2, date: '2026-10-10', label: 'Vétérinaire', amount: 6500, type: 'EXPENSE',
    status: 'COMPLETED', categoryId: pets.id, tags: [] });
  confirmOccurrence(d, 1, '2026-10-09', '2026-10-10', 4410); // Courses hebdo
  skipOccurrence(d, 2, '2026-11-05'); // Loyer de novembre ignore
  Object.assign(tx(6), { status: 'COMPLETED', date: '2026-10-12' }); // Traiteur prevu, realise
  tx(5).label = 'Station Total'; // operation du desktop modifiee sur le site
  createTransfer(d, { fromAccountId: 1, toAccountId: 3, date: '2026-10-12', label: 'Épargne web', amount: 5000,
    status: 'COMPLETED' });
  const cash = saveAccount(d, { name: 'Espèces', type: 'CASH', initialBalance: 4000, openingDate: '2026-10-01',
    includeInAvailable: true });
  saveTransaction(d, { accountId: cash.id, date: '2026-10-11', label: 'Fleurs', amount: 1200, type: 'EXPENSE',
    status: 'COMPLETED', categoryId: null, tags: [] });
  saveTransaction(d, { accountId: 1, date: '2026-10-20', label: 'Coiffeur', amount: 2500, type: 'EXPENSE',
    status: 'PLANNED', categoryId: 37, tags: [] });
  return d;
}

function expectations(d: FinanceData) {
  const b = balances(d);
  return {
    today: TODAY,
    balances: Object.fromEntries(d.accounts.map((a) => [a.name, b.get(a.id) ?? 0])),
    endOfMonth: availableFor(d, TODAY, 'END_OF_MONTH').result.available,
    custom: availableFor(d, TODAY, 'CUSTOM_DATE', '2026-11-20').result.available,
  };
}

describe('sauvegarde du site pour le desktop', () => {
  it('fixture a jour et lisible', async () => {
    const data = scenario();
    if (process.env.WRITE_FIXTURE) {
      const { vault } = await createProfile('Téléphone', PASSWORD, data);
      writeFileSync(FIXTURE, JSON.stringify({ password: PASSWORD, expected: expectations(data),
        backup: JSON.parse(await exportBackup(vault.id)) }, null, 1) + '\n');
      return; // profil deja present dans ce navigateur de test : relecture au prochain lancement
    }
    const fixture = JSON.parse(readFileSync(FIXTURE, 'utf8'));
    const profile = await importBackup(JSON.stringify(fixture.backup));
    const opened = await unlock<FinanceData>(profile.id, fixture.password);
    expect(expectations(opened.data)).toEqual(fixture.expected);
    expect(expectations(data)).toEqual(fixture.expected);
  });
});
