import { isValidDate, type IsoDate } from './dates';
import type { Cents } from './money';
import { isCustom, occurrences } from './recurrence';
import {
  ACCOUNT_TYPES, type Account, type AccountType, type Category, type CategoryKind, type FinanceData,
  type Frequency, type RecurringRule, type Transaction, type TransactionStatus, type TransactionType,
} from './types';

/** Erreur de saisie : son message est montre tel quel a l'utilisateur. */
export class BusinessError extends Error {}

function fail(message: string): never {
  throw new BusinessError(message);
}

function nextId(data: FinanceData): number {
  return data.nextId++;
}

function requireLabel(label: string, what = 'Le libellé'): string {
  const value = (label ?? '').trim();
  if (!value) {
    fail(`${what} est obligatoire`);
  }
  if (value.length > 200) {
    fail(`${what} est trop long (200 caractères au plus)`);
  }
  return value;
}

function requireDate(date: string, what = 'La date'): IsoDate {
  if (!date || !isValidDate(date)) {
    fail(`${what} est invalide`);
  }
  return date;
}

function requireAccount(data: FinanceData, id: number | undefined, what = 'Choisissez un compte'): Account {
  const account = data.accounts.find((a) => a.id === id);
  if (!account) {
    fail(what);
  }
  return account;
}

/** Etiquettes saisies "vacances, travaux" : sans doublon (casse ignoree), 30 caracteres, triees. */
export function cleanTags(tags: string[]): string[] {
  const seen = new Map<string, string>();
  for (const raw of tags) {
    const tag = raw.trim().replace(/,/g, '');
    if (!tag) {
      continue;
    }
    if (tag.length > 30) {
      fail(`L'étiquette « ${tag.slice(0, 30)}… » dépasse 30 caractères`);
    }
    if (!seen.has(tag.toLowerCase())) {
      seen.set(tag.toLowerCase(), tag);
    }
  }
  return [...seen.values()].sort((a, b) => a.localeCompare(b, 'fr', { sensitivity: 'base' }));
}

/** Etiquettes existantes, les plus utilisees d'abord. */
export function allTags(data: FinanceData): string[] {
  const counts = new Map<string, { name: string; n: number }>();
  for (const t of [...data.transactions, ...data.rules]) {
    for (const tag of t.tags) {
      const key = tag.toLowerCase();
      counts.set(key, { name: counts.get(key)?.name ?? tag, n: (counts.get(key)?.n ?? 0) + 1 });
    }
  }
  return [...counts.values()].sort((a, b) => b.n - a.n || a.name.localeCompare(b.name, 'fr')).map((c) => c.name);
}

// ---------------------------------------------------------------- comptes

export interface AccountDraft {
  name: string;
  type: AccountType;
  initialBalance: Cents;
  openingDate: IsoDate;
  includeInAvailable: boolean;
  color?: string;
}

export function saveAccount(data: FinanceData, draft: AccountDraft, id?: number): Account {
  const name = requireLabel(draft.name, 'Le nom du compte');
  if (!(draft.type in ACCOUNT_TYPES)) {
    fail('Type de compte inconnu');
  }
  if (!Number.isSafeInteger(draft.initialBalance)) {
    fail('Solde initial invalide');
  }
  const openingDate = requireDate(draft.openingDate, "La date d'ouverture");
  if (data.accounts.some((a) => a.id !== id && a.name.toLowerCase() === name.toLowerCase())) {
    fail(`Un compte « ${name} » existe déjà`);
  }
  if (id === undefined) {
    const account: Account = {
      id: nextId(data), name, type: draft.type, initialBalance: draft.initialBalance, openingDate,
      includeInAvailable: draft.includeInAvailable, archived: false, color: draft.color,
    };
    data.accounts.push(account);
    return account;
  }
  const account = requireAccount(data, id, 'Compte introuvable');
  Object.assign(account, { name, type: draft.type, initialBalance: draft.initialBalance, openingDate,
    includeInAvailable: draft.includeInAvailable, color: draft.color });
  return account;
}

export function setArchived(data: FinanceData, id: number, archived: boolean): void {
  requireAccount(data, id, 'Compte introuvable').archived = archived;
}

