import type { IsoDate } from './dates';
import type { Cents } from './money';
import type { PlannedItem } from './types';

export type SectionKind =
  | 'CURRENT_BALANCE' | 'PLANNED_EXPENSES' | 'SAVINGS_TRANSFERS' | 'RESERVATIONS' | 'EXPECTED_INCOME'
  | 'EXCLUDED_INCOME';

export const SECTION_TITLES: Record<SectionKind, string> = {
  CURRENT_BALANCE: 'Solde actuel',
  PLANNED_EXPENSES: 'Dépenses prévues',
  SAVINGS_TRANSFERS: 'Épargne et virements prévus',
  RESERVATIONS: 'Budgets et objectifs réservés',
  EXPECTED_INCOME: 'Revenus prévus',
  EXCLUDED_INCOME: 'Revenus non comptés',
};

const ORDER: SectionKind[] = ['CURRENT_BALANCE', 'PLANNED_EXPENSES', 'SAVINGS_TRANSFERS', 'RESERVATIONS',
  'EXPECTED_INCOME', 'EXCLUDED_INCOME'];

export interface Line {
  label: string;
  date?: IsoDate;
  amount: Cents;
  overdue: boolean;
}

export interface Section {
  kind: SectionKind;
  total: Cents;
  /** false : section informative, non comptee dans le resultat. */
  counted: boolean;
  lines: Line[];
}

export interface AvailableResult {
  today: IsoDate;
  horizonEnd: IsoDate;
  available: Cents;
  availableBeforeReservations: Cents;
  sections: Section[];
}

export interface AvailableInput {
  today: IsoDate;
  horizonEnd: IsoDate;
  balances: { accountId: number; name: string; balance: Cents }[];
  planned: PlannedItem[];
  reservations: { label: string; amount: Cents }[];
  includeCertainIncome: boolean;
}

/**
 * Disponible reel :
 *   solde actuel des comptes du perimetre
 * - depenses prevues jusqu'a l'echeance (retards compris)
 * +/- virements prevus avec des comptes hors perimetre (epargne)
 * - reservations
 * + revenus prevus juges certains (si l'option est active)
 * Un virement entre deux comptes du perimetre est neutre. Chaque montant figure
 * dans une ligne : le resultat est entierement explicable.
 */
export function computeAvailable(input: AvailableInput): AvailableResult {
  const scope = new Set(input.balances.map((b) => b.accountId));
  const lines = new Map<SectionKind, Line[]>();
  const add = (kind: SectionKind, line: Line) => lines.set(kind, [...(lines.get(kind) ?? []), line]);

  for (const b of input.balances) {
    add('CURRENT_BALANCE', { label: b.name, amount: b.balance, overdue: false });
  }
  for (const item of input.planned) {
    if (!scope.has(item.accountId) || item.date > input.horizonEnd) {
      continue;
    }
    const line: Line = { label: item.label, date: item.date, amount: item.amount, overdue: item.date < input.today };
    if (item.type === 'EXPENSE') {
      add('PLANNED_EXPENSES', line);
    } else if (item.type === 'TRANSFER') {
      if (item.transferAccountId === undefined || !scope.has(item.transferAccountId)) {
        add('SAVINGS_TRANSFERS', line);
      }
    } else {
      add(input.includeCertainIncome && item.certain ? 'EXPECTED_INCOME' : 'EXCLUDED_INCOME', line);
    }
  }
  for (const r of input.reservations) {
    if (r.amount !== 0) {
      add('RESERVATIONS', { label: r.label, amount: -r.amount, overdue: false });
    }
  }

  const sections: Section[] = [];
  let available = 0;
  let reservations = 0;
  for (const kind of ORDER) {
    const sectionLines = [...(lines.get(kind) ?? [])];
    if (sectionLines.length === 0 && kind !== 'CURRENT_BALANCE') {
      continue;
    }
    sectionLines.sort((a, b) => (a.date ?? '').localeCompare(b.date ?? ''));
    const total = sectionLines.reduce((s, l) => s + l.amount, 0);
    const counted = kind !== 'EXCLUDED_INCOME';
    sections.push({ kind, total, counted, lines: sectionLines });
    if (counted) {
      available += total;
    }
    if (kind === 'RESERVATIONS') {
      reservations = total;
    }
  }
  return {
    today: input.today, horizonEnd: input.horizonEnd, available,
    availableBeforeReservations: available - reservations, sections,
  };
}

export function sectionTotal(result: AvailableResult, kind: SectionKind): Cents {
  return result.sections.find((s) => s.kind === kind)?.total ?? 0;
}
