import { useState } from 'react';
import { formatDate, plusDays } from '../../domain/dates';
import { upcomingForDisplay } from '../../domain/ledger';
import { categoryPath, skipOccurrence } from '../../domain/operations';
import type { PlannedItem, Transaction } from '../../domain/types';
import { mutate, useData } from '../../store/session';
import { ConfirmOccurrenceDialog, TransactionDialog } from '../components/dialogs';
import { AccountSelect, Empty, Money, useToday } from '../common';

export function UpcomingPage() {
  const data = useData();
  const today = useToday();
  const [days, setDays] = useState(60);
  const [accountId, setAccountId] = useState<number | undefined>();
  const [confirming, setConfirming] = useState<PlannedItem | null>(null);
  const [editing, setEditing] = useState<Transaction | null>(null);
  const items = upcomingForDisplay(data, today, plusDays(today, days))
    .filter((i) => accountId === undefined || i.accountId === accountId
      || (i.type === 'TRANSFER' && i.transferAccountId === accountId));
  const accounts = new Map(data.accounts.map((a) => [a.id, a.name]));
  return (
    <>
      <div className="row">
        <select value={days} onChange={(e) => setDays(Number(e.target.value))} style={{ width: 'auto' }} aria-label="Période">
          <option value={7}>7 prochains jours</option>
          <option value={30}>30 prochains jours</option>
          <option value={60}>60 prochains jours</option>
          <option value={180}>6 prochains mois</option>
        </select>
        <div style={{ minWidth: 180 }}>
          <AccountSelect accounts={data.accounts} value={accountId} onChange={setAccountId} emptyLabel="Tous les comptes" />
        </div>
      </div>
      <div className="card">
        {items.length === 0 ? <Empty>Rien de prévu sur cette période. Les opérations récurrentes et les opérations
          « prévues » apparaissent ici.</Empty> : (
          <div className="rows">
            {items.map((i, n) => (
              <div className="op-row" key={`${i.ruleId ?? i.transactionId}-${i.date}-${n}`}>
                <span className="date">{formatDate(i.date).slice(0, 5)}</span>
                <div className="texts">
                  <div className="label">{i.label} {i.date < today && <span className="badge warning">en retard</span>}
                    {i.type === 'INCOME' && !i.certain && <span className="badge warning">incertain</span>}</div>
                  <div className="detail">{accounts.get(i.accountId)}
                    {i.type === 'TRANSFER' ? ` → ${accounts.get(i.transferAccountId ?? -1) ?? ''}`
                      : i.categoryId ? ` · ${categoryPath(data, i.categoryId)}` : ''}
                    {i.source === 'RECURRING' ? ' · récurrence' : ' · opération prévue'}</div>
                </div>
                <Money cents={i.amount} signed tone />
                <div className="actions">
                  {i.source === 'RECURRING' ? <>
                    <button className="btn small primary" onClick={() => setConfirming(i)}>Valider</button>
                    <button className="btn small" onClick={() => {
                      if (window.confirm(`Ignorer l'échéance du ${formatDate(i.date)} de « ${i.label} » ? Elle ne sera pas comptée.`)) {
                        mutate((d) => skipOccurrence(d, i.ruleId!, i.date));
                      }
                    }}>Ignorer</button>
                  </> : (
                    <button className="btn small" onClick={() => setEditing(data.transactions.find((t) => t.id === i.transactionId)!)}>
                      Modifier</button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
      <p className="hint">Valider enregistre l'opération avec sa date et son montant réels. Une échéance récurrente non
        validée reste affichée « en retard » pendant 14 jours.</p>
      {confirming && <ConfirmOccurrenceDialog item={confirming} onClose={() => setConfirming(null)} />}
      {editing && <TransactionDialog existing={editing} onClose={() => setEditing(null)} />}
    </>
  );
}