/** Seul un compte sans operation ni recurrence peut etre supprime (sinon : l'archiver). */
export function deleteAccount(data: FinanceData, id: number): void {
  if (data.transactions.some((t) => t.accountId === id || t.transferAccountId === id)
    || data.rules.some((r) => r.accountId === id || r.toAccountId === id)) {
    fail('Ce compte a des opérations ou des récurrences : archivez-le plutôt (l\'historique est conservé)');
  }
  data.accounts = data.accounts.filter((a) => a.id !== id);
}

// ---------------------------------------------------------------- operations

export interface TransactionDraft {
  accountId: number;
  date: IsoDate;
  label: string;
  /** Montant positif saisi ; le sens vient du type. */
  amount: Cents;
  type: 'EXPENSE' | 'INCOME';
  status: TransactionStatus;
  categoryId: number | null;
  note?: string;
  tags: string[];
}

function checkAmount(amount: Cents): void {
  if (!Number.isSafeInteger(amount) || amount <= 0) {
    fail('Le montant doit être strictement positif');
  }
}

export function saveTransaction(data: FinanceData, draft: TransactionDraft, id?: number): Transaction {
  requireAccount(data, draft.accountId);
  checkAmount(draft.amount);
  if (draft.categoryId !== null && !data.categories.some((c) => c.id === draft.categoryId)) {
    fail('Catégorie introuvable');
  }
  const fields = {
    accountId: draft.accountId,
    date: requireDate(draft.date),
    label: requireLabel(draft.label),
    amount: draft.type === 'EXPENSE' ? -draft.amount : draft.amount,
    type: draft.type as TransactionType,
    status: draft.status,
    categoryId: draft.categoryId,
    note: draft.note?.trim() || undefined,
    tags: cleanTags(draft.tags),
  };
  if (id === undefined) {
    const t: Transaction = { id: nextId(data), ...fields };
    data.transactions.push(t);
    return t;
  }
  const existing = data.transactions.find((t) => t.id === id) ?? fail('Opération introuvable');
  if (existing.type === 'TRANSFER') {
    fail('Un virement se modifie comme un virement');
  }
  Object.assign(existing, fields);
  return existing;
}

export interface TransferDraft {
  fromAccountId: number;
  toAccountId: number;
  date: IsoDate;
  label: string;
  amount: Cents;
  status: TransactionStatus;
  note?: string;
}

function transferLegs(data: FinanceData, d: TransferDraft): [Omit<Transaction, 'id'>, Omit<Transaction, 'id'>] {
  requireAccount(data, d.fromAccountId, 'Choisissez le compte à débiter');
  requireAccount(data, d.toAccountId, 'Choisissez le compte à créditer');
  if (d.fromAccountId === d.toAccountId) {
    fail('Un virement doit relier deux comptes différents');
  }
  checkAmount(d.amount);
  const base = {
    date: requireDate(d.date), label: requireLabel(d.label), type: 'TRANSFER' as const, status: d.status,
    categoryId: null, note: d.note?.trim() || undefined, tags: [],
  };
  return [
    { ...base, accountId: d.fromAccountId, amount: -d.amount, transferAccountId: d.toAccountId },
    { ...base, accountId: d.toAccountId, amount: d.amount, transferAccountId: d.fromAccountId },
  ];
}

/** Virement interne : deux operations liees, neutre pour le patrimoine. */
export function createTransfer(data: FinanceData, d: TransferDraft, recurring?: { ruleId: number; occurrence: IsoDate }):
  Transaction[] {
  const group = crypto.randomUUID();
  return transferLegs(data, d).map((leg) => {
    const t: Transaction = { id: nextId(data), ...leg, transferGroup: group,
      recurringId: recurring?.ruleId, occurrenceDate: recurring?.occurrence };
    data.transactions.push(t);
    return t;
  });
}

export function updateTransfer(data: FinanceData, group: string, d: TransferDraft): void {
  const legs = data.transactions.filter((t) => t.transferGroup === group);
  if (legs.length !== 2) {
    fail('Virement introuvable');
  }
  const [out, inn] = transferLegs(data, d);
  const debit = legs.find((t) => t.amount < 0)!;
  const credit = legs.find((t) => t.amount > 0)!;
  Object.assign(debit, out);
  Object.assign(credit, inn);
}

