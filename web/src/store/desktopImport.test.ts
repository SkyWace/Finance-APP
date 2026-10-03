import 'fake-indexeddb/auto';
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { balances } from '../domain/ledger';
import type { FinanceData } from '../domain/types';
import { dashboard } from '../domain/views';
import { importBackup, recover, unlock } from './vault';

/**
 * Fichier produit par l'application desktop (Parametres > Exporter pour la version web),
 * avec la cryptographie Java : il doit s'ouvrir ici avec WebCrypto.
 */
const FIXTURE = readFileSync(new URL('./fixtures/desktop-export.json', import.meta.url), 'utf8');
const PASSWORD = 'Épargne sûre 2026 €';
const RECOVERY_KEY = '84RW-BFHU-GBMZ-AMR5-CNYR-CS6T-F8LA-PXGT';

describe('import d\'un export desktop', () => {
  it('s\'ouvre avec le mot de passe choisi sur le desktop, soldes identiques', async () => {
    const profile = await importBackup(FIXTURE);
    expect(profile.name).toBe('Profil principal');
    const opened = await unlock<FinanceData>(profile.id, PASSWORD);
    const data = opened.data;
    const byName = new Map(data.accounts.map((a) => [a.name, a]));
    const b = balances(data);
    expect(b.get(byName.get('Compte courant')!.id)).toBe(78000);
    expect(b.get(byName.get('Livret A')!.id)).toBe(530000);
    expect(byName.has('Compte suisse')).toBe(false);
    const courses = data.transactions.find((t) => t.label === 'Courses "Bio"')!;
    expect(courses.amount).toBe(-12000);
    expect(courses.tags).toEqual(['vacances']);
    expect(courses.note).toContain('Ventilée : Nourriture test (90,00) + Maison test (30,00)');
    expect(data.categories.find((c) => c.name === 'Logement')!.parentId).toBeNull();
    expect(data.rules[0].tags).toEqual(['vacances']);
    expect(data.transactions.filter((t) => t.type === 'TRANSFER')).toHaveLength(2);
    expect(data.nextId).toBeGreaterThan(Math.max(...data.transactions.map((t) => t.id)));
    expect(() => dashboard(data, '2026-10-03')).not.toThrow();
    expect(dashboard(data, '2026-10-03').netWorth).toBe(78000 + 530000);
  });

  it('la cle de recuperation affichee par le desktop fonctionne aussi', async () => {
    const id = JSON.parse(FIXTURE).profile.id as string;
    const reopened = await recover<FinanceData>(id, RECOVERY_KEY.toLowerCase(), 'nouveau mot de passe web');
    expect(reopened.data.accounts).toHaveLength(2);
  });
});
