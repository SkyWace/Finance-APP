import { useState } from 'react';
import { BUDGET_STATUS, budgetProgress, type BudgetProgress } from '../../domain/budgets';
import { formatLongMonth, plusMonths, startOfMonth } from '../../domain/dates';
import { deleteBudget } from '../../domain/operations';
import type { Budget } from '../../domain/types';
import { mutate, useData } from '../../store/session';
import { BudgetDialog } from '../components/budgetDialogs';
import { Empty, Money, useToday } from '../common';

/** "43,4 %" */
export function formatPermille(permille: number): string {
  return `${(permille / 10).toLocaleString('fr-FR', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} %`;
}

const STATUS_CLASS = { OK: 'positive', WARNING: 'warning', REACHED: 'info', EXCEEDED: 'negative' } as const;

/** Barre de progression : la valeur est aussi ecrite en toutes lettres a cote (jamais la seule forme). */
export function ProgressBar({ permille, tone }: { permille: number; tone: string }) {
  return (
    <div className={`progress ${tone}`} role="progressbar" aria-valuemin={0} aria-valuemax={100}
      aria-valuenow={Math.round(permille / 10)}>
      <span style={{ width: `${Math.min(100, permille / 10)}%` }} />
    </div>
  );
}

export function BudgetCard({ p, compact = false, children }: { p: BudgetProgress; compact?: boolean;
  children?: React.ReactNode }) {
  const status = BUDGET_STATUS[p.status];
  return (
    <div className={compact ? 'budget-compact' : 'card section budget-card'}>
      <div className="row">
        <strong>{p.categoryName}</strong>
        <span className="spacer" />
        <span className={`badge ${STATUS_CLASS[p.status]}`}>{status.symbol} {status.label}</span>
      </div>
      <div className="row progress-row">
        <ProgressBar permille={p.permille} tone={STATUS_CLASS[p.status]} />
        <span className="percent">{formatPermille(p.permille)}</span>
      </div>
      <div className="row">
        <span className="muted"><Money cents={p.spent} /> / <Money cents={p.budget.limit} /></span>
        <span className="spacer" />
        {p.remaining < 0 ? <span><Money cents={-p.remaining} className="negative" /> de dépassement</span>
          : <span><Money cents={p.remaining} /> restants</span>}
      </div>
      {!compact && p.planned > 0 && (
        <p className="hint">dont <Money cents={p.planned} /> encore prévus ce mois-ci (<Money cents={p.remaining - p.planned} /> disponibles
          après ces dépenses)</p>
      )}
      {children}
    </div>
  );
}

export function BudgetsPage() {
  const data = useData();
  const today = useToday();
  const [month, setMonth] = useState(startOfMonth(today));
  const [editing, setEditing] = useState<{ budget?: Budget } | null>(null);
  const list = budgetProgress(data, month, today);
  const limit = list.reduce((s, p) => s + p.budget.limit, 0);
  const spent = list.reduce((s, p) => s + p.spent, 0);
  const alerts = list.filter((p) => p.status !== 'OK').length;
  return (
    <>
      <div className="row">
        <button className="btn primary" onClick={() => setEditing({})}>+ Nouveau budget</button>
        <span className="spacer" />
        <div className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
          <button className="btn small" aria-label="Mois précédent" onClick={() => setMonth(plusMonths(month, -1))}>‹</button>
          <strong style={{ minWidth: 130, textAlign: 'center' }}>{formatLongMonth(month)}</strong>
          <button className="btn small" aria-label="Mois suivant" onClick={() => setMonth(plusMonths(month, 1))}>›</button>
          {month !== startOfMonth(today) && <button className="btn small" onClick={() => setMonth(startOfMonth(today))}>
            Ce mois-ci</button>}
        </div>
      </div>
      {list.length === 0 ? (
        <section className="card section"><Empty>Aucun budget. Fixez un plafond mensuel par catégorie (courses,
          carburant, loisirs…) : l'application suit la consommation et réserve le reste dans votre disponible
          réel.</Empty></section>
      ) : <>
        <div className="kpis">
          <div className="card kpi"><div className="title">Budgété</div><div className="value"><Money cents={limit} /></div>
            <div className="sub">{formatLongMonth(month)}</div></div>
          <div className="card kpi"><div className="title">Dépensé</div><div className="value"><Money cents={spent} /></div></div>
          <div className="card kpi"><div className="title">Reste</div>
            <div className="value"><Money cents={limit - spent} tone signed={limit - spent < 0} /></div></div>
          <div className="card kpi"><div className="title">Alertes</div>
            <div className="value">{alerts}</div>
            <div className="sub">{alerts === 0 ? 'tous les budgets sont respectés' : 'proches de la limite ou dépassés'}</div></div>
        </div>
        {list.map((p) => (
          <BudgetCard p={p} key={p.budget.id}>
            <div className="row" style={{ marginTop: 8 }}>
              <span className="muted small-text">{p.budget.reserveInAvailable ? 'Reste réservé dans le disponible réel'
                : 'Non réservé dans le disponible'}</span>
              <span className="spacer" />
              <button className="btn small" onClick={() => setEditing({ budget: p.budget })}>Modifier</button>
              <button className="btn small danger" onClick={() => {
                if (window.confirm(`Supprimer le budget « ${p.categoryName} » ? Les opérations ne sont pas touchées.`)) {
                  mutate((d) => deleteBudget(d, p.budget.id));
                }
              }}>Supprimer</button>
            </div>
          </BudgetCard>
        ))}
      </>}
      {editing && <BudgetDialog existing={editing.budget} onClose={() => setEditing(null)} />}
    </>
  );
}