/** Supprime une operation (les deux jambes pour un virement). */
export function deleteTransaction(data: FinanceData, id: number): void {
  const t = data.transactions.find((x) => x.id === id);
  if (!t) {
    return;
  }
  data.transactions = data.transactions.filter((x) => x.id !== id
    && (t.transferGroup === undefined || x.transferGroup !== t.transferGroup));
}

/** Jambes d'un virement, la debitrice en premier. */
export function transferLegsOf(data: FinanceData, group: string): Transaction[] {
  return data.transactions.filter((t) => t.transferGroup === group).sort((a, b) => a.amount - b.amount);
}

// ---------------------------------------------------------------- recurrences

export interface RuleDraft {
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
  certain: boolean;
  active: boolean;
  note?: string;
  tags: string[];
}

export function saveRule(data: FinanceData, d: RuleDraft, today: IsoDate, id?: number): RecurringRule {
  requireAccount(data, d.accountId);
  checkAmount(d.amount);
  const startDate = requireDate(d.startDate, 'La première échéance');
  const endDate = d.endDate ? requireDate(d.endDate, 'La dernière échéance') : undefined;
  if (endDate && endDate < startDate) {
    fail('La dernière échéance précède la première');
  }
  const interval = isCustom(d.frequency) ? Math.trunc(d.interval) : 1;
  if (!Number.isFinite(interval) || interval < 1 || interval > 365) {
    fail("L'intervalle doit être compris entre 1 et 365");
  }
  if (d.type === 'TRANSFER') {
    requireAccount(data, d.toAccountId, 'Choisissez le compte destinataire');
    if (d.toAccountId === d.accountId) {
      fail('Un virement récurrent nécessite un compte destinataire différent');
    }
  }
  const fields = {
    accountId: d.accountId,
    toAccountId: d.type === 'TRANSFER' ? d.toAccountId : undefined,
    type: d.type,
    label: requireLabel(d.label),
    amount: d.amount,
    categoryId: d.type === 'TRANSFER' ? null : d.categoryId,
    frequency: d.frequency,
    interval,
    startDate,
    endDate,
    certain: d.type === 'INCOME' ? d.certain : true,
    active: d.active,
    note: d.note?.trim() || undefined,
    tags: d.type === 'TRANSFER' ? [] : cleanTags(d.tags),
  };
  if (id === undefined) {
    // Les occurrences anterieures a la creation sont reputees deja traitees.
    const rule: RecurringRule = { id: nextId(data), ...fields, trackedFrom: startDate > today ? startDate : today };
    data.rules.push(rule);
    return rule;
  }
  const rule = data.rules.find((r) => r.id === id) ?? fail('Récurrence introuvable');
  Object.assign(rule, fields);
  return rule;
}

/** Arrete une recurrence apres la date donnee (l'historique est conserve). */
export function endRule(data: FinanceData, id: number, lastDate: IsoDate): void {
  const rule = data.rules.find((r) => r.id === id) ?? fail('Récurrence introuvable');
  rule.endDate = lastDate < rule.startDate ? rule.startDate : lastDate;
}

/** Supprime la regle ; les operations deja validees restent dans l'historique. */
export function deleteRule(data: FinanceData, id: number): void {
  data.rules = data.rules.filter((r) => r.id !== id);
  for (const t of data.transactions) {
    if (t.recurringId === id) {
      delete t.recurringId;
      delete t.occurrenceDate;
    }
  }
}

