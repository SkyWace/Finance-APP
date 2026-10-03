import { useState } from 'react';
import { formatDate } from '../../domain/dates';
import { categoryPath, deleteRule, endRule } from '../../domain/operations';
import { isCustom, monthlyEquivalent, nextOccurrence } from '../../domain/recurrence';
import { FREQUENCY_LABELS, type RecurringRule } from '../../domain/types';
import { mutate, useData } from '../../store/session';
import { RuleDialog } from '../components/dialogs';
import { AccountSelect, Empty, Money, useToday } from '../common';

export function RecurringPage() {
  const data = useData();
  const today = useToday();
  const [accountId, setAccountId] = useState<number | undefined>();
  const [editing, setEditing] = useState<{ rule?: RecurringRule } | null>(null);
  const accounts = new Map(data.accounts.map((a) => [a.id, a.name]));
  const rules = data.rules
    .filter((r) => accountId === undefined || r.accountId === accountId || r.toAccountId === accountId)
    .sort((a, b) => a.label.localeCompare(b.label, 'fr'));
  const isEnded = (r: RecurringRule) => !r.active || (r.endDate !== undefined && r.endDate < today);
  const active = rules.filter((r) => !isEnded(r));
  const ended = rules.filter(isEnded);
  const monthly = (kind: 'EXPENSE' | 'INCOME') =>
    active.filter((r) => r.type === kind).reduce((s, r) => s + monthlyEquivalent(r), 0);
  const charges = monthly('EXPENSE');
  const income = monthly('INCOME');

  const row = (r: RecurringRule, isOld: boolean) => {
    const next = isOld ? undefined : nextOccurrence(r, today);
    const frequency = isCustom(r.frequency) ? FREQUENCY_LABELS[r.frequency].replace('N', String(r.interval))
      : FREQUENCY_LABELS[r.frequency];
    return (
      <div className="op-row" key={r.id}>
        <div className="texts">
          <div className="label">{r.label} {r.type === 'TRANSFER' && <span className="badge">virement</span>}
            {r.type === 'INCOME' && !r.certain && <span className="badge warning">incertain</span>}</div>
          <div className="detail">{r.type === 'TRANSFER'
            ? `${accounts.get(r.accountId)} → ${accounts.get(r.toAccountId ?? -1) ?? ''}`
            : `${r.categoryId ? categoryPath(data, r.categoryId) + ' · ' : ''}${accounts.get(r.accountId)}`}
            {r.tags.length > 0 && ` · ${r.tags.map((t) => `[${t}]`).join(' ')}`}</div>
        </div>
        <div className="texts hide-mobile" style={{ flex: '0 0 190px' }}>
          <div className="detail">{frequency}</div>
          <div className="detail">{isOld ? 'terminée' : next ? `prochaine : ${formatDate(next)}` : 'aucune échéance à venir'}</div>
        </div>
        <Money cents={r.type === 'INCOME' ? r.amount : -r.amount} signed tone />
        <div className="actions">
          <button className="btn small" onClick={() => setEditing({ rule: r })}>Modifier</button>
          {!isOld && <button className="btn small" onClick={() => {
            if (window.confirm(`Plus aucune occurrence de « ${r.label} » après aujourd'hui. L'historique est conservé.`)) {
              mutate((d) => endRule(d, r.id, today));
            }
          }}>Arrêter</button>}
          <button className="btn small danger" onClick={() => {
            if (window.confirm(`Supprimer « ${r.label} » ? Les opérations déjà validées restent dans l'historique.`)) {
              mutate((d) => deleteRule(d, r.id));
            }
          }}>Supprimer</button>
        </div>
      </div>
    );
  };

  return (
    <>
      <div className="row">
        <button className="btn primary" onClick={() => setEditing({})} disabled={data.accounts.length === 0}>
          + Nouvelle récurrence</button>
        <span className="spacer" />
        <div style={{ minWidth: 180 }}>
          <AccountSelect accounts={data.accounts} value={accountId} onChange={setAccountId} emptyLabel="Tous les comptes" />
        </div>
      </div>
      <div className="kpis">
        <div className="card kpi"><div className="title">Charges récurrentes</div>
          <div className="value"><Money cents={charges} signed tone /> <span className="muted">/mois</span></div>
          <div className="sub">soit <Money cents={charges * 12} signed /> par an</div></div>
        <div className="card kpi"><div className="title">Revenus récurrents</div>
          <div className="value"><Money cents={income} signed tone /> <span className="muted">/mois</span></div>
          <div className="sub">soit <Money cents={income * 12} signed /> par an</div></div>
        <div className="card kpi"><div className="title">Solde récurrent</div>
          <div className="value"><Money cents={income + charges} signed tone /> <span className="muted">/mois</span></div>
          <div className="sub">hors virements internes</div></div>
      </div>
      <section className="card section">
        <h2>Actives</h2>
        {active.length === 0 ? <Empty>Aucune récurrence. Ajoutez votre salaire, votre loyer, vos abonnements…</Empty>
          : <div className="rows">{active.map((r) => row(r, false))}</div>}
      </section>
      {ended.length > 0 && (
        <section className="card section">
          <h2>Terminées ou suspendues</h2>
          <div className="rows">{ended.map((r) => row(r, true))}</div>
        </section>
      )}
      {editing && <RuleDialog existing={editing.rule} onClose={() => setEditing(null)} />}
    </>
  );
}
