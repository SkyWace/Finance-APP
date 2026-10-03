import { useMemo, useState } from 'react';
import { exportCsv } from '../../domain/csv';
import { endOfMonth, formatDate, plusDays, plusMonths, startOfMonth } from '../../domain/dates';
import { parseAmount } from '../../domain/money';
import { allTags, categoryPath } from '../../domain/operations';
import { STATUS_LABELS, type Transaction, type TransactionStatus } from '../../domain/types';
import { useData } from '../../store/session';
import { TransactionDialog } from '../components/dialogs';
import { AccountSelect, CategorySelect, Empty, Money, download, useToday } from '../common';
import { useNav } from '../nav';

type Period = 'ALL' | 'THIS_MONTH' | 'LAST_MONTH' | 'LAST_90' | 'THIS_YEAR';
const PERIODS: Record<Period, string> = {
  ALL: 'Toutes les dates', THIS_MONTH: 'Ce mois-ci', LAST_MONTH: 'Le mois dernier', LAST_90: '90 derniers jours',
  THIS_YEAR: 'Cette année',
};
const PAGE = 200;

export function TransactionsPage() {
  const data = useData();
  const today = useToday();
  const nav = useNav();
  const [text, setText] = useState('');
  const [accountId, setAccountId] = useState<number | undefined>();
  const [categoryId, setCategoryId] = useState<number | null>(null);
  const [tag, setTag] = useState('');
  const [period, setPeriod] = useState<Period>('ALL');
  const [status, setStatus] = useState<TransactionStatus | ''>('');
  const [min, setMin] = useState('');
  const [max, setMax] = useState('');
  const [limit, setLimit] = useState(PAGE);
  const [editing, setEditing] = useState<Transaction | null>(null);

  const results = useMemo(() => {
    const [from, to] = period === 'THIS_MONTH' ? [startOfMonth(today), endOfMonth(today)]
      : period === 'LAST_MONTH' ? [startOfMonth(plusMonths(today, -1)), endOfMonth(plusMonths(today, -1))]
        : period === 'LAST_90' ? [plusDays(today, -90), today]
          : period === 'THIS_YEAR' ? [`${today.slice(0, 4)}-01-01`, `${today.slice(0, 4)}-12-31`] : ['', '9999-12-31'];
    const cats = categoryId === null ? null
      : new Set([categoryId, ...data.categories.filter((c) => c.parentId === categoryId).map((c) => c.id)]);
    const needle = text.trim().toLowerCase();
    const minC = parseAmount(min);
    const maxC = parseAmount(max);
    return data.transactions
      .filter((t) => accountId === undefined || t.accountId === accountId)
      .filter((t) => t.date >= from && t.date <= to)
      .filter((t) => !needle || t.label.toLowerCase().includes(needle) || (t.note ?? '').toLowerCase().includes(needle))
      .filter((t) => cats === null || (t.categoryId !== null && cats.has(t.categoryId)))
      .filter((t) => !tag || t.tags.some((x) => x.toLowerCase() === tag.toLowerCase()))
      .filter((t) => !status || t.status === status)
      .filter((t) => minC === null || Math.abs(t.amount) >= minC)
      .filter((t) => maxC === null || Math.abs(t.amount) <= maxC)
      .sort((a, b) => (a.date !== b.date ? (a.date < b.date ? 1 : -1) : b.id - a.id));
  }, [data, today, text, accountId, categoryId, tag, period, status, min, max]);

  const counted = results.filter((t) => t.type !== 'TRANSFER' && t.status !== 'CANCELLED');
  const spent = counted.filter((t) => t.amount < 0).reduce((s, t) => s + t.amount, 0);
  const earned = counted.filter((t) => t.amount > 0).reduce((s, t) => s + t.amount, 0);
  const accounts = new Map(data.accounts.map((a) => [a.id, a.name]));
  const tags = allTags(data);

  return (
    <>
      <div className="row">
        <button className="btn primary" onClick={() => nav.newOperation('EXPENSE')}>− Dépense</button>
        <button className="btn" onClick={() => nav.newOperation('INCOME')}>+ Revenu</button>
        <button className="btn" onClick={() => nav.newOperation('TRANSFER')}>⇄ Virement</button>
        <span className="spacer" />
        <button className="btn ghost" disabled={results.length === 0}
          onClick={() => download(`operations-${today}.csv`, exportCsv(data, results), 'text/csv;charset=utf-8')}>
          ⇪ Exporter (CSV)</button>
        <input type="search" placeholder="Rechercher un libellé…" value={text} onChange={(e) => setText(e.target.value)}
          aria-label="Rechercher" style={{ maxWidth: 260 }} />
      </div>
      <div className="row" role="group" aria-label="Filtres">
        <div style={{ minWidth: 160 }}>
          <AccountSelect accounts={data.accounts} value={accountId} onChange={setAccountId} emptyLabel="Tous les comptes" />
        </div>
        <div style={{ minWidth: 190 }}>
          <CategorySelect data={data} income={null} value={categoryId} onChange={setCategoryId}
            emptyLabel="Toutes les catégories" />
        </div>
        {tags.length > 0 && (
          <select value={tag} onChange={(e) => setTag(e.target.value)} style={{ width: 'auto' }} aria-label="Étiquette">
            <option value="">Toutes les étiquettes</option>
            {tags.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        )}
        <select value={period} onChange={(e) => setPeriod(e.target.value as Period)} style={{ width: 'auto' }}
          aria-label="Période">
          {Object.entries(PERIODS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        <select value={status} onChange={(e) => setStatus(e.target.value as TransactionStatus | '')} style={{ width: 'auto' }}
          aria-label="Statut">
          <option value="">Tous les statuts</option>
          {Object.entries(STATUS_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        <input type="text" inputMode="decimal" placeholder="Montant min." value={min} onChange={(e) => setMin(e.target.value)}
          style={{ width: 120 }} aria-label="Montant minimum" />
        <input type="text" inputMode="decimal" placeholder="max." value={max} onChange={(e) => setMax(e.target.value)}
          style={{ width: 100 }} aria-label="Montant maximum" />
      </div>
      <div className="card table-wrap">
        {results.length === 0 ? <Empty>Aucune opération ne correspond.</Empty> : (
          <table className="data">
            <thead>
              <tr>
                <th>Date</th><th>Libellé</th><th className="hide-mobile">Catégorie</th><th className="hide-mobile">Compte</th>
                <th>Statut</th><th className="num">Montant</th>
              </tr>
            </thead>
            <tbody>
              {results.slice(0, limit).map((t) => (
                <tr key={t.id} className="clickable" onClick={() => setEditing(t)} tabIndex={0}
                  onKeyDown={(e) => e.key === 'Enter' && setEditing(t)}>
                  <td>{formatDate(t.date)}</td>
                  <td>{t.label}{t.tags.length > 0 && <span className="muted"> {t.tags.map((x) => `[${x}]`).join(' ')}</span>}
                  </td>
                  <td className="hide-mobile">{t.type === 'TRANSFER'
                    ? `Virement ${t.amount < 0 ? 'vers' : 'depuis'} ${accounts.get(t.transferAccountId ?? -1) ?? ''}`
                    : categoryPath(data, t.categoryId)}</td>
                  <td className="hide-mobile">{accounts.get(t.accountId)}</td>
                  <td><span className={`badge ${t.status === 'COMPLETED' ? 'positive' : t.status === 'CANCELLED' ? ''
                    : 'warning'}`}>{STATUS_LABELS[t.status]}</span></td>
                  <td className="num"><Money cents={t.amount} signed tone /></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      <div className="row">
        <span className="muted">{results.length} opération(s) · dépensé <Money cents={spent} /> · reçu{' '}
          <Money cents={earned} /> (virements internes et annulations exclus)</span>
        <span className="spacer" />
        {results.length > limit && <button className="btn ghost" onClick={() => setLimit(limit + PAGE)}>Charger plus</button>}
      </div>
      {editing && (
        <TransactionDialog existing={editing} onClose={() => setEditing(null)} />
      )}
    </>
  );
}
