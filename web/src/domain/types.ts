import type { Cents } from './money';
import type { IsoDate } from './dates';

export type AccountGroup = 'CURRENT' | 'SAVINGS' | 'CASH' | 'OTHER';

export interface AccountTypeInfo {
  label: string;
  group: AccountGroup;
  /** Inclus dans le disponible reel a la creation. */
  includedByDefault: boolean;
  /** Plafond de versements reglementaire, en centimes. */
  ceiling?: Cents;
}

export const ACCOUNT_TYPES = {
  CHECKING: { label: 'Compte courant', group: 'CURRENT', includedByDefault: true },
  JOINT: { label: 'Compte joint', group: 'CURRENT', includedByDefault: true },
  CASH: { label: 'Espèces', group: 'CASH', includedByDefault: true },
  PREPAID_CARD: { label: 'Carte prépayée', group: 'CASH', includedByDefault: true },
  LIVRET_A: { label: 'Livret A', group: 'SAVINGS', includedByDefault: false, ceiling: 2_295_000 },
  LDDS: { label: 'LDDS (développement durable et solidaire)', group: 'SAVINGS', includedByDefault: false, ceiling: 1_200_000 },
  LEP: { label: 'LEP (épargne populaire)', group: 'SAVINGS', includedByDefault: false, ceiling: 1_000_000 },
  LIVRET_JEUNE: { label: 'Livret Jeune', group: 'SAVINGS', includedByDefault: false, ceiling: 160_000 },
  CEL: { label: 'CEL (compte épargne logement)', group: 'SAVINGS', includedByDefault: false, ceiling: 1_530_000 },
  PASSBOOK: { label: 'Livret bancaire', group: 'SAVINGS', includedByDefault: false },
  SAVINGS: { label: 'Compte épargne', group: 'SAVINGS', includedByDefault: false },
  PEL: { label: 'PEL (plan épargne logement)', group: 'SAVINGS', includedByDefault: false, ceiling: 6_120_000 },
  LIFE_INSURANCE: { label: 'Assurance-vie', group: 'SAVINGS', includedByDefault: false },
  PEA: { label: "PEA (plan d'épargne en actions)", group: 'SAVINGS', includedByDefault: false },
  PER: { label: "PER (plan d'épargne retraite)", group: 'SAVINGS', includedByDefault: false },
  EMPLOYEE_SAVINGS: { label: 'Épargne salariale (PEE, PERCOL…)', group: 'SAVINGS', includedByDefault: false },
  SECURITIES: { label: 'Compte-titres', group: 'SAVINGS', includedByDefault: false },
  CRYPTO: { label: 'Crypto-actifs', group: 'SAVINGS', includedByDefault: false },
  OTHER: { label: 'Autre', group: 'OTHER', includedByDefault: false },
} as const satisfies Record<string, AccountTypeInfo>;

export type AccountType = keyof typeof ACCOUNT_TYPES;

export interface Account {
  id: number;
  name: string;
  type: AccountType;
  initialBalance: Cents;
  openingDate: IsoDate;
  includeInAvailable: boolean;
  archived: boolean;
  color?: string;
}

export type CategoryKind = 'EXPENSE' | 'INCOME' | 'BOTH';

export interface Category {
  id: number;
  parentId: number | null;
  name: string;
  kind: CategoryKind;
  archived: boolean;
  /** Identifiant stable des categories par defaut (meme renommees). */
  code?: string;
}

export type TransactionType = 'EXPENSE' | 'INCOME' | 'TRANSFER';
export type TransactionStatus = 'PLANNED' | 'PENDING' | 'COMPLETED' | 'CANCELLED';

export const STATUS_LABELS: Record<TransactionStatus, string> = {
  PLANNED: 'Prévu',
  PENDING: 'En attente',
  COMPLETED: 'Effectué',
  CANCELLED: 'Annulé',
};

/** Effectue et en attente : comptes dans le solde. */
export function countsInBalance(status: TransactionStatus): boolean {
  return status === 'PENDING' || status === 'COMPLETED';
}

/**
 * Operation sur un compte. Montant signe : negatif pour une depense ou la jambe
 * debitrice d'un virement. Un virement = deux operations liees par transferGroup.
 */
