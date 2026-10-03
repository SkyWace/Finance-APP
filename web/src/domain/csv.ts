import { formatDate, isValidDate, iso, type IsoDate } from './dates';
import { parseAmount, toEditable, type Cents } from './money';
import { categoryPath } from './operations';
import { STATUS_LABELS, type FinanceData, type Transaction } from './types';

/**
 * Formules de tableur neutralisees : une cellule commencant par = + - @ est
 * prefixee d'une apostrophe (injection de formules dans Excel / LibreOffice).
 */
function cell(value: string): string {
  let v = value;
  if (/^[=+\-@\t\r]/.test(v) && !/^-?\d/.test(v)) {
    v = "'" + v;
  }
  return /[;"\n\r]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
}

/** Export CSV (point-virgule, UTF-8 avec BOM) : s'ouvre directement dans Excel ou LibreOffice. */
export function exportCsv(data: FinanceData, rows: Transaction[]): string {
  const accounts = new Map(data.accounts.map((a) => [a.id, a.name]));
  const header = ['Date', 'Compte', 'Libellé', 'Montant', 'Type', 'Statut', 'Catégorie', 'Étiquettes', 'Commentaire'];
  const lines = rows.map((t) => [
    formatDate(t.date),
    accounts.get(t.accountId) ?? '',
    t.label,
    toEditable(t.amount),
    t.type === 'EXPENSE' ? 'Dépense' : t.type === 'INCOME' ? 'Revenu' : 'Virement',
    STATUS_LABELS[t.status],
    t.type === 'TRANSFER' ? `Virement ${t.amount < 0 ? 'vers' : 'depuis'} ${accounts.get(t.transferAccountId ?? -1) ?? ''}`
      : categoryPath(data, t.categoryId),
    t.tags.join(', '),
    t.note ?? '',
  ].map(cell).join(';'));
  return '﻿' + [header.join(';'), ...lines].join('\r\n') + '\r\n';
}

export interface CsvRow {
  date: IsoDate;
  label: string;
  amount: Cents;
}

export interface CsvParseResult {
  rows: CsvRow[];
  /** Lignes ignorees, avec leur numero et la raison. */
  rejected: { line: number; reason: string }[];
}

function splitLine(line: string, sep: string): string[] {
  const out: string[] = [];
  let cur = '';
  let quoted = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (quoted) {
      if (ch === '"' && line[i + 1] === '"') {
        cur += '"';
        i++;
      } else if (ch === '"') {
        quoted = false;
      } else {
        cur += ch;
      }
    } else if (ch === '"') {
      quoted = true;
    } else if (ch === sep) {
      out.push(cur);
      cur = '';
    } else {
      cur += ch;
    }
  }
  out.push(cur);
  return out.map((s) => s.trim());
}

function parseDate(text: string): IsoDate | null {
  const t = text.trim();
  let m = /^(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})$/.exec(t);
  if (m) {
    const d = iso(Number(m[3]), Number(m[2]), Number(m[1]));
    return isValidDate(d) ? d : null;
  }
  m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(t);
  return m && isValidDate(t) ? t : null;
}

/**
 * Releve CSV simple : colonnes Date ; Libelle ; Montant (ou Debit ; Credit).
 * La ligne d'en-tete est reconnue a ses noms de colonnes ; separateur ; ou ,.
 */
export function parseCsv(text: string): CsvParseResult {
  const lines = text.replace(/^﻿/, '').split(/\r?\n/).filter((l) => l.trim() !== '');
  const rows: CsvRow[] = [];
  const rejected: { line: number; reason: string }[] = [];
  if (lines.length === 0) {
    return { rows, rejected };
  }
  const sep = (lines[0].match(/;/g)?.length ?? 0) >= (lines[0].match(/,/g)?.length ?? 0) ? ';' : ',';
  const header = splitLine(lines[0], sep).map((h) => h.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, ''));
  const find = (...names: string[]) => header.findIndex((h) => names.some((n) => h.includes(n)));
  let date = find('date');
  let label = find('libelle', 'label', 'description', 'intitule', 'operation');
  let amount = find('montant', 'amount');
  let debit = find('debit');
  let credit = find('credit');
  let start = 1;
  if (date < 0) {
    // Pas d'en-tete reconnu : Date ; Libelle ; Montant
    date = 0; label = 1; amount = 2; debit = -1; credit = -1; start = 0;
  }
  for (let i = start; i < lines.length; i++) {
    const cols = splitLine(lines[i], sep);
    const d = parseDate(cols[date] ?? '');
    if (!d) {
      rejected.push({ line: i + 1, reason: 'date illisible' });
      continue;
    }
    let value: Cents | null = null;
    if (amount >= 0 && cols[amount]) {
      value = parseAmount(cols[amount]);
    } else if (debit >= 0 || credit >= 0) {
      const dv = debit >= 0 && cols[debit] ? parseAmount(cols[debit]) : 0;
      const cv = credit >= 0 && cols[credit] ? parseAmount(cols[credit]) : 0;
      value = dv === null || cv === null ? null : cv - Math.abs(dv);
    }
    if (value === null || value === 0) {
      rejected.push({ line: i + 1, reason: 'montant illisible ou nul' });
      continue;
    }
    const text = (cols[label] ?? '').trim();
    if (!text) {
      rejected.push({ line: i + 1, reason: 'libellé vide' });
      continue;
    }
    rows.push({ date: d, label: text.slice(0, 200), amount: value });
  }
  return { rows, rejected };
}

/** Une ligne de releve deja presente (meme compte, date, montant et libelle) n'est pas reimportee. */
export function isDuplicate(data: FinanceData, accountId: number, row: CsvRow): boolean {
  const label = row.label.toLowerCase();
  return data.transactions.some((t) => t.accountId === accountId && t.date === row.date && t.amount === row.amount
    && t.label.toLowerCase() === label);
}