function materialize(data: FinanceData, ruleId: number, occurrence: IsoDate, actualDate: IsoDate, amount: Cents,
  status: TransactionStatus): Transaction[] {
  const rule = data.rules.find((r) => r.id === ruleId) ?? fail('Récurrence introuvable');
  if (!occurrences(rule, occurrence, occurrence).includes(occurrence)) {
    fail("Cette date n'est pas une occurrence de la récurrence");
  }
  if (data.transactions.some((t) => t.recurringId === ruleId && t.occurrenceDate === occurrence)) {
    fail('Cette occurrence a déjà été traitée');
  }
  checkAmount(amount);
  if (rule.type === 'TRANSFER') {
    return createTransfer(data, {
      fromAccountId: rule.accountId, toAccountId: rule.toAccountId!, date: actualDate, label: rule.label, amount,
      status, note: rule.note,
    }, { ruleId, occurrence });
  }
  const t: Transaction = {
    id: nextId(data), accountId: rule.accountId, date: requireDate(actualDate), label: rule.label,
    amount: rule.type === 'EXPENSE' ? -amount : amount, type: rule.type, status, categoryId: rule.categoryId,
    note: rule.note, tags: [...rule.tags], recurringId: ruleId, occurrenceDate: occurrence,
  };
  data.transactions.push(t);
  return [t];
}

/** Valide une occurrence : la date et le montant reels peuvent differer du prevu. */
export function confirmOccurrence(data: FinanceData, ruleId: number, occurrence: IsoDate, actualDate: IsoDate,
  amount: Cents): Transaction[] {
  return materialize(data, ruleId, occurrence, actualDate, amount, 'COMPLETED');
}

/** Ignore une occurrence : trace annulee, sans effet sur le solde. */
export function skipOccurrence(data: FinanceData, ruleId: number, occurrence: IsoDate): Transaction[] {
  const rule = data.rules.find((r) => r.id === ruleId) ?? fail('Récurrence introuvable');
  return materialize(data, ruleId, occurrence, occurrence, rule.amount, 'CANCELLED');
}

// ---------------------------------------------------------------- categories

export function saveCategory(data: FinanceData, name: string, parentId: number | null, kind: CategoryKind,
  id?: number): Category {
  const clean = requireLabel(name, 'Le nom de la catégorie');
  if (parentId !== null) {
    const parent = data.categories.find((c) => c.id === parentId) ?? fail('Catégorie parente introuvable');
    if (parent.parentId !== null) {
      fail('Deux niveaux au plus : une sous-catégorie ne peut pas en contenir');
    }
    if (parent.id === id) {
      fail('Une catégorie ne peut pas être sa propre parente');
    }
  }
  const siblings = data.categories.filter((c) => c.parentId === parentId && c.id !== id);
  if (siblings.some((c) => c.name.toLowerCase() === clean.toLowerCase())) {
    fail(`La catégorie « ${clean} » existe déjà à cet endroit`);
  }
  if (id === undefined) {
    const category: Category = { id: nextId(data), parentId, name: clean, kind, archived: false };
    data.categories.push(category);
    return category;
  }
  const category = data.categories.find((c) => c.id === id) ?? fail('Catégorie introuvable');
  if (parentId !== null && data.categories.some((c) => c.parentId === id)) {
    fail('Cette catégorie a des sous-catégories : elle ne peut pas devenir une sous-catégorie');
  }
  Object.assign(category, { name: clean, parentId, kind });
  return category;
}

export function categoryUsage(data: FinanceData, id: number): number {
  return data.transactions.filter((t) => t.categoryId === id).length
    + data.rules.filter((r) => r.categoryId === id).length;
}

/** Une categorie utilisee ne se supprime pas : elle s'archive. */
export function deleteCategory(data: FinanceData, id: number): void {
  if (data.categories.some((c) => c.parentId === id)) {
    fail("Supprimez ou déplacez d'abord ses sous-catégories");
  }
  if (categoryUsage(data, id) > 0) {
    fail('Catégorie utilisée par des opérations : archivez-la plutôt');
  }
  data.categories = data.categories.filter((c) => c.id !== id);
}

export function setCategoryArchived(data: FinanceData, id: number, archived: boolean): void {
  const category = data.categories.find((c) => c.id === id) ?? fail('Catégorie introuvable');
  category.archived = archived;
}

/** "Logement › Loyer" */
export function categoryPath(data: FinanceData, id: number | null): string {
  if (id === null) {
    return '';
  }
  const c = data.categories.find((x) => x.id === id);
  if (!c) {
    return '';
  }
  const parent = c.parentId === null ? undefined : data.categories.find((x) => x.id === c.parentId);
  return parent ? `${parent.name} › ${c.name}` : c.name;
}