export interface Transaction {
  id: number;
  accountId: number;
  date: IsoDate;
  label: string;
  amount: Cents;
  type: TransactionType;
  status: TransactionStatus;
  categoryId: number | null;
  note?: string;
  tags: string[];
  transferGroup?: string;
  transferAccountId?: number;
  /** Occurrence de recurrence dont l'operation est issue. */
  recurringId?: number;
  occurrenceDate?: IsoDate;
}

export type Frequency =
  | 'WEEKLY' | 'BIWEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'YEARLY'
  | 'EVERY_N_DAYS' | 'EVERY_N_WEEKS' | 'EVERY_N_MONTHS';

export const FREQUENCY_LABELS: Record<Frequency, string> = {
  WEEKLY: 'Hebdomadaire',
  BIWEEKLY: 'Toutes les deux semaines',
  MONTHLY: 'Mensuelle',
  QUARTERLY: 'Trimestrielle',
  YEARLY: 'Annuelle',
  EVERY_N_DAYS: 'Tous les N jours',
  EVERY_N_WEEKS: 'Toutes les N semaines',
  EVERY_N_MONTHS: 'Tous les N mois',
};

/**
 * Regle recurrente : ses occurrences sont calculees, jamais stockees ; seules les
 * occurrences validees ou ignorees deviennent des operations.
 *
 * @property amount toujours positif ; le sens est donne par le type
 * @property trackedFrom les occurrences anterieures sont reputees traitees
 */
export interface RecurringRule {
  id: number;
  accountId: number;
  toAccountId?: number;
  type: TransactionType;
  label: string;
  amount: Cents;
  categoryId: number | null;
  frequency: Frequency;
  interval: number;
  startDate: IsoDate;
  endDate?: IsoDate;
  trackedFrom: IsoDate;
  /** Revenu assez certain pour etre compte dans le disponible reel. */
  certain: boolean;
  active: boolean;
  note?: string;
  tags: string[];
}

/**
 * Budget mensuel d'une categorie de depenses (sous-categories comprises).
 * @property limit plafond mensuel, en centimes (positif)
 * @property reserveInAvailable reserver le reste du budget dans le disponible reel
 */
export interface Budget {
  id: number;
  categoryId: number;
  limit: Cents;
  reserveInAvailable: boolean;
  active: boolean;
}

/**
 * Objectif d'epargne.
 * @property linkedAccountId compte dont le solde represente l'epargne accumulee ; absent = montant tenu a la main
 * @property reserveInAvailable reserver chaque mois l'effort necessaire dans le disponible reel
 */
export interface SavingsGoal {
  id: number;
  name: string;
  target: Cents;
  targetDate?: IsoDate;
  linkedAccountId?: number;
  manualSaved: Cents;
  reserveInAvailable: boolean;
  archived: boolean;
}

export type HorizonType = 'END_OF_WEEK' | 'NEXT_PAYDAY' | 'END_OF_MONTH' | 'CUSTOM_DATE';

export const HORIZON_LABELS: Record<HorizonType, string> = {
  END_OF_WEEK: 'Fin de semaine',
  NEXT_PAYDAY: 'Prochaine paie',
  END_OF_MONTH: 'Fin du mois',
  CUSTOM_DATE: 'Date personnalisée',
};

export interface Settings {
  defaultHorizon: HorizonType;
  includeCertainIncome: boolean;
  autoLockMinutes: number;
  privacy: boolean;
}

/** Toutes les donnees d'un profil : chiffrees ensemble dans le navigateur. */
export interface FinanceData {
  version: 1;
  nextId: number;
  settings: Settings;
  accounts: Account[];
  categories: Category[];
  transactions: Transaction[];
  rules: RecurringRule[];
  budgets: Budget[];
  goals: SavingsGoal[];
}

/** Operation a venir : operation "prevue" ou occurrence de recurrence non traitee. */
export interface PlannedItem {
  date: IsoDate;
  accountId: number;
  label: string;
  /** Signe, vu du compte. */
  amount: Cents;
  type: TransactionType;
  categoryId: number | null;
  source: 'PLANNED_TRANSACTION' | 'RECURRING';
  transactionId?: number;
  ruleId?: number;
  transferAccountId?: number;
  certain: boolean;
}
