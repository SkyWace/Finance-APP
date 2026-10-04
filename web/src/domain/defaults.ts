import type { Category, CategoryKind, FinanceData } from './types';

const TREE: [string, CategoryKind, string, [string, string][]][] = [
  ['Logement', 'EXPENSE', 'HOUSING', [['Loyer', 'RENT'], ['Électricité', 'ELECTRICITY'], ['Gaz', 'GAS'],
    ['Eau', 'WATER'], ['Internet', 'INTERNET']]],
  ['Transport', 'EXPENSE', 'TRANSPORT', [['Carburant', 'FUEL'], ['Assurance', 'INSURANCE'],
    ['Entretien', 'MAINTENANCE'], ['Péage', 'TOLL'], ['Parking', 'PARKING'], ['Crédit véhicule', 'CAR_LOAN']]],
  ['Alimentation', 'EXPENSE', 'FOOD', [['Courses', 'GROCERIES'], ['Restaurant', 'RESTAURANT'],
    ['Fast-food', 'FAST_FOOD']]],
  ['Abonnements', 'EXPENSE', 'SUBSCRIPTIONS', [['Streaming', 'STREAMING'], ['Musique', 'MUSIC'],
    ['Téléphone', 'PHONE'], ['Sport', 'SPORT']]],
  ['Loisirs', 'EXPENSE', 'LEISURE', []],
  ['Santé', 'EXPENSE', 'HEALTH', []],
  ['Shopping', 'EXPENSE', 'SHOPPING', []],
  ['Épargne', 'BOTH', 'SAVINGS', []],
  ['Revenus', 'INCOME', 'INCOME', [['Salaire', 'SALARY'], ['Remboursement', 'REFUND'],
    ['Autres revenus', 'OTHER_INCOME']]],
  ['Autres', 'BOTH', 'OTHER', []],
];

/** Donnees d'un nouveau profil : reglages par defaut et categories usuelles (comme l'application desktop). */
export function emptyData(): FinanceData {
  const categories: Category[] = [];
  let id = 1;
  for (const [name, kind, code, children] of TREE) {
    const parentId = id++;
    categories.push({ id: parentId, parentId: null, name, kind, archived: false, code });
    for (const [child, childCode] of children) {
      categories.push({ id: id++, parentId, name: child, kind, archived: false, code: `${code}.${childCode}` });
    }
  }
  return {
    version: 1,
    nextId: id,
    settings: { defaultHorizon: 'END_OF_MONTH', includeCertainIncome: true, autoLockMinutes: 5, privacy: false },
    accounts: [],
    categories,
    transactions: [],
    rules: [],
    budgets: [],
    goals: [],
  };
}

/**
 * Complete les donnees d'une version anterieure (ou d'un export d'une ancienne version
 * desktop) : champs ajoutes depuis, avec leur valeur par defaut.
 */
export function upgradeData(data: FinanceData): FinanceData {
  const base = emptyData();
  return {
    ...data,
    settings: { ...base.settings, ...data.settings },
    rules: data.rules.map((r) => ({ ...r, tags: r.tags ?? [] })),
    transactions: data.transactions.map((t) => ({ ...t, tags: t.tags ?? [] })),
    budgets: data.budgets ?? [],
    goals: data.goals ?? [],
  };
}
